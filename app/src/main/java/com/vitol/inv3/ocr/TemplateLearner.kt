package com.vitol.inv3.ocr

import android.graphics.Rect
import android.net.Uri
import com.vitol.inv3.data.local.FieldRegion
import com.vitol.inv3.data.local.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import android.content.Context
import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * Automatically learns field positions by matching confirmed values to OCR text.
 * Saves templates for future use with the same company.
 */
class TemplateLearner(
    private val templateStore: TemplateStore,
    private val context: Context
) {
    /**
     * Learn template from confirmed invoice data.
     * Matches confirmed values to OCR text and saves their positions.
     * 
     * @param imageUri The invoice image URI
     * @param ocrBlocks OCR text blocks with bounding boxes
     * @param confirmedFields Map of field names to confirmed values
     * @param companyKeys List of keys to identify the company (e.g., company_number, company_name, vat_number)
     *                    The template will be saved under all provided keys for easier lookup
     */
    suspend fun learnTemplate(
        imageUri: Uri,
        ocrBlocks: List<OcrBlock>,
        confirmedFields: Map<String, String?>,
        companyKeys: List<String>
    ) = withContext(Dispatchers.IO) {
        if (companyKeys.isEmpty()) {
            Timber.d("No company keys provided, skipping template learning")
            return@withContext
        }

        // Get image dimensions for normalization
        val imageSize = getImageSize(imageUri) ?: run {
            Timber.w("Could not get image size, skipping template learning")
            return@withContext
        }

        Timber.d("Learning template for company keys: $companyKeys (image: ${imageSize.width}x${imageSize.height}, ${ocrBlocks.size} OCR blocks)")

        val regions = mutableListOf<FieldRegion>()
        
        // Load existing template for outlier detection
        val existingTemplate = if (companyKeys.isNotEmpty()) {
            templateStore.loadTemplate(companyKeys.first())
        } else {
            emptyList()
        }
        val existingMap = existingTemplate.associateBy { it.field }
        
        // Match each confirmed field to OCR text
        confirmedFields.forEach { (fieldName, confirmedValue) ->
            if (confirmedValue.isNullOrBlank()) {
                Timber.d("Skipping $fieldName: value is null or blank")
                return@forEach
            }
            
            // Validate the confirmed value
            if (!FieldValidator.validate(fieldName, confirmedValue)) {
                Timber.w("Skipping $fieldName: invalid value '$confirmedValue'")
                return@forEach
            }
            
            Timber.d("Matching field $fieldName with value '$confirmedValue'")
            // Find ALL matching blocks (field value might span multiple blocks)
            val matchedBlocks = findAllMatchingBlocks(ocrBlocks, confirmedValue)
            if (matchedBlocks.isNotEmpty()) {
                // Calculate match quality
                val matchQuality = FieldValidator.calculateMatchQuality(confirmedValue, matchedBlocks)
                if (matchQuality < 0.5f) {
                    Timber.w("Low quality match for $fieldName (quality: $matchQuality), skipping")
                    return@forEach
                }
                
                // Create a bounding box that encompasses all matching blocks
                val boundingBox = createBoundingBox(matchedBlocks)
                if (boundingBox != null) {
                    // Normalize coordinates to 0.0-1.0 range
                    val newRegion = FieldRegion(
                        field = fieldName,
                        left = boundingBox.left.toFloat() / imageSize.width,
                        top = boundingBox.top.toFloat() / imageSize.height,
                        right = boundingBox.right.toFloat() / imageSize.width,
                        bottom = boundingBox.bottom.toFloat() / imageSize.height,
                        confidence = matchQuality,
                        sampleCount = 1
                    )
                    
                    // Outlier detection: check if position differs significantly from existing template
                    val existingRegion = existingMap[fieldName]
                    if (existingRegion != null) {
                        val distance = calculateRegionDistance(existingRegion, newRegion)
                        val threshold = 0.15f  // 15% difference threshold
                        if (distance > threshold) {
                            Timber.w("Outlier detected for $fieldName: distance=$distance (threshold=$threshold), skipping update")
                            // Don't add this region, but we could create an alternative template version
                            return@forEach
                        } else {
                            Timber.d("Position for $fieldName is consistent (distance=$distance)")
                        }
                    }
                    
                    regions.add(newRegion)
                    val matchedTexts = matchedBlocks.map { it.text }.joinToString(" | ")
                    Timber.d("Learned position for $fieldName: normalized (${newRegion.left}, ${newRegion.top}, ${newRegion.right}, ${newRegion.bottom}), " +
                            "absolute (${boundingBox.left}, ${boundingBox.top}, ${boundingBox.right}, ${boundingBox.bottom}), " +
                            "quality=$matchQuality, matched ${matchedBlocks.size} blocks: '$matchedTexts'")
                } else {
                    Timber.w("Could not create bounding box for $fieldName: '$confirmedValue' (${matchedBlocks.size} blocks matched but no bounding boxes)")
                }
            } else {
                Timber.w("Could not find match for $fieldName: '$confirmedValue' (searched ${ocrBlocks.size} blocks)")
            }
        }
        
        // Merge template incrementally if we found at least one field
        // Save under all provided keys so it can be found regardless of recognition method
        if (regions.isNotEmpty()) {
            companyKeys.forEach { key ->
                templateStore.mergeTemplate(key, regions)
                Timber.d("Merged template for company key '$key' with ${regions.size} fields: ${regions.map { it.field }.joinToString()}")
            }
            Timber.d("Template merged under ${companyKeys.size} keys: $companyKeys")
        } else {
            Timber.w("No fields matched, skipping template update for company keys: $companyKeys")
        }
    }

    /**
     * Find ALL matching OCR blocks for a confirmed value.
     * A field value might span multiple blocks (e.g., "2024-03-15" could be split into "2024", "-", "03", "-", "15").
     * Uses fuzzy matching to handle slight variations.
     */
    private fun findAllMatchingBlocks(ocrBlocks: List<OcrBlock>, confirmedValue: String): List<OcrBlock> {
        val normalizedValue = confirmedValue.trim().lowercase()
        val matchedBlocks = mutableListOf<OcrBlock>()
        
        // Strategy 1: Try exact match first
        val exactMatch = ocrBlocks.find { block ->
            val blockText = block.text.trim().lowercase()
            blockText == normalizedValue
        }
        if (exactMatch != null) {
            Timber.d("  Exact match found: '${exactMatch.text}' == '$normalizedValue'")
            return listOf(exactMatch)
        }
        
        // Strategy 2: Try contains match (for partial matches, e.g., "123.45" matches "123.45€")
        ocrBlocks.forEach { block ->
            val blockText = block.text.trim().lowercase()
            if (normalizedValue in blockText || blockText in normalizedValue) {
                Timber.d("  Contains match found: '$blockText' contains '$normalizedValue' or vice versa")
                matchedBlocks.add(block)
            }
        }
        if (matchedBlocks.isNotEmpty()) {
            // If we found multiple blocks, filter to keep only spatially close ones
            return filterSpatiallyCloseBlocks(matchedBlocks)
        }
        
        // Strategy 3: Try to find blocks that together form the value
        // Split the confirmed value into parts and try to match them
        val valueParts = splitValueIntoParts(normalizedValue)
        if (valueParts.size > 1) {
            val candidateBlocks = mutableListOf<OcrBlock>()
            var matchedParts = 0
            
            for (part in valueParts) {
                val matchingBlock = ocrBlocks.find { block ->
                    val blockText = block.text.trim().lowercase()
                    part in blockText || blockText in part
                }
                if (matchingBlock != null && matchingBlock !in candidateBlocks) {
                    candidateBlocks.add(matchingBlock)
                    matchedParts++
                }
            }
            
            // If we matched at least 50% of the parts, consider it a match
            if (matchedParts >= (valueParts.size * 0.5f).toInt()) {
                Timber.d("  Multi-block match found: matched $matchedParts/${valueParts.size} parts")
                // Filter to keep only spatially close blocks
                return filterSpatiallyCloseBlocks(candidateBlocks)
            }
        }
        
        // Strategy 4: Try fuzzy match (remove spaces, special chars)
        val cleanedValue = normalizedValue.replace(Regex("[^a-z0-9.,]"), "")
        ocrBlocks.forEach { block ->
            val cleanedBlock = block.text.trim().lowercase().replace(Regex("[^a-z0-9.,]"), "")
            if (cleanedValue == cleanedBlock || cleanedValue in cleanedBlock || cleanedBlock in cleanedValue) {
                Timber.d("  Fuzzy match found: cleaned '$cleanedBlock' matches cleaned '$cleanedValue'")
                matchedBlocks.add(block)
            }
        }
        if (matchedBlocks.isNotEmpty()) {
            return matchedBlocks
        }
        
        // Strategy 5: For numeric values, try to match numbers even if formatting differs
        val confirmedNumber = normalizedValue.replace(',', '.').toDoubleOrNull()
        if (confirmedNumber != null) {
            ocrBlocks.forEach { block ->
                val blockNumber = block.text.replace(',', '.').replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                if (blockNumber != null && kotlin.math.abs(confirmedNumber - blockNumber) < 0.01) {
                    Timber.d("  Numeric match found: $blockNumber ≈ $confirmedNumber")
                    matchedBlocks.add(block)
                }
            }
        }
        
        if (matchedBlocks.isEmpty()) {
            Timber.d("  No match found for '$confirmedValue'")
        }
        return matchedBlocks
    }
    
    /**
     * Split a value into logical parts (e.g., "2024-03-15" -> ["2024", "-", "03", "-", "15"])
     */
    private fun splitValueIntoParts(value: String): List<String> {
        val parts = mutableListOf<String>()
        var currentPart = StringBuilder()
        
        for (char in value) {
            if (char.isLetterOrDigit()) {
                currentPart.append(char)
            } else {
                if (currentPart.isNotEmpty()) {
                    parts.add(currentPart.toString())
                    currentPart.clear()
                }
                parts.add(char.toString())
            }
        }
        if (currentPart.isNotEmpty()) {
            parts.add(currentPart.toString())
        }
        
        return parts.filter { it.isNotBlank() }
    }
    
    /**
     * Filter blocks to keep only those that are spatially close to each other.
     * This prevents matching blocks from distant parts of the invoice.
     */
    private fun filterSpatiallyCloseBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) {
            return blocks
        }
        
        val blocksWithBounds = blocks.mapNotNull { block ->
            block.boundingBox?.let { box -> block to box }
        }
        
        if (blocksWithBounds.isEmpty()) {
            return blocks.take(1) // If no bounding boxes, just return first match
        }
        
        // Group blocks by spatial proximity
        // Calculate average center and distance threshold (50% of average block size)
        val avgWidth = blocksWithBounds.map { it.second.width() }.average()
        val avgHeight = blocksWithBounds.map { it.second.height() }.average()
        val threshold = (avgWidth + avgHeight) / 2 * 2.0 // 2x average size as threshold
        
        // Start with the first block and find all blocks within threshold
        val firstBlock = blocksWithBounds.first()
        val centerX = firstBlock.second.centerX()
        val centerY = firstBlock.second.centerY()
        
        val closeBlocks = blocksWithBounds.filter { (_, box) ->
            val dx = (box.centerX() - centerX).toDouble()
            val dy = (box.centerY() - centerY).toDouble()
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            distance <= threshold
        }
        
        return if (closeBlocks.isNotEmpty()) {
            closeBlocks.map { it.first }
        } else {
            listOf(firstBlock.first) // If no close blocks, just return the first one
        }
    }
    
    /**
     * Create a bounding box that encompasses all matching blocks.
     */
    private fun createBoundingBox(blocks: List<OcrBlock>): Rect? {
        val boxesWithBounds = blocks.mapNotNull { it.boundingBox }
        if (boxesWithBounds.isEmpty()) {
            return null
        }
        
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE
        
        boxesWithBounds.forEach { box ->
            minLeft = minOf(minLeft, box.left)
            minTop = minOf(minTop, box.top)
            maxRight = maxOf(maxRight, box.right)
            maxBottom = maxOf(maxBottom, box.bottom)
        }
        
        return Rect(minLeft, minTop, maxRight, maxBottom)
    }

    /**
     * Get image dimensions from URI.
     */
    private suspend fun getImageSize(uri: Uri): ImageSize? {
        return try {
            val stream: InputStream? = context.contentResolver.openInputStream(uri)
            stream?.use {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(it, null, options)
                ImageSize(options.outWidth, options.outHeight)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get image size")
            null
        }
    }

    /**
     * Calculate distance between two field regions (normalized 0.0-1.0).
     * Returns a value indicating how different the positions are.
     */
    private fun calculateRegionDistance(region1: FieldRegion, region2: FieldRegion): Float {
        // Calculate center points
        val center1X = (region1.left + region1.right) / 2f
        val center1Y = (region1.top + region1.bottom) / 2f
        val center2X = (region2.left + region2.right) / 2f
        val center2Y = (region2.top + region2.bottom) / 2f
        
        // Euclidean distance in normalized space
        val dx = center1X - center2X
        val dy = center1Y - center2Y
        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        
        // Also consider size difference
        val size1 = (region1.right - region1.left) * (region1.bottom - region1.top)
        val size2 = (region2.right - region2.left) * (region2.bottom - region2.top)
        val sizeDiff = abs(size1 - size2) / maxOf(size1, size2, 0.001f)
        
        // Combined distance metric
        return (distance * 0.7f + sizeDiff * 0.3f)
    }

    private data class ImageSize(val width: Int, val height: Int)
}


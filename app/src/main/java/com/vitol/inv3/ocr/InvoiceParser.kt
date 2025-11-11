package com.vitol.inv3.ocr

import android.graphics.Rect
import com.vitol.inv3.data.local.FieldRegion
import timber.log.Timber
import kotlin.math.max

data class ParsedInvoice(
    val invoiceId: String? = null,
    val date: String? = null,
    val companyName: String? = null,
    val amountWithoutVatEur: String? = null,
    val vatAmountEur: String? = null,
    val vatNumber: String? = null,
    val companyNumber: String? = null,
    val lines: List<String> = emptyList()
)

object InvoiceParser {
    /**
     * Parse invoice using keyword matching (original method).
     */
    fun parse(lines: List<String>): ParsedInvoice {
        var invoiceId: String? = null
        var date: String? = null
        var companyName: String? = null
        var amountNoVat: String? = null
        var vatAmount: String? = null
        var vatNumber: String? = null
        var companyNumber: String? = null

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            val key = KeywordMapping.normalizeKey(line)
            when (key) {
                "Invoice_ID" -> if (invoiceId == null) invoiceId = takeKeyValue(line)
                "Date" -> if (date == null) date = FieldExtractors.tryExtractDate(line) ?: takeKeyValue(line)
                "Company_name" -> if (companyName == null) companyName = takeKeyValue(line)
                "Amount_without_VAT_EUR" -> if (amountNoVat == null) amountNoVat = FieldExtractors.tryExtractAmount(line)
                "VAT_amount_EUR" -> if (vatAmount == null) vatAmount = FieldExtractors.tryExtractAmount(line)
                "VAT_number" -> if (vatNumber == null) vatNumber = FieldExtractors.tryExtractVatNumber(line) ?: takeKeyValue(line)
                "Company_number" -> if (companyNumber == null) companyNumber = FieldExtractors.tryExtractCompanyNumber(line)
            }
        }

        return ParsedInvoice(
            invoiceId = invoiceId,
            date = date,
            companyName = companyName,
            amountWithoutVatEur = amountNoVat,
            vatAmountEur = vatAmount,
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            lines = lines
        )
    }

    /**
     * Parse invoice using template regions if available, fallback to keyword matching.
     * 
     * @param ocrBlocks OCR blocks with bounding boxes
     * @param imageWidth Image width for denormalizing template coordinates
     * @param imageHeight Image height for denormalizing template coordinates
     * @param template Template regions (normalized 0.0-1.0), null if no template
     */
    fun parseWithTemplate(
        ocrBlocks: List<OcrBlock>,
        imageWidth: Int,
        imageHeight: Int,
        template: List<FieldRegion>?
    ): ParsedInvoice {
        val result = ParsedInvoice(lines = ocrBlocks.map { it.text })
        
        // If no template, fallback to keyword matching
        if (template.isNullOrEmpty()) {
            return parse(ocrBlocks.map { it.text })
        }
        
        // Extract fields using template regions
        // Sort by confidence (highest first) to prioritize reliable regions
        val sortedTemplate = template.sortedByDescending { it.confidence }
        val templateResults = mutableMapOf<String, String?>()
        
        sortedTemplate.forEach { region ->
            // Adjust padding based on confidence: lower confidence = more padding
            // High confidence regions can use tighter matching
            val basePaddingX = (imageWidth * 0.10f).toInt().coerceAtLeast(20)
            val basePaddingY = (imageHeight * 0.10f).toInt().coerceAtLeast(20)
            val confidenceMultiplier = 1.0f + (1.0f - region.confidence) * 0.5f  // 1.0x to 1.5x padding
            val paddingX = (basePaddingX * confidenceMultiplier).toInt()
            val paddingY = (basePaddingY * confidenceMultiplier).toInt()
            
            val left = ((region.left * imageWidth).toInt() - paddingX).coerceAtLeast(0)
            val top = ((region.top * imageHeight).toInt() - paddingY).coerceAtLeast(0)
            val right = ((region.right * imageWidth).toInt() + paddingX).coerceAtMost(imageWidth)
            val bottom = ((region.bottom * imageHeight).toInt() + paddingY).coerceAtMost(imageHeight)
            val regionRect = Rect(left, top, right, bottom)
            
            Timber.d("Template region ${region.field}: normalized (${region.left}, ${region.top}, ${region.right}, ${region.bottom}) -> denormalized ($left, $top, $right, $bottom) with padding ($paddingX, $paddingY)")
            
            // Find OCR blocks that are within or near this region
            // Use multiple strategies: intersection, center point containment, and proximity
            val matchingBlocks = ocrBlocks.filter { block ->
                block.boundingBox?.let { box ->
                    // Strategy 1: Check if rectangles intersect
                    val intersects = Rect.intersects(regionRect, box)
                    
                    // Strategy 2: Check if block center is within the expanded region
                    val centerX = box.centerX()
                    val centerY = box.centerY()
                    val centerInRegion = regionRect.contains(centerX, centerY)
                    
                    // Strategy 3: Check if block is close to the region (within 2x padding distance)
                    val blockLeft = box.left
                    val blockTop = box.top
                    val blockRight = box.right
                    val blockBottom = box.bottom
                    val isNearRegion = (
                        (blockLeft >= left - paddingX && blockLeft <= right + paddingX) ||
                        (blockRight >= left - paddingX && blockRight <= right + paddingX) ||
                        (blockLeft <= left && blockRight >= right)
                    ) && (
                        (blockTop >= top - paddingY && blockTop <= bottom + paddingY) ||
                        (blockBottom >= top - paddingY && blockBottom <= bottom + paddingY) ||
                        (blockTop <= top && blockBottom >= bottom)
                    )
                    
                    val matches = intersects || centerInRegion || isNearRegion
                    
                    if (matches) {
                        Timber.d("  Block '${block.text}' matches region ${region.field} (intersects=$intersects, centerIn=$centerInRegion, near=$isNearRegion)")
                    }
                    matches
                } ?: false
            }
            
            // Combine text from matching blocks, sorted by position (top to bottom, left to right)
            val sortedBlocks = matchingBlocks.sortedWith(compareBy<OcrBlock> { it.boundingBox?.top ?: 0 }
                .thenBy { it.boundingBox?.left ?: 0 })
            val extractedText = sortedBlocks.joinToString(" ") { it.text }.trim()
            
            if (extractedText.isNotBlank()) {
                // Validate extracted value before using it
                if (FieldValidator.validate(region.field, extractedText)) {
                    templateResults[region.field] = extractedText
                    Timber.d("Extracted ${region.field} from template: '$extractedText' (${matchingBlocks.size} blocks, confidence=${region.confidence}, samples=${region.sampleCount})")
                } else {
                    Timber.w("Extracted invalid value for ${region.field}: '$extractedText', skipping (confidence=${region.confidence})")
                    // Don't add invalid values, will fallback to keyword matching
                }
            } else {
                Timber.w("No text extracted for ${region.field} from template region (checked ${ocrBlocks.size} blocks, region: $left,$top-$right,$bottom, confidence=${region.confidence})")
            }
        }
        
        // Merge template results with keyword matching (template takes priority)
        val lines = ocrBlocks.map { it.text }
        val keywordResults = parse(lines)
        
        val finalResult = ParsedInvoice(
            invoiceId = templateResults["Invoice_ID"] ?: keywordResults.invoiceId,
            date = templateResults["Date"] ?: keywordResults.date,
            companyName = templateResults["Company_name"] ?: keywordResults.companyName,
            amountWithoutVatEur = templateResults["Amount_without_VAT_EUR"] ?: keywordResults.amountWithoutVatEur,
            vatAmountEur = templateResults["VAT_amount_EUR"] ?: keywordResults.vatAmountEur,
            vatNumber = templateResults["VAT_number"] ?: keywordResults.vatNumber,
            companyNumber = templateResults["Company_number"] ?: keywordResults.companyNumber,
            lines = lines
        )
        
        Timber.d("Template parsing summary: ${templateResults.size} fields from template, ${keywordResults.invoiceId?.let { 1 } ?: 0} fields from keyword matching")
        
        return finalResult
    }

    private fun takeKeyValue(line: String): String? {
        val idx = line.indexOf(':')
        return if (idx > 0 && idx + 1 < line.length) line.substring(idx + 1).trim() else null
    }
}


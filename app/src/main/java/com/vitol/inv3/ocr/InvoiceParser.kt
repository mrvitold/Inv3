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
        if (lines.isEmpty()) {
            Timber.w("InvoiceParser.parse called with empty lines list")
            return ParsedInvoice(lines = lines)
        }
        
        Timber.d("InvoiceParser.parse starting with ${lines.size} lines")
        // Log first 10 lines for debugging
        lines.take(10).forEachIndexed { idx, line ->
            Timber.d("Line $idx: $line")
        }
        
        var invoiceId: String? = null
        var date: String? = null
        var companyName: String? = null
        var amountNoVat: String? = null
        var vatAmount: String? = null
        var vatNumber: String? = null
        var companyNumber: String? = null

        // First pass: keyword matching
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val key = KeywordMapping.normalizeKey(line)
            when (key) {
                "Invoice_ID" -> if (invoiceId == null) {
                    invoiceId = extractInvoiceIdFromLine(line) ?: takeKeyValue(line)
                }
                "Date" -> if (date == null) {
                    date = FieldExtractors.tryExtractDate(line) ?: takeKeyValue(line)
                }
                "Company_name" -> if (companyName == null) {
                    val l = line.lowercase().trim()
                    // Skip if line is just a label
                    val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier)$", RegexOption.IGNORE_CASE)
                    if (!l.matches(labelPattern) && !l.contains("saskaita") && !l.contains("faktura") && !l.contains("invoice")) {
                        val extracted = extractCompanyNameFromLine(line, lines, index)
                        if (extracted != null) {
                            val extractedLower = extracted.lowercase()
                            // Ensure extracted name is not a label and has company type suffix
                            if (!extractedLower.matches(labelPattern) && 
                                !extractedLower.contains("saskaita") && 
                                !extractedLower.contains("faktura") && 
                                extracted.length > 5 &&
                                (extractedLower.contains("uab") || extractedLower.contains("ab") || 
                                 extractedLower.contains("mb") || extractedLower.contains("iį") ||
                                 extractedLower.contains("ltd") || extractedLower.contains("oy") || 
                                 extractedLower.contains("as") || extractedLower.contains("sp"))) {
                                companyName = extracted
                                Timber.d("Extracted Company_name from keyword match: $companyName")
                            }
                        }
                    }
                }
                "Amount_without_VAT_EUR" -> if (amountNoVat == null) {
                    amountNoVat = FieldExtractors.tryExtractAmount(line)?.let { raw ->
                        val normalized = FieldExtractors.normalizeAmount(raw)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) normalized else null
                    }
                }
                "VAT_amount_EUR" -> if (vatAmount == null) {
                    vatAmount = FieldExtractors.tryExtractAmount(line)?.let { raw ->
                        val normalized = FieldExtractors.normalizeAmount(raw)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) normalized else null
                    }
                }
                "VAT_number" -> if (vatNumber == null) {
                    val extracted = FieldExtractors.tryExtractVatNumber(line)
                    if (extracted != null && !isIban(extracted)) {
                        vatNumber = extracted
                    } else {
                        vatNumber = takeKeyValue(line)
                    }
                }
                "Company_number" -> if (companyNumber == null) {
                    companyNumber = FieldExtractors.tryExtractCompanyNumber(line)
                }
            }
        }
        
        // Second pass: date extraction if still null
        if (date == null) {
            for (line in lines) {
                val extracted = FieldExtractors.tryExtractDate(line)
                if (extracted != null) {
                    date = extracted
                    Timber.d("Extracted date in second pass: $date")
                    break
                }
            }
        }
        
        // Second pass: invoice ID extraction if still null
        if (invoiceId == null) {
            for (line in lines) {
                val extracted = extractInvoiceIdFromLine(line)
                if (extracted != null) {
                    invoiceId = extracted
                    Timber.d("Extracted Invoice ID in second pass: $invoiceId")
                    break
                }
            }
        }
        
        // Second pass: company name extraction with multiple strategies
        if (companyName == null || isInvalidCompanyName(companyName)) {
            Timber.d("Company name is null or invalid, starting second pass extraction")
            companyName = extractCompanyNameAdvanced(lines, companyNumber, vatNumber)
        }
        
        // Second pass: VAT number with context
        if (vatNumber == null) {
            var sellerVatNumber: String? = null
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                val isInSellerSection = i < lines.size / 2 || 
                                       (i > 0 && lines[i-1].lowercase().contains("pardavejas"))
                val hasVatContext = l.contains("pvm kodas") || l.contains("pvmkodas") || 
                                   l.contains("pvm numeris") || l.contains("pvmnumeris")
                val extracted = FieldExtractors.tryExtractVatNumber(line)
                if (extracted != null && !isIban(extracted)) {
                    if (hasVatContext && isInSellerSection) {
                        sellerVatNumber = extracted
                        Timber.d("Found seller VAT number with context: $extracted")
                    }
                }
            }
            if (sellerVatNumber != null) {
                vatNumber = sellerVatNumber
            }
        }
        
        // Second pass: amounts with keywords
        if (amountNoVat == null) {
            val amountKeywords = listOf("suma be pvm", "suma bepvm", "sumabepvm", 
                                       "pardavimo tarpine suma", "pardavimotarpinesuma")
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                if (amountKeywords.any { keyword -> l.contains(keyword) } && 
                    !l.contains("suma su pvm") && !l.contains("sumasupvm")) {
                    var extracted = FieldExtractors.tryExtractAmount(line)
                    if (extracted == null && i + 1 < lines.size) {
                        extracted = FieldExtractors.tryExtractAmount(lines[i + 1])
                    }
                    if (extracted != null) {
                        val normalized = FieldExtractors.normalizeAmount(extracted)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            amountNoVat = normalized
                            Timber.d("Extracted amount without VAT: $amountNoVat")
                            break
                        }
                    }
                }
            }
        }
        
        // Third pass: amounts in totals section
        if (amountNoVat == null) {
            val totalsSectionStart = (lines.size * 0.8).toInt()
            for (i in totalsSectionStart until lines.size) {
                val line = lines[i]
                val l = line.lowercase()
                if ((l.contains("suma") || l.contains("eur")) && !l.contains("suma su pvm")) {
                    val extracted = FieldExtractors.tryExtractAmount(line)
                    if (extracted != null) {
                        val normalized = FieldExtractors.normalizeAmount(extracted)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            val amountValue = normalized.toDoubleOrNull()
                            if (amountValue != null && amountValue < 1000000) {
                                amountNoVat = normalized
                                Timber.d("Extracted amount from totals section: $amountNoVat")
                                break
                            }
                        }
                    }
                }
            }
        }
        
        // Fourth pass: Lithuanian format amounts (comma decimal)
        if (amountNoVat == null) {
            val commaAmountPattern = Regex("([0-9]{1,6}[,\\.][0-9]{2})\\s*€?\\s*EUR?", RegexOption.IGNORE_CASE)
            for (i in lines.size - 1 downTo (lines.size * 0.7).toInt()) {
                val line = lines[i]
                    val match = commaAmountPattern.find(line)
                    if (match != null) {
                        val rawAmount = match.groupValues[1]
                        val normalized = FieldExtractors.normalizeAmount(rawAmount)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            amountNoVat = normalized
                            Timber.d("Extracted amount with Lithuanian format: $amountNoVat")
                            break
                        }
                    }
            }
        }
        
        // Second pass: VAT amount
        if (vatAmount == null) {
            val vatKeywords = listOf("pvm suma", "pvmsuma", "vat amount", "vat suma")
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                if (vatKeywords.any { keyword -> l.contains(keyword) } && 
                    !l.contains("pvm %") && !l.contains("pvm%")) {
                    var extracted = FieldExtractors.tryExtractAmount(line)
                    if (extracted == null && i + 1 < lines.size) {
                        extracted = FieldExtractors.tryExtractAmount(lines[i + 1])
                    }
                    if (extracted != null) {
                        val normalized = FieldExtractors.normalizeAmount(extracted)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            vatAmount = normalized
                            Timber.d("Extracted VAT amount: $vatAmount")
                            break
                        }
                    }
                }
            }
        }
        
        // Third pass for amounts - look in totals section (usually last 20% of document)
        if (amountNoVat == null) {
            Timber.d("Amount without VAT still null, searching totals section")
            val totalsSectionStart = (lines.size * 0.8).toInt() // Last 20% of lines
            for (i in totalsSectionStart until lines.size) {
                val line = lines[i]
                val l = line.lowercase()
                
                // Look for amount patterns near "suma", "total", "eur" keywords
                if ((l.contains("suma") || l.contains("total") || l.contains("eur")) &&
                    !l.contains("suma su pvm") && !l.contains("sumasupvm") && 
                    !l.contains("total su pvm")) {
                    val extracted = FieldExtractors.tryExtractAmount(line)
                    if (extracted != null && FieldExtractors.isValidAmount(extracted)) {
                        // Check if it's a reasonable amount (not too large)
                        val amountValue = extracted.toDoubleOrNull()
                        if (amountValue != null && amountValue < 1000000) { // Reasonable limit
                            amountNoVat = extracted
                            Timber.d("Extracted amount from totals section: $amountNoVat (line $i)")
                            break
                        }
                    }
                }
            }
        }
        
        // Fourth pass: Look for amounts with comma decimal separator (Lithuanian format)
        if (amountNoVat == null) {
            Timber.d("Amount without VAT still null, searching for Lithuanian format (comma decimal)")
            val commaAmountPattern = Regex("([0-9]{1,6}[,\\.][0-9]{2})\\s*€?\\s*EUR?", RegexOption.IGNORE_CASE)
            for (i in lines.size - 1 downTo (lines.size * 0.7).toInt()) { // Search backwards from bottom
                val line = lines[i]
                val l = line.lowercase()
                // Only check lines in totals section (last 30% of document)
                if (l.contains("suma") || l.contains("pvm") || l.contains("total")) {
                    val match = commaAmountPattern.find(line)
                    if (match != null) {
                        val rawAmount = match.groupValues[1]
                        val normalized = FieldExtractors.normalizeAmount(rawAmount)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            amountNoVat = normalized
                            Timber.d("Extracted amount with Lithuanian format: $amountNoVat (line $i)")
                            break
                        }
                    }
                }
            }
        }
        
        // Similar improvements for VAT amount - search totals section
        if (vatAmount == null) {
            Timber.d("VAT amount still null, searching totals section")
            val totalsSectionStart = (lines.size * 0.8).toInt()
            for (i in totalsSectionStart until lines.size) {
                val line = lines[i]
                val l = line.lowercase()
                
                if ((l.contains("pvm") || l.contains("vat")) && 
                    (l.contains("suma") || l.contains("amount")) &&
                    !l.contains("pvm %") && !l.contains("pvm%") &&
                    !l.contains("suma su pvm") && !l.contains("sumasupvm")) {
                    val extracted = FieldExtractors.tryExtractAmount(line)
                    if (extracted != null) {
                        val normalized = FieldExtractors.normalizeAmount(extracted)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            vatAmount = normalized
                            Timber.d("Extracted VAT amount from totals section: $vatAmount (line $i)")
                            break
                        }
                    }
                }
            }
        }
        
        // Fifth pass for VAT: Look for Lithuanian comma format
        if (vatAmount == null) {
            Timber.d("VAT amount still null, searching for Lithuanian format")
            val commaAmountPattern = Regex("([0-9]{1,6}[,\\.][0-9]{2})\\s*€?\\s*EUR?", RegexOption.IGNORE_CASE)
            for (i in lines.size - 1 downTo (lines.size * 0.7).toInt()) {
                val line = lines[i]
                val l = line.lowercase()
                if (l.contains("pvm") && (l.contains("suma") || l.contains("amount"))) {
                    val match = commaAmountPattern.find(line)
                    if (match != null) {
                        val rawAmount = match.groupValues[1]
                        val normalized = FieldExtractors.normalizeAmount(rawAmount)
                        if (normalized != null && FieldExtractors.isValidAmount(normalized)) {
                            vatAmount = normalized
                            Timber.d("Extracted VAT amount with Lithuanian format: $vatAmount (line $i)")
                            break
                        }
                    }
                }
            }
        }
        
        // Final validation: reject invalid company names
        val finalCompanyName = companyName?.let { name ->
            if (isInvalidCompanyName(name)) {
                Timber.w("Rejecting invalid company name: '$name'")
                null
            } else {
                name
            }
        }

        return ParsedInvoice(
            invoiceId = invoiceId,
            date = date,
            companyName = finalCompanyName,
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

    private fun isInvalidCompanyName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val lower = name.lowercase().trim()
        
        // Explicitly reject common invoice labels
        if (lower == "saskaita" || lower == "faktura" || lower == "invoice" ||
            lower.matches(Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier)$"))) {
            return true
        }
        
        // Reject if it contains invoice-related words
        if (lower.contains("saskaita") || lower.contains("faktura") || lower.contains("invoice")) {
            return true
        }
        
        // Must have reasonable length
        if (name.length < 5) {
            return true
        }
        
        // Must contain Lithuanian company type suffix (UAB, MB, IĮ, AB, etc.)
        val hasCompanyType = lower.contains("uab") || lower.contains("mb") || lower.contains("iį") || 
                            lower.contains("ab") || lower.contains("ltd") || lower.contains("as") || 
                            lower.contains("sp") || lower.contains("oy")
        
        return !hasCompanyType
    }
    
    private fun extractInvoiceIdFromLine(line: String): String? {
        // Pattern 1: "Nr. SSP000393734" or "Nr SSP000393734" or "Nr: SSP000393734"
        val nrPattern = Regex("nr\\.?\\s*:?\\s*([A-Z]{2,}[0-9]+)", RegexOption.IGNORE_CASE)
        val nrMatch = nrPattern.find(line)
        if (nrMatch != null) {
            val id = nrMatch.groupValues.getOrNull(1)
            if (id != null && id.length >= 6 && id.uppercase() != "INVOICE") {
                Timber.d("Extracted Invoice ID from 'Nr.' pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        
        // Pattern 2: "SSP000393734" standalone (letters followed by numbers)
        val invoiceIdPattern = Regex("\\b([A-Z]{2,}[0-9]{6,})\\b", RegexOption.IGNORE_CASE)
        val idMatch = invoiceIdPattern.find(line)
        if (idMatch != null) {
            val id = idMatch.value
            if (id.uppercase() != "INVOICE" && id.length >= 6) {
                Timber.d("Extracted Invoice ID from standalone pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        
        // Pattern 3: "Saskaitos numeris: SSP000393734" or similar
        val colonPattern = Regex("(?:numeris|serija|number|id)[:.]?\\s*([A-Z]{2,}[0-9]+)", RegexOption.IGNORE_CASE)
        val colonMatch = colonPattern.find(line)
        if (colonMatch != null) {
            val id = colonMatch.groupValues.getOrNull(1)
            if (id != null && id.length >= 6 && id.uppercase() != "INVOICE") {
                Timber.d("Extracted Invoice ID from colon pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        
        return null
    }
    
    private fun extractCompanyNameFromLine(line: String, allLines: List<String>, currentIndex: Int): String? {
        val l = line.lowercase().trim()
        if (l.contains("saskaita") || l.contains("faktura") || l.contains("invoice") ||
            l == "saskaita") {
            return null
        }
        // List of Lithuanian labels to skip
        val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|imone|kompanija|bendrove|company)$", RegexOption.IGNORE_CASE)
        val isLabelOnly = l.matches(labelPattern)
        
        // NEVER extract a label itself
        if (l.matches(labelPattern)) {
            return null
        }
        
        if (isLabelOnly) {
            // Look for company name in next lines after label
            for (i in 1..5) {
                if (currentIndex + i < allLines.size) {
                    val nextLine = allLines[currentIndex + i].trim()
                    val nextLower = nextLine.lowercase()
                    
                    // Skip labels
                    if (nextLower.matches(labelPattern)) {
                        continue
                    }
                    
                    if (nextLower.contains("saskaita") || nextLower.contains("faktura") || 
                        nextLower.contains("invoice") || nextLower == "saskaita") {
                        continue
                    }
                    if (nextLower.contains("kodas") || nextLower.contains("numeris") || 
                        nextLower.contains("pvm") || nextLower.matches(Regex(".*[0-9]{7,}.*")) ||
                        nextLine.length < 5 || nextLine.length > 150) {
                        continue
                    }
                    
                    // REQUIRE Lithuanian company type suffix (UAB, MB, IĮ, AB)
                    val hasCompanyType = nextLower.contains("uab") || nextLower.contains("ab") || 
                                       nextLower.contains("mb") || nextLower.contains("iį") ||
                                       nextLower.contains("ltd") || nextLower.contains("oy") || 
                                       nextLower.contains("as") || nextLower.contains("sp")
                    
                    // Only accept if it has company type suffix
                    if (hasCompanyType) {
                        val cleaned = nextLine.trim()
                            .replace(Regex("^(imone|kompanija|bendrove|company|pvm|kodas|numeris|registracijos|saskaita|faktura|invoice|pardavejas|tiekejas)[:\\s]+", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("[:\\s]+(kodas|numeris|pvm|vat|saskaita|faktura|invoice).*$", RegexOption.IGNORE_CASE), "")
                            .trim()
                        if (cleaned.isNotBlank() && cleaned.length > 5 && 
                            !cleaned.matches(Regex("^[0-9\\s.,]+$")) &&
                            !cleaned.lowercase().contains("saskaita") &&
                            !cleaned.lowercase().contains("faktura") &&
                            !cleaned.lowercase().matches(labelPattern)) {
                            Timber.d("Extracted company name after label: $cleaned")
                            return cleaned
                        }
                    }
                }
            }
        } else {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && colonIdx + 1 < line.length) {
                val value = line.substring(colonIdx + 1).trim()
                val valueLower = value.lowercase()
                
                // Reject if it's a label
                if (valueLower.matches(Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|saskaita|faktura|invoice)$"))) {
                    return null
                }
                
                // REQUIRE company type suffix
                val hasCompanyType = valueLower.contains("uab") || valueLower.contains("ab") || 
                                   valueLower.contains("mb") || valueLower.contains("iį") ||
                                   valueLower.contains("ltd") || valueLower.contains("oy") || 
                                   valueLower.contains("as") || valueLower.contains("sp")
                
                if (value.isNotBlank() && value.length > 5 && hasCompanyType &&
                    !valueLower.contains("saskaita") &&
                    !valueLower.contains("faktura")) {
                    Timber.d("Extracted company name from colon format: $value")
                    return value
                }
            }
        }
        return null
    }
    
    private fun extractCompanyNameAdvanced(lines: List<String>, companyNumber: String?, vatNumber: String?): String? {
        // Strategy 1: Use VAT/company number to find company name (look backwards)
        if (companyNumber != null || vatNumber != null) {
            val searchNumber = companyNumber ?: vatNumber
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase().trim()
                if (searchNumber != null && l.contains(searchNumber)) {
                    val isInSellerSection = i < lines.size / 2 || 
                                           (i > 0 && (lines[i-1].lowercase().contains("pardavejas") ||
                                                     (i > 1 && lines[i-2].lowercase().contains("pardavejas"))))
                    if (isInSellerSection) {
                        for (j in 1..10) {
                            if (i - j >= 0) {
                                val candidateLine = lines[i - j].trim()
                                val candidateLower = candidateLine.lowercase()
                                if (candidateLower == "pardavejas" || candidateLower.contains("pardavejas")) {
                                    for (k in 1..3) {
                                        if (i - j + k < lines.size && i - j + k != i) {
                                            val nameLine = lines[i - j + k].trim()
                                            val nameLower = nameLine.lowercase()
                                            if (isValidCompanyNameLine(nameLine, nameLower)) {
                                                val cleaned = cleanCompanyName(nameLine)
                                                if (cleaned != null) return cleaned
                                            }
                                        }
                                    }
                                }
                                if (isValidCompanyNameLine(candidateLine, candidateLower)) {
                                    val cleaned = cleanCompanyName(candidateLine)
                                    if (cleaned != null) return cleaned
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Strategy 2: Find "PARDAVEJAS" or "TIEKEJAS" section
        val labelKeywords = listOf("pardavejas", "tiekejas", "gavejas")
        for (i in lines.indices) {
            val line = lines[i]
            val l = line.lowercase().trim()
            // Check if this line is a label
            val isLabel = labelKeywords.any { keyword -> 
                l == keyword || (l.contains(keyword) && l.length < 20)
            }
            if (isLabel) {
                // Look for company name in next lines
                for (j in 1..8) {
                    if (i + j < lines.size) {
                        val nextLine = lines[i + j].trim()
                        val nextLower = nextLine.lowercase()
                        // Skip if next line is also a label
                        if (labelKeywords.any { keyword -> nextLower == keyword || (nextLower.contains(keyword) && nextLower.length < 20) }) {
                            continue
                        }
                        if (isValidCompanyNameLine(nextLine, nextLower)) {
                            val cleaned = cleanCompanyName(nextLine)
                            if (cleaned != null) {
                                Timber.d("Extracted company name after label '$l': $cleaned")
                                return cleaned
                            }
                        }
                    }
                }
            }
        }
        
        // Strategy 3: Find company type in first half
        val firstHalf = lines.take(lines.size / 2)
        for (line in firstHalf) {
            val l = line.lowercase().trim()
            if (isValidCompanyNameLine(line, l)) {
                val cleaned = cleanCompanyName(line)
                if (cleaned != null) return cleaned
            }
        }
        return null
    }
    
    private fun isValidCompanyNameLine(line: String, lower: String): Boolean {
        val trimmedLower = lower.trim()
        
        // Reject labels
        if (trimmedLower.matches(Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|imone|kompanija|bendrove|company)$"))) {
            return false
        }
        
        if (trimmedLower.contains("saskaita") || trimmedLower.contains("faktura") || trimmedLower.contains("invoice") ||
            trimmedLower.contains("pvmsaskaitafaktura") || trimmedLower.contains("saskaitafaktura")) {
            return false
        }
        if (trimmedLower.contains("kodas") || trimmedLower.contains("numeris") || 
            (trimmedLower.contains("pvm") && !trimmedLower.contains("uab")) ||
            trimmedLower.matches(Regex(".*[0-9]{7,}.*"))) {
            return false
        }
        if (line.length < 5 || line.length > 150) {
            return false
        }
        
        // REQUIRE Lithuanian company type suffix (UAB, MB, IĮ, AB) - this is mandatory
        val hasCompanyType = trimmedLower.contains("uab") || trimmedLower.contains("ab") || 
                            trimmedLower.contains("mb") || trimmedLower.contains("iį") ||
                            trimmedLower.contains("ltd") || trimmedLower.contains("oy") || 
                            trimmedLower.contains("as") || trimmedLower.contains("sp")
        
        // Only return true if it has company type suffix
        return hasCompanyType
    }
    
    private fun cleanCompanyName(line: String): String? {
        val cleaned = line.trim()
            .replace(Regex("^(imone|kompanija|bendrove|company|pvm|kodas|numeris|registracijos|saskaita|faktura|invoice|pardavejas|tiekejas)[:\\s]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[:\\s]+(kodas|numeris|pvm|vat|saskaita|faktura|invoice).*$", RegexOption.IGNORE_CASE), "")
            .trim()
        
        val cleanedLower = cleaned.lowercase()
        
        // Reject if it's a label
        if (cleanedLower.matches(Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier)$"))) {
            return null
        }
        
        // REQUIRE company type suffix
        val hasCompanyType = cleanedLower.contains("uab") || cleanedLower.contains("ab") || 
                            cleanedLower.contains("mb") || cleanedLower.contains("iį") ||
                            cleanedLower.contains("ltd") || cleanedLower.contains("oy") || 
                            cleanedLower.contains("as") || cleanedLower.contains("sp")
        
        if (cleaned.isNotBlank() && cleaned.length > 3 && hasCompanyType &&
            !cleaned.matches(Regex("^[0-9\\s.,]+$")) &&
            !cleanedLower.contains("saskaita") &&
            !cleanedLower.contains("faktura") &&
            !cleanedLower.contains("invoice")) {
            return cleaned
        }
        return null
    }
    
    private fun isIban(value: String): Boolean {
        if (value.length >= 15 && (value.startsWith("LT") || value.matches(Regex("^[A-Z]{2}[0-9]{2}.*")))) {
            return true
        }
        if (value.startsWith("LT") && value.length >= 14 && value.matches(Regex("^LT[0-9]+$"))) {
            if (value.length >= 14 && value.startsWith("LT49")) {
                return true
            }
            if (value.length >= 16) {
                return true
            }
        }
        return false
    }
}


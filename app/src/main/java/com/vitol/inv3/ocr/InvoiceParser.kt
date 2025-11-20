package com.vitol.inv3.ocr

import android.graphics.Rect
import com.vitol.inv3.data.local.FieldRegion
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

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
    // Lithuanian VAT rates: 21%, 9%, 5%, and 0%
    private val LITHUANIAN_VAT_RATES = listOf(0.21, 0.09, 0.05, 0.0)
    
    /**
     * Calculate expected VAT amount based on amount without VAT and Lithuanian VAT rates.
     * Returns the VAT amount that matches one of the standard rates.
     */
    fun calculateVatFromAmount(amountWithoutVat: String?): String? {
        if (amountWithoutVat.isNullOrBlank()) return null
        val amount = amountWithoutVat.replace(",", ".").toDoubleOrNull() ?: return null
        
        // Try each VAT rate and see if we can find a matching VAT amount
        // This helps identify which amount is which
        for (rate in LITHUANIAN_VAT_RATES) {
            val calculatedVat = amount * rate
            val formatted = String.format("%.2f", calculatedVat)
            Timber.d("Calculated VAT for amount $amountWithoutVat at rate ${(rate * 100).toInt()}%: $formatted")
        }
        return null
    }
    
    /**
     * Validate and identify amounts using Lithuanian VAT rates.
     * Returns a pair of (amountWithoutVat, vatAmount) if they match a standard VAT rate.
     */
    fun identifyAmountsWithVatRates(amount1: String?, amount2: String?): Pair<String?, String?>? {
        if (amount1.isNullOrBlank() || amount2.isNullOrBlank()) return null
        
        val a1 = amount1.replace(",", ".").toDoubleOrNull() ?: return null
        val a2 = amount2.replace(",", ".").toDoubleOrNull() ?: return null
        
        // Try both combinations: a1 as base, a2 as VAT and vice versa
        for (rate in LITHUANIAN_VAT_RATES) {
            if (rate == 0.0) continue // Skip 0% rate
            
            // Try a1 as base, a2 as VAT
            val expectedVat1 = a1 * rate
            if (kotlin.math.abs(a2 - expectedVat1) < 0.01) { // Allow small rounding differences
                Timber.d("Identified amounts: base=$amount1, VAT=$amount2 (rate=${(rate * 100).toInt()}%)")
                return Pair(amount1, amount2)
            }
            
            // Try a2 as base, a1 as VAT
            val expectedVat2 = a2 * rate
            if (kotlin.math.abs(a1 - expectedVat2) < 0.01) {
                Timber.d("Identified amounts: base=$amount2, VAT=$amount1 (rate=${(rate * 100).toInt()}%)")
                return Pair(amount2, amount1)
            }
        }
        
        return null
    }
    
    /**
     * Parse invoice using keyword matching (original method).
     * @param excludeOwnCompanyNumber Own company number to exclude (partner's company only)
     * @param excludeOwnVatNumber Own company VAT number to exclude (partner's company only)
     */
    fun parse(lines: List<String>, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null): ParsedInvoice {
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
                    // First try to extract with serial and number combination from current and nearby lines
                    val extracted = extractInvoiceIdWithSerialAndNumber(
                        lines.subList(max(0, index - 2), min(lines.size, index + 3))
                    )
                    if (extracted != null) {
                        invoiceId = extracted
                    } else {
                        invoiceId = extractInvoiceIdFromLine(line) ?: takeKeyValue(line)
                    }
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
                    val extracted = FieldExtractors.tryExtractVatNumber(line, excludeOwnVatNumber)
                    if (extracted != null && !isIban(extracted)) {
                        // Double-check: exclude own company VAT number even if extracted
                        if (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true)) {
                            vatNumber = extracted
                        } else {
                            Timber.d("Skipped own company VAT number in first pass: $extracted")
                        }
                    } else {
                        // Try key-value extraction - but only if it has "LT" prefix
                        val keyValue = takeKeyValue(line)
                        if (keyValue != null && keyValue.uppercase().startsWith("LT")) {
                            val normalizedVat = keyValue.uppercase()
                            // Exclude own company VAT number
                            if (excludeOwnVatNumber == null || !normalizedVat.equals(excludeOwnVatNumber, ignoreCase = true)) {
                                vatNumber = normalizedVat
                            } else {
                                Timber.d("Skipped own company VAT number from key-value: $normalizedVat")
                            }
                        }
                    }
                }
                "Company_number" -> if (companyNumber == null) {
                    // Pass VAT number and own company number to exclude them from company number extraction
                    companyNumber = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
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
            // First try to extract with serial and number combination
            invoiceId = extractInvoiceIdWithSerialAndNumber(lines)
            if (invoiceId != null) {
                Timber.d("Extracted Invoice ID with serial+number in second pass: $invoiceId")
            } else {
                // Fallback to single-line extraction
                for (line in lines) {
                    val extracted = extractInvoiceIdFromLine(line)
                    if (extracted != null) {
                        invoiceId = extracted
                        Timber.d("Extracted Invoice ID in second pass: $invoiceId")
                        break
                    }
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
                val extracted = FieldExtractors.tryExtractVatNumber(line, excludeOwnVatNumber)
                if (extracted != null && !isIban(extracted)) {
                    // Double-check: exclude own company VAT number even if extracted
                    if (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true)) {
                        if (hasVatContext && isInSellerSection) {
                            sellerVatNumber = extracted
                            Timber.d("Found seller VAT number with context: $extracted")
                        }
                    } else {
                        Timber.d("Skipped own company VAT number in second pass: $extracted")
                    }
                }
            }
            if (sellerVatNumber != null) {
                vatNumber = sellerVatNumber
            }
        }
        
        // Second pass: Company number - ensure it's different from VAT number and own company number
        if (companyNumber == null || companyNumber == vatNumber?.removePrefix("LT")?.removePrefix("lt")) {
            // Look for company number that's different from VAT number and own company number
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                val hasCompanyContext = l.contains("imones kodas") || l.contains("imoneskodas") || 
                                       l.contains("registracijos kodas") || l.contains("registracijos numeris")
                if (hasCompanyContext) {
                    val extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                    if (extracted != null) {
                        companyNumber = extracted
                        Timber.d("Found company number with context: $extracted")
                        break
                    }
                }
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
        
        // Use Lithuanian VAT rates to help identify and validate amounts
        if (amountNoVat != null && vatAmount != null) {
            // Validate that amounts match a Lithuanian VAT rate
            val identified = identifyAmountsWithVatRates(amountNoVat, vatAmount)
            if (identified != null) {
                // Amounts match a standard VAT rate, use the identified values
                amountNoVat = identified.first
                vatAmount = identified.second
                Timber.d("Validated amounts using Lithuanian VAT rates: base=$amountNoVat, VAT=$vatAmount")
            }
        } else if (amountNoVat == null && vatAmount != null) {
            // Try to calculate amount without VAT from VAT amount using Lithuanian rates
            val vatValue: String = vatAmount ?: return ParsedInvoice(lines = lines)
            val vat = vatValue.replace(",", ".").toDoubleOrNull()
            if (vat != null) {
                for (rate in LITHUANIAN_VAT_RATES) {
                    if (rate == 0.0) continue
                    val calculatedBase = vat / rate
                    val formatted = String.format("%.2f", calculatedBase)
                    Timber.d("Calculated base amount from VAT $vatValue at rate ${(rate * 100).toInt()}%: $formatted")
                }
            }
        } else if (amountNoVat != null && vatAmount == null) {
            // Try to calculate VAT from amount without VAT using Lithuanian rates
            calculateVatFromAmount(amountNoVat)
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
    
    /**
     * Extract invoice ID by combining serial and number when they appear separately.
     * Example: Serial "25DF" + Number "2569" = "25DF2569"
     * This is a public function so it can be called from AzureDocumentIntelligenceService.
     * Serial number usually goes after word "Serija", just before invoice number, or 1-2 words separated.
     */
    fun extractInvoiceIdWithSerialAndNumber(lines: List<String>): String? {
        // Pattern for serial: 2-6 alphanumeric characters with at least one letter (e.g., "25DF", "SSP", "A1B2")
        val serialPattern = Regex("\\b([A-Z0-9]{2,6})\\b", RegexOption.IGNORE_CASE)
        // Pattern for invoice number: 3-15 digits (e.g., "2569", "000393734", "41222181749")
        val numberPattern = Regex("\\b([0-9]{3,15})\\b")
        
        // Look for "Serija" keyword (primary keyword for serial)
        val serialKeywords = listOf("serija", "series", "serijos", "serijos kodas", "serijos kodas:")
        val numberKeywords = listOf("numeris", "number", "nr", "nr.", "numerio", "numerio:")
        
        // First pass: Look for "Serija" keyword and extract serial that comes RIGHT AFTER it
        for (i in lines.indices) {
            val line = lines[i].trim()
            val lowerLine = line.lowercase()
            
            // Check if line contains "Serija" keyword
            val serijaKeyword = serialKeywords.firstOrNull { keyword -> 
                lowerLine.contains(Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE))
            }
            
            if (serijaKeyword != null) {
                // Find the position of "Serija" keyword
                val serijaIndex = lowerLine.indexOf(serijaKeyword, ignoreCase = true)
                if (serijaIndex >= 0) {
                    // Get the text after "Serija" keyword (within 1-2 words, ~50 characters)
                    val afterSerija = line.substring(serijaIndex + serijaKeyword.length).take(50)
                    
                    // Extract serial number that comes immediately after "Serija" (within 1-2 words)
                    val serialMatch = serialPattern.find(afterSerija)
                    if (serialMatch != null) {
                        val serialCandidate = serialMatch.groupValues[1].uppercase()
                        
                        // Validate serial: should contain at least one letter, not just digits
                        if (serialCandidate.any { it.isLetter() } && serialCandidate.length >= 2 && serialCandidate.length <= 6) {
                            if (!serialCandidate.matches(Regex("^[0-9]{4}$"))) { // Not a year
                                // Now look for invoice number after the serial (within 1-2 words)
                                val serialEndIndex = serialMatch.range.last + 1
                                val afterSerial = afterSerija.substring(serialEndIndex).take(30)
                                
                                // Try to find number keyword first
                                val hasNumberKeyword = numberKeywords.any { keyword ->
                                    afterSerial.lowercase().contains(Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE))
                                }
                                
                                // Extract number that comes after serial (within 1-2 words)
                                val numberMatch = numberPattern.find(afterSerial)
                                if (numberMatch != null) {
                                    val numberCandidate = numberMatch.groupValues[1]
                                    
                                    // Validate number: should be 3-15 digits, not a date component
                                    if (numberCandidate.length >= 3 && numberCandidate.length <= 15) {
                                        val isDateComponent = numberCandidate.toIntOrNull()?.let { num ->
                                            num in 1..31 && numberCandidate.length <= 2
                                        } ?: false
                                        if (!isDateComponent) {
                                            val combined = "$serialCandidate$numberCandidate"
                                            Timber.d("Found serial '$serialCandidate' after 'Serija' and number '$numberCandidate' in same line: $line -> $combined")
                                            return combined
                                        }
                                    }
                                }
                                
                                // If number not found in same line, try to find it in the same line or next line
                                // Look for number keyword in the same line
                                val numberKeywordInLine = numberKeywords.firstOrNull { keyword ->
                                    lowerLine.contains(Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE))
                                }
                                
                                if (numberKeywordInLine != null) {
                                    // Find number after the number keyword
                                    val numberKeywordIndex = lowerLine.indexOf(numberKeywordInLine, ignoreCase = true)
                                    if (numberKeywordIndex >= 0) {
                                        val afterNumberKeyword = line.substring(numberKeywordIndex + numberKeywordInLine.length).take(30)
                                        val numberMatch2 = numberPattern.find(afterNumberKeyword)
                                        if (numberMatch2 != null) {
                                            val numberCandidate = numberMatch2.groupValues[1]
                                            if (numberCandidate.length >= 3 && numberCandidate.length <= 15) {
                                                val isDateComponent = numberCandidate.toIntOrNull()?.let { num ->
                                                    num in 1..31 && numberCandidate.length <= 2
                                                } ?: false
                                                if (!isDateComponent) {
                                                    val combined = "$serialCandidate$numberCandidate"
                                                    Timber.d("Found serial '$serialCandidate' after 'Serija' and number '$numberCandidate' after number keyword in same line: $line -> $combined")
                                                    return combined
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // If number not found in same line, check next 1-2 lines
                                for (offset in 1..2) {
                                    val nextLineIndex = i + offset
                                    if (nextLineIndex < lines.size) {
                                        val nextLine = lines[nextLineIndex].trim()
                                        val nextLineLower = nextLine.lowercase()
                                        
                                        // Check if next line has number keyword
                                        val hasNumberKeyword = numberKeywords.any { keyword ->
                                            nextLineLower.contains(Regex("\\b$keyword\\b", RegexOption.IGNORE_CASE))
                                        }
                                        
                                        val numberMatch3 = numberPattern.find(nextLine)
                                        if (numberMatch3 != null) {
                                            val numberCandidate = numberMatch3.groupValues[1]
                                            if (numberCandidate.length >= 3 && numberCandidate.length <= 15) {
                                                val isDateComponent = numberCandidate.toIntOrNull()?.let { num ->
                                                    num in 1..31 && numberCandidate.length <= 2
                                                } ?: false
                                                if (!isDateComponent) {
                                                    val combined = "$serialCandidate$numberCandidate"
                                                    Timber.d("Found serial '$serialCandidate' after 'Serija' in line $i and number '$numberCandidate' in next line (offset $offset): $combined")
                                                    return combined
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return null
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
        
        // Pattern 4: Combined serial and number on same line (e.g., "Serija: 25DF Numeris: 2569" or "Serija SS Nr. 41222181749")
        val serialNumberPattern = Regex("(?:serija|series)[:.]?\\s*([A-Z0-9]{2,6})\\s+(?:numeris|number|nr)[:.]?\\s*([0-9]{3,15})", RegexOption.IGNORE_CASE)
        val serialNumberMatch = serialNumberPattern.find(line)
        if (serialNumberMatch != null) {
            val serial = serialNumberMatch.groupValues.getOrNull(1)?.uppercase()
            val number = serialNumberMatch.groupValues.getOrNull(2)
            if (serial != null && number != null && serial.any { it.isLetter() }) {
                val combined = "$serial$number"
                Timber.d("Extracted Invoice ID from serial+number pattern: $combined")
                return combined
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
    
    fun extractCompanyNameAdvanced(lines: List<String>, companyNumber: String?, vatNumber: String?): String? {
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


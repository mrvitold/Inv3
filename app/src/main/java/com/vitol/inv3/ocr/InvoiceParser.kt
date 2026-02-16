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
    val vatRate: String? = null,
    val lines: List<String> = emptyList(),
    /** User-visible message when no text could be read (e.g. empty OCR result). */
    val extractionMessage: String? = null
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
     * @param excludeOwnCompanyName Own company name to exclude (partner's company only)
     * @param invoiceType Invoice type: "S" for sales, "P" for purchase. Used to prioritize buyer vs seller sections.
     */
    fun parse(lines: List<String>, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null, excludeOwnCompanyName: String? = null, invoiceType: String? = null): ParsedInvoice {
        Timber.d("InvoiceParser.parse starting with ${lines.size} lines, excludeOwnCompanyNumber: $excludeOwnCompanyNumber, excludeOwnVatNumber: $excludeOwnVatNumber, excludeOwnCompanyName: $excludeOwnCompanyName")
        if (lines.isEmpty()) {
            Timber.w("InvoiceParser.parse called with empty lines list")
            return ParsedInvoice(lines = lines, vatRate = null)
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
        var vatRate: String? = null

        // PRE-PASS 1: Extract VAT number FIRST (company numbers are usually near VAT numbers)
        Timber.d("InvoiceParser: Pre-pass 1 - searching for VAT number")
        var vatNumberLineIndex: Int? = null
        for (i in lines.indices) {
            val line = lines[i]
            val extracted = FieldExtractors.tryExtractVatNumber(line, excludeOwnVatNumber)
            if (extracted != null && !isIban(extracted)) {
                if (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true)) {
                    vatNumber = extracted
                    vatNumberLineIndex = i
                    Timber.d("InvoiceParser: Pre-pass 1 found VAT number '$vatNumber' on line $i: $line")
                    break
                }
            }
        }
        // Also try key-value extraction for VAT
        if (vatNumber == null) {
            for (i in lines.indices) {
                val line = lines[i]
                val key = KeywordMapping.normalizeKey(line)
                if (key == "VAT_number") {
                    val keyValue = takeKeyValue(line)
                    if (keyValue != null && keyValue.uppercase().startsWith("LT")) {
                        val normalizedVat = keyValue.uppercase()
                        if (excludeOwnVatNumber == null || !normalizedVat.equals(excludeOwnVatNumber, ignoreCase = true)) {
                            vatNumber = normalizedVat
                            vatNumberLineIndex = i
                            Timber.d("InvoiceParser: Pre-pass 1 found VAT number '$vatNumber' from key-value on line $i")
                            break
                        }
                    }
                }
            }
        }

        // PRE-PASS 2: Extract company number, PRIORITIZING ones right after "Įmonės kodas" text
        // Company code usually goes just after "Įmonės kodas" or "Kodas" text - this is the key insight!
        Timber.d("InvoiceParser: Pre-pass 2 - searching for company number after 'Įmonės kodas' or 'Kodas' text")
        val allCompanyNumbers = mutableListOf<Pair<String, Int>>() // (number, lineIndex)
        val companyCodeKeywords = listOf("įmonės kodas", "imones kodas", "im. kodas", "im.k", "įm.k", "im.kodas", "kodas", "company code", "company number", "company_number")
        val buyerKeywords = listOf("pirkėjo", "pirkėjas", "buyer", "pirkėjo pavadinimas", "buyer name")
        val sellerKeywords = listOf("pardavėjo", "pardavėjas", "seller", "pardavėjo pavadinimas", "seller name")
        
        // PRIORITY 0: Company number RIGHT AFTER "Įmonės kodas" text (highest priority!)
        // Collect ALL company numbers found after "Įmonės kodas" with their section info
        data class CompanyNumberWithSection(val number: String, val lineIndex: Int, val isBuyer: Boolean, val isSeller: Boolean)
        val companyNumbersAfterKeyword = mutableListOf<CompanyNumberWithSection>()
        
        for (i in lines.indices) {
            val line = lines[i]
            val lineLower = line.lowercase()
            
            // Check if this line contains "Įmonės kodas" or standalone "Kodas" keyword
            // For standalone "Kodas", make sure it's not part of "PVM kodas" or other contexts
            val hasCompanyCodeKeyword = companyCodeKeywords.any { keyword -> 
                if (keyword == "kodas") {
                    // For standalone "Kodas", check it's not part of "PVM kodas" or invoice context
                    lineLower.contains(Regex("\\bkodas\\b", RegexOption.IGNORE_CASE)) &&
                    !lineLower.contains("pvm kodas") &&
                    !lineLower.contains("pvmnumeris") &&
                    !lineLower.contains("saskaitos kodas")
                } else {
                    lineLower.contains(keyword)
                }
            }
            
            if (hasCompanyCodeKeyword) {
                // Extract company number from this line (right after the keyword)
                val extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                if (extracted != null) {
                    // Check if this is in buyer or seller section
                    val searchRange = max(0, i - 10)..min(lines.size - 1, i + 2)
                    val isInBuyerSection = searchRange.any { lineIdx ->
                        val searchLineLower = lines[lineIdx].lowercase()
                        buyerKeywords.any { keyword -> searchLineLower.contains(keyword) }
                    }
                    val isInSellerSection = searchRange.any { lineIdx ->
                        val searchLineLower = lines[lineIdx].lowercase()
                        sellerKeywords.any { keyword -> searchLineLower.contains(keyword) }
                    }
                    
                    companyNumbersAfterKeyword.add(
                        CompanyNumberWithSection(extracted, i, isInBuyerSection, isInSellerSection)
                    )
                    val section = when {
                        isInBuyerSection && !isInSellerSection -> "buyer"
                        isInSellerSection && !isInBuyerSection -> "seller"
                        else -> "unknown"
                    }
                    Timber.d("InvoiceParser: Pre-pass 2 found $section company number '$extracted' right after 'Įmonės kodas' on line $i")
                }
            }
        }
        
        // Determine priority based on invoice type:
        // - Sales (S): prioritize buyer section (we want buyer's company number)
        // - Purchase (P): prioritize seller section (we want seller's company number)
        val isSalesInvoice = invoiceType?.uppercase() == "S"
        val isPurchaseInvoice = invoiceType?.uppercase() == "P"
        
        // Select the best company number from those found after "Įmonės kodas"
        var companyNumberAfterKeyword: Pair<String, Int>? = null
        var isBuyerSection = false
        
        if (companyNumbersAfterKeyword.isNotEmpty()) {
            // Filter out own company number - we want the OTHER company's number (partner's)
            // It doesn't matter if it's buyer or seller - we just need the partner's company number
            val partnerCompanyNumbers = companyNumbersAfterKeyword.filter { 
                excludeOwnCompanyNumber == null || it.number != excludeOwnCompanyNumber.trim() 
            }
            
            val selected = if (excludeOwnCompanyNumber == null && companyNumbersAfterKeyword.size >= 2) {
                // CRITICAL: If excludeOwnCompanyNumber is null but we have 2+ company numbers,
                // we need to select the OTHER one (not own). Use invoice type to determine:
                // - Purchase (P): buyer is own company, seller is partner -> select EARLIER (seller's)
                // - Sales (S): seller is own company, buyer is partner -> select LATER (buyer's)
                // - Unknown: prefer seller (earlier) as default
                val selection = when {
                    isPurchaseInvoice -> {
                        // Purchase: select seller's company number (earlier, appears first)
                        Timber.d("InvoiceParser: excludeOwnCompanyNumber is null but found ${companyNumbersAfterKeyword.size} company numbers, selecting EARLIER one (seller's) for purchase invoice")
                        companyNumbersAfterKeyword.minByOrNull { it.lineIndex }
                    }
                    isSalesInvoice -> {
                        // Sales: select buyer's company number (later, appears after seller)
                        Timber.d("InvoiceParser: excludeOwnCompanyNumber is null but found ${companyNumbersAfterKeyword.size} company numbers, selecting LATER one (buyer's) for sales invoice")
                        companyNumbersAfterKeyword.maxByOrNull { it.lineIndex }
                    }
                    else -> {
                        // Unknown type: prefer seller (earlier) as default
                        Timber.d("InvoiceParser: excludeOwnCompanyNumber is null but found ${companyNumbersAfterKeyword.size} company numbers, selecting EARLIER one (seller's) as default")
                        companyNumbersAfterKeyword.minByOrNull { it.lineIndex }
                    }
                }
                selection
            } else if (partnerCompanyNumbers.isNotEmpty()) {
                // We have partner company numbers (after filtering) - select based on invoice type:
                // - Purchase (P): prefer seller's company number (partner is seller)
                // - Sales (S): prefer buyer's company number (partner is buyer)
                val prioritized = when {
                    isPurchaseInvoice -> {
                        // Purchase: prefer seller's company number
                        partnerCompanyNumbers.firstOrNull { it.isSeller && !it.isBuyer }
                            ?: partnerCompanyNumbers.firstOrNull { it.isSeller || it.isBuyer }
                            ?: partnerCompanyNumbers.first()
                    }
                    isSalesInvoice -> {
                        // Sales: prefer buyer's company number
                        partnerCompanyNumbers.firstOrNull { it.isBuyer && !it.isSeller }
                            ?: partnerCompanyNumbers.firstOrNull { it.isBuyer || it.isSeller }
                            ?: partnerCompanyNumbers.first()
                    }
                    else -> {
                        // Unknown type: prefer buyer's company number as default
                        partnerCompanyNumbers.firstOrNull { it.isBuyer && !it.isSeller }
                            ?: partnerCompanyNumbers.firstOrNull { it.isSeller && !it.isBuyer }
                            ?: partnerCompanyNumbers.firstOrNull { it.isBuyer || it.isSeller }
                            ?: partnerCompanyNumbers.first()
                    }
                }
                prioritized
            } else {
                // All are own company numbers - this shouldn't happen, but fallback to first
                Timber.w("InvoiceParser: All company numbers after 'Įmonės kodas' match own company number!")
                companyNumbersAfterKeyword.first()
            }
            
            if (selected != null) {
                companyNumberAfterKeyword = Pair(selected.number, selected.lineIndex)
                isBuyerSection = selected.isBuyer
                val sectionDesc = when {
                    selected.isBuyer && !selected.isSeller -> "buyer"
                    selected.isSeller && !selected.isBuyer -> "seller"
                    else -> "unknown"
                }
                val isOwnCompany = excludeOwnCompanyNumber != null && selected.number == excludeOwnCompanyNumber.trim()
                Timber.d("InvoiceParser: Pre-pass 2 selected $sectionDesc company number '${selected.number}' from 'Įmonės kodas' on line ${selected.lineIndex} (invoice type: ${invoiceType ?: "unknown"}, isOwn: $isOwnCompany)")
            }
        }
        
        // Also collect all company numbers for fallback
        for (i in lines.indices) {
            val line = lines[i]
            val extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
            if (extracted != null) {
                allCompanyNumbers.add(Pair(extracted, i))
                Timber.d("InvoiceParser: Pre-pass 2 found company number candidate '$extracted' on line $i: $line")
            }
        }
        
        if (allCompanyNumbers.isNotEmpty() || companyNumberAfterKeyword != null) {
            // Filter out own company number
            val filteredCandidates = allCompanyNumbers.filter { (num, _) ->
                if (excludeOwnCompanyNumber != null && num == excludeOwnCompanyNumber.trim()) {
                    Timber.d("InvoiceParser: Pre-pass 2 excluding own company number '$num'")
                    false
                } else {
                    true
                }
            }
            
            val candidatesToUse = if (filteredCandidates.isEmpty()) {
                Timber.d("InvoiceParser: All candidates were own company number, using original list")
                allCompanyNumbers
            } else {
                filteredCandidates
            }
            
            // Determine priority based on invoice type:
            // - Sales (S): prioritize buyer section (we want buyer's company number)
            // - Purchase (P): prioritize seller section (we want seller's company number)
            val isSalesInvoice = invoiceType?.uppercase() == "S"
            val isPurchaseInvoice = invoiceType?.uppercase() == "P"
            
            // PRIORITY 1: Company number right after "Įmonės kodas" based on invoice type
            if (companyNumberAfterKeyword != null) {
                val (num, idx) = companyNumberAfterKeyword
                val shouldUse = when {
                    isSalesInvoice && isBuyerSection -> true // Sales: want buyer's company number
                    isPurchaseInvoice && !isBuyerSection -> true // Purchase: want seller's company number
                    !isSalesInvoice && !isPurchaseInvoice -> isBuyerSection // Unknown type: prefer buyer (default)
                    else -> false
                }
                
                if (shouldUse && (excludeOwnCompanyNumber == null || num != excludeOwnCompanyNumber.trim())) {
                    companyNumber = num
                    val section = if (isBuyerSection) "buyer" else "seller"
                    Timber.d("InvoiceParser: Pre-pass 2 selected $section company number '$num' from 'Įmonės kodas' on line $idx (invoice type: ${invoiceType ?: "unknown"})")
                }
            }
            
            // PRIORITY 2: Company number right after "Įmonės kodas" in opposite section (fallback)
            if (companyNumber == null && companyNumberAfterKeyword != null) {
                val (num, idx) = companyNumberAfterKeyword
                val shouldUse = when {
                    isSalesInvoice && !isBuyerSection -> false // Sales: don't use seller's
                    isPurchaseInvoice && isBuyerSection -> false // Purchase: don't use buyer's
                    else -> true // Unknown type or opposite section: use as fallback
                }
                
                if (shouldUse && (excludeOwnCompanyNumber == null || num != excludeOwnCompanyNumber.trim())) {
                    companyNumber = num
                    val section = if (isBuyerSection) "buyer" else "seller"
                    Timber.d("InvoiceParser: Pre-pass 2 selected $section company number '$num' from 'Įmonės kodas' on line $idx (fallback, invoice type: ${invoiceType ?: "unknown"})")
                }
            }
            
            // PRIORITY 3: Company number near buyer/seller keywords based on invoice type
            if (companyNumber == null) {
                val prioritized = if (isSalesInvoice) {
                    // Sales: prioritize buyer keywords
                    candidatesToUse.firstOrNull { (num, idx) ->
                        val searchRange = max(0, idx - 5)..min(lines.size - 1, idx + 2)
                        searchRange.any { lineIdx ->
                            val lineLower = lines[lineIdx].lowercase()
                            buyerKeywords.any { keyword -> lineLower.contains(keyword) }
                        }
                    } ?: candidatesToUse.firstOrNull { (num, idx) ->
                        val searchRange = max(0, idx - 5)..min(lines.size - 1, idx + 2)
                        searchRange.any { lineIdx ->
                            val lineLower = lines[lineIdx].lowercase()
                            sellerKeywords.any { keyword -> lineLower.contains(keyword) }
                        }
                    }
                } else if (isPurchaseInvoice) {
                    // Purchase: prioritize seller keywords
                    candidatesToUse.firstOrNull { (num, idx) ->
                        val searchRange = max(0, idx - 5)..min(lines.size - 1, idx + 2)
                        searchRange.any { lineIdx ->
                            val lineLower = lines[lineIdx].lowercase()
                            sellerKeywords.any { keyword -> lineLower.contains(keyword) }
                        }
                    } ?: candidatesToUse.firstOrNull { (num, idx) ->
                        val searchRange = max(0, idx - 5)..min(lines.size - 1, idx + 2)
                        searchRange.any { lineIdx ->
                            val lineLower = lines[lineIdx].lowercase()
                            buyerKeywords.any { keyword -> lineLower.contains(keyword) }
                        }
                    }
                } else {
                    // Unknown type: prioritize buyer keywords
                    candidatesToUse.firstOrNull { (num, idx) ->
                        val searchRange = max(0, idx - 5)..min(lines.size - 1, idx + 2)
                        searchRange.any { lineIdx ->
                            val lineLower = lines[lineIdx].lowercase()
                            buyerKeywords.any { keyword -> lineLower.contains(keyword) }
                        }
                    } ?: candidatesToUse.firstOrNull { (num, idx) ->
                        val searchRange = max(0, idx - 5)..min(lines.size - 1, idx + 2)
                        searchRange.any { lineIdx ->
                            val lineLower = lines[lineIdx].lowercase()
                            sellerKeywords.any { keyword -> lineLower.contains(keyword) }
                        }
                    }
                }
                companyNumber = prioritized?.first
                if (companyNumber != null) {
                    Timber.d("InvoiceParser: Pre-pass 2 selected company number '$companyNumber' near keywords (invoice type: ${invoiceType ?: "unknown"})")
                }
            }
            
            // PRIORITY 4: Company number after "Kodas" keyword (high priority, very reliable)
            if (companyNumber == null) {
                for (i in lines.indices) {
                    val line = lines[i]
                    val lineLower = line.lowercase()
                    // Check if line contains "Kodas" keyword (standalone, not "Įmonės kodas")
                    if (lineLower.contains(Regex("\\bkodas\\b", RegexOption.IGNORE_CASE)) && 
                        !lineLower.contains("įmonės kodas") && 
                        !lineLower.contains("imones kodas")) {
                        val extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                        if (extracted != null && (excludeOwnCompanyNumber == null || extracted != excludeOwnCompanyNumber.trim())) {
                            // Check if this is in seller section (for purchase invoices) or buyer section (for sales)
                            val searchRange = max(0, i - 5)..min(lines.size - 1, i + 2)
                            val isInSellerSection = searchRange.any { lineIdx ->
                                val searchLineLower = lines[lineIdx].lowercase()
                                sellerKeywords.any { keyword -> searchLineLower.contains(keyword) }
                            }
                            val isInBuyerSection = searchRange.any { lineIdx ->
                                val searchLineLower = lines[lineIdx].lowercase()
                                buyerKeywords.any { keyword -> searchLineLower.contains(keyword) }
                            }
                            
                            val shouldUse = when {
                                isPurchaseInvoice && isInSellerSection -> true // Purchase: want seller's
                                isSalesInvoice && isInBuyerSection -> true // Sales: want buyer's
                                !isPurchaseInvoice && !isSalesInvoice -> isInSellerSection || isInBuyerSection // Unknown: prefer any section
                                else -> false
                            }
                            
                            if (shouldUse) {
                                companyNumber = extracted
                                Timber.d("InvoiceParser: Pre-pass 2 selected company number '$companyNumber' from 'Kodas' on line $i (invoice type: ${invoiceType ?: "unknown"})")
                                break
                            }
                        }
                    }
                }
            }
            
            // PRIORITY 5: First valid company number (excluding own company number and invoice numbers)
            if (companyNumber == null) {
                // Filter out numbers that appear to be invoice numbers (after "nr", "serija", etc.)
                val filteredCandidates = candidatesToUse.filter { (num, idx) ->
                    val line = lines[idx]
                    val lineLower = line.lowercase()
                    // Skip if it's clearly an invoice number
                    val isInvoiceNumber = lineLower.contains(Regex("(?:nr\\.?|numeris|serija)\\s*$num", RegexOption.IGNORE_CASE)) ||
                                        (lineLower.contains("serija") && lineLower.contains("nr"))
                    if (isInvoiceNumber) {
                        Timber.d("InvoiceParser: Pre-pass 2 skipping '$num' - appears to be invoice number")
                        false
                    } else {
                        true
                    }
                }
                
                companyNumber = filteredCandidates.firstOrNull { (num, _) -> 
                    excludeOwnCompanyNumber == null || num != excludeOwnCompanyNumber.trim() 
                }?.first
                ?: filteredCandidates.firstOrNull()?.first // Fallback to first if all are own company
                
                if (companyNumber != null) {
                    Timber.d("InvoiceParser: Pre-pass 2 selected first valid company number '$companyNumber'")
                }
            }
            
            Timber.d("InvoiceParser: Pre-pass 2 final selection: '$companyNumber' from ${allCompanyNumbers.size} candidates")
        }

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
                    // Reuse isPurchaseInvoice declared earlier in the function
                    // Skip if line is just a label
                    val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier)$", RegexOption.IGNORE_CASE)
                    
                    // For purchase invoices, also check if this line contains "Tiekėjas" and extract from next line
                    // Handle Lithuanian characters: "tiekėjas" contains 'ė', so use case-insensitive contains
                    val containsTiekejas = l.contains("tiekėjas", ignoreCase = true) || l.contains("tiekejas", ignoreCase = true)
                    if (isPurchaseInvoice && containsTiekejas && l.length <= 25) {
                        // Look for company name in next 1-3 lines after "Tiekėjas"
                        for (offset in 1..3) {
                            if (index + offset < lines.size) {
                                val nextLine = lines[index + offset].trim()
                                val nextLower = nextLine.lowercase()
                                // Skip if next line is also a label
                                if (!nextLower.matches(labelPattern) && 
                                    !nextLower.contains("saskaita") && 
                                    !nextLower.contains("faktura") &&
                                    !nextLower.contains("kodas") &&
                                    !nextLower.contains("numeris") &&
                                    !nextLower.matches(Regex(".*[0-9]{7,}.*")) &&
                                    nextLine.length >= 5 &&
                                    (nextLower.contains("uab") || nextLower.contains("ab") || 
                                     nextLower.contains("mb") || nextLower.contains("iį") ||
                                     nextLower.contains("ltd") || nextLower.contains("oy") || 
                                     nextLower.contains("as") || nextLower.contains("sp"))) {
                                    val extracted = extractCompanyNameFromLine(nextLine, lines, index + offset)
                                    if (extracted != null) {
                                        val extractedLower = extracted.lowercase()
                                        // CRITICAL: Exclude own company name - NEVER fill it
                                        if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(extracted, excludeOwnCompanyName)) {
                                            Timber.d("Skipped own company name after Tiekėjas: $extracted")
                                        } else if (!extractedLower.matches(labelPattern) && 
                                            !extractedLower.contains("saskaita") && 
                                            !extractedLower.contains("faktura") && 
                                            extracted.length > 5) {
                                            companyName = extracted
                                            Timber.d("Extracted Company_name after Tiekėjas label: $companyName")
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Standard extraction (for non-label lines)
                    if (companyName == null && !l.matches(labelPattern) && !l.contains("saskaita") && !l.contains("faktura") && !l.contains("invoice")) {
                        val extracted = extractCompanyNameFromLine(line, lines, index)
                        if (extracted != null) {
                            val extractedLower = extracted.lowercase()
                            // CRITICAL: Exclude own company name - NEVER fill it
                            if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(extracted, excludeOwnCompanyName)) {
                                Timber.d("Skipped own company name in first pass: $extracted")
                            } else if (!extractedLower.matches(labelPattern) && 
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
                "Company_number" -> {
                    // Always try to extract, even if we found one in pre-pass (keyword match might be more accurate)
                    val extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                    if (extracted != null) {
                        companyNumber = extracted
                        Timber.d("InvoiceParser: First pass found company number '$extracted' from keyword match on line: $line")
                    }
                }
            }
        }
        
        // POST-PASS: If VAT number was found in first pass but company number is still null,
        // search for company numbers near the VAT number location
        val currentVatNumber = vatNumber // Store in local val for smart cast
        if (currentVatNumber != null && companyNumber == null) {
            Timber.d("InvoiceParser: Post-pass - VAT number found but company number is null, searching near VAT location")
            // Find VAT number location in lines
            var vatLocation: Int? = null
            for (i in lines.indices) {
                val line = lines[i]
                if (line.contains(currentVatNumber, ignoreCase = true)) {
                    vatLocation = i
                    Timber.d("InvoiceParser: Found VAT number '$currentVatNumber' on line $i")
                    break
                }
            }
            
            // Search for company numbers within 5 lines of VAT number
            if (vatLocation != null) {
                val searchStart = max(0, vatLocation - 5)
                val searchEnd = min(lines.size - 1, vatLocation + 5)
                Timber.d("InvoiceParser: Searching for company number near VAT (lines $searchStart-$searchEnd)")
                
                for (i in searchStart..searchEnd) {
                    val line = lines[i]
                    val extracted = FieldExtractors.tryExtractCompanyNumber(line, currentVatNumber, excludeOwnCompanyNumber)
                    if (extracted != null) {
                        companyNumber = extracted
                        val distance = kotlin.math.abs(i - vatLocation)
                        Timber.d("InvoiceParser: Post-pass found company number '$extracted' on line $i (distance from VAT: $distance lines)")
                        break
                    }
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
        
        // Helper function to check if company name has valid type suffix
        fun hasCompanyTypeSuffix(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            val lower = name.lowercase()
            return lower.contains("uab") || lower.contains("ab") || lower.contains("mb") || 
                   lower.contains("iį") || lower.contains("ltd") || lower.contains("as") || 
                   lower.contains("sp") || lower.contains("oy")
        }
        
        // Second pass: company name extraction with multiple strategies
        // For purchase invoices, always try extractCompanyNameAdvanced to prioritize "Tiekėjas" section
        // Reuse isPurchaseInvoice declared earlier in the function
        val shouldTryAdvanced = companyName == null || isInvalidCompanyName(companyName) || isPurchaseInvoice
        
        if (shouldTryAdvanced) {
            if (companyName != null && !isInvalidCompanyName(companyName)) {
                Timber.d("Company name already found but re-checking with advanced extraction for purchase invoice: $companyName")
            } else {
                Timber.d("Company name is null or invalid, starting second pass extraction")
            }
            var extractedName = extractCompanyNameAdvanced(lines, companyNumber, vatNumber, excludeOwnCompanyNumber, excludeOwnCompanyName, invoiceType)
            if (extractedName != null && isKnownSectionLabel(extractedName)) {
                Timber.d("Rejected section label as company name: $extractedName")
                extractedName = null
            }
            if (extractedName != null && isInvalidCompanyName(extractedName)) {
                Timber.d("Rejected invalid company name (e.g. amount-in-words): $extractedName")
                extractedName = null
            }
            // CRITICAL: Exclude own company name - NEVER fill it (normalize to match with/without quotes)
            if (extractedName != null && excludeOwnCompanyName != null && isSameAsOwnCompanyName(extractedName, excludeOwnCompanyName)) {
                Timber.d("Skipped own company name in second pass: $extractedName")
                // Keep existing companyName if it was valid, otherwise set to null
                if (companyName == null || isInvalidCompanyName(companyName)) {
                    companyName = null
                }
            } else if (extractedName != null && hasCompanyTypeSuffix(extractedName)) {
                // Prefer extracted name if it has company type suffix (more reliable)
                companyName = extractedName
                Timber.d("Replaced company name with advanced extraction result: $companyName")
            } else if (companyName == null || isInvalidCompanyName(companyName)) {
                // Use extracted name if current is null or invalid
                companyName = extractedName
            }
        } else {
            // CRITICAL: Double-check existing company name - exclude own company name
            if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(companyName ?: "", excludeOwnCompanyName)) {
                Timber.d("Skipped own company name (already extracted): $companyName")
                companyName = null
            }
        }
        
        // Second pass: VAT number with context
        // For purchase invoices: look for seller/vendor VAT (exclude own company)
        // For sales invoices: look for buyer/customer VAT (exclude own company)
        // Since we don't know invoice type during OCR, check both sections and exclude own company
        if (vatNumber == null) {
            var partnerVatNumber: String? = null
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                // Check if we're in seller section (for purchase invoices) or buyer section (for sales invoices)
                val isInSellerSection = i < lines.size / 2 || 
                                       (i > 0 && lines[i-1].lowercase().contains("pardavejas"))
                val isInBuyerSection = i > lines.size / 2 || 
                                      (i > 0 && (lines[i-1].lowercase().contains("pirkėjas") || 
                                       lines[i-1].lowercase().contains("pirkėjo")))
                val hasVatContext = l.contains("pvm kodas") || l.contains("pvmkodas") || 
                                   l.contains("pvm numeris") || l.contains("pvmnumeris") ||
                                   l.contains("pvm kodas:") || l.contains("pvmkodas:")
                val extracted = FieldExtractors.tryExtractVatNumber(line, excludeOwnVatNumber)
                if (extracted != null && !isIban(extracted)) {
                    // Double-check: exclude own company VAT number even if extracted
                    if (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true)) {
                        // Accept VAT number from either seller or buyer section (whichever is not own company)
                        if (hasVatContext && (isInSellerSection || isInBuyerSection)) {
                            partnerVatNumber = extracted
                            Timber.d("Found partner VAT number with context (seller/buyer section): $extracted")
                            break // Use first valid VAT number found
                        }
                    } else {
                        Timber.d("Skipped own company VAT number in second pass: $extracted")
                    }
                }
            }
            if (partnerVatNumber != null) {
                vatNumber = partnerVatNumber
            }
        }
        
        // Second pass: Company number - ensure it's different from VAT number and own company number
        // Always try to extract company number in second pass, even if we found something in first pass
        // (first pass might have found own company number)
        if (companyNumber == null || companyNumber == vatNumber?.removePrefix("LT")?.removePrefix("lt") || companyNumber == excludeOwnCompanyNumber) {
            // Look for company number that's different from VAT number and own company number
            // Priority 1: Direct context with Lithuanian keywords
            val companyCodeKeywords = listOf(
                "imones kodas", "imoneskodas", "imones kodas:", "imoneskodas:",
                "im. kodas", "im.kodas", "im. kodas:", "im.kodas:",
                "im.k", "įm.k", "im.k.",
                "registracijos kodas", "registracijoskodas", "registracijos kodas:",
                "registracijos numeris", "registracijosnumeris", "registracijos numeris:",
                "imonenes registracijos numeris", "imonenesregistracijosnumeris",
                "pirkėjo", "pardavėjo", "buyer", "seller", "customer", "vendor" // Also check buyer/seller sections
            )
            
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                val hasCompanyContext = companyCodeKeywords.any { keyword -> l.contains(keyword) }
                
                if (hasCompanyContext) {
                    // Try to extract from same line
                    var extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                    if (extracted != null) {
                        companyNumber = extracted
                        Timber.d("Found company number with context on same line: $extracted")
                        break
                    }
                    
                    // Try next 3 lines (company code often appears after label, sometimes with spacing)
                    for (offset in 1..3) {
                        if (i + offset < lines.size) {
                            val nextLine = lines[i + offset]
                            extracted = FieldExtractors.tryExtractCompanyNumber(nextLine, vatNumber, excludeOwnCompanyNumber)
                            if (extracted != null) {
                                companyNumber = extracted
                                Timber.d("Found company number with context on line ${i + offset}: $extracted")
                                break
                            }
                        }
                    }
                    if (companyNumber != null) break
                }
            }
            
            // Priority 2: If still not found, try key-value extraction (e.g., "Įmonės kodas: 303309250")
            if (companyNumber == null) {
                for (i in lines.indices) {
                    val line = lines[i]
                    val l = line.lowercase()
                    if (companyCodeKeywords.any { keyword -> l.contains(keyword) }) {
                        val colonIdx = line.indexOf(':')
                        if (colonIdx > 0 && colonIdx + 1 < line.length) {
                            val value = line.substring(colonIdx + 1).trim()
                            val extracted = FieldExtractors.tryExtractCompanyNumber(value, vatNumber, excludeOwnCompanyNumber)
                            if (extracted != null) {
                                companyNumber = extracted
                                Timber.d("Found company number from key-value: $extracted")
                                break
                            }
                        }
                    }
                }
            }
            
            // Priority 3: Fallback - search all lines for company numbers (without keyword requirement)
            // This helps when company number is not near keywords or keywords are misspelled
            // Use less strict filtering for fallback since we're searching more broadly
            if (companyNumber == null) {
                Timber.d("Company number still null, trying fallback extraction from all lines")
                for (i in lines.indices) {
                    val line = lines[i]
                    // Skip lines that are clearly not relevant (too short, just labels, etc.)
                    val l = line.lowercase().trim()
                    if (l.length < 5 || l.matches(Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer)$", RegexOption.IGNORE_CASE))) {
                        continue
                    }
                    
                    // Try extraction with less strict filtering
                    val extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                    if (extracted != null) {
                        companyNumber = extracted
                        Timber.d("Found company number in fallback search on line $i: $extracted")
                        break
                    }
                }
            }
            
            // Priority 4: Last resort - extract from any line that contains a valid pattern
            // Even more permissive - only exclude obvious false positives
            if (companyNumber == null) {
                Timber.d("Company number still null, trying last resort extraction")
                val allMatches = mutableListOf<Pair<String, String>>() // (number, line)
                
                for (i in lines.indices) {
                    val line = lines[i]
                    // Find all potential company numbers in the line
                    val matches = Regex("\\b((?:[1-3][0-9]{8})|(?:[0-9]{6}))\\b").findAll(line)
                    for (match in matches) {
                        val candidate = match.groupValues.getOrNull(1) ?: continue
                        
                        // Basic validation - must match strict pattern
                        if (!candidate.matches(Regex("^(([1-3][0-9]{8})|([0-9]{6}))$"))) {
                            continue
                        }
                        
                        // Exclude if it's the VAT number digits
                        val vatDigits = vatNumber?.removePrefix("LT")?.removePrefix("lt")
                        if (vatDigits != null && candidate == vatDigits) {
                            continue
                        }
                        
                        // Exclude if it's own company number
                        if (excludeOwnCompanyNumber != null && candidate == excludeOwnCompanyNumber.trim()) {
                            continue
                        }
                        
                        // Exclude if it's clearly part of IBAN (starts with LT and candidate follows)
                        if (line.contains("LT$candidate", ignoreCase = true)) {
                            continue
                        }
                        
                        // Exclude if it's clearly an invoice number (near "nr", "serija", etc.)
                        val lineLower = line.lowercase()
                        val matchStart = match.range.first
                        val contextBefore = if (matchStart > 0) lineLower.substring(maxOf(0, matchStart - 15), matchStart) else ""
                        if (contextBefore.contains(Regex("(?:nr|numeris|serija)\\s*$"))) {
                            continue
                        }
                        
                        // Exclude if it's clearly an amount (has decimal point nearby)
                        val contextAfter = if (match.range.last < line.length - 1) line.substring(match.range.last + 1, minOf(line.length, match.range.last + 3)) else ""
                        if (contextAfter.startsWith(",") || contextAfter.startsWith(".")) {
                            continue
                        }
                        
                        // This looks like a valid company number
                        allMatches.add(Pair(candidate, line))
                    }
                }
                
                // If we found matches, prefer ones that are near company-related keywords
                if (allMatches.isNotEmpty()) {
                    val companyKeywords = listOf("imones", "kodas", "registracijos", "pirkėjo", "pardavėjo", "buyer", "seller", "customer", "vendor")
                    
                    // First try to find one near company keywords
                    var found = false
                    for ((number, line) in allMatches) {
                        val lineLower = line.lowercase()
                        if (companyKeywords.any { keyword -> lineLower.contains(keyword) }) {
                            companyNumber = number
                            Timber.d("Found company number in last resort search (near keywords): $number")
                            found = true
                            break
                        }
                    }
                    
                    // If none found near keywords, use the first one (better than nothing)
                    if (!found && allMatches.isNotEmpty()) {
                        companyNumber = allMatches.first().first
                        Timber.d("Found company number in last resort search (first match): $companyNumber")
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
            val amountNoVatVal = amountNoVat!!
            // Try to calculate VAT from amount without VAT using Lithuanian rates
            calculateVatFromAmount(amountNoVatVal)
            // Search document for a number matching one of the calculated VAT amounts and set vatAmount + vatRate
            val baseAmount = amountNoVatVal.replace(",", ".").toDoubleOrNull() ?: 0.0
            if (baseAmount > 0) {
                for (rate in LITHUANIAN_VAT_RATES) {
                    if (rate == 0.0) continue
                    val expectedVat = baseAmount * rate
                    val expectedVatStr = String.format("%.2f", expectedVat)
                    for (line in lines) {
                        val extracted = FieldExtractors.tryExtractAmount(line)
                        if (extracted != null) {
                            val normalized = FieldExtractors.normalizeAmount(extracted)
                            if (normalized != null) {
                                val value = normalized.toDoubleOrNull() ?: continue
                                if (kotlin.math.abs(value - expectedVat) < 0.02) {
                                    vatAmount = normalized
                                    vatRate = (rate * 100).toInt().toString()
                                    Timber.d("Inferred VAT from document: amount=$vatAmount, rate=$vatRate% (matched calculated $expectedVatStr)")
                                    break
                                }
                            }
                        }
                    }
                    if (vatAmount != null) break
                }
            }
        }
        
        // Extract VAT rate from text (look for "21%", "21% PVM", "PVM 21%", "PVM 21", "21 %", "tarifas 21", etc.)
        if (vatRate == null) {
            val vatRatePatterns = listOf(
                Regex("""(\d+(?:[.,]\d+)?)\s*%\s*(?:PVM|VAT)?""", RegexOption.IGNORE_CASE),
                Regex("""(?:PVM|VAT)\s*[:\s]*(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE),
                Regex("""(?:PVM|VAT)\s+(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE),
                Regex("""(\d+)\s*%\s*(?:PVM|vat)""", RegexOption.IGNORE_CASE),
                Regex("""(?:tarifas|rate|procentas)\s*[:\s]*(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE)
            )
            for (line in lines) {
                for (pattern in vatRatePatterns) {
                    val match = pattern.find(line)
                    if (match != null) {
                        val rateValue = match.groupValues[1].replace(",", ".")
                        val rate = rateValue.toDoubleOrNull()
                        if (rate != null && rate in 0.0..100.0) {
                            vatRate = rate.toInt().toString()
                            Timber.d("Extracted VAT rate from text: $vatRate%")
                            break
                        }
                    }
                    if (vatRate != null) break
                }
                if (vatRate != null) break
            }
            // Also try to calculate VAT rate from amounts if not found
            if (vatRate == null) {
                val currentAmountNoVat = amountNoVat
                val currentVatAmount = vatAmount
                if (currentAmountNoVat != null && currentVatAmount != null) {
                    val amount = currentAmountNoVat.replace(",", ".").toDoubleOrNull()
                    val vat = currentVatAmount.replace(",", ".").toDoubleOrNull()
                    if (amount != null && vat != null && amount > 0) {
                        val calculatedRate = (vat / amount * 100)
                        // Check if it matches a standard Lithuanian VAT rate
                        for (standardRate in LITHUANIAN_VAT_RATES) {
                            if (kotlin.math.abs(calculatedRate - standardRate * 100) < 0.1) {
                                vatRate = (standardRate * 100).toInt().toString()
                                Timber.d("Calculated VAT rate from amounts: $vatRate%")
                                break
                            }
                        }
                    }
                }
            }
        }
        
        // Final validation: reject invalid company names and exclude own company name
        val finalCompanyName = companyName?.let { name ->
            // CRITICAL: Exclude own company name - NEVER fill it
            if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(name, excludeOwnCompanyName)) {
                Timber.w("Rejecting own company name: '$name'")
                null
            } else if (isInvalidCompanyName(name)) {
                Timber.w("Rejecting invalid company name: '$name'")
                null
            } else {
                name
            }
        }

        Timber.d("InvoiceParser.parse completed - InvoiceID: '$invoiceId', Date: '$date', CompanyName: '$finalCompanyName', " +
                "AmountNoVat: '$amountNoVat', VatAmount: '$vatAmount', VatNumber: '$vatNumber', CompanyNumber: '$companyNumber', VatRate: '$vatRate'")
        return ParsedInvoice(
            invoiceId = invoiceId,
            date = date,
            companyName = finalCompanyName,
            amountWithoutVatEur = amountNoVat,
            vatAmountEur = vatAmount,
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            vatRate = vatRate,
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
            vatRate = keywordResults.vatRate,
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
        
        // Reject "Suma žodžiais" / "Suma zodžiais" (amount in words - Lithuanian)
        if (lower.contains("suma žodžiais") || lower.contains("suma zodžiais") ||
            lower.contains("suma zodziais") || lower.contains("suma ādāais") ||
            (lower.startsWith("suma ") && lower.contains("eurai") && lower.contains("centas"))) {
            return true
        }
        // Reject amount-in-words: "X eurai ir Y centas" (e.g. "Šešiasdešimt aštuoni eurai ir 51 centas")
        // OCR may truncate "Suma žodžiais" to "ais:" - catch by eurai+centas pattern
        if (lower.contains("eurai") && lower.contains("centas")) {
            return true
        }
        
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
        // Exclude "as" when it's part of "centas" (cents) - that's amount-in-words, not company
        val hasCompanyType = lower.contains("uab") || lower.contains("mb") || lower.contains("iį") ||
                            lower.contains("ab") || lower.contains("ltd") ||
                            (lower.contains("as") && !lower.contains("centas")) ||
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
                        
                        // Validate serial: can be alphanumeric (with at least one letter) OR purely numeric (2-6 digits, but not a year)
                        val isValidSerial = if (serialCandidate.any { it.isLetter() }) {
                            // Alphanumeric serial: must have at least one letter
                            serialCandidate.length >= 2 && serialCandidate.length <= 6
                        } else {
                            // Numeric serial: must be 2-6 digits and not a 4-digit year
                            serialCandidate.length >= 2 && serialCandidate.length <= 6 && 
                            !serialCandidate.matches(Regex("^[0-9]{4}$")) // Not a year
                        }
                        
                        if (isValidSerial) {
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
        
        // Pattern 4: Combined serial and number on same line (e.g., "Serija: 25DF Numeris: 2569" or "Serija 01 Nr. 1442")
        val serialNumberPattern = Regex("(?:serija|series)[:.]?\\s*([A-Z0-9]{2,6})\\s+(?:numeris|number|nr)[:.]?\\s*([0-9]{3,15})", RegexOption.IGNORE_CASE)
        val serialNumberMatch = serialNumberPattern.find(line)
        if (serialNumberMatch != null) {
            val serial = serialNumberMatch.groupValues.getOrNull(1)?.uppercase()
            val number = serialNumberMatch.groupValues.getOrNull(2)
            if (serial != null && number != null) {
                // Validate serial: can be alphanumeric (with at least one letter) OR purely numeric (2-6 digits, but not a year)
                val isValidSerial = if (serial.any { it.isLetter() }) {
                    // Alphanumeric serial: must have at least one letter
                    serial.length >= 2 && serial.length <= 6
                } else {
                    // Numeric serial: must be 2-6 digits and not a 4-digit year
                    serial.length >= 2 && serial.length <= 6 && 
                    !serial.matches(Regex("^[0-9]{4}$")) // Not a year
                }
                if (isValidSerial) {
                    val combined = "$serial$number"
                    Timber.d("Extracted Invoice ID from serial+number pattern: $combined")
                    return combined
                }
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
    
    fun extractCompanyNameAdvanced(lines: List<String>, companyNumber: String?, vatNumber: String?, excludeOwnCompanyNumber: String? = null, excludeOwnCompanyName: String? = null, invoiceType: String? = null): String? {
        // Strategy 1: Use VAT/company number to find company name ABOVE it (highest priority!)
        // Company name usually appears ABOVE company number and VAT number
        // CRITICAL: Prioritize VAT number over company number because:
        // 1. VAT number is more reliable for identifying partner company
        // 2. Company number might be own company (especially for purchase invoices)
        // 3. If company number matches own company, definitely use VAT number instead
        val isPurchaseInvoice = invoiceType?.uppercase() == "P"
        val companyNumberIsOwn = excludeOwnCompanyNumber != null && companyNumber != null && companyNumber.trim() == excludeOwnCompanyNumber.trim()
        
        // Determine which identifier to use for searching
        val searchNumber = when {
            // If company number is own company, use VAT number
            companyNumberIsOwn -> vatNumber
            // For purchase invoices, prefer VAT number (seller's VAT is more reliable)
            isPurchaseInvoice && vatNumber != null -> vatNumber
            // Otherwise, prefer company number if available, fallback to VAT
            else -> companyNumber ?: vatNumber
        }
        
        // Strategy 1: Use VAT/company number to find company name ABOVE it (highest priority!)
        if (searchNumber != null) {
            var numberLineIndex: Int? = null
            
            // Find the line index where company number or VAT number appears
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase().trim()
                if (l.contains(searchNumber)) {
                    numberLineIndex = i
                    Timber.d("extractCompanyNameAdvanced: Found company/VAT number '$searchNumber' on line $i, searching ABOVE for company name")
                    break
                }
            }
            
            // If found, look backwards (upwards) for company name
            if (numberLineIndex != null) {
                // Search up to 8 lines above the company/VAT number
                // Prioritize lines closer to the number (company name is usually 1-3 lines above)
                for (offset in 1..8) {
                    val candidateIndex = numberLineIndex!! - offset
                    if (candidateIndex >= 0) {
                        val candidateLine = lines[candidateIndex].trim()
                        val candidateLower = candidateLine.lowercase()
                        
                        // Skip labels
                        val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|imone|kompanija|bendrove|company)$", RegexOption.IGNORE_CASE)
                        if (candidateLower.matches(labelPattern)) {
                            continue
                        }
                        
                        // Check if this line has a company type prefix (UAB, MB, AB, etc.)
                        val hasCompanyPrefix = candidateLower.contains("uab") || candidateLower.contains("ab") || 
                                             candidateLower.contains("mb") || candidateLower.contains("iį") ||
                                             candidateLower.contains("ltd") || candidateLower.contains("oy") || 
                                             candidateLower.contains("as") || candidateLower.contains("sp")
                        
                        if (hasCompanyPrefix && isValidCompanyNameLine(candidateLine, candidateLower)) {
                            val cleaned = cleanCompanyName(candidateLine)
                            if (cleaned != null) {
                                // CRITICAL: Exclude own company name - NEVER fill it
                                if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) {
                                    Timber.d("extractCompanyNameAdvanced: Skipped own company name: '$cleaned'")
                                    continue
                                }
                                Timber.d("extractCompanyNameAdvanced: Found company name '$cleaned' $offset line(s) ABOVE company/VAT number")
                                return cleaned
                            }
                        }
                    }
                }
                
                // Fallback: Look for any valid company name above (even without explicit prefix check)
                for (offset in 1..8) {
                    val candidateIndex = numberLineIndex!! - offset
                    if (candidateIndex >= 0) {
                        val candidateLine = lines[candidateIndex].trim()
                        val candidateLower = candidateLine.lowercase()
                        
                        // Skip labels
                        val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|imone|kompanija|bendrove|company)$", RegexOption.IGNORE_CASE)
                        if (candidateLower.matches(labelPattern)) {
                            continue
                        }
                        
                        if (isValidCompanyNameLine(candidateLine, candidateLower)) {
                            val cleaned = cleanCompanyName(candidateLine)
                            if (cleaned != null) {
                                // CRITICAL: Exclude own company name - NEVER fill it
                                if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) {
                                    Timber.d("extractCompanyNameAdvanced: Skipped own company name (fallback): '$cleaned'")
                                    continue
                                }
                                Timber.d("extractCompanyNameAdvanced: Found company name '$cleaned' $offset line(s) ABOVE company/VAT number (fallback)")
                                return cleaned
                            }
                        }
                    }
                }
            }
        }
        
        // Strategy 2: Find company name ABOVE both company number AND VAT number (if both found)
        // This is more reliable - company name appears above both identifiers
        if (companyNumber != null && vatNumber != null) {
            var companyNumberLineIndex: Int? = null
            var vatNumberLineIndex: Int? = null
            
            // Find line indices for both
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase().trim()
                if (l.contains(companyNumber) && companyNumberLineIndex == null) {
                    companyNumberLineIndex = i
                }
                if (l.contains(vatNumber) && vatNumberLineIndex == null) {
                    vatNumberLineIndex = i
                }
            }
            
            // If both found, look for company name above the EARLIER one (closer to top)
            if (companyNumberLineIndex != null && vatNumberLineIndex != null) {
                val earlierIndex = minOf(companyNumberLineIndex, vatNumberLineIndex)
                Timber.d("extractCompanyNameAdvanced: Found both company number (line $companyNumberLineIndex) and VAT number (line $vatNumberLineIndex), searching ABOVE line $earlierIndex")
                
                // Search up to 8 lines above
                for (offset in 1..8) {
                    val candidateIndex = earlierIndex - offset
                    if (candidateIndex >= 0) {
                        val candidateLine = lines[candidateIndex].trim()
                        val candidateLower = candidateLine.lowercase()
                        
                        // Skip labels
                        val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|imone|kompanija|bendrove|company)$", RegexOption.IGNORE_CASE)
                        if (candidateLower.matches(labelPattern)) {
                            continue
                        }
                        
                        // Prioritize lines with company type prefix
                        val hasCompanyPrefix = candidateLower.contains("uab") || candidateLower.contains("ab") || 
                                             candidateLower.contains("mb") || candidateLower.contains("iį") ||
                                             candidateLower.contains("ltd") || candidateLower.contains("oy") || 
                                             candidateLower.contains("as") || candidateLower.contains("sp")
                        
                        if (hasCompanyPrefix && isValidCompanyNameLine(candidateLine, candidateLower)) {
                            val cleaned = cleanCompanyName(candidateLine)
                            if (cleaned != null) {
                                // CRITICAL: Exclude own company name - NEVER fill it
                                if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) {
                                    Timber.d("extractCompanyNameAdvanced: Skipped own company name (both numbers): '$cleaned'")
                                    continue
                                }
                                Timber.d("extractCompanyNameAdvanced: Found company name '$cleaned' $offset line(s) ABOVE both company/VAT numbers")
                                return cleaned
                            }
                        }
                    }
                }
            }
        }
        
        // Strategy 3: Find "TIEKĖJAS" (Supplier) or "PARDAVEJAS" (Seller) or "PIRKĖJAS" (Buyer) section
        // For purchase invoices, prioritize "Tiekėjas" (seller) section only - NEVER use "pirkėjas" (buyer)
        // as that would fill in the user's own company name.
        val labelKeywords = listOf("pardavejas", "tiekejas", "gavejas", "pirkėjas", "pirkėjo")
        val priorityKeywords = if (isPurchaseInvoice) {
            listOf("tiekejas", "pardavejas", "gavejas")  // seller/supplier only - never buyer
        } else {
            listOf("pirkėjas", "pirkėjo", "pardavejas", "tiekejas", "gavejas")
        }
        
        // First pass: Check priority keywords (for purchase invoices, this is "Tiekėjas")
        for (priorityKeyword in priorityKeywords) {
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase().trim()
                // More flexible label detection - check if line contains the keyword
                val isLabel = l == priorityKeyword || 
                             (l.contains(priorityKeyword) && l.length <= 25) ||
                             l.matches(Regex(".*\\b$priorityKeyword\\b.*", RegexOption.IGNORE_CASE))
                
                if (isLabel) {
                    Timber.d("extractCompanyNameAdvanced: Found label '$priorityKeyword' at line $i, searching for company name")
                    
                    // Check if company/VAT number appears after this label (validates this is the right section)
                    val hasNumberAfter = if (companyNumber != null || vatNumber != null) {
                        val searchNumber = companyNumber ?: vatNumber
                        (i + 1 until min(i + 15, lines.size)).any { j ->
                            lines[j].lowercase().contains(searchNumber ?: "")
                        }
                    } else {
                        false
                    }
                    
                    // Look for company name in next lines (immediately after label is most common)
                    // For purchase invoices with "Tiekėjas", prioritize lines 1-3
                    val searchRange = if (isPurchaseInvoice && priorityKeyword == "tiekejas") {
                        1..5  // More aggressive for purchase invoices
                    } else {
                        1..8
                    }
                    
                    for (j in searchRange) {
                        if (i + j < lines.size) {
                            val nextLine = lines[i + j].trim()
                            val nextLower = nextLine.lowercase()
                            
                            // Skip if next line is also a label (use normalized compare so PARDAVĖJAS matches pardavejas)
                            if (isKnownSectionLabel(nextLine)) {
                                continue
                            }
                            if (labelKeywords.any { keyword -> 
                                normalizeForLabelCompare(nextLower) == normalizeForLabelCompare(keyword) ||
                                (nextLower.length < 20 && normalizeForLabelCompare(nextLower).contains(normalizeForLabelCompare(keyword)))
                            }) {
                                continue
                            }
                            
                            // Skip if this line contains company/VAT number (name should be BEFORE number)
                            if (companyNumber != null && nextLower.contains(companyNumber)) {
                                continue
                            }
                            if (vatNumber != null && nextLower.contains(vatNumber)) {
                                continue
                            }
                            
                            // Check if line is valid company name (with or without quotes)
                            if (isValidCompanyNameLine(nextLine, nextLower)) {
                                val cleaned = cleanCompanyName(nextLine)
                                if (cleaned != null && !isKnownSectionLabel(cleaned)) {
                                    // CRITICAL: Exclude own company name - NEVER fill it
                                    if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) {
                                        Timber.d("extractCompanyNameAdvanced: Skipped own company name (after label): '$cleaned'")
                                        continue
                                    }
                                    Timber.d("extractCompanyNameAdvanced: Extracted company name after label '$priorityKeyword' at line $i: '$cleaned' (hasNumberAfter: $hasNumberAfter, offset: $j)")
                                    return cleaned
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Second pass: Check all other label keywords
        // For purchase invoices, NEVER extract from buyer section (pirkėjas) - that would fill user's own company
        val buyerLabels = listOf("pirkėjas", "pirkėjo", "buyer")
        for (i in lines.indices) {
            val line = lines[i]
            val l = line.lowercase().trim()
            // Check if this line is a label (but skip if already checked in priority pass)
            val isLabel = labelKeywords.any { keyword -> 
                l == keyword || 
                (l.contains(keyword) && l.length < 20) ||
                l.matches(Regex(".*\\b$keyword\\b.*", RegexOption.IGNORE_CASE))
            }
            
            if (isLabel && !priorityKeywords.any { l.contains(it) }) {
                // For purchase invoices, skip buyer section entirely - only seller is wanted
                if (isPurchaseInvoice && buyerLabels.any { normalizeForLabelCompare(l).contains(normalizeForLabelCompare(it)) }) {
                    Timber.d("extractCompanyNameAdvanced: Skipping buyer label '$l' for purchase invoice")
                    continue
                }
                // Check if company/VAT number appears after this label
                val hasNumberAfter = if (companyNumber != null || vatNumber != null) {
                    val searchNumber = companyNumber ?: vatNumber
                    (i + 1 until min(i + 15, lines.size)).any { j ->
                        lines[j].lowercase().contains(searchNumber ?: "")
                    }
                } else {
                    false
                }
                
                // Look for company name in next lines
                for (j in 1..8) {
                    if (i + j < lines.size) {
                        val nextLine = lines[i + j].trim()
                        val nextLower = nextLine.lowercase()
                        if (isKnownSectionLabel(nextLine)) continue
                        if (labelKeywords.any { keyword -> normalizeForLabelCompare(nextLower) == normalizeForLabelCompare(keyword) }) continue
                        if (companyNumber != null && nextLower.contains(companyNumber)) continue
                        if (vatNumber != null && nextLower.contains(vatNumber)) continue
                        if (isValidCompanyNameLine(nextLine, nextLower)) {
                            val cleaned = cleanCompanyName(nextLine)
                            if (cleaned != null && !isKnownSectionLabel(cleaned)) {
                                if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) {
                                    Timber.d("extractCompanyNameAdvanced: Skipped own company name (after label): '$cleaned'")
                                    continue
                                }
                                Timber.d("Extracted company name after label '$l': $cleaned (hasNumberAfter: $hasNumberAfter)")
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
                if (cleaned != null && !isKnownSectionLabel(cleaned)) {
                    if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) {
                        Timber.d("extractCompanyNameAdvanced: Skipped own company name (first half): '$cleaned'")
                        continue
                    }
                    return cleaned
                }
            }
        }
        return null
    }
    
    /** Normalize Lithuanian chars (ė, ē, į, ą, etc.) to ASCII for label matching so "PARDAVĖJAS" matches "pardavejas". */
    private fun normalizeForLabelCompare(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.trim().lowercase()
            .replace('\u0117', 'e')  // ė
            .replace('\u0113', 'e')  // ē
            .replace('\u012f', 'i')  // į
            .replace('\u012b', 'i')  // ī
            .replace('\u0105', 'a')  // ą
            .replace('\u0173', 'u')  // ų
            .replace('\u016b', 'u')  // ū
            .replace(Regex("\\s+"), " ")
    }
    
    /** Known section headers that must never be used as company name (seller, buyer, etc.). */
    private val KNOWN_SECTION_LABELS_NORMALIZED = setOf(
        "pardavejas", "tiekejas", "gavejas", "pirkėjas", "pirkėjo",
        "seller", "buyer", "recipient", "supplier", "imone", "kompanija", "bendrove", "company"
    )
    
    /** True if the string is a known section label (e.g. PARDAVĖJAS, TIEKĖJAS) - never use as company name. */
    private fun isKnownSectionLabel(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = normalizeForLabelCompare(name)
        if (n.length < 3) return false
        return KNOWN_SECTION_LABELS_NORMALIZED.any { label -> n == normalizeForLabelCompare(label) }
    }
    
    /** True if name is the same as own company (uses CompanyNameUtils for fuzzy matching). */
    private fun isSameAsOwnCompanyName(name: String?, ownName: String?): Boolean =
        CompanyNameUtils.isSameAsOwnCompanyName(name, ownName)
    
    private fun isValidCompanyNameLine(line: String, lower: String): Boolean {
        val trimmedLower = lower.trim()
        
        // Reject amount-in-words: "X eurai ir Y centas" (e.g. "Šešiasdešimt aštuoni eurai ir 51 centas")
        if (trimmedLower.contains("eurai") && trimmedLower.contains("centas")) {
            return false
        }
        
        // Reject labels (including Lithuanian ė: PARDAVĖJAS -> pardavėjas -> normalize -> pardavejas)
        if (isKnownSectionLabel(trimmedLower)) {
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
        // Exclude "as" when part of "centas" (amount-in-words)
        val hasCompanyType = trimmedLower.contains("uab") || trimmedLower.contains("ab") ||
                            trimmedLower.contains("mb") || trimmedLower.contains("iį") ||
                            trimmedLower.contains("ltd") || trimmedLower.contains("oy") ||
                            (trimmedLower.contains("as") && !trimmedLower.contains("centas")) || trimmedLower.contains("sp")
        
        // Only return true if it has company type suffix
        return hasCompanyType
    }
    
    private fun cleanCompanyName(line: String): String? {
        // Preserve quotes in company names (e.g., "UAB "Vilniaus rentvėjus"")
        var cleaned = line.trim()
        
        // Remove label prefixes but preserve quotes
        cleaned = cleaned.replace(Regex("^(imone|kompanija|bendrove|company|pvm|kodas|numeris|registracijos|saskaita|faktura|invoice|pardavejas|tiekejas)[:\\s]+", RegexOption.IGNORE_CASE), "")
        
        // Remove trailing labels and codes, but preserve quotes
        cleaned = cleaned.replace(Regex("[:\\s]+(kodas|numeris|pvm|vat|saskaita|faktura|invoice).*$", RegexOption.IGNORE_CASE), "")
        
        cleaned = cleaned.trim()
        
        // Remove outer quotes if they wrap the entire name, but preserve inner quotes
        // Example: "UAB "Vilniaus rentvėjus"" -> UAB "Vilniaus rentvėjus"
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.count { it == '"' } == 2) {
            cleaned = cleaned.removePrefix("\"").removeSuffix("\"")
        }
        
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


package com.vitol.inv3.ocr

import android.graphics.Rect
import com.vitol.inv3.data.local.FieldRegion
import com.vitol.inv3.export.TaxCodeDeterminer
import com.vitol.inv3.export.VatRateValidation
import timber.log.Timber
import java.util.Locale
import kotlin.math.abs
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
     * Fuel / retail LT receipts: a single tax row lists PVM, Be PVM, Su PVM as three amounts with VAT+net=gross
     * (e.g. "21,00% 9,46 45,07 54,53" or three lines with one amount each). Azure often maps only the total
     * into "subtotal" — this finds the real net and VAT.
     */
    fun tryExtractNetVatFromLtTaxBreakdown(lines: List<String>): Pair<String, String>? {
        val money = Regex("""\b(\d{1,6}[,.]\d{2})\b""")
        fun skipLineForFuelNoise(lower: String): Boolean {
            if (Regex("""\d+[,.]\d+\s*(?:eur|€)\s*/\s*l\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) return true
            if (Regex("""\d+[,.]\d+\s*l\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) && lower.length < 40) return true
            return false
        }
        fun parseDoubles(tokens: List<String>): List<Double>? {
            val out = tokens.map { it.replace(",", ".").toDoubleOrNull() ?: return null }
            return if (out.all { it in 0.01..999_999.0 }) out else null
        }
        fun toFormatted(net: Double, vat: Double): Pair<String, String>? {
            val n = FieldExtractors.normalizeAmount(String.format(Locale.US, "%.2f", net).replace('.', ','))
            val v = FieldExtractors.normalizeAmount(String.format(Locale.US, "%.2f", vat).replace('.', ','))
            return if (n != null && v != null) Pair(n, v) else null
        }
        fun tryOnText(line: String, joinedFromThreeLines: Boolean): Pair<String, String>? {
            val lower = line.lowercase()
            if (skipLineForFuelNoise(lower)) return null
            val raw = money.findAll(line).map { it.groupValues[1] }.toList()
            if (raw.size < 3) return null
            // Four tokens: rate%, VAT, net, gross (Circle K–style)
            if (raw.size >= 4) {
                val d = parseDoubles(raw.takeLast(4)) ?: return null
                val rate = d[0]
                val vatA = d[1]
                val netA = d[2]
                val grossA = d[3]
                if (rate in 5.0..27.0 && abs(vatA + netA - grossA) <= 0.08) {
                    val implied = vatA / netA * 100.0
                    if (implied in 4.5..27.5) return toFormatted(netA, vatA)
                }
            }
            val d3 = parseDoubles(raw.takeLast(3)) ?: return null
            val vatA = d3[0]
            val netA = d3[1]
            val grossA = d3[2]
            if (vatA <= 0.0 || netA <= 0.0 || grossA <= 0.0) return null
            if (abs(vatA + netA - grossA) > 0.08) return null
            val impliedRate = vatA / netA * 100.0
            if (impliedRate !in 4.5..27.5) return null
            // When OCR glues lines, a stray leading amount (e.g. unit price) can make a false triple — avoid tiny VAT
            if (!joinedFromThreeLines && vatA < 0.2 && grossA > 5.0) return null
            return toFormatted(netA, vatA)
        }
        for (line in lines) {
            tryOnText(line, joinedFromThreeLines = false)?.let { return it }
        }
        for (i in 0 until lines.size - 2) {
            val joined = listOf(lines[i], lines[i + 1], lines[i + 2]).joinToString(" ")
            tryOnText(joined, joinedFromThreeLines = true)?.let { return it }
        }
        return null
    }

    /** Numeric invoice number segment: 3–15 digits, optional `-` + short suffix (e.g. `20260209-3`). */
    private val invoiceNumberDigitsWithOptionalSuffix = Regex("^[0-9]{3,15}(?:-[0-9]{1,10})?$")

    /** LT + 9–12 digits — VAT code, not an invoice serial (standalone OCR often mislabels it as "invoice id"). */
    fun isLikelyLithuanianVatCode(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        return s.trim().uppercase().matches(Regex("^LT\\d{9,12}$"))
    }

    /**
     * Footer line from VĮ Registrų centras (company registration boilerplate) — not a supplier trading name.
     * OCR often picks this instead of the real UAB line below Tiekėjas when layout is two-column.
     */
    fun isLikelyRegistrarRegistryFooterLine(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val l = text.lowercase()
        if (Regex("(?i)registr[uų]\\s+centras").containsMatchIn(l)) return true
        if (l.contains("kudirkos") && l.contains("vilnius") && (l.contains("nr ab") || l.contains("nr. ab"))) return true
        if (Regex("(?i)imone\\s+ir\\.?\\s+v[iį]|įmonė\\s+ir\\.?\\s+v[iį]").containsMatchIn(l)) return true
        if (l.contains("vĮ registr") || l.contains("vi registr")) return true
        return false
    }

    /** Line-item discount / margin columns often contain "6,25 %" which must not be read as PVM %. */
    private fun isLikelyNonVatPercentContext(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("nuolaida") || l.contains("nuol.") || l.contains("discount") ||
            l.contains("antkainis") || l.contains("marža") || l.contains("marza")
    }

    /**
     * When net and VAT totals are consistent with a standard rate, prefer them over a stray "X%" from the table
     * (e.g. Nuolaida column) or OCR noise.
     */
    private fun reconcileVatRateWithTotals(
        current: String?,
        amountNoVat: String?,
        vatAmount: String?
    ): String? {
        val net = amountNoVat?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val vat = vatAmount?.replace(",", ".")?.toDoubleOrNull() ?: return null
        if (net <= 0) return null
        val inference = TaxCodeDeterminer.inferVatRateFromAmounts(net, vat) ?: return null
        val inferred = inference.ratePercent.toInt().toString()
        if (current.isNullOrBlank()) return inferred
        val ocr = current.replace(",", ".").toDoubleOrNull() ?: return inferred
        val impliedVat = net * (ocr / 100.0)
        return if (kotlin.math.abs(impliedVat - vat) <= 0.08) null else inferred
    }
    
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

        val buyerKeywords = listOf("pirkėjo", "pirkėjas", "buyer", "pirkėjo pavadinimas", "buyer name")
        val sellerKeywords = listOf("pardavėjo", "pardavėjas", "seller", "pardavėjo pavadinimas", "seller name")
        fun lineMatchesSellerKw(line: String) = sellerKeywords.any { line.lowercase().contains(it) }
        fun lineMatchesBuyerKw(line: String) = buyerKeywords.any { line.lowercase().contains(it) }
        val sellerBlockStart = lines.indexOfFirst { lineMatchesSellerKw(it) }.takeIf { it >= 0 }
        val buyerBlockStart = lines.indexOfFirst { lineMatchesBuyerKw(it) }.takeIf { it >= 0 }
        val isSalesInvoice = invoiceType?.uppercase() == "S"
        val isPurchaseInvoice = invoiceType?.uppercase() == "P"
        fun indexInSellerBlock(idx: Int): Boolean {
            val s = sellerBlockStart ?: return false
            val endExclusive = when {
                buyerBlockStart != null && buyerBlockStart > s -> buyerBlockStart
                else -> lines.size
            }
            return idx in s until endExclusive
        }
        fun indexInBuyerBlock(idx: Int): Boolean {
            val b = buyerBlockStart ?: return false
            val endExclusive = when {
                sellerBlockStart != null && sellerBlockStart > b -> sellerBlockStart
                else -> lines.size
            }
            return idx in b until endExclusive
        }

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
        // Purchase: seller VAT often only appears under Pardavėjas; scan that block if global pass missed it.
        if (vatNumber == null && isPurchaseInvoice && sellerBlockStart != null) {
            val endExclusive = when {
                buyerBlockStart != null && buyerBlockStart > sellerBlockStart -> buyerBlockStart
                else -> lines.size
            }
            for (i in sellerBlockStart until endExclusive) {
                val extracted = FieldExtractors.tryExtractVatNumber(lines[i], excludeOwnVatNumber)
                if (extracted != null && !isIban(extracted) &&
                    (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true))) {
                    vatNumber = extracted
                    vatNumberLineIndex = i
                    Timber.d("InvoiceParser: Pre-pass 1 found VAT in seller block line $i: $extracted")
                    break
                }
            }
        }
        if (vatNumber == null && isSalesInvoice && buyerBlockStart != null) {
            val endExclusive = when {
                sellerBlockStart != null && sellerBlockStart > buyerBlockStart -> sellerBlockStart
                else -> lines.size
            }
            for (i in buyerBlockStart until endExclusive) {
                val extracted = FieldExtractors.tryExtractVatNumber(lines[i], excludeOwnVatNumber)
                if (extracted != null && !isIban(extracted) &&
                    (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true))) {
                    vatNumber = extracted
                    vatNumberLineIndex = i
                    Timber.d("InvoiceParser: Pre-pass 1 found VAT in buyer block line $i: $extracted")
                    break
                }
            }
        }

        // PRE-PASS 2: Extract company number, PRIORITIZING ones right after "Įmonės kodas" text
        // Company code usually goes just after "Įmonės kodas" or "Kodas" text - this is the key insight!
        Timber.d("InvoiceParser: Pre-pass 2 - searching for company number after 'Įmonės kodas' or 'Kodas' text")
        val allCompanyNumbers = mutableListOf<Pair<String, Int>>() // (number, lineIndex)
        val companyCodeKeywords = listOf("įmonės kodas", "imones kodas", "im. kodas", "im.k", "įm.k", "im.kodas", "kodas", "company code", "company number", "company_number")
        
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
                // Extract company number from this line (right after the keyword), or from the next 1–2 lines
                // when the label is alone (common on scans: "Įmonės kodas" on one line, "121320015" on the next).
                var extracted = FieldExtractors.tryExtractCompanyNumber(line, vatNumber, excludeOwnCompanyNumber)
                var numberLineIndex = i
                if (extracted == null) {
                    for (off in 1..2) {
                        if (i + off >= lines.size) break
                        val nextLine = lines[i + off]
                        extracted = FieldExtractors.tryExtractCompanyNumber(nextLine, vatNumber, excludeOwnCompanyNumber)
                        if (extracted != null) {
                            numberLineIndex = i + off
                            break
                        }
                    }
                }
                if (extracted != null) {
                    // Check if this is in buyer or seller section
                    val searchRange = max(0, i - 25)..min(lines.size - 1, i + 4)
                    val isInBuyerSection = searchRange.any { lineIdx ->
                        val searchLineLower = lines[lineIdx].lowercase()
                        buyerKeywords.any { keyword -> searchLineLower.contains(keyword) }
                    }
                    val isInSellerSection = searchRange.any { lineIdx ->
                        val searchLineLower = lines[lineIdx].lowercase()
                        sellerKeywords.any { keyword -> searchLineLower.contains(keyword) }
                    }
                    
                    companyNumbersAfterKeyword.add(
                        CompanyNumberWithSection(extracted, numberLineIndex, isInBuyerSection, isInSellerSection)
                    )
                    val section = when {
                        isInBuyerSection && !isInSellerSection -> "buyer"
                        isInSellerSection && !isInBuyerSection -> "seller"
                        else -> "unknown"
                    }
                    Timber.d("InvoiceParser: Pre-pass 2 found $section company number '$extracted' right after 'Įmonės kodas' on line $numberLineIndex")
                }
            }
        }
        
        // Determine priority based on invoice type:
        // - Sales (S): prioritize buyer section (we want buyer's company number)
        // - Purchase (P): prioritize seller section (we want seller's company number)
        
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
            
            // PRIORITY 3: Prefer company codes in the correct party block (Pardavėjas vs Pirkėjas), with a wide keyword window
            if (companyNumber == null) {
                val winBefore = 22
                val winAfter = 6
                fun nearBuyer(idx: Int) =
                    (max(0, idx - winBefore)..min(lines.size - 1, idx + winAfter)).any { li ->
                        buyerKeywords.any { lines[li].lowercase().contains(it) }
                    }
                fun nearSeller(idx: Int) =
                    (max(0, idx - winBefore)..min(lines.size - 1, idx + winAfter)).any { li ->
                        sellerKeywords.any { lines[li].lowercase().contains(it) }
                    }
                val prioritized = when {
                    isSalesInvoice -> {
                        val inBuyerBlock = candidatesToUse.filter { (_, idx) -> indexInBuyerBlock(idx) }
                        inBuyerBlock.firstOrNull { nearBuyer(it.second) }
                            ?: inBuyerBlock.firstOrNull { nearSeller(it.second) }
                            ?: inBuyerBlock.firstOrNull()
                            ?: candidatesToUse.firstOrNull { nearBuyer(it.second) }
                            ?: candidatesToUse.firstOrNull { nearSeller(it.second) }
                    }
                    isPurchaseInvoice -> {
                        val inSellerBlock = candidatesToUse.filter { (_, idx) -> indexInSellerBlock(idx) }
                        inSellerBlock.firstOrNull { nearSeller(it.second) }
                            ?: inSellerBlock.firstOrNull { nearBuyer(it.second) }
                            ?: inSellerBlock.firstOrNull()
                            ?: candidatesToUse.firstOrNull { nearSeller(it.second) }
                            ?: candidatesToUse.firstOrNull { nearBuyer(it.second) }
                    }
                    else -> {
                        candidatesToUse.firstOrNull { nearBuyer(it.second) }
                            ?: candidatesToUse.firstOrNull { nearSeller(it.second) }
                    }
                }
                companyNumber = prioritized?.first
                if (companyNumber != null) {
                    Timber.d("InvoiceParser: Pre-pass 2 selected company number '$companyNumber' near keywords/block (invoice type: ${invoiceType ?: "unknown"})")
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
                            val searchRange = max(0, i - 20)..min(lines.size - 1, i + 4)
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

                fun isLikelyLtCompanyCode9(num: String): Boolean =
                    num.length == 9 && num[0] in '1'..'3' && num.all { it.isDigit() }

                val nineDigit = filteredCandidates.filter { (num, _) -> isLikelyLtCompanyCode9(num) }
                val pickNineDigit: Pair<String, Int>? = when {
                    nineDigit.isEmpty() -> null
                    nineDigit.size == 1 -> nineDigit.first()
                    isPurchaseInvoice -> {
                        // Retail: seller (partner) block usually above buyer — take earliest 9-digit line
                        nineDigit.minByOrNull { it.second }
                    }
                    isSalesInvoice -> {
                        // Partner is buyer — usually later in the document
                        nineDigit.maxByOrNull { it.second }
                    }
                    else -> nineDigit.minByOrNull { it.second }
                }

                val nineAfterExclude = pickNineDigit?.let { picked ->
                    val eligible = nineDigit.filter { (n, _) ->
                        excludeOwnCompanyNumber == null || n != excludeOwnCompanyNumber.trim()
                    }
                    eligible.firstOrNull()?.first ?: picked.first
                }

                companyNumber = nineAfterExclude
                    ?: filteredCandidates.firstOrNull { (num, _) ->
                        excludeOwnCompanyNumber == null || num != excludeOwnCompanyNumber.trim()
                    }?.first
                    ?: filteredCandidates.firstOrNull()?.first // Fallback to first if all are own company

                if (companyNumber != null) {
                    Timber.d("InvoiceParser: Pre-pass 2 selected company number '$companyNumber' (priority 5: prefer 9-digit Įm. kodas over 6-digit receipt/Kasos)")
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
                    // Handle Lithuanian characters and OCR variants: "tiekėjas", "tiekejas", "tiek jas" (OCR)
                    val containsTiekejas = l.contains("tiekėjas", ignoreCase = true) || l.contains("tiekejas", ignoreCase = true) || l.contains("tiek jas", ignoreCase = true)
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
                val isInSellerSection = indexInSellerBlock(i) ||
                    (i > 0 && lineMatchesSellerKw(lines[i - 1]))
                val isInBuyerSection = indexInBuyerBlock(i) ||
                    (i > 0 && lineMatchesBuyerKw(lines[i - 1]))
                val hasVatContext = l.contains("pvm kodas") || l.contains("pvmkodas") ||
                    l.contains("pvm numeris") || l.contains("pvmnumeris") ||
                    l.contains("pvm kodas:") || l.contains("pvmkodas:")
                val extracted = FieldExtractors.tryExtractVatNumber(line, excludeOwnVatNumber)
                if (extracted != null && !isIban(extracted)) {
                    if (excludeOwnVatNumber == null || !extracted.equals(excludeOwnVatNumber, ignoreCase = true)) {
                        val sectionOk = when {
                            isPurchaseInvoice -> isInSellerSection
                            isSalesInvoice -> isInBuyerSection
                            else -> isInSellerSection || isInBuyerSection
                        }
                        if (hasVatContext && sectionOk) {
                            partnerVatNumber = extracted
                            Timber.d("Found partner VAT number with context (seller/buyer block): $extracted")
                            break
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
        
        // LT fuel / retail: tax line PVM + Be PVM + Su PVM (before keyword / totals heuristics)
        if (amountNoVat == null || vatAmount == null) {
            tryExtractNetVatFromLtTaxBreakdown(lines)?.let { (net, vat) ->
                amountNoVat = net
                vatAmount = vat
                Timber.d("Extracted net+VAT from LT tax breakdown row: net=$net, VAT=$vat")
            }
        }
        
        // Second pass: amounts with keywords
        if (amountNoVat == null) {
            val amountKeywords = listOf("suma be pvm", "suma bepvm", "sumabepvm", 
                                       "pardavimo tarpine suma", "pardavimotarpinesuma")
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase()
                val hasBePvmColumn = Regex("""(?i)\bbe\s+pvm\b""").containsMatchIn(l) && !l.contains("su pvm")
                if ((amountKeywords.any { keyword -> l.contains(keyword) } || hasBePvmColumn) &&
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
        
        // Extract VAT rate from text — avoid bare "X%" (matches Nuolaida column); require PVM/VAT/tarifas context.
        if (vatRate == null) {
            val vatRatePatterns = listOf(
                Regex("""PVM\s*%?\s*[:\s]*(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE),
                Regex("""(?:PVM|VAT)\s*[:\s]*(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE),
                Regex("""(?:PVM|VAT)\s+(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE),
                Regex("""(\d+(?:[.,]\d+)?)\s*%\s*(?:PVM|VAT)\b""", RegexOption.IGNORE_CASE),
                Regex("""(?:tarifas|rate|procentas)\s*[:\s]*(\d+(?:[.,]\d+)?)\s*%?""", RegexOption.IGNORE_CASE)
            )
            vatRateLoop@ for (line in lines) {
                if (isLikelyNonVatPercentContext(line)) continue
                for (pattern in vatRatePatterns) {
                    val match = pattern.find(line) ?: continue
                    val rateValue = match.groupValues[1].replace(",", ".")
                    val rate = rateValue.toDoubleOrNull() ?: continue
                    val snapped = VatRateValidation.snapRatioPercentToStandardOrNull(rate) ?: continue
                    vatRate = snapped.toInt().toString()
                    Timber.d("Extracted VAT rate from text: $vatRate% (raw=$rate%)")
                    break@vatRateLoop
                }
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

        reconcileVatRateWithTotals(vatRate, amountNoVat, vatAmount)?.let { reconciled ->
            if (reconciled != vatRate) {
                Timber.d("Reconciled VAT rate using net+VAT totals: $vatRate% -> $reconciled%")
            }
            vatRate = reconciled
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

        // Ensure date is always YYYY-MM-DD format
        val normalizedDate = date?.let { com.vitol.inv3.utils.DateFormatter.formatDateForDatabase(it) } ?: date
        
        Timber.d("InvoiceParser.parse completed - InvoiceID: '$invoiceId', Date: '$normalizedDate', CompanyName: '$finalCompanyName', " +
                "AmountNoVat: '$amountNoVat', VatAmount: '$vatAmount', VatNumber: '$vatNumber', CompanyNumber: '$companyNumber', VatRate: '$vatRate'")
        return ParsedInvoice(
            invoiceId = invoiceId,
            date = normalizedDate,
            companyName = finalCompanyName?.let { CompanyNameUtils.normalizeCompanyNameQuotes(it) },
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
        if (isLikelySignaturePlaceholderName(name)) return true
        if (isLikelyRegistrarRegistryFooterLine(name)) return true
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
        // 0) Header/title line often contains the real serial (e.g. "PVM SĄSKAITA FAKTŪRA BIK1858415")
        for (line in lines.take(15)) {
            val trimmed = line.trim()
            val lower = trimmed.lowercase()
            if (lower.contains("fakt") || lower.contains("saskaita") || lower.contains("invoice")) {
                Regex("""\b([A-Z]{3,12}\d{5,}(?:-[0-9]{1,10})?)\b""", RegexOption.IGNORE_CASE).find(trimmed)?.let { m ->
                    val id = m.groupValues[1].uppercase()
                    if (!isLikelyLithuanianVatCode(id)) {
                        Timber.d("extractInvoiceIdWithSerialAndNumber: id from invoice title line: $id")
                        return id
                    }
                }
            }
        }
        // 0b) Short alphanumeric serial (e.g. BIK1858415, DDV20260209-3) near top when title OCR is fragmented
        for (line in lines.take(10)) {
            Regex("""\b([A-Z]{3}\d{6,}(?:-[0-9]{1,10})?)\b""", RegexOption.IGNORE_CASE).find(line.trim())?.let { m ->
                val id = m.groupValues[1].uppercase()
                if (!id.startsWith("LT") && !isLikelyLithuanianVatCode(id)) {
                    Timber.d("extractInvoiceIdWithSerialAndNumber: id from header token: $id")
                    return id
                }
            }
        }

        // Pattern for serial: 2-6 alphanumeric characters with at least one letter (e.g., "25DF", "SSP", "A1B2")
        val serialPattern = Regex("\\b([A-Z0-9]{2,6})\\b", RegexOption.IGNORE_CASE)
        // Pattern for invoice number: 3-15 digits, optional hyphen + short suffix (e.g. "20260209-3", "2569")
        val numberPattern = Regex("\\b([0-9]{3,15}(?:-[0-9]{1,10})?)\\b")
        
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
                                    
                                    // Validate number: 3–15 digits with optional hyphen suffix; not a lone day-of-month
                                    if (invoiceNumberDigitsWithOptionalSuffix.matches(numberCandidate)) {
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
                                            if (invoiceNumberDigitsWithOptionalSuffix.matches(numberCandidate)) {
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
                                            if (invoiceNumberDigitsWithOptionalSuffix.matches(numberCandidate)) {
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

    /** Cash register / receipt footer lines (e.g. "EKA Nr. AM230151, kvito Nr.") — not the VAT invoice number. */
    private fun isCashRegisterOrReceiptInvoiceLine(line: String): Boolean {
        val l = line.lowercase()
        if (l.contains("eka") && (l.contains("kvito") || l.contains("kasos"))) return true
        if (l.contains("kasos kvit")) return true
        if (l.contains("kvito nr") && l.contains("eka")) return true
        return false
    }
    
    private fun extractInvoiceIdFromLine(line: String): String? {
        val trimmed = line.trim()
        if (isCashRegisterOrReceiptInvoiceLine(trimmed)) {
            return null
        }
        // Common on LT invoices: "NR. VB-000077353" (hyphen). Older regex required letters+digits with no hyphen,
        // so "VB-000077353" never matched and a later "EKA Nr. AM230151" could be mistaken for the invoice id.
        val nrHyphenPattern = Regex("""nr\.?\s*:?\s*([A-Z]{2,12}-\d{4,12})""", RegexOption.IGNORE_CASE)
        nrHyphenPattern.find(trimmed)?.let { m ->
            val id = m.groupValues.getOrNull(1) ?: return@let
            if (id.length >= 8 && id.uppercase() != "INVOICE") {
                Timber.d("Extracted Invoice ID from 'Nr.' hyphen pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        // Pattern 1: "Nr. SSP000393734" or "Nr SSP000393734" or "Nr: SSP000393734" (optional "-N" suffix on the number)
        val nrPattern = Regex("nr\\.?\\s*:?\\s*([A-Z]{2,}[0-9]+(?:-[0-9]{1,10})?)", RegexOption.IGNORE_CASE)
        val nrMatch = nrPattern.find(trimmed)
        if (nrMatch != null) {
            val id = nrMatch.groupValues.getOrNull(1)
            if (id != null && id.length >= 6 && id.uppercase() != "INVOICE" && !isLikelyLithuanianVatCode(id)) {
                Timber.d("Extracted Invoice ID from 'Nr.' pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }

        // Hyphenated id without leading "Nr." (e.g. "VB-000077353"); exclude LT-… (VAT numbers)
        Regex("""\b([A-Z]{2,12}-\d{6,})\b""", RegexOption.IGNORE_CASE).find(trimmed)?.let { m ->
            val id = m.groupValues.getOrNull(1) ?: return@let
            if (id.uppercase().startsWith("LT-")) return@let
            if (id.length >= 8 && id.uppercase() != "INVOICE") {
                Timber.d("Extracted Invoice ID from standalone hyphen pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        
        // Pattern 2: "SSP000393734" standalone (letters + digits, optional "-N" suffix e.g. DDV20260209-3)
        val invoiceIdPattern = Regex("\\b([A-Z]{2,}[0-9]{6,}(?:-[0-9]{1,10})?)\\b", RegexOption.IGNORE_CASE)
        val idMatch = invoiceIdPattern.find(trimmed)
        if (idMatch != null) {
            val id = idMatch.value
            if (id.uppercase() != "INVOICE" && id.length >= 6 && !isLikelyLithuanianVatCode(id)) {
                Timber.d("Extracted Invoice ID from standalone pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        
        // Pattern 3: "Saskaitos numeris: SSP000393734" or similar (optional "-N" suffix)
        val colonPattern = Regex("(?:numeris|serija|number|id)[:.]?\\s*([A-Z]{2,}[0-9]+(?:-[0-9]{1,10})?)", RegexOption.IGNORE_CASE)
        val colonMatch = colonPattern.find(trimmed)
        if (colonMatch != null) {
            val id = colonMatch.groupValues.getOrNull(1)
            if (id != null && id.length >= 6 && id.uppercase() != "INVOICE" && !isLikelyLithuanianVatCode(id)) {
                Timber.d("Extracted Invoice ID from colon pattern: ${id.uppercase()}")
                return id.uppercase()
            }
        }
        
        // Pattern 4: Combined serial and number on same line (e.g., "Serija: 25DF Numeris: 2569" or "Serija DDV Numeris 20260209-3")
        val serialNumberPattern = Regex("(?:serija|series)[:.]?\\s*([A-Z0-9]{2,6})\\s+(?:numeris|number|nr)[:.]?\\s*([0-9]{3,15}(?:-[0-9]{1,10})?)", RegexOption.IGNORE_CASE)
        val serialNumberMatch = serialNumberPattern.find(trimmed)
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
                            .replace(Regex("^(imone|kompanija|bendrove|company|pvm|kodas|numeris|registracijos|saskaita|faktura|invoice|pardav[eė]jas|tiek[eė]jas|pirk[eė]jas|gav[eė]jas)[:\\s]+", RegexOption.IGNORE_CASE), "")
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
                // Search well above the code: payment/IBAN rows often sit just under the real seller name (layout vs content line order differs).
                for (offset in 1..20) {
                    val candidateIndex = numberLineIndex!! - offset
                    if (candidateIndex >= 0) {
                        val candidateLine = lines[candidateIndex].trim()
                        val candidateLower = candidateLine.lowercase()
                        
                        // Skip labels (including with colon: "Pirkėjas:", "Pardavėjas:")
                        if (isKnownSectionLabel(candidateLine)) {
                            continue
                        }
                        if (isLikelyPaymentBankName(candidateLine)) {
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
                for (offset in 1..20) {
                    val candidateIndex = numberLineIndex!! - offset
                    if (candidateIndex >= 0) {
                        val candidateLine = lines[candidateIndex].trim()
                        val candidateLower = candidateLine.lowercase()
                        
                        // Skip labels (including with colon: "Pirkėjas:", "Pardavėjas:")
                        if (isKnownSectionLabel(candidateLine)) {
                            continue
                        }
                        if (isLikelyPaymentBankName(candidateLine)) {
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
                
                // Search well above both identifiers (payment rows may sit between name and codes)
                for (offset in 1..20) {
                    val candidateIndex = earlierIndex - offset
                    if (candidateIndex >= 0) {
                        val candidateLine = lines[candidateIndex].trim()
                        val candidateLower = candidateLine.lowercase()
                        
                        // Skip labels
                        val labelPattern = Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier|imone|kompanija|bendrove|company)$", RegexOption.IGNORE_CASE)
                        if (candidateLower.matches(labelPattern)) {
                            continue
                        }
                        if (isLikelyPaymentBankName(candidateLine)) {
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
            // Not gavejas: on LT invoices "Gavėjas" is the recipient/buyer; OCR repeats it in the signature
            // block with "(parašas)" underneath — that must not drive supplier name.
            listOf("tiekejas", "pardavejas")
        } else {
            listOf("pirkėjas", "pirkėjo", "pardavejas", "tiekejas", "gavejas")
        }
        
        // First pass: Check priority keywords (for purchase invoices, this is "Tiekėjas")
        for (priorityKeyword in priorityKeywords) {
            for (i in lines.indices) {
                val line = lines[i]
                val l = line.lowercase().trim()
                val normL = normalizeForLabelCompare(l)
                val normKw = normalizeForLabelCompare(priorityKeyword)
                // More flexible label detection - use normalized compare so "Tiek jas :" (OCR) matches "tiekejas"
                val isLabel = normL == normKw ||
                             (normL.contains(normKw) && l.length <= 25) ||
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
                    // Two-column LT layout: consecutive "Pirkėjas" then "Pardavėjas" — OCR often puts the buyer
                    // name on the first line after "Pardavėjas" and the seller on the second.
                    val buyerLabelImmediatelyAbove = i > 0 &&
                        normalizeForLabelCompare(lines[i - 1].trim()) == "pirkejas" &&
                        line.length <= 40
                    val startOffset = if (isPurchaseInvoice && priorityKeyword == "pardavejas" && buyerLabelImmediatelyAbove) {
                        2
                    } else {
                        1
                    }
                    
                    for (j in startOffset..searchRange.last) {
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
            val normL = normalizeForLabelCompare(l)
            // Check if this line is a label (use normalized compare for OCR variants like "tiek jas")
            val isLabel = labelKeywords.any { keyword -> 
                normL == normalizeForLabelCompare(keyword) ||
                (normL.contains(normalizeForLabelCompare(keyword)) && l.length < 20) ||
                l == keyword || (l.contains(keyword) && l.length < 20) ||
                l.matches(Regex(".*\\b$keyword\\b.*", RegexOption.IGNORE_CASE))
            }
            
            if (isLabel && !priorityKeywords.any { l.contains(it) }) {
                // For purchase invoices, skip buyer section entirely - only seller is wanted
                if (isPurchaseInvoice && buyerLabels.any { normalizeForLabelCompare(l).contains(normalizeForLabelCompare(it)) }) {
                    Timber.d("extractCompanyNameAdvanced: Skipping buyer label '$l' for purchase invoice")
                    continue
                }
                // gavejas is not in purchase priorityKeywords; second pass would still match "Gavėjas:" here
                // (priorityKeywords.any { l.contains(it) } fails for "gavėjas" vs ASCII "gavejas").
                if (isPurchaseInvoice && normalizeForLabelCompare(l).let { nl ->
                    nl == "gavejas" || (nl.contains("gavejas") && l.length <= 25)
                }) {
                    Timber.d("extractCompanyNameAdvanced: Skipping gavejas label '$l' for purchase (recipient, not supplier)")
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

    /**
     * Finds a seller legal-entity line in OCR (UAB, MB, …), including OCR typo "UAR" for "UAB".
     * Used to avoid using Azure's [VendorName] when it is only the logo (e.g. "KESKO" / "SENUKAI").
     */
    fun findSellerLegalNameFromOcrLines(lines: List<String>, excludeOwnCompanyName: String?): String? {
        for (line in lines.take(45)) {
            val t = line.trim()
            if (t.length < 12) continue
            val lower = t.lowercase()
            if (isLikelyPaymentBankName(t) || isLikelySignaturePlaceholderName(t)) continue
            if (isLikelyRegistrarRegistryFooterLine(t)) continue
            if (isKnownSectionLabel(t)) continue
            if (!isValidCompanyNameLine(t, lower)) continue
            val cleaned = cleanCompanyName(t) ?: continue
            if (excludeOwnCompanyName != null && isSameAsOwnCompanyName(cleaned, excludeOwnCompanyName)) continue
            return cleaned
        }
        return null
    }
    
    /** Normalize Lithuanian chars (ė, ē, į, ą, etc.) to ASCII for label matching so "PARDAVĖJAS" matches "pardavejas".
     * Also normalizes OCR-mangled variants where "ė" is read as space: "tiek jas" -> "tiekejas", etc. */
    private fun normalizeForLabelCompare(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var n = s.trim().lowercase()
            .replace(Regex("[:\\s]+$"), "")  // Strip trailing colons and spaces (e.g. "Pirkėjas:" -> "pirkėjas")
            .replace('\u0117', 'e')  // ė
            .replace('\u0113', 'e')  // ē
            .replace('\u012f', 'i')  // į
            .replace('\u012b', 'i')  // ī
            .replace('\u0105', 'a')  // ą
            .replace('\u0173', 'u')  // ų
            .replace('\u016b', 'u')  // ū
            .replace(Regex("\\s+"), " ")
        // OCR often reads "ė" as space: "Tiekėjas" -> "Tiek jas", "Pardavėjas" -> "Pardav jas"
        n = n.replace("tiek jas", "tiekejas").replace("tiek ejas", "tiekejas")
        n = n.replace("pardav jas", "pardavejas").replace("pardav ejas", "pardavejas")
        n = n.replace("pirk jas", "pirkejas").replace("pirk ejas", "pirkejas")
        n = n.replace("gav jas", "gavejas").replace("gav ejas", "gavejas")
        return n
    }
    
    /** Known section headers that must never be used as company name (seller, buyer, etc.). */
    private val KNOWN_SECTION_LABELS_NORMALIZED = setOf(
        "pardavejas", "tiekejas", "gavejas", "pirkėjas", "pirkėjo",
        "seller", "buyer", "recipient", "supplier", "imone", "kompanija", "bendrove", "company",
        "adresas", // LT invoice address block header — not a company name
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
    
    /**
     * Bank name / payment block lines (e.g. "AB SEB bankas", IBAN row) must not be used as supplier company name.
     * OCR often places these above Įm. kodas in the payment column while the real seller name is farther up.
     */
    /**
     * Signature lines and form hints like "(parašas)" / "Parašas:" must never be used as company name.
     * OCR often pairs them with a footer "Gavėjas:" label.
     */
    fun isLikelySignaturePlaceholderName(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim().lowercase()
        if (t.contains("parašas") || t.contains("parasas")) return true
        val core = t.replace("š", "s").replace("ž", "z")
            .removePrefix("(").removeSuffix(")").trim().trimEnd(':').trim()
        if (core == "parasas" || core == "parasa") return true
        if (core == "signature" || core == "sign") return true
        return false
    }

    fun isLikelyPaymentBankName(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val l = text.lowercase().trim()
        val norm = normalizeForLabelCompare(text)
        if (l.contains("iban") || l.contains("swift") || l.contains("bik")) return true
        if (l.contains("bankas") || l.contains("bank ") || l.endsWith(" bank")) return true
        if (norm.contains("mokejimo") && norm.contains("informacija")) return true
        if (norm.contains("mokujimo") && norm.contains("informacija")) return true
        if (Regex("\\bab\\s+seb\\b").containsMatchIn(l)) return true
        if (l.contains("swedbank") || l.contains("luminor") || l.contains("citadele")) return true
        if (l.contains("revolut") && l.contains("bank")) return true
        return false
    }
    
    private fun isValidCompanyNameLine(line: String, lower: String): Boolean {
        val trimmedLower = lower.trim()
        if (isLikelyRegistrarRegistryFooterLine(line)) {
            return false
        }
        
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
        
        if (isLikelyPaymentBankName(line)) {
            return false
        }
        if (isLikelySignaturePlaceholderName(line)) {
            return false
        }
        
        // REQUIRE Lithuanian company type suffix (UAB, MB, IĮ, AB) - this is mandatory
        // Use word boundary for "as" (legal form) — loose contains("as") matched "Adresas", etc.
        // Common OCR: trailing "UAB" misread as "UAR" (e.g. "KESKO SENUKAI LITHUANIA, UAR")
        val hasUarTypo = Regex("""(?i)(,\s*uar\s*$|\blithuania.*\buar\b)""").containsMatchIn(trimmedLower)
        val hasCompanyType = trimmedLower.contains("uab") || trimmedLower.contains("uad") ||
                            trimmedLower.contains("ab") ||
                            trimmedLower.contains("mb") || trimmedLower.contains("iį") ||
                            trimmedLower.contains("ltd") || trimmedLower.contains("oy") ||
                            (Regex("""(?i)\bas\b""").containsMatchIn(trimmedLower) && !trimmedLower.contains("centas")) ||
                            trimmedLower.contains("sp") || hasUarTypo
        
        // Only return true if it has company type suffix
        return hasCompanyType
    }
    
    private fun cleanCompanyName(line: String): String? {
        // Preserve quotes in company names (e.g., "UAB "Vilniaus rentvėjus"")
        var cleaned = line.trim()
        // "Pirkėjo/… pavadinimas: UAB …" — full label on the same line as the legal name (Azure advanced search passes whole line).
        cleaned = Regex(
            """(?i)^(pirk[eė]jo|pardav[eė]jo|tiek[eė]jo|gav[eė]jo)\s+pavadinimas\s*:\s*"""
        ).replaceFirst(cleaned, "")
        cleaned = Regex(
            """(?i)^(buyer|seller|supplier|recipient)\s+name\s*:\s*"""
        ).replaceFirst(cleaned, "")
        // OCR misread of trailing UAB as UAR on retailer invoices (comma or word boundary)
        // Raw strings: use \s (one backslash), not \\s — double backslash breaks whitespace matching.
        cleaned = cleaned.replace(Regex("""(?i),\s*UAR\s*$"""), ", UAB")
        cleaned = cleaned.replace(Regex("""(?i)(?<![A-Za-z])UAR\s*$"""), "UAB")
        
        // Remove label prefixes (Pardavėjas:, Pirkėjas:, Tiekėjas:, Gavėjas:, etc.)
        // Use [eė] for Lithuanian ė; also match OCR variant "tiek jas" (space instead of ė)
        val prefixPattern = Regex(
            "^(imone|kompanija|bendrove|company|pvm|kodas|numeris|registracijos|saskaita|faktura|invoice|pardav[eė\\s]jas|tiek[eė\\s]jas|pirk[eė\\s]jas|gav[eė\\s]jas)[:\\s]+",
            RegexOption.IGNORE_CASE
        )
        cleaned = prefixPattern.replace(cleaned, "")
        
        // Remove trailing labels and codes, but preserve quotes
        cleaned = cleaned.replace(Regex("[:\\s]+(kodas|numeris|pvm|vat|saskaita|faktura|invoice).*$", RegexOption.IGNORE_CASE), "")
        
        cleaned = cleaned.trim()
        
        // Remove outer quotes if they wrap the entire name, but preserve inner quotes
        // Example: "UAB "Vilniaus rentvėjus"" -> UAB "Vilniaus rentvėjus"
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.count { it == '"' } == 2) {
            cleaned = cleaned.removePrefix("\"").removeSuffix("\"")
        }
        
        val cleanedLower = cleaned.lowercase()
        
        if (isLikelySignaturePlaceholderName(cleaned)) {
            return null
        }
        // Reject if it's a label (with or without trailing colon)
        if (isKnownSectionLabel(cleaned)) {
            return null
        }
        
        // REQUIRE company type suffix (uad = common OCR for uab)
        val hasCompanyType = cleanedLower.contains("uab") || cleanedLower.contains("uad") ||
                            cleanedLower.contains("ab") ||
                            cleanedLower.contains("mb") || cleanedLower.contains("iį") ||
                            cleanedLower.contains("ltd") || cleanedLower.contains("oy") ||
                            Regex("""(?i)\bas\b""").containsMatchIn(cleanedLower) ||
                            cleanedLower.contains("sp")
        
        if (cleaned.isNotBlank() && cleaned.length > 3 && hasCompanyType &&
            !cleaned.matches(Regex("^[0-9\\s.,]+$")) &&
            !cleanedLower.contains("saskaita") &&
            !cleanedLower.contains("faktura") &&
            !cleanedLower.contains("invoice")) {
            return CompanyNameUtils.normalizeCompanyNameQuotes(cleaned)
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


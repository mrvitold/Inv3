package com.vitol.inv3.export

import timber.log.Timber

/**
 * Determines the appropriate PVM (VAT) tax code based on invoice data.
 * 
 * Special cases:
 * - If invoice text contains "96 straipsnis" or "atvirkštinis PVM" → PVM25
 * - Otherwise, maps VAT rate to default codes (can be refined later)
 */
object TaxCodeDeterminer {
    
    /**
     * Determine tax code based on VAT rate and invoice text.
     * 
     * @param vatRate VAT rate as percentage (e.g., 21.0, 9.0, 5.0, 0.0)
     * @param invoiceText Optional invoice text to check for special keywords
     * @return Tax code (e.g., "PVM1", "PVM25")
     */
    fun determineTaxCode(vatRate: Double?, invoiceText: String? = null): String {
        val safeRate = VatRateValidation.sanitizeStoredPercent(vatRate)
        // Check for special keywords first - reverse charge VAT (PVM25)
        val text = invoiceText?.uppercase() ?: ""
        // Check for various patterns indicating reverse charge VAT (Article 96)
        // Patterns to check (case-insensitive substring matching)
        val reverseChargeKeywords = listOf(
            "96 STRAIPSNIS",
            "96 STR",
            "96 STR.",
            "ATVIRKŠTINIS PVM",
            "ATVIRKŠTINIS PVM APMOKESTINIMAS",
            "APMOKESTINIMAS PAGAL 96",
            "APMOKESTINIMAS PAGAL 96 STR",
            "APMOKESTINIMAS PAGAL 96 STR.",
            "PAGAL 96 STRAIPSNIS",
            "PAGAL 96 STR",
            "PAGAL 96 STR.",
            "APMOKESTINIMAS PAGAL 96 STR", // Additional variations
            "PAGAL 96 STR" // Additional variations
        )
        
        for (keyword in reverseChargeKeywords) {
            if (text.contains(keyword, ignoreCase = true)) {
                Timber.d("Tax code determined: PVM25 (reverse charge detected: keyword '$keyword')")
                return "PVM25"
            }
        }
        
        // Also check with regex for more flexible matching (handles variations with spaces/punctuation)
        val reverseChargeRegexPatterns = listOf(
            Regex("96\\s*STRAIPSNIS", RegexOption.IGNORE_CASE),
            Regex("96\\s*STR\\.?", RegexOption.IGNORE_CASE),
            Regex("ATVIRKŠTINIS\\s*PVM", RegexOption.IGNORE_CASE),
            Regex("APMOKESTINIMAS\\s*PAGAL\\s*96", RegexOption.IGNORE_CASE),
            Regex("PAGAL\\s*96\\s*STR", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in reverseChargeRegexPatterns) {
            if (pattern.containsMatchIn(text)) {
                Timber.d("Tax code determined: PVM25 (reverse charge detected: regex pattern '${pattern.pattern}')")
                return "PVM25"
            }
        }
        
        // Map VAT rate to default tax codes
        // TODO: Refine this mapping based on PVM klasifikatorius document
        val code = when {
            safeRate == null -> "PVM1" // Default if rate unknown
            safeRate >= 20.0 -> "PVM1" // 21% VAT
            safeRate >= 8.0 -> "PVM2"  // 9% VAT
            safeRate >= 4.0 -> "PVM3"  // 5% VAT
            safeRate >= 0.0 -> "PVM4"  // 0% VAT
            else -> "PVM1" // Default fallback
        }
        
        Timber.d("Tax code determined: $code (VAT rate: $safeRate%, raw=$vatRate%)")
        return code
    }
    
    /**
     * Calculate VAT rate from amount without VAT and VAT amount.
     * 
     * @param amountWithoutVat Amount without VAT
     * @param vatAmount VAT amount
     * @return VAT rate as percentage, or null if calculation not possible
     */
    fun calculateVatRate(amountWithoutVat: Double?, vatAmount: Double?): Double? {
        if (amountWithoutVat == null) {
            return null
        }
        val vat = vatAmount ?: 0.0
        if (amountWithoutVat == 0.0) {
            return if (vat == 0.0) 0.0 else null
        }
        
        val rate = (vat / amountWithoutVat) * 100.0
        val snapped = VatRateValidation.snapRatioPercentToStandardOrNull(rate)
        Timber.d("Calculated VAT rate: $rate% -> snapped: $snapped%")
        return snapped
    }
}
















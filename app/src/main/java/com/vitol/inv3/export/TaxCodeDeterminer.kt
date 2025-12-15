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
        // Check for special keywords first - reverse charge VAT (PVM25)
        val text = invoiceText?.uppercase() ?: ""
        // Check for various patterns indicating reverse charge VAT (Article 96)
        val reverseChargePatterns = listOf(
            "96 STRAIPSNIS",
            "96 STR",
            "96 STR\\.",
            "ATVIRKŠTINIS PVM",
            "ATVIRKŠTINIS PVM APMOKESTINIMAS",
            "APMOKESTINIMAS PAGAL 96",
            "APMOKESTINIMAS PAGAL 96 STR",
            "APMOKESTINIMAS PAGAL 96 STR\\.",
            "PAGAL 96 STRAIPSNIS",
            "PAGAL 96 STR",
            "PAGAL 96 STR\\."
        )
        
        for (pattern in reverseChargePatterns) {
            if (text.contains(Regex(pattern, RegexOption.IGNORE_CASE))) {
                Timber.d("Tax code determined: PVM25 (reverse charge detected: pattern '$pattern')")
                return "PVM25"
            }
        }
        
        // Map VAT rate to default tax codes
        // TODO: Refine this mapping based on PVM klasifikatorius document
        val code = when {
            vatRate == null -> "PVM1" // Default if rate unknown
            vatRate >= 20.0 -> "PVM1" // 21% VAT
            vatRate >= 8.0 -> "PVM2"  // 9% VAT
            vatRate >= 4.0 -> "PVM3"  // 5% VAT
            vatRate >= 0.0 -> "PVM4"  // 0% VAT
            else -> "PVM1" // Default fallback
        }
        
        Timber.d("Tax code determined: $code (VAT rate: $vatRate%)")
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
        if (amountWithoutVat == null || vatAmount == null || amountWithoutVat == 0.0) {
            return null
        }
        
        val rate = (vatAmount / amountWithoutVat) * 100.0
        
        // Round to nearest standard rate (21, 9, 5, 0)
        val standardRates = listOf(21.0, 9.0, 5.0, 0.0)
        val roundedRate = standardRates.minByOrNull { kotlin.math.abs(it - rate) } ?: rate
        
        Timber.d("Calculated VAT rate: $rate% -> rounded to: $roundedRate%")
        return roundedRate
    }
}
















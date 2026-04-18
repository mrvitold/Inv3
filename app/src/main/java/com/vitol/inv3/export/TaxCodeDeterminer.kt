package com.vitol.inv3.export

import timber.log.Timber
import java.util.Locale

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
     * Result of inferring VAT rate from a monetary line that might be net or gross (with VAT).
     * Many invoices mis-label or OCR swaps "Suma be PVM" vs "Suma su PVM"; we try both.
     */
    data class VatAmountsInference(
        val ratePercent: Double,
        /** If the candidate was gross, this is net = gross − VAT for correcting the form. */
        val correctedNetAmount: Double?
    )

    /**
     * Try [amountCandidate] as **net** (without VAT); if that does not snap to a standard LT rate,
     * try as **gross** (with VAT) so net = candidate − VAT (when VAT is positive).
     */
    fun inferVatRateFromAmounts(amountCandidate: Double?, vatAmount: Double?): VatAmountsInference? {
        if (amountCandidate == null) return null
        if (amountCandidate < 0) return null
        var vat = vatAmount ?: 0.0
        // OCR / Azure may attach a minus to VAT while net is clearly positive (wrong cell or sign)
        if (amountCandidate > 0 && vat < 0) {
            Timber.d("VAT rate inference: negative VAT with positive net; using absolute value for inference (was $vat)")
            vat = -vat
        }
        if (vat < 0) return null

        if (amountCandidate == 0.0) {
            return if (vat == 0.0) {
                val z = VatRateValidation.snapRatioPercentToStandardOrNull(0.0) ?: 0.0
                VatAmountsInference(z, null)
            } else {
                null
            }
        }

        val rateIfNet = (vat / amountCandidate) * 100.0
        val snappedNet = VatRateValidation.snapRatioPercentToStandardOrNull(rateIfNet)
        if (snappedNet != null) {
            Timber.d("VAT rate inference: amount as NET ($amountCandidate): $rateIfNet% -> $snappedNet%")
            return VatAmountsInference(snappedNet, null)
        }

        if (vat == 0.0) return null

        val impliedNet = amountCandidate - vat
        if (impliedNet <= 0.001) return null
        val rateIfGross = (vat / impliedNet) * 100.0
        val snappedGross = VatRateValidation.snapRatioPercentToStandardOrNull(rateIfGross)
        if (snappedGross != null) {
            Timber.d(
                "VAT rate inference: amount as GROSS (implied net=$impliedNet): " +
                    "$rateIfGross% -> $snappedGross%"
            )
            return VatAmountsInference(snappedGross, impliedNet)
        }

        Timber.d("VAT rate inference: no match (as net: $rateIfNet%, as gross net=$impliedNet: $rateIfGross%)")
        return null
    }

    /** Format [value] with the same decimal separator as [originalAmountString] (`,` vs `.`). */
    fun formatAmountPreservingSeparator(value: Double, originalAmountString: String): String {
        val sep = if (originalAmountString.contains(',')) ',' else '.'
        return String.format(Locale.US, "%.2f", value).replace('.', sep)
    }

    /**
     * Calculate VAT rate from amount without VAT and VAT amount.
     * If the first argument is actually **gross**, still returns the correct standard rate when possible.
     */
    fun calculateVatRate(amountWithoutVat: Double?, vatAmount: Double?): Double? {
        return inferVatRateFromAmounts(amountWithoutVat, vatAmount)?.ratePercent
    }
}
















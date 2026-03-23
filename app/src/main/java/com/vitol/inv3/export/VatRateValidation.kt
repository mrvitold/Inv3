package com.vitol.inv3.export

import kotlin.math.abs

/**
 * Lithuanian VAT is only 21%, 9%, 5%, or 0%. OCR often misreads unrelated numbers (e.g. "100%")
 * or net/VAT fields get swapped, producing impossible "100%" from vat/net ratio.
 */
object VatRateValidation {

    /** Official LT VAT rates as percentages. */
    val STANDARD_PERCENTAGES = listOf(21.0, 9.0, 5.0, 0.0)

    /** Max plausible standard rate in LT (21%) plus small OCR tolerance. */
    private const val MAX_PLAUSIBLE_PERCENT = 27.0

    /** Reject raw ratio when VAT equals net (wrong fields) — exactly ~100%. */
    private const val EXACT_100_TOLERANCE = 0.05

    /**
     * If [rawPercent] is within this many percentage points of a standard rate, we accept the snap.
     * Covers rounding noise on line totals (e.g. 20.97% → 21%).
     */
    private const val SNAP_TOLERANCE = 3.5

    fun isImplausibleRatioPercent(rawPercent: Double): Boolean {
        if (rawPercent.isNaN() || rawPercent.isInfinite()) return true
        if (rawPercent < 0) return true
        if (abs(rawPercent - 100.0) < EXACT_100_TOLERANCE) return true
        if (rawPercent > MAX_PLAUSIBLE_PERCENT) return true
        return false
    }

    /**
     * Map a computed VAT/net ratio (percent) to the nearest standard LT rate, or null if unreliable.
     */
    fun snapRatioPercentToStandardOrNull(rawPercent: Double): Double? {
        if (isImplausibleRatioPercent(rawPercent)) return null
        val nearest = STANDARD_PERCENTAGES.minByOrNull { abs(it - rawPercent) } ?: return null
        if (abs(nearest - rawPercent) > SNAP_TOLERANCE) return null
        return nearest
    }

    /**
     * Clean a value already stored or entered by the user (reject obvious OCR garbage like 100%).
     */
    fun sanitizeStoredPercent(percent: Double?): Double? {
        if (percent == null) return null
        if (percent.isNaN() || percent.isInfinite()) return null
        if (abs(percent - 100.0) < EXACT_100_TOLERANCE) return null
        if (percent < 0 || percent > MAX_PLAUSIBLE_PERCENT + 0.01) return null
        return percent
    }

    /**
     * Parse OCR text like "21", "21%", "9,5" and return a canonical integer percent string, or null if invalid.
     */
    fun sanitizeOcrPercentToDisplayString(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val num = value.replace(",", ".").replace("%", "").trim().toDoubleOrNull() ?: return null
        val snapped = snapRatioPercentToStandardOrNull(num) ?: return null
        return snapped.toInt().toString()
    }
}

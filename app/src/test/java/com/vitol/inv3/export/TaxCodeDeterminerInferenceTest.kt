package com.vitol.inv3.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxCodeDeterminerInferenceTest {

    @Test
    fun inferVatRateFromAmounts_positiveNet_negativeVat_snapshotsToStandardRate() {
        // Same ratio as positive VAT; Azure sometimes emits negative VAT valueNumber.
        val inf = TaxCodeDeterminer.inferVatRateFromAmounts(23.55, -4.94)
        assertEquals(21.0, inf?.ratePercent ?: Double.NaN, 0.001)
    }

    @Test
    fun inferVatRateFromAmounts_bothNegativeNetAndVat_returnsNull() {
        assertNull(TaxCodeDeterminer.inferVatRateFromAmounts(-10.0, -2.0))
    }
}

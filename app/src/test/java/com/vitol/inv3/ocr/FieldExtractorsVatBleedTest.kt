package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldExtractorsVatBleedTest {

    @Test
    fun normalizeLithuanianVatColumnBleed_keskoMergedLt11FromBuyerColumn() {
        assertEquals(
            "LT1343765219",
            FieldExtractors.normalizeLithuanianVatColumnBleed("LT11343765219")
        )
    }

    @Test
    fun normalizeLithuanianVatColumnBleed_alreadyCorrectTenDigitsUnchanged() {
        assertEquals(
            "LT1343765219",
            FieldExtractors.normalizeLithuanianVatColumnBleed("LT1343765219")
        )
    }

    @Test
    fun normalizeLithuanianVatColumnBleed_longBuyerStyleVatUnchanged() {
        assertEquals(
            "LT1100008809112",
            FieldExtractors.normalizeLithuanianVatColumnBleed("LT1100008809112")
        )
    }

    @Test
    fun normalizeLithuanianVatColumnBleed_nullStaysNull() {
        assertNull(FieldExtractors.normalizeLithuanianVatColumnBleed(null))
    }

    @Test
    fun normalizeLithuanianVatColumnBleed_spacesAndDashesNormalizedBeforeFix() {
        assertEquals(
            "LT1343765219",
            FieldExtractors.normalizeLithuanianVatColumnBleed("LT 11 343 765 219")
        )
    }
}

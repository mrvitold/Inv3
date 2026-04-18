package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceParserLtRetailTaxBreakdownTest {

    @Test
    fun tryExtractNetVatFromLtTaxBreakdown_fourTokenRow_rateVatNetGross() {
        val lines = listOf(
            "Mokestis PVM Be PVM Su PVM",
            "A 21,00% 9,46 45,07 54,53",
        )
        val p = InvoiceParser.tryExtractNetVatFromLtTaxBreakdown(lines)
        assertEquals("45.07", p?.first)
        assertEquals("9.46", p?.second)
    }

    @Test
    fun tryExtractNetVatFromLtTaxBreakdown_threeTokenRow_vatNetGross() {
        val lines = listOf(
            "9,46 45,07 54,53",
        )
        val p = InvoiceParser.tryExtractNetVatFromLtTaxBreakdown(lines)
        assertEquals("45.07", p?.first)
        assertEquals("9.46", p?.second)
    }

    @Test
    fun tryExtractNetVatFromLtTaxBreakdown_noMatchWrongSum() {
        val lines = listOf(
            "1,00 2,00 5,00",
        )
        assertNull(InvoiceParser.tryExtractNetVatFromLtTaxBreakdown(lines))
    }
}

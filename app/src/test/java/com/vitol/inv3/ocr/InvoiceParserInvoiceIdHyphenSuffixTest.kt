package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceParserInvoiceIdHyphenSuffixTest {

    @Test
    fun extractInvoiceIdWithSerialAndNumber_combinesSerijaNumerisWithHyphenInNumber() {
        val lines = listOf(
            "Serija DDV Numeris 20260209-3",
        )
        assertEquals("DDV20260209-3", InvoiceParser.extractInvoiceIdWithSerialAndNumber(lines))
    }

    @Test
    fun extractInvoiceIdWithSerialAndNumber_handlesColonLabels() {
        val lines = listOf(
            "Serija: DDV Numeris: 20260209-3",
        )
        assertEquals("DDV20260209-3", InvoiceParser.extractInvoiceIdWithSerialAndNumber(lines))
    }

    @Test
    fun parse_secondPass_findsStandaloneIdWithHyphenSuffix() {
        val parsed = InvoiceParser.parse(
            listOf(
                "Some header",
                "DDV20260209-3",
            )
        )
        assertEquals("DDV20260209-3", parsed.invoiceId)
    }

    @Test
    fun parse_secondPass_findsNrLineWithHyphenSuffix() {
        val parsed = InvoiceParser.parse(
            listOf(
                "Nr. DDV20260209-3",
            )
        )
        assertEquals("DDV20260209-3", parsed.invoiceId)
    }
}

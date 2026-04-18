package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceParserBuyerPavadinimasLabelTest {

    @Test
    fun extractCompanyNameAdvanced_stripsPirkejoPavadinimasPrefix() {
        val lines = listOf(
            "Pirkėjo pavadinimas: UAB \"UHB FOOD\"",
            "Įmonės kodas 305214783",
        )
        val name = InvoiceParser.extractCompanyNameAdvanced(
            lines,
            companyNumber = "305214783",
            vatNumber = null,
            excludeOwnCompanyNumber = null,
            excludeOwnCompanyName = null,
            invoiceType = "S",
        )
        assertEquals("UAB \"UHB FOOD\"", name)
    }

    @Test
    fun extractCompanyNameAdvanced_stripsPardavejoPavadinimasPrefix() {
        val lines = listOf(
            "Pardavėjo pavadinimas: UAB \"Partner\"",
            "Įmonės kodas 303309250",
        )
        val name = InvoiceParser.extractCompanyNameAdvanced(
            lines,
            companyNumber = "303309250",
            vatNumber = null,
            excludeOwnCompanyNumber = null,
            excludeOwnCompanyName = null,
            invoiceType = "P",
        )
        assertEquals("UAB \"Partner\"", name)
    }
}

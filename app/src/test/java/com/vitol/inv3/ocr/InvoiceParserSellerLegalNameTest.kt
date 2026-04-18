package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceParserSellerLegalNameTest {

    @Test
    fun uarTypoRegex_matchesTrailingCommaUar() {
        val trimmedLower = "kesko senukai lithuania, uar"
        val hasUarTypo =
            Regex("""(?i)(,\s*uar\s*$|\blithuania.*\buar\b)""").containsMatchIn(trimmedLower)
        assertTrue(hasUarTypo)
    }

    @Test
    fun findSellerLegalNameFromOcrLines_fixesUarTypo() {
        val lines = listOf(
            "KESKO",
            "SENUKAI",
            "PVM SĄSKAITA FAKTŪRA Nr. SSP000725912",
            "PARDAVĖJAS",
            "MOKĖJIMO INFORMACIJA",
            "KESKO SENUKAI LITHUANIA, UAR",
            "Islandijos pl. 32B",
        )
        val name = InvoiceParser.findSellerLegalNameFromOcrLines(lines, excludeOwnCompanyName = "UAB Buyer")
        assertEquals("KESKO SENUKAI LITHUANIA, UAB", name)
    }

    @Test
    fun findSellerLegalNameFromOcrLines_skipsOwnCompany() {
        val lines = listOf(
            "UAB STATYBŲ FRONTAS",
        )
        assertNull(
            InvoiceParser.findSellerLegalNameFromOcrLines(
                lines,
                excludeOwnCompanyName = "UAB Statybu frontas",
            )
        )
    }
}

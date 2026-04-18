package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyNameUtilsTest {
    @Test
    fun normalizeCompanyNameQuotes_fixesOcrLithuanianQuotesAndArtifacts() {
        // Azure/OCR: „ and “ misread as "." and "*"
        assertEquals(
            "UAB GINESTRA",
            CompanyNameUtils.normalizeCompanyNameQuotes("UAB .GINESTRA*")
        )
        assertEquals(
            "UAB GINESTRA",
            CompanyNameUtils.normalizeCompanyNameQuotes("""UAD -GINESTRA"""")
        )
        // Real Lithuanian „ in string before OCR fixes (closing “ may still be missing)
        assertEquals(
            """UAB "GINESTRA""",
            CompanyNameUtils.normalizeCompanyNameQuotes("UAB \u201EGINESTRA*")
        )
        assertEquals(
            """UAB "GINESTRA"""",
            CompanyNameUtils.normalizeCompanyNameQuotes("""UAB "GINESTRA"""")
        )
    }
}

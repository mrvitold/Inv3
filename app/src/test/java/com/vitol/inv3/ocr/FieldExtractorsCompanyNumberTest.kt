package com.vitol.inv3.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldExtractorsCompanyNumberTest {
    @Test
    fun tryExtractCompanyNumber_skipsHyphenatedInvoiceSuffixOnNrLine() {
        assertNull(
            FieldExtractors.tryExtractCompanyNumber(
                "Nr. VLN-VOBP-120792",
                excludeVatNumber = "LT213200113",
                excludeOwnCompanyNumber = "303309250"
            )
        )
    }

    @Test
    fun tryExtractCompanyNumber_findsNineDigitCodeOnOwnLine() {
        assertEquals(
            "121320015",
            FieldExtractors.tryExtractCompanyNumber(
                "121320015",
                excludeVatNumber = "LT213200113",
                excludeOwnCompanyNumber = "303309250"
            )
        )
    }

    @Test
    fun tryExtractCompanyNumber_skipsSixDigitsSuffixOfLtPhoneLine() {
        assertNull(
            FieldExtractors.tryExtractCompanyNumber(
                "+370 37 337952",
                excludeVatNumber = "LT105995515",
                excludeOwnCompanyNumber = "303309250"
            )
        )
    }

    @Test
    fun tryExtractCompanyNumber_stillFindsCodeWhenNineDigitBeforePhoneOnSameLine() {
        assertEquals(
            "210599550",
            FieldExtractors.tryExtractCompanyNumber(
                "Įmonės kodas 210599550, tel. +370 37 337952",
                excludeVatNumber = "LT105995515",
                excludeOwnCompanyNumber = "303309250"
            )
        )
    }
}

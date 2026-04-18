package com.vitol.inv3.ui.scan

import com.vitol.inv3.ocr.ParsedInvoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewScanMergeTest {

    @Test
    fun merge_prefersPage2Totals_overPage1Subtotals() {
        val page1 = MergedFormData(
            invoiceId = "VB-1",
            date = "2026-03-26",
            companyName = "UAB Seller",
            amountWithoutVat = "100,00",
            vatAmount = "21,00",
            vatNumber = "LT111111111",
            companyNumber = "123456789",
            vatRate = "21",
            taxCode = "PVM1"
        )
        val page2 = ParsedInvoice(
            amountWithoutVatEur = "220,56",
            vatAmountEur = "46,32",
            vatRate = "21"
        )
        val r = ReviewScanMerge.mergeFormFromOcr(
            mergedFromPage1 = page1,
            parsed = page2,
            invoiceType = "P",
            ownCompanyName = "UAB Buyer",
            ownCompanyNumber = "999999999",
            ownCompanyVatNumber = "LT999999999",
        )
        assertEquals("220,56", r.amountWithoutVat)
        assertEquals("46,32", r.vatAmount)
        assertEquals("UAB Seller", r.companyName)
        assertEquals("LT111111111", r.vatNumber)
    }

    @Test
    fun merge_usesPage1OcrSnapshot_whenFormIdentityStillEmpty() {
        val page1Snap = ParsedInvoice(
            companyName = "UAB GINESTRA",
            vatNumber = "LT230555515",
            companyNumber = "123055551",
            invoiceId = "VB-000077536",
            date = "2026-03-26",
        )
        val page1 = MergedFormData(
            companyName = "",
            vatNumber = "",
            companyNumber = "",
            invoiceId = "",
            date = "",
            amountWithoutVat = "",
            vatAmount = "",
            vatRate = "",
            taxCode = "PVM1",
            page1ParsedInvoice = page1Snap,
        )
        val page2 = ParsedInvoice(
            companyName = "Ask Al Assistar Shore PVM SĄSKAITA FAKTŪRA",
            amountWithoutVatEur = "220,56",
            vatAmountEur = "46,32",
        )
        val r = ReviewScanMerge.mergeFormFromOcr(
            mergedFromPage1 = page1,
            parsed = page2,
            invoiceType = "P",
            ownCompanyName = null,
            ownCompanyNumber = null,
            ownCompanyVatNumber = null,
        )
        assertEquals("UAB GINESTRA", r.companyName)
        assertEquals("LT230555515", r.vatNumber)
        assertEquals("123055551", r.companyNumber)
        assertEquals("VB-000077536", r.invoiceId)
        assertEquals("220,56", r.amountWithoutVat)
        assertEquals("46,32", r.vatAmount)
    }

    @Test
    fun merge_keepsPage1Identity_whenPage2wouldOverwriteWithDifferentParsedName() {
        val page1 = MergedFormData(
            companyName = "UAB GINESTRA",
            vatNumber = "LT230555515",
            companyNumber = "123055551",
            invoiceId = "VB-X",
            date = "2026-03-26",
            amountWithoutVat = "",
            vatAmount = "",
            vatRate = "",
            taxCode = "PVM1"
        )
        val page2 = ParsedInvoice(
            companyName = "Some Other UAB Name",
            invoiceId = "OTHER",
            vatNumber = "LT999999999",
            companyNumber = "999999999",
        )
        val r = ReviewScanMerge.mergeFormFromOcr(
            mergedFromPage1 = page1,
            parsed = page2,
            invoiceType = "P",
            ownCompanyName = null,
            ownCompanyNumber = null,
            ownCompanyVatNumber = null,
        )
        assertEquals("UAB GINESTRA", r.companyName)
        assertEquals("LT230555515", r.vatNumber)
        assertEquals("123055551", r.companyNumber)
        assertEquals("VB-X", r.invoiceId)
    }

    @Test
    fun merge_fillsIdentityFromPage2WhenPage1Blank_purchase() {
        val page1 = MergedFormData(
            companyName = "",
            vatNumber = "",
            companyNumber = "",
            invoiceId = "VB-1",
            date = "2026-01-01",
            amountWithoutVat = "",
            vatAmount = "",
            vatRate = "21",
            taxCode = "PVM1"
        )
        val page2 = ParsedInvoice(
            companyName = "UAB Partner Krautuvė",
            vatNumber = "LT100100100",
            companyNumber = "300100600",
            amountWithoutVatEur = "50,00",
            vatAmountEur = "10,50",
        )
        val r = ReviewScanMerge.mergeFormFromOcr(
            mergedFromPage1 = page1,
            parsed = page2,
            invoiceType = "P",
            ownCompanyName = "UAB Mes",
            ownCompanyNumber = "111111111",
            ownCompanyVatNumber = "LT111111111",
        )
        assertEquals("UAB Partner Krautuvė", r.companyName)
        assertEquals("LT100100100", r.vatNumber)
        assertEquals("300100600", r.companyNumber)
        assertEquals("50,00", r.amountWithoutVat)
    }

    @Test
    fun singlePage_usesParsedOnly() {
        val p = ParsedInvoice(
            invoiceId = "S-1",
            companyName = "UAB Solo",
            amountWithoutVatEur = "10,00",
            vatAmountEur = "2,10",
            vatNumber = "LT555555555",
            companyNumber = "555555555",
            vatRate = "21",
        )
        val r = ReviewScanMerge.mergeFormFromOcr(
            mergedFromPage1 = null,
            parsed = p,
            invoiceType = "P",
            ownCompanyName = null,
            ownCompanyNumber = null,
            ownCompanyVatNumber = null,
        )
        assertEquals("S-1", r.invoiceId)
        assertEquals("UAB Solo", r.companyName)
        assertEquals("10,00", r.amountWithoutVat)
        assertEquals("PVM1", r.taxCode)
    }
}

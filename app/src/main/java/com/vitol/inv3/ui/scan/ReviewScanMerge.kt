package com.vitol.inv3.ui.scan

import com.vitol.inv3.export.VatRateValidation
import com.vitol.inv3.ocr.CompanyNameUtils
import com.vitol.inv3.ocr.ParsedInvoice

/**
 * Combines the user's page-1 form snapshot (after "Merge with next") with OCR from page 2.
 *
 * Product rules:
 * - **Seller / identity** (name, JA kodas, LT VAT): prefer **page 1** when present; page 2 only fills gaps.
 *   Continuation pages often have watermarks or buyer blocks at the top — never let them replace a good header.
 * - **Totals** (net, VAT, rate): prefer **page 2** when present, then page-1 form, then page-1 OCR.
 * - **Invoice id & date**: form first, then page-1 OCR, then page 2.
 * - Own-company exclusion applies to identity fields so buyer OCR is not saved as seller.
 */
object ReviewScanMerge {

    data class MergedFormFields(
        val invoiceId: String,
        val date: String,
        val companyName: String,
        val amountWithoutVat: String,
        val vatAmount: String,
        val vatNumber: String,
        val companyNumber: String,
        val vatRate: String,
        val taxCode: String,
    )

    fun mergeFormFromOcr(
        mergedFromPage1: MergedFormData?,
        parsed: ParsedInvoice,
        invoiceType: String,
        ownCompanyName: String?,
        ownCompanyNumber: String?,
        ownCompanyVatNumber: String?,
    ): MergedFormFields {
        val p2NameTrusted = parsed.companyName?.takeUnless { CompanyNameUtils.isLikelyGarbageCompanyName(it) }

        fun sanitizeRate(raw: String?): String =
            VatRateValidation.sanitizeOcrPercentToDisplayString(raw) ?: ""

        val merged = mergedFromPage1 ?: return buildSinglePage(
            parsed = parsed,
            invoiceType = invoiceType,
            trustedName = p2NameTrusted,
            ownCompanyName = ownCompanyName,
            ownCompanyNumber = ownCompanyNumber,
            ownCompanyVatNumber = ownCompanyVatNumber,
            sanitizeRate = ::sanitizeRate,
        )

        val p1 = merged.page1ParsedInvoice
        val p1NameTrusted = p1?.companyName?.takeUnless { CompanyNameUtils.isLikelyGarbageCompanyName(it) }

        // --- Multi-page: page 1 form + optional page-1 OCR snapshot + page 2 OCR ---
        val invoiceId = coalesceText(merged.invoiceId, p1?.invoiceId, parsed.invoiceId)
        val date = coalesceText(merged.date, p1?.date, parsed.date)

        var companyName = coalesceText(
            merged.companyName.takeIf { it.isNotBlank() },
            p1NameTrusted?.takeIf { it.isNotBlank() },
            p2NameTrusted?.takeIf { it.isNotBlank() },
        )

        if (invoiceType == "P" && ownCompanyName != null) {
            val p2n = p2NameTrusted
            if (!p2n.isNullOrBlank() && CompanyNameUtils.hasLegalFormMarker(p2n) &&
                !CompanyNameUtils.isSameAsOwnCompanyName(p2n, ownCompanyName)
            ) {
                if (companyName.isBlank() ||
                    CompanyNameUtils.isSameAsOwnCompanyName(companyName, ownCompanyName)
                ) {
                    companyName = p2n
                }
            }
        }

        var vatNumber = coalesceText(merged.vatNumber, p1?.vatNumber, parsed.vatNumber)
        var companyNumber = coalesceText(merged.companyNumber, p1?.companyNumber, parsed.companyNumber)

        val amountWithoutVat = pickAmountsFirst(
            parsed.amountWithoutVatEur,
            merged.amountWithoutVat.takeIf { it.isNotBlank() },
            p1?.amountWithoutVatEur,
        )
        val vatAmount = pickAmountsFirst(
            parsed.vatAmountEur,
            merged.vatAmount.takeIf { it.isNotBlank() },
            p1?.vatAmountEur,
        )
        val vatRate = sanitizeRate(parsed.vatRate).takeIf { it.isNotBlank() }
            ?: sanitizeRate(merged.vatRate).takeIf { it.isNotBlank() }
            ?: sanitizeRate(p1?.vatRate)

        val taxCode = merged.taxCode.ifBlank { "PVM1" }

        if (ownCompanyNumber != null && companyNumber == ownCompanyNumber) {
            companyNumber = ""
        }
        if (ownCompanyVatNumber != null && vatNumber.equals(ownCompanyVatNumber, ignoreCase = true)) {
            vatNumber = ""
        }
        if (ownCompanyName != null && CompanyNameUtils.isSameAsOwnCompanyName(companyName, ownCompanyName)) {
            companyName = ""
        }

        if (invoiceType == "P" && companyName.isBlank() && ownCompanyName != null) {
            companyName = fallbackPurchaseCompanyName(
                page2 = parsed,
                page1 = p1,
                ownCompanyName = ownCompanyName,
            )
        }

        return MergedFormFields(
            invoiceId = invoiceId,
            date = date,
            companyName = companyName,
            amountWithoutVat = amountWithoutVat,
            vatAmount = vatAmount,
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            vatRate = vatRate,
            taxCode = taxCode,
        )
    }

    private fun buildSinglePage(
        parsed: ParsedInvoice,
        invoiceType: String,
        trustedName: String?,
        ownCompanyName: String?,
        ownCompanyNumber: String?,
        ownCompanyVatNumber: String?,
        sanitizeRate: (String?) -> String,
    ): MergedFormFields {
        var companyName = trustedName ?: ""
        var vatNumber = parsed.vatNumber ?: ""
        var companyNumber = parsed.companyNumber ?: ""

        if (ownCompanyNumber != null && companyNumber == ownCompanyNumber) {
            companyNumber = ""
        }
        if (ownCompanyVatNumber != null && vatNumber.equals(ownCompanyVatNumber, ignoreCase = true)) {
            vatNumber = ""
        }
        if (ownCompanyName != null && CompanyNameUtils.isSameAsOwnCompanyName(companyName, ownCompanyName)) {
            companyName = ""
        }

        if (invoiceType == "P" && companyName.isBlank() && ownCompanyName != null) {
            val pn = parsed.companyName
            if (pn != null && !CompanyNameUtils.isLikelyGarbageCompanyName(pn) &&
                !CompanyNameUtils.isSameAsOwnCompanyName(pn, ownCompanyName)
            ) {
                companyName = pn
            }
        }

        return MergedFormFields(
            invoiceId = parsed.invoiceId ?: "",
            date = parsed.date ?: "",
            companyName = companyName,
            amountWithoutVat = parsed.amountWithoutVatEur ?: "",
            vatAmount = parsed.vatAmountEur ?: "",
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            vatRate = sanitizeRate(parsed.vatRate),
            taxCode = "PVM1",
        )
    }

    private fun coalesceText(vararg parts: String?): String {
        for (p in parts) {
            if (!p.isNullOrBlank()) return p.trim()
        }
        return ""
    }

    private fun pickAmountsFirst(page2: String?, form: String?, page1: String?): String {
        if (!page2.isNullOrBlank()) return page2.trim()
        if (!form.isNullOrBlank()) return form.trim()
        if (!page1.isNullOrBlank()) return page1.trim()
        return ""
    }

    /** Prefer page-2 candidate, then page-1 raw OCR (continuation page may lack seller text). */
    private fun fallbackPurchaseCompanyName(
        page2: ParsedInvoice,
        page1: ParsedInvoice?,
        ownCompanyName: String,
    ): String {
        for (pn in listOfNotNull(page2.companyName, page1?.companyName)) {
            if (!CompanyNameUtils.isLikelyGarbageCompanyName(pn) &&
                !CompanyNameUtils.isSameAsOwnCompanyName(pn, ownCompanyName)
            ) {
                return pn.trim()
            }
        }
        return ""
    }
}

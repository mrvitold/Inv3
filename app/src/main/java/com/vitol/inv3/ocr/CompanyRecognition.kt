package com.vitol.inv3.ocr

object CompanyRecognition {
    data class Candidate(
        val companyName: String?,
        val companyNumber: String?,
        val vatNumber: String?,
        val confidence: Float
    )

    /**
     * Recognize company from invoice text.
     * @param lines Invoice text lines
     * @param excludeOwnCompanyNumber Own company number to exclude (partner's company only)
     * @param excludeOwnVatNumber Own company VAT number to exclude (partner's company only)
     */
    fun recognize(lines: List<String>, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null): Candidate {
        val joined = lines.joinToString("\n").lowercase()
        val vat = FieldExtractors.tryExtractVatNumber(joined, excludeOwnVatNumber)
        // Extract company number, excluding VAT number and own company number to avoid duplicates
        val compNo = FieldExtractors.tryExtractCompanyNumber(joined, vat, excludeOwnCompanyNumber)

        val possibleName = lines.firstOrNull { line ->
            val l = line.lowercase()
            ("uab" in l || "ab" in l || "mb" in l || "ltd" in l || "oy" in l || "as" in l) && l.length < 80
        }?.trim()

        val confidence = listOfNotNull(vat, compNo, possibleName).size / 3f

        return Candidate(
            companyName = possibleName,
            companyNumber = compNo,
            vatNumber = vat,
            confidence = confidence
        )
    }
}


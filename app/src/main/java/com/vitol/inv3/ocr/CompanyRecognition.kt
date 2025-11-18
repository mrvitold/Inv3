package com.vitol.inv3.ocr

object CompanyRecognition {
    data class Candidate(
        val companyName: String?,
        val companyNumber: String?,
        val vatNumber: String?,
        val confidence: Float
    )

    fun recognize(lines: List<String>): Candidate {
        val joined = lines.joinToString("\n").lowercase()
        val vat = FieldExtractors.tryExtractVatNumber(joined)
        // Extract company number, excluding VAT number to avoid duplicates
        val compNo = FieldExtractors.tryExtractCompanyNumber(joined, vat)

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


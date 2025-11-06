package com.vitol.inv3.ocr

data class ParsedInvoice(
    val invoiceId: String? = null,
    val date: String? = null,
    val companyName: String? = null,
    val amountWithoutVatEur: String? = null,
    val vatAmountEur: String? = null,
    val vatNumber: String? = null,
    val companyNumber: String? = null,
    val lines: List<String> = emptyList()
)

object InvoiceParser {
    fun parse(lines: List<String>): ParsedInvoice {
        var invoiceId: String? = null
        var date: String? = null
        var companyName: String? = null
        var amountNoVat: String? = null
        var vatAmount: String? = null
        var vatNumber: String? = null
        var companyNumber: String? = null

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            val key = KeywordMapping.normalizeKey(line)
            when (key) {
                "Invoice_ID" -> if (invoiceId == null) invoiceId = takeKeyValue(line)
                "Date" -> if (date == null) date = FieldExtractors.tryExtractDate(line) ?: takeKeyValue(line)
                "Company_name" -> if (companyName == null) companyName = takeKeyValue(line)
                "Amount_without_VAT_EUR" -> if (amountNoVat == null) amountNoVat = FieldExtractors.tryExtractAmount(line)
                "VAT_amount_EUR" -> if (vatAmount == null) vatAmount = FieldExtractors.tryExtractAmount(line)
                "VAT_number" -> if (vatNumber == null) vatNumber = FieldExtractors.tryExtractVatNumber(line) ?: takeKeyValue(line)
                "Company_number" -> if (companyNumber == null) companyNumber = FieldExtractors.tryExtractCompanyNumber(line)
            }
        }

        return ParsedInvoice(
            invoiceId = invoiceId,
            date = date,
            companyName = companyName,
            amountWithoutVatEur = amountNoVat,
            vatAmountEur = vatAmount,
            vatNumber = vatNumber,
            companyNumber = companyNumber,
            lines = lines
        )
    }

    private fun takeKeyValue(line: String): String? {
        val idx = line.indexOf(':')
        return if (idx > 0 && idx + 1 < line.length) line.substring(idx + 1).trim() else null
    }
}


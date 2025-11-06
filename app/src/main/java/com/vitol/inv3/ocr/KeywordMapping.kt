package com.vitol.inv3.ocr

object KeywordMapping {
    private val map: Map<String, List<String>> = mapOf(
        "Invoice_ID" to listOf("invoice number", "saskaitos numeris", "saskaitos serija", "fakturos serija"),
        "Date" to listOf("data", "saskaitos data", "israsymo data", "invoice date", "proforma date"),
        "Company_name" to listOf("imone", "kompanija", "bendrove"),
        "Amount_without_VAT_EUR" to listOf("suma be pvm", "suma", "apmokestinamoji verte", "before vat", "without vat"),
        "VAT_amount_EUR" to listOf("pvm", "pvm suma", "vat amount"),
        "VAT_number" to listOf("pvm kodas", "pvm numeris", "vat number"),
        "Company_number" to listOf("imones kodas", "imonenes registracijos numeris", "registracijos kodas")
    )

    fun normalizeKey(raw: String): String? {
        val s = raw.trim().lowercase()
        return map.entries.firstOrNull { (_, synonyms) -> synonyms.any { s.contains(it) } }?.key
    }
}

object FieldExtractors {
    private val dateRegex = Regex("\n?(?:date|data)[^0-9]*([0-3]?[0-9][./-][01]?[0-9][./-](?:[0-9]{2}|[0-9]{4}))", RegexOption.IGNORE_CASE)
    private val amountRegex = Regex("([0-9]+(?:[.,][0-9]{2})?)")
    private val vatNumberRegex = Regex("(LT)?[0-9A-Z]{8,12}")
    private val companyNumberRegex = Regex("[0-9]{7,14}")

    fun tryExtractDate(line: String): String? = dateRegex.find(line)?.groupValues?.getOrNull(1)
    fun tryExtractAmount(line: String): String? = amountRegex.find(line)?.groupValues?.getOrNull(1)
    fun tryExtractVatNumber(line: String): String? = vatNumberRegex.find(line)?.value
    fun tryExtractCompanyNumber(line: String): String? = companyNumberRegex.find(line)?.value
}


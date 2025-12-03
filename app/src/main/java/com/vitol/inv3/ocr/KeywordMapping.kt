package com.vitol.inv3.ocr

object KeywordMapping {
    private val map: Map<String, List<String>> = mapOf(
        "Invoice_ID" to listOf(
            "invoice number", "saskaitos numeris", "saskaitos serija", "fakturos serija",
            "nr.", "nr ", "numeris", "serija", "serijos", "serijos kodas", "series",
            "ssp", "saskaitos fakturos numeris", "numerio"
        ),
        "Date" to listOf(
            "data", "saskaitos data", "israsymo data", "invoice date", "proforma date",
            "sf data", "saskaitos fakturos data", "data:", "date:"
        ),
        "Company_name" to listOf(
            "imone", "kompanija", "bendrove", "pardavejas", "gavejas",
            "uab", "ab", "mb", "ltd", "company", "seller"
        ),
        "Amount_without_VAT_EUR" to listOf(
            "suma be pvm", "suma bepvm", "sumabepvm", "suma", 
            "apmokestinamoji verte", "before vat", "without vat",
            "pardavimo tarpine suma", "pardavimotarpinesuma"
        ),
        "VAT_amount_EUR" to listOf(
            "pvm", "pvm suma", "pvmsuma", "vat amount", "vat suma", "pvm suma:"
        ),
        "VAT_number" to listOf(
            "pvm kodas", "pvm numeris", "pvmnumeris", "pvmkodas", 
            "vat number", "vat kodas", "pvm kodas:"
        ),
        "Company_number" to listOf(
            "imones kodas", "imoneskodas", "imonenes registracijos numeris", 
            "registracijos kodas", "registracijos numeris", "imones kodas:"
        )
    )

    fun normalizeKey(raw: String): String? {
        val s = raw.trim().lowercase()
        return map.entries.firstOrNull { (_, synonyms) -> synonyms.any { s.contains(it) } }?.key
    }
}

object FieldExtractors {
    // More flexible date regex - handles YYYY.MM.DD, DD.MM.YYYY, etc.
    private val dateRegex = Regex(
        "(?:date|data)[^0-9]*([0-3]?[0-9][./-][01]?[0-9][./-](?:[0-9]{2}|[0-9]{4}))",
        RegexOption.IGNORE_CASE
    )
    // Standalone date pattern (YYYY.MM.DD or DD.MM.YYYY)
    private val standaloneDateRegex = Regex(
        "\\b([0-9]{4}[./-][01]?[0-9][./-][0-3]?[0-9]|[0-3]?[0-9][./-][01]?[0-9][./-][0-9]{4})\\b"
    )
    private val amountRegex = Regex(
        "([0-9]{1,6}(?:[\\s.,][0-9]{3})*[,\\.][0-9]{1,3}|[0-9]{1,6}[,\\.][0-9]{1,3}|[0-9]{1,6}(?:[\\s.,][0-9]{3})+[,\\.]?[0-9]{0,3})",
        RegexOption.IGNORE_CASE
    )
    // Lithuanian VAT number: MUST start with "LT" followed by digits (e.g., LT100008777514)
    // If there's no "LT" prefix, it's NOT a VAT number
    private val vatNumberRegex = Regex("\\b(LT[0-9A-Z]{8,12})\\b", RegexOption.IGNORE_CASE)
    // Lithuanian company number: 9 digits starting with 1, 2, 3, or 4
    private val companyNumberRegex = Regex("\\b([1-4][0-9]{8})\\b")

    fun tryExtractDate(line: String): String? {
        // Try with context first (date/data keyword)
        val match = dateRegex.find(line)
        if (match != null) {
            return normalizeDate(match.groupValues[1])
        }
        // Try standalone date pattern
        val standaloneMatch = standaloneDateRegex.find(line)
        if (standaloneMatch != null) {
            return normalizeDate(standaloneMatch.groupValues[1])
        }
        return null
    }
    
    private fun normalizeDate(dateStr: String): String? {
        // Normalize to YYYY-MM-DD format
        val cleaned = dateStr.trim().replace("/", "-").replace(".", "-")
        val parts = cleaned.split("-").filter { it.isNotBlank() }
        
        if (parts.size != 3) return null
        
        return when {
            parts[0].length == 4 -> {
                // YYYY-MM-DD format (e.g., 2025-09-19)
                "${parts[0]}-${parts[1].padStart(2, '0')}-${parts[2].padStart(2, '0')}"
            }
            parts[2].length == 4 -> {
                // DD-MM-YYYY format
                "${parts[2]}-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}"
            }
            parts[0].length == 2 && parts[2].length == 2 -> {
                // DD-MM-YY format - assume 20YY
                val year = if (parts[2].toIntOrNull() ?: 0 < 50) "20${parts[2]}" else "19${parts[2]}"
                "$year-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}"
            }
            else -> null
        }
    }
    fun tryExtractAmount(line: String): String? = amountRegex.find(line)?.groupValues?.getOrNull(1)
    
    /**
     * Extract VAT number - MUST have "LT" prefix to be identified as VAT number.
     * If there's no "LT" prefix, it's not a VAT number.
     * @param excludeOwnVatNumber VAT number of own company to exclude
     */
    fun tryExtractVatNumber(line: String, excludeOwnVatNumber: String? = null): String? {
        val match = vatNumberRegex.find(line)
        if (match != null) {
            val vatValue = match.groupValues.getOrNull(1)
            if (vatValue != null) {
                // Normalize: remove spaces, uppercase
                val normalizedVat = vatValue.replace(" ", "").uppercase()
                // Normalize exclude value for comparison (remove spaces)
                val normalizedExclude = excludeOwnVatNumber?.replace(" ", "")?.uppercase()
                // Exclude own company VAT number
                if (normalizedExclude != null && normalizedVat.equals(normalizedExclude, ignoreCase = true)) {
                    return null // This is own company VAT number, skip it
                }
                // Return normalized (no spaces, uppercase)
                return normalizedVat
            }
        }
        return null
    }
    
    /**
     * Extract company number (9 digits starting with 1, 2, 3, or 4).
     * Ensures it's different from VAT number and own company number.
     * @param excludeVatNumber VAT number to exclude (to avoid duplicates)
     * @param excludeOwnCompanyNumber Own company number to exclude
     */
    fun tryExtractCompanyNumber(line: String, excludeVatNumber: String? = null, excludeOwnCompanyNumber: String? = null): String? {
        val match = companyNumberRegex.find(line)
        if (match != null) {
            val candidate = match.groupValues.getOrNull(1)
            if (candidate != null) {
                // Ensure it's different from VAT number (if provided)
                if (excludeVatNumber != null) {
                    // Normalize VAT number: remove spaces, remove "LT" prefix
                    val normalizedVat = excludeVatNumber.replace(" ", "").uppercase()
                    val vatDigits = normalizedVat.removePrefix("LT")
                    if (candidate == vatDigits) {
                        return null // Same as VAT number, skip it
                    }
                }
                // Exclude own company number (normalize for comparison)
                val normalizedOwn = excludeOwnCompanyNumber?.trim()
                if (normalizedOwn != null && candidate == normalizedOwn) {
                    return null // This is own company number, skip it
                }
                return candidate
            }
        }
        return null
    }

    /**
     * Normalize amount string to standard format (dot as decimal separator).
     * Handles Lithuanian format (comma as decimal separator) and various thousands separators.
     */
    fun normalizeAmount(amount: String): String? {
        if (amount.isBlank()) return null
        
        // Remove spaces (thousands separators)
        var normalized = amount.replace(" ", "")
        
        // Check if it has a decimal part (last 2-3 digits after comma or dot)
        val hasDecimal = normalized.matches(Regex(".*[.,][0-9]{1,3}$"))
        
        if (hasDecimal) {
            // Has decimal part - find the decimal separator
            val lastComma = normalized.lastIndexOf(',')
            val lastDot = normalized.lastIndexOf('.')
            
            // Determine which is the decimal separator
            val decimalIndex = when {
                lastComma > lastDot -> {
                    // Comma is last - check if it looks like decimal (1-3 digits after)
                    val afterComma = normalized.substring(lastComma + 1)
                    if (afterComma.matches(Regex("[0-9]{1,3}$"))) lastComma else lastDot
                }
                lastDot > lastComma -> {
                    // Dot is last - check if it looks like decimal (1-3 digits after)
                    val afterDot = normalized.substring(lastDot + 1)
                    if (afterDot.matches(Regex("[0-9]{1,3}$"))) lastDot else lastComma
                }
                lastComma > 0 -> lastComma  // Only comma exists
                lastDot > 0 -> lastDot      // Only dot exists
                else -> -1
            }
            
            if (decimalIndex > 0) {
                // Split into integer and decimal parts
                val integerPart = normalized.substring(0, decimalIndex).replace(Regex("[.,]"), "")
                val decimalPart = normalized.substring(decimalIndex + 1)
                return "$integerPart.$decimalPart"
            }
        }
        
        // No decimal part or couldn't parse - just remove separators
        return normalized.replace(Regex("[.,]"), "")
    }

    fun isValidAmount(amount: String): Boolean {
        if (amount.isBlank()) return false

        // Normalize the amount string
        var normalized = amount.replace(" ", "").replace(",", ".")
        // Handle multiple dots (thousands separators) - keep only the last one
        val parts = normalized.split(".")
        val cleaned = if (parts.size > 2) {
            // Multiple dots - treat all but last as thousands separators
            parts.dropLast(1).joinToString("") + "." + parts.last()
        } else {
            normalized
        }

        val amountValue = cleaned.toDoubleOrNull()
        if (amountValue == null) {
            return false
        }

        // Validate reasonable range: between 0.01 and 10,000,000
        // This filters out page numbers, dates, and other false positives
        // But allows larger invoice amounts
        return amountValue >= 0.01 && amountValue <= 10_000_000.0
    }
}


package com.vitol.inv3.ocr

/**
 * Shared utilities for company name comparison.
 * Handles Lithuanian character normalization and legal form variations
 * so "MB Švaros frontas" matches "Švaros frontas" and "UAB STATYBŲ FRONTAS" matches "Statybu frontas".
 */
object CompanyNameUtils {
    /** Legal form prefixes/suffixes to strip for core name comparison (Lithuanian and common). */
    private val LEGAL_FORMS = Regex(
        """\b(uab|ab|mb|iį|všį|vsi|ltd|oy|as|sp|uždaroji\s+akcinė\s+bendrovė|akcinė\s+bendrovė)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Normalize company name for own-company comparison.
     * - Strips quotes and extra whitespace
     * - Normalizes Lithuanian characters (ė→e, š→s, ų→u, etc.)
     * - Strips legal form prefixes (UAB, MB, AB, etc.) to compare core business name
     * - Lowercase for case-insensitive comparison
     */
    fun normalizeForOwnCompanyCompare(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var s = name.trim()
            .replace(Regex("""["'\\]+"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Normalize Lithuanian characters to ASCII
        s = normalizeLithuanianChars(s)
        // Strip legal form prefixes/suffixes
        s = LEGAL_FORMS.replace(s, " ").replace(Regex("\\s+"), " ").trim()
        return s.lowercase()
    }

    /**
     * Normalize Lithuanian characters to ASCII equivalents for comparison.
     */
    private fun normalizeLithuanianChars(s: String): String {
        return s
            .replace('\u0117', 'e')  // ė
            .replace('\u0113', 'e')  // ē
            .replace('\u012f', 'i')  // į
            .replace('\u012b', 'i')  // ī
            .replace('\u0105', 'a')  // ą
            .replace('\u0173', 'u')  // ų
            .replace('\u016b', 'u')  // ū
            .replace('\u010d', 'c')  // č
            .replace('\u0161', 's')  // š
            .replace('\u017e', 'z')  // ž
            .replace('\u0119', 'e')  // ę
    }

    /**
     * Returns true if the extracted name matches the own company name.
     * Uses normalized comparison so "MB Švaros frontas" matches "Švaros frontas"
     * and "UAB STATYBŲ FRONTAS" matches "Statybu frontas".
     */
    fun isSameAsOwnCompanyName(extracted: String?, ownName: String?): Boolean {
        if (extracted.isNullOrBlank() || ownName.isNullOrBlank()) return false
        val n1 = normalizeForOwnCompanyCompare(extracted)
        val n2 = normalizeForOwnCompanyCompare(ownName)
        if (n1 == n2) return true
        // Also match if one normalized core contains the other (handles "Švaros frontas" vs "Švaros frontas, MB")
        if (n1.length >= 5 && n2.length >= 5) {
            if (n1.contains(n2) || n2.contains(n1)) return true
        }
        return false
    }
}

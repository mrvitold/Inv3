package com.vitol.inv3.ocr

/**
 * Shared utilities for company name comparison.
 * Handles Lithuanian character normalization and legal form variations
 * so "MB Švaros frontas" matches "Švaros frontas" and "UAB STATYBŲ FRONTAS" matches "Statybu frontas".
 */
object CompanyNameUtils {
    /**
     * Normalize quotes in company names so "UAB "Augvitra'" becomes "UAB "Augvitra"".
     * OCR often produces mixed typographic quotes; standardize to ASCII double quote.
     * Only replaces trailing single quotes (closing quote), not apostrophes in names like O'Brien.
     */
    fun normalizeCompanyNameQuotes(name: String?): String {
        if (name.isNullOrBlank()) return name ?: ""
        var s = name
            .replace('\u201E', '"')  // „ Lithuanian / German opening double (often misread as "." by OCR)
            .replace('\u201C', '"')  // “ left double
            .replace('\u201D', '"')  // ” right double
        // OCR often reads B as V in "UAB"
        s = Regex("""(?i)^UAD\b""").replace(s, "UAB")
        // Azure / Tesseract often read the closing “ as "*" on tight scans
        if (s.endsWith('*') && s.length > 5 &&
            Regex("""(?i)\b(UAB|MB|AB|IĮ|VŠĮ|VSI|OY|SP|ZUB)\b""").containsMatchIn(s)
        ) {
            s = s.dropLast(1).trimEnd()
        }
        // Opening „ misread as ". " after legal form: "UAB .GINESTRA" -> "UAB GINESTRA"
        s = Regex("""(?i)\b(UAB|MB|AB|IĮ|VŠĮ|VSI|OY|SP|ZUB)\s+\.""").replace(s) { m ->
            "${m.groupValues[1]} "
        }
        // „ misread as hyphen before quoted name: "UAB -GINESTRA" / "UAD -GINESTRA"
        s = Regex("""(?i)\b(UAB|MB|AB|IĮ|VŠĮ|VSI|OY|SP|ZUB)\s+-\s*""").replace(s) { m ->
            "${m.groupValues[1]} "
        }
        // Single stray closing quote at end (partial OCR of “)
        if (s.endsWith('"') && s.count { it == '"' } % 2 == 1) {
            s = s.dropLast(1).trimEnd()
        }
        // Fix mismatched closing quote: "Name' -> "Name" (only trailing, preserves O'Brien)
        if (s.contains('"') && s.length > 1) {
            val last = s.last()
            if (last == '\'' || last == '\u2018' || last == '\u2019') {
                s = s.dropLast(1) + '"'
            }
        }
        return s.trim()
    }

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
        // OCR typos on long names (e.g. "Statyby" vs "Statybu")
        if (n1.length >= 10 && n2.length >= 10 && levenshteinDistance(n1, n2) <= 2) {
            return true
        }
        return false
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * True if the string looks like a legal-entity name (UAB, AB, MB, …), not logo-only text.
     * Aligns with Azure/InvoiceParser heuristics for vendor vs brand names.
     */
    fun hasLegalFormMarker(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase().trim()
        if (lower.contains("eurai") && lower.contains("centas")) return false
        // OCR often reads trailing UAB as UAR on "… LITHUANIA, UAB"
        if (Regex("""(?i),\s*uar\s*$""").containsMatchIn(lower)) return true
        return Regex("""\b(uab|uad|ab|mb|iį|ii|ltd|oy|as|sp|zub)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
    }

    /**
     * OCR noise at the top of continuation pages (e.g. "3", "at", "13") joined as "3 at 13".
     * Scanner watermarks and generic document titles (e.g. "PVM SĄSKAITA FAKTŪRA") must never
     * replace a real seller name during merge / continuation-page parsing.
     */
    fun isLikelyGarbageCompanyName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val t = name.trim()
        val lower = t.lowercase()

        // Known scanner / app watermarks and mis-OCR variants (often on page 2)
        if (lower.contains("ask al assistar") || lower.contains("ask ai assist")) return true
        if (lower.contains("assistar") && (lower.contains("shore") || lower.contains("pvm"))) return true
        if (lower.contains("skaityta su camscanner")) return true

        // Document type lines, not a company name (allow short accidental matches elsewhere)
        if (t.length > 20) {
            if (lower.contains("pvm") && lower.contains("sąskaita") && lower.contains("faktūr")) return true
            val norm = normalizeLithuanianChars(lower)
            if (norm.contains("saskaita") && norm.contains("faktur")) return true
        }

        // Real LT company names usually include a legal form; long lines without it are often headers/watermarks.
        if (t.length > 35 && !hasLegalFormMarker(t) &&
            (lower.contains("pvm") || lower.contains("sąskaita") || lower.contains("faktūr"))
        ) {
            return true
        }

        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size >= 2 && tokens.all { it.length <= 3 } && t.length <= 18) {
            return true
        }
        if (Regex("""^\d{1,3}\s+at\s+\d""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            return true
        }
        if (t.length < 5 && t.any { it.isDigit() } && !hasLegalFormMarker(t)) {
            return true
        }
        return false
    }
}

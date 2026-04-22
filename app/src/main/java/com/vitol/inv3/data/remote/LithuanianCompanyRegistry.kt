package com.vitol.inv3.data.remote

import java.util.Locale

/**
 * Helpers for resolving Lithuanian juridinio asmens kodas (9 digits) from OCR fields.
 */
object LithuanianCompanyRegistry {
    private val LEGAL_FORM_REPLACEMENTS = listOf(
        Regex("""\bmažoji\s+bendrija\b""", RegexOption.IGNORE_CASE) to "MB",
        Regex("""\bmazoji\s+bendrija\b""", RegexOption.IGNORE_CASE) to "MB",
        Regex("""\buždaroji\s+akcinė\s+bendrovė\b""", RegexOption.IGNORE_CASE) to "UAB",
        Regex("""\buzdaroji\s+akcine\s+bendrove\b""", RegexOption.IGNORE_CASE) to "UAB",
        Regex("""\bindividuali\s+įmonė\b""", RegexOption.IGNORE_CASE) to "IĮ",
        Regex("""\bindividuali\s+imone\b""", RegexOption.IGNORE_CASE) to "IĮ",
        Regex("""\bviešoji\s+įstaiga\b""", RegexOption.IGNORE_CASE) to "VšĮ",
        Regex("""\bviesoji\s+istaiga\b""", RegexOption.IGNORE_CASE) to "VšĮ",
        Regex("""\bakcinė\s+bendrovė\b""", RegexOption.IGNORE_CASE) to "AB",
        Regex("""\bakcine\s+bendrove\b""", RegexOption.IGNORE_CASE) to "AB",
    )

    /**
     * JAR often stores only the legal form for individual enterprises, not the distinctive name.
     * In those cases we keep the OCR-derived name instead of replacing it with this generic label.
     */
    fun isUninformativeRegisterName(name: String): Boolean {
        val n = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        return n == "individuali įmonė" || n == "individuali imone"
    }

    /** Keep only digits; return value only if exactly 9 digits (standard JA kodas). */
    fun normalizeJaKodas(input: String?): String? {
        val digits = input?.filter { it.isDigit() }?.trim() ?: return null
        return digits.takeIf { it.length == 9 }
    }

    /**
     * Lithuanian VAT for legal entities is usually LT + 9-digit company code.
     */
    fun jaKodasFromLithuanianVat(vat: String?): String? {
        val compact = vat?.replace(" ", "")?.uppercase() ?: return null
        if (!compact.startsWith("LT")) return null
        val digits = compact.drop(2).filter { it.isDigit() }
        return digits.takeIf { it.length == 9 }
    }

    /**
     * Prefer company number when it is a valid 9-digit code; otherwise derive from LT VAT.
     */
    fun resolveJaKodas(companyNumber: String?, vatNumber: String?): String? {
        normalizeJaKodas(companyNumber)?.let { return it }
        return jaKodasFromLithuanianVat(vatNumber)
    }

    /**
     * Convert full Lithuanian legal-form names to required short variants.
     * Output abbreviations are normalized exactly as requested: MB, UAB, IĮ, VšĮ, AB.
     */
    fun shortenLithuanianLegalForm(companyName: String?): String? {
        val input = companyName ?: return null
        if (input.isBlank()) return input
        var normalized = input
        for ((pattern, shortForm) in LEGAL_FORM_REPLACEMENTS) {
            normalized = pattern.replace(normalized, shortForm)
        }
        return normalized.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Result of looking up the official register row for a resolved JA kodas.
 * @param officialName Null when the register name is generic (e.g. individuali įmonė only) — keep OCR name.
 * @param fillCompanyNumberIfBlank When OCR missed the code but VAT had it, fill the form with this code.
 */
data class LtCompanyLookupResult(
    val officialName: String?,
    val fillCompanyNumberIfBlank: String?
)

package com.vitol.inv3.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.concurrent.TimeUnit

data class LtOpenDataCompany(
    val jaKodas: String?,
    val name: String?,
    val vatNumber: String?
)

data class LtOpenDataCompanySuggestion(
    val name: String,
    val jaKodas: String?,
    val vatNumber: String?
)

/**
 * Lightweight client for Lithuania open data API:
 * https://get.data.gov.lt/datasets/gov/vmi/mm_registras/MokesciuMoketojas
 */
object LithuanianOpenDataApi {
    private const val BASE_URL = "https://get.data.gov.lt/datasets/gov/vmi/mm_registras/MokesciuMoketojas"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun lookupCompany(
        jaKodas: String?,
        vatNumber: String?
    ): LtOpenDataCompany? = withContext(Dispatchers.IO) {
        val normalizedJaKodas = LithuanianCompanyRegistry.normalizeJaKodas(jaKodas)
        if (normalizedJaKodas != null) {
            queryByField("ja_kodas", normalizedJaKodas)?.let { return@withContext it }
        }

        val normalizedVatDigits = vatNumber
            ?.replace(" ", "")
            ?.uppercase()
            ?.removePrefix("LT")
            ?.filter { it.isDigit() }
            ?.takeIf { it.isNotBlank() }

        if (normalizedVatDigits != null) {
            queryByField("pvm_kodas", normalizedVatDigits)?.let { return@withContext it }
        }

        null
    }

    suspend fun searchCompaniesByName(
        query: String,
        limit: Int = 20
    ): List<LtOpenDataCompanySuggestion> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 2) return@withContext emptyList()

        val normalizedInput = normalizeLtForSearch(trimmedQuery)
        val tokens = normalizedInput.split(' ').filter { it.length >= 2 }

        // Query composition:
        // 1) full phrase variants, 2) two-token phrase variants, 3) token variants.
        // This lowers noise from generic words like "frontas".
        val phraseQueries = buildQueryVariants(trimmedQuery).take(8)
        val twoTokenPhrases = if (tokens.size >= 2) {
            tokens.zipWithNext().map { (a, b) -> "$a $b" }
        } else {
            emptyList()
        }
        val twoTokenQueries = twoTokenPhrases
            .flatMap { buildQueryVariants(it).take(4) }
            .distinct()
            .take(8)
        val tokenQueries = tokens
            .flatMap { buildQueryVariants(it).take(6) }
            .distinct()
            .take(12)
        val allQueries = (phraseQueries + twoTokenQueries + tokenQueries).distinct().take(20)

        val merged = LinkedHashMap<String, LtOpenDataCompanySuggestion>()
        allQueries.forEach { candidate ->
            // Fetch multiple pages so relevant entries are not lost in API's first page.
            queryByNameContains(candidate, pageSize = 40, maxPages = 3).forEach { suggestion ->
                val key = "${suggestion.name.uppercase()}|${suggestion.jaKodas.orEmpty()}|${suggestion.vatNumber.orEmpty()}"
                if (!merged.containsKey(key)) merged[key] = suggestion
            }
        }

        val strictFiltered = merged.values.filter {
            normalizeLtForSearch(it.name).contains(normalizedInput)
        }
        val allTokenNormalized = tokens.filter { it.isNotBlank() }
        val tokenFiltered = merged.values.filter { suggestion ->
            val normalizedName = normalizeLtForSearch(suggestion.name)
            allTokenNormalized.all { token -> normalizedName.contains(token) }
        }
        val candidates = when {
            strictFiltered.isNotEmpty() -> strictFiltered
            tokenFiltered.isNotEmpty() -> tokenFiltered
            else -> merged.values.toList()
        }

        candidates
            .sortedByDescending { scoreSuggestion(it, normalizedInput, allTokenNormalized) }
            .take(limit)
    }

    private fun queryByNameContains(
        query: String,
        pageSize: Int,
        maxPages: Int
    ): List<LtOpenDataCompanySuggestion> {
        val merged = LinkedHashMap<String, LtOpenDataCompanySuggestion>()
        for (page in 0 until maxPages) {
            val offset = page * pageSize
            val batch = queryByNameContainsPage(query = query, limit = pageSize, offset = offset)
            if (batch.isEmpty()) break
            batch.forEach { suggestion ->
                val key = "${suggestion.name.uppercase()}|${suggestion.jaKodas.orEmpty()}|${suggestion.vatNumber.orEmpty()}"
                if (!merged.containsKey(key)) merged[key] = suggestion
            }
            if (batch.size < pageSize) break
        }
        return merged.values.toList()
    }

    private fun queryByNameContainsPage(
        query: String,
        limit: Int,
        offset: Int
    ): List<LtOpenDataCompanySuggestion> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val url = BASE_URL.toHttpUrlOrNull()
            ?.newBuilder()
            // API expects function-style filters in raw query (without '=' key/value form).
            ?.encodedQuery("contains(pavadinimas,%22$encoded%22)&limit($limit)&offset($offset)")
            ?.build()
            ?: return emptyList()
        val request = Request.Builder().url(url).get().build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Open data API name search failed: ${response.code} for query=$query")
                    return@use emptyList()
                }
                val body = response.body?.string().orEmpty()
                parseCompanySuggestions(body)
            }
        }.onFailure {
            Timber.w(it, "Open data API name search exception for query=$query")
        }.getOrElse { emptyList() }
    }

    private fun queryByField(field: String, value: String): LtOpenDataCompany? {
        val queryValue = when (field) {
            // In this API, pvm_kodas is a string-typed filter and must be quoted.
            "pvm_kodas" -> "\"$value\""
            else -> value
        }
        val url = BASE_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(field, queryValue)
            ?.build()
            ?: return null

        val request = Request.Builder().url(url).get().build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Open data API request failed: ${response.code} for $field=$queryValue")
                    return null
                }
                val body = response.body?.string().orEmpty()
                parseFirstCompany(body)
            }
        }.onFailure {
            Timber.w(it, "Open data API lookup failed for $field=$queryValue")
        }.getOrNull()
    }

    private fun parseFirstCompany(body: String): LtOpenDataCompany? {
        if (body.isBlank()) return null
        val root = JSONObject(body)
        val data = root.optJSONArray("_data") ?: return null
        if (data.length() == 0) return null

        val item = data.optJSONObject(0) ?: return null
        val jaKodas = item.opt("ja_kodas")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val name = item.optString("pavadinimas", "").trim().takeIf { it.isNotEmpty() }
        val vatPrefix = sanitizeNullish(item.opt("pvm_kodas_pref")?.toString())
        val vatCode = sanitizeNullish(item.opt("pvm_kodas")?.toString())
        val vat = when {
            !vatPrefix.isNullOrBlank() && !vatCode.isNullOrBlank() -> "$vatPrefix$vatCode"
            !vatCode.isNullOrBlank() -> vatCode
            else -> null
        }

        return LtOpenDataCompany(
            jaKodas = jaKodas,
            name = name,
            vatNumber = vat
        )
    }

    private fun parseCompanySuggestions(body: String): List<LtOpenDataCompanySuggestion> {
        if (body.isBlank()) return emptyList()
        val root = JSONObject(body)
        val data = root.optJSONArray("_data") ?: return emptyList()
        if (data.length() == 0) return emptyList()

        val results = LinkedHashMap<String, LtOpenDataCompanySuggestion>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val rawName = item.optString("pavadinimas", "").trim().takeIf { it.isNotEmpty() } ?: continue
            val normalizedName = LithuanianCompanyRegistry.shortenLithuanianLegalForm(rawName) ?: rawName
            val jaKodas = item.opt("ja_kodas")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val vatPrefix = sanitizeNullish(item.opt("pvm_kodas_pref")?.toString())
            val vatCode = sanitizeNullish(item.opt("pvm_kodas")?.toString())
            val vatNumber = when {
                !vatPrefix.isNullOrBlank() && !vatCode.isNullOrBlank() -> "$vatPrefix$vatCode"
                !vatCode.isNullOrBlank() -> vatCode
                // i.SAF requires "ND" when VAT ID is unknown / not available.
                else -> "ND"
            }

            val dedupeKey = buildString {
                append(normalizedName.uppercase())
                append('|')
                append(jaKodas.orEmpty())
                append('|')
                append(vatNumber.orEmpty())
            }
            if (!results.containsKey(dedupeKey)) {
                results[dedupeKey] = LtOpenDataCompanySuggestion(
                    name = normalizedName,
                    jaKodas = jaKodas,
                    vatNumber = vatNumber
                )
            }
        }
        return results.values.toList()
    }

    private fun sanitizeNullish(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        return trimmed
    }

    private fun buildQueryVariants(input: String): List<String> {
        val lower = input.lowercase().trim()
        if (lower.isBlank()) return emptyList()

        val variants = LinkedHashSet<String>()
        variants += lower

        fun expand(chars: CharArray, index: Int) {
            if (variants.size >= 18) return
            for (i in index until chars.size) {
                val options = ASCII_TO_LT[chars[i]] ?: continue
                for (option in options) {
                    if (option == chars[i]) continue
                    val copy = chars.copyOf()
                    copy[i] = option
                    val candidate = String(copy)
                    if (variants.add(candidate)) {
                        expand(copy, i + 1)
                        if (variants.size >= 18) return
                    }
                }
            }
        }

        expand(lower.toCharArray(), 0)
        return variants.toList()
    }

    private fun normalizeLtForSearch(input: String): String {
        val lower = input.lowercase().replace(Regex("[\"“”„'`]+"), " ")
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val withoutCombiningMarks = decomposed.replace(Regex("\\p{Mn}+"), "")
        return withoutCombiningMarks
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun scoreSuggestion(
        suggestion: LtOpenDataCompanySuggestion,
        normalizedInput: String,
        normalizedTokens: List<String>
    ): Int {
        val name = normalizeLtForSearch(suggestion.name)
        var score = 0

        if (name == normalizedInput) score += 1000
        if (name.removePrefix("mb ").removePrefix("uab ").removePrefix("ab ") == normalizedInput) score += 850
        if (name.startsWith(normalizedInput)) score += 600
        if (name.contains(normalizedInput)) score += 350

        // Strongly prefer candidates containing all typed words.
        val matchingTokens = normalizedTokens.count { token -> name.contains(token) }
        score += matchingTokens * 120
        if (normalizedTokens.isNotEmpty() && matchingTokens == normalizedTokens.size) {
            score += 300
        }

        // Slight preference for shorter/legal-name-nearer strings when score ties.
        score -= name.length / 8
        return score
    }

    private val ASCII_TO_LT: Map<Char, List<Char>> = mapOf(
        'a' to listOf('a', 'ą'),
        'c' to listOf('c', 'č'),
        'e' to listOf('e', 'ę', 'ė'),
        'i' to listOf('i', 'į'),
        's' to listOf('s', 'š'),
        'u' to listOf('u', 'ų', 'ū'),
        'z' to listOf('z', 'ž')
    )
}

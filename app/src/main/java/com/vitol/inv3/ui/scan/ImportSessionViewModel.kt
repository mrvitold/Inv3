package com.vitol.inv3.ui.scan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vitol.inv3.ocr.AzureDocumentIntelligenceService
import com.vitol.inv3.ocr.CompanyNameUtils
import com.vitol.inv3.ocr.InvoiceParser
import com.vitol.inv3.ocr.ParsedInvoice
import com.vitol.inv3.utils.ImportImageCache
import com.vitol.inv3.utils.PdfPageResolver
import timber.log.Timber
import javax.inject.Inject

/** Result of building import pages from URIs. Survives composition so IO can run in viewModelScope. */
sealed class BuildPagesResult {
    data object Idle : BuildPagesResult()
    data object Loading : BuildPagesResult()
    data class Success(val pages: List<ImportPage>, val invoiceType: String) : BuildPagesResult()
    data class Error(val message: String) : BuildPagesResult()
}

/** Import extraction state: run extraction for all pages then show review. */
sealed class ImportExtractionState {
    data object Idle : ImportExtractionState()
    data class Extracting(val current: Int, val total: Int) : ImportExtractionState()
    data object Done : ImportExtractionState()
    data class Error(val message: String) : ImportExtractionState()
}

/**
 * Holds state for the "import files" flow so it survives navigation from
 * Import preparing screen to ReviewScan and across multiple ReviewScan instances.
 * Retrieve with activity as ViewModelStoreOwner to get the same instance across the flow.
 */
@HiltViewModel
class ImportSessionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _buildPagesResult = MutableStateFlow<BuildPagesResult>(BuildPagesResult.Idle)
    val buildPagesResult: StateFlow<BuildPagesResult> = _buildPagesResult.asStateFlow()

    /** Set before launching the file picker so we know the type when the picker returns. */
    private var pendingImportType: String? = null

    fun setPendingImportType(invoiceType: String) {
        pendingImportType = invoiceType
    }

    fun buildPagesFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val invoiceType = pendingImportType ?: "P"
        pendingImportType = null
        _buildPagesResult.value = BuildPagesResult.Loading
        viewModelScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    buildImportPages(appContext, uris)
                }
                _buildPagesResult.value = if (pages.isEmpty()) {
                    BuildPagesResult.Error(
                        if (uris.size == 1) "Could not read the selected file. Use an image or PDF."
                        else "Could not read the selected files. Use images or PDFs."
                    )
                } else {
                    BuildPagesResult.Success(pages, invoiceType)
                }
            } catch (e: Exception) {
                Timber.e(e, "ImportSessionViewModel: failed to build pages")
                _buildPagesResult.value = BuildPagesResult.Error(
                    "Error reading files: ${e.message ?: "Please try again."}"
                )
            }
        }
    }

    fun clearBuildPagesResult() {
        _buildPagesResult.value = BuildPagesResult.Idle
    }

    private val _pendingPages = MutableStateFlow<List<ImportPage>>(emptyList())
    val pendingPages: StateFlow<List<ImportPage>> = _pendingPages.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _invoiceType = MutableStateFlow("P")
    val invoiceType: StateFlow<String> = _invoiceType.asStateFlow()

    /** Pre-extracted invoices (one per page). Filled by runExtraction(). */
    private val _parsedInvoices = MutableStateFlow<List<ParsedInvoice>>(emptyList())
    val parsedInvoices: StateFlow<List<ParsedInvoice>> = _parsedInvoices.asStateFlow()

    /** Extraction progress. Done → navigate to review. */
    private val _extractionState = MutableStateFlow<ImportExtractionState>(ImportExtractionState.Idle)
    val extractionState: StateFlow<ImportExtractionState> = _extractionState.asStateFlow()

    private var prefetchedUri: Uri? = null
    private var prefetchedForIndex: Int = -1

    val totalCount: Int get() = _pendingPages.value.size
    val hasNext: Boolean get() = _currentIndex.value + 1 < totalCount
    val hasPrevious: Boolean get() = _currentIndex.value > 0
    val isActive: Boolean get() = _pendingPages.value.isNotEmpty()

    fun startSession(pages: List<ImportPage>, type: String) {
        _pendingPages.value = pages
        _currentIndex.value = 0
        _invoiceType.value = type
        _parsedInvoices.value = emptyList()
        _extractionState.value = ImportExtractionState.Idle
        prefetchedUri = null
        prefetchedForIndex = -1
        Timber.d("Import session started: ${pages.size} pages, type=$type")
    }

    /** Set before runExtraction so we exclude own company from parsed fields. */
    fun setOwnCompanyForExtraction(companyNumber: String?, vatNumber: String?, companyName: String?) {
        _excludeOwnCompanyNumber = companyNumber
        _excludeOwnVatNumber = vatNumber
        _excludeOwnCompanyName = companyName
    }

    private var _excludeOwnCompanyNumber: String? = null
    private var _excludeOwnVatNumber: String? = null
    private var _excludeOwnCompanyName: String? = null

    /**
     * Run OCR extraction for all pages. Call after startSession (and optionally setOwnCompanyForExtraction).
     * Fills parsedInvoices and sets extractionState to Done or Error.
     */
    fun runExtraction(context: Context) {
        val pages = _pendingPages.value
        val invoiceType = _invoiceType.value
        if (pages.isEmpty()) {
            _extractionState.value = ImportExtractionState.Error("No pages to extract")
            return
        }
        _extractionState.value = ImportExtractionState.Extracting(0, pages.size)
        _parsedInvoices.value = emptyList()
        viewModelScope.launch {
            try {
                val azure = AzureDocumentIntelligenceService(context)
                val results = mutableListOf<ParsedInvoice>()
                for (i in pages.indices) {
                    _extractionState.value = ImportExtractionState.Extracting(i + 1, pages.size)
                    val uri = getUriForPage(context, i)
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    val mime = context.contentResolver.getType(uri)?.takeIf { !it.isNullOrBlank() } ?: "image/jpeg"
                    val parsed = if (bytes == null || bytes.isEmpty()) {
                        ParsedInvoice(extractionMessage = "Could not read file. Enter details manually.")
                    } else {
                        azure.extractFromBytes(
                            bytes,
                            mime,
                            _excludeOwnCompanyNumber,
                            _excludeOwnVatNumber,
                            _excludeOwnCompanyName,
                            invoiceType
                        )
                    }
                    results.add(parsed)
                    _parsedInvoices.value = results.toList()
                    // Brief delay between pages to reduce Azure rate limiting
                    if (i < pages.size - 1) delay(1500)
                }
                _extractionState.value = ImportExtractionState.Done
                Timber.d("Import extraction done: ${results.size} invoices")
            } catch (e: Exception) {
                Timber.e(e, "Import extraction failed")
                _extractionState.value = ImportExtractionState.Error(e.message ?: "Extraction failed")
            }
        }
    }

    /**
     * Returns merged parsed invoice for import review.
     * When multiple pages exist (e.g. 2-page PDF), merges results: page 1 provides header/company
     * fields, later pages fill amounts when page 1 has null. Prevents page 2 from overwriting
     * page 1's company/VAT/date with wrong or empty data.
     */
    fun getCurrentParsedInvoice(): ParsedInvoice? {
        val list = _parsedInvoices.value
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]
        val merged = mergeParsedInvoicesForMultiPage(list)
        Timber.d("Import merge: ${list.size} pages -> Company: ${merged.companyName}, VAT: ${merged.vatNumber}, CompanyNo: ${merged.companyNumber}, Amount: ${merged.amountWithoutVatEur}, VatAmount: ${merged.vatAmountEur}")
        return merged
    }

    /** Normalize amount string: comma to dot for consistent display and parsing. */
    private fun normalizeAmount(s: String?): String? =
        s?.trim()?.takeIf { it.isNotBlank() }?.replace(',', '.')

    /** Merge multiple page extractions: header from first pages, amounts from last (totals usually on last page). */
    private fun mergeParsedInvoicesForMultiPage(pages: List<ParsedInvoice>): ParsedInvoice {
        val excludeName = _excludeOwnCompanyName
        val excludeVat = _excludeOwnVatNumber
        val excludeNumber = _excludeOwnCompanyNumber
        fun firstValidCompanyName(): String? =
            pages.mapNotNull { it.companyName?.takeIf { n -> n.isNotBlank() } }
                .firstOrNull { n ->
                    val lower = n.lowercase()
                    val notAmountInWords = !lower.contains("suma žodžiais") && !lower.contains("suma zodžiais") &&
                        !lower.contains("suma zodziais") && !(lower.contains("eurai") && lower.contains("centas") && lower.startsWith("suma "))
                    val notOwnCompany = excludeName == null || !CompanyNameUtils.isSameAsOwnCompanyName(n, excludeName)
                    notAmountInWords && notOwnCompany
                }
        fun firstValidVatNumber(): String? =
            pages.mapNotNull { it.vatNumber?.takeIf { s -> s.isNotBlank() } }
                .firstOrNull { v -> excludeVat == null || !v.equals(excludeVat, ignoreCase = true) }
        fun firstValidCompanyNumber(): String? =
            pages.mapNotNull { it.companyNumber?.takeIf { s -> s.isNotBlank() } }
                .firstOrNull { c -> excludeNumber == null || c.trim() != excludeNumber.trim() }
        // Amounts/totals are usually on the last page; prefer last non-null
        fun lastNotNullValue(selector: (ParsedInvoice) -> String?): String? =
            pages.mapNotNull { normalizeAmount(selector(it)) }.lastOrNull()

        var vatNum = firstValidVatNumber()
        var companyNum = firstValidCompanyNumber()
        var amountNoVat = lastNotNullValue { it.amountWithoutVatEur } ?: pages.mapNotNull { normalizeAmount(it.amountWithoutVatEur) }.firstOrNull()
        var vatAmount = lastNotNullValue { it.vatAmountEur } ?: pages.mapNotNull { normalizeAmount(it.vatAmountEur) }.firstOrNull()
        var vatRate = lastNotNullValue { it.vatRate } ?: pages.mapNotNull { it.vatRate?.takeIf { s -> s.isNotBlank() } }.firstOrNull()

        val combinedLines = pages.flatMap { it.lines }
        // Fallback: parse combined lines when any key field is missing
        if (combinedLines.isNotEmpty() && (vatNum == null || companyNum == null || amountNoVat == null || vatAmount == null || vatRate == null)) {
            val fromCombined = InvoiceParser.parse(
                combinedLines,
                excludeNumber,
                excludeVat,
                excludeName,
                _invoiceType.value
            )
            if (vatNum == null && fromCombined.vatNumber != null && (excludeVat == null || !fromCombined.vatNumber.equals(excludeVat, ignoreCase = true))) {
                vatNum = fromCombined.vatNumber
                Timber.d("Import merge: filled VAT from combined lines: $vatNum")
            }
            if (companyNum == null && fromCombined.companyNumber != null && (excludeNumber == null || fromCombined.companyNumber.trim() != excludeNumber.trim())) {
                companyNum = fromCombined.companyNumber
                Timber.d("Import merge: filled company number from combined lines: $companyNum")
            }
            if (amountNoVat == null && fromCombined.amountWithoutVatEur != null) {
                amountNoVat = normalizeAmount(fromCombined.amountWithoutVatEur)
                Timber.d("Import merge: filled amount from combined lines: $amountNoVat")
            }
            if (vatAmount == null && fromCombined.vatAmountEur != null) {
                vatAmount = normalizeAmount(fromCombined.vatAmountEur)
                Timber.d("Import merge: filled VAT amount from combined lines: $vatAmount")
            }
            if (vatRate == null && fromCombined.vatRate != null) {
                vatRate = fromCombined.vatRate.takeIf { s -> s.isNotBlank() }
                Timber.d("Import merge: filled VAT rate from combined lines: $vatRate")
            }
        }

        // Log per-page values for debugging
        pages.forEachIndexed { i, p ->
            Timber.d("Import merge page $i: amountNoVat=${p.amountWithoutVatEur}, vatAmount=${p.vatAmountEur}, vatRate=${p.vatRate}")
        }

        return ParsedInvoice(
            invoiceId = pages.firstNotNullOfOrNull { it.invoiceId?.takeIf { s -> s.isNotBlank() } },
            date = pages.firstNotNullOfOrNull { it.date?.takeIf { s -> s.isNotBlank() } },
            companyName = firstValidCompanyName(),
            amountWithoutVatEur = amountNoVat,
            vatAmountEur = vatAmount,
            vatNumber = vatNum,
            companyNumber = companyNum,
            vatRate = vatRate,
            lines = combinedLines,
            extractionMessage = pages.firstNotNullOfOrNull { it.extractionMessage }
        )
    }

    fun advanceToPrevious() {
        if (_currentIndex.value > 0) _currentIndex.value = _currentIndex.value - 1
    }

    /**
     * Returns a content URI for the current page (for Azure and display).
     * For ImagePage returns the Uri as-is; for PdfPage renders that page to a temp file.
     */
    suspend fun getUriForCurrentPage(context: Context): Uri = withContext(Dispatchers.IO) {
        getUriForPage(context, _currentIndex.value)
    }

    /**
     * Returns a content URI for the page at the given index.
     * Used for prefetching (e.g. index = currentIndex + 1).
     */
    suspend fun getUriForPage(context: Context, index: Int): Uri = withContext(Dispatchers.IO) {
        val pages = _pendingPages.value
        if (index !in pages.indices) throw IndexOutOfBoundsException("Import page index $index, size ${pages.size}")
        when (val page = pages[index]) {
            is ImportPage.ImagePage -> page.uri
            is ImportPage.PdfPage -> PdfPageResolver.renderPageToUri(context, page.pdfUri, page.pageIndex)
        }
    }

    /**
     * Use prefetched URI for the given index if available and clear prefetch.
     * Returns the prefetched Uri or null if none for this index.
     */
    fun takePrefetchedUriForIndex(index: Int): Uri? {
        return if (prefetchedForIndex == index && prefetchedUri != null) {
            val u = prefetchedUri
            prefetchedUri = null
            prefetchedForIndex = -1
            u
        } else null
    }

    /**
     * Prefetch the URI for the next page in the background.
     * Safe to call even if there is no next page.
     */
    fun prefetchNextPage(context: Context, scope: CoroutineScope) {
        val nextIndex = _currentIndex.value + 1
        if (nextIndex >= totalCount) return
        scope.launch(Dispatchers.IO) {
            try {
                val uri = getUriForPage(context, nextIndex)
                prefetchedUri = uri
                prefetchedForIndex = nextIndex
                Timber.d("Prefetched URI for import page $nextIndex")
            } catch (e: Exception) {
                Timber.w(e, "Prefetch failed for page $nextIndex")
            }
        }
    }

    /**
     * Advance to the next page index (for Skip - no need to fetch URI).
     * Use advanceToNext when you need the URI (e.g. after Save).
     */
    fun advanceToNextIndex() {
        if (!hasNext) return
        _currentIndex.value = _currentIndex.value + 1
    }

    /**
     * Advance to the next page (call after user saved current invoice).
     * Returns the Uri for the next page (uses prefetched if available), or null if no next.
     */
    suspend fun advanceToNext(context: Context): Uri? {
        if (!hasNext) return null
        val nextIndex = _currentIndex.value + 1
        _currentIndex.value = nextIndex
        return takePrefetchedUriForIndex(nextIndex) ?: getUriForPage(context, nextIndex)
    }

    fun clear() {
        _pendingPages.value = emptyList()
        _currentIndex.value = 0
        _parsedInvoices.value = emptyList()
        _extractionState.value = ImportExtractionState.Idle
        prefetchedUri = null
        prefetchedForIndex = -1
        PdfPageResolver.clearCache(appContext)
        ImportImageCache.clearCache(appContext)
        Timber.d("Import session cleared")
    }

    override fun onCleared() {
        super.onCleared()
        PdfPageResolver.clearCache(appContext)
        ImportImageCache.clearCache(appContext)
    }
}

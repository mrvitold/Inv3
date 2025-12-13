package com.vitol.inv3.ui.review

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import androidx.navigation.NavController
import com.vitol.inv3.Routes
import com.vitol.inv3.data.local.getActiveOwnCompanyIdFlow
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.data.local.FieldRegion
import com.vitol.inv3.data.local.TemplateStore
import com.vitol.inv3.ocr.CompanyRecognition
import com.vitol.inv3.ocr.InvoiceParser
import com.vitol.inv3.ocr.InvoiceTextRecognizer
import com.vitol.inv3.ocr.OcrBlock
import com.vitol.inv3.ocr.TemplateLearner
import com.vitol.inv3.ocr.AzureDocumentIntelligenceService
import com.vitol.inv3.utils.DateFormatter
import com.vitol.inv3.export.TaxCodeDeterminer
import android.graphics.BitmapFactory
import java.io.InputStream
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    imageUris: List<Uri>,
    navController: NavController? = null,
    viewModel: ReviewViewModel = hiltViewModel(),
    fileImportViewModel: com.vitol.inv3.ui.scan.FileImportViewModel = run {
        // Get Activity-scoped ViewModel to share state across navigation routes
        // This ensures the same ViewModel instance is used across all screens
        val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
        if (activity != null) {
            // Use Activity's ViewModelStoreOwner with Hilt's factory
            // Since Activity is @AndroidEntryPoint, defaultViewModelProviderFactory is Hilt's factory
            androidx.lifecycle.viewmodel.compose.viewModel<com.vitol.inv3.ui.scan.FileImportViewModel>(
                viewModelStoreOwner = activity,
                factory = activity.defaultViewModelProviderFactory
            )
        } else {
            hiltViewModel()
        }
    }
) {
    // Use first URI as primary for backward compatibility
    val imageUri = imageUris.firstOrNull() ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Track all pages for this invoice
    var allImageUris by remember(imageUris) { mutableStateOf(imageUris) }
    
    // Check if invoice is from camera (FileProvider) or from import
    // Camera photos are saved in "captures" directory with "INV3_" prefix
    // Imports can be:
    //   - External storage URIs: content://com.android.externalstorage.documents/...
    //   - PDF pages converted to images: .../pdf_pages/...
    //   - HEIC converted images: .../converted_images/...
    val isFromCamera = remember(imageUri) {
        val uriString = imageUri.toString()
        // Camera photos are in "captures" directory with "INV3_" prefix
        uriString.contains("/captures/", ignoreCase = true) && 
        uriString.contains("INV3_", ignoreCase = true)
    }
    
    // Helper function to encode multiple URIs for navigation
    fun encodeUris(uris: List<Uri>): String {
        return uris.joinToString(",") { android.net.Uri.encode(it.toString()) }
    }
    
    // Get active own company ID to exclude from matching
    val activeCompanyIdFlow = remember { context.getActiveOwnCompanyIdFlow() }
    val activeCompanyId by activeCompanyIdFlow.collectAsState(initial = null)
    
    // Get own company details to exclude from extraction
    var ownCompanyNumber by remember { mutableStateOf<String?>(null) }
    var ownCompanyVatNumber by remember { mutableStateOf<String?>(null) }
    
    // Get repo from MainActivityViewModel to access company data
    val mainActivityViewModel: com.vitol.inv3.MainActivityViewModel = hiltViewModel()
    val repo = mainActivityViewModel.repo
    
    LaunchedEffect(activeCompanyId) {
        if (activeCompanyId != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val ownCompany = repo.getCompanyById(activeCompanyId!!)
                ownCompanyNumber = ownCompany?.company_number
                ownCompanyVatNumber = ownCompany?.vat_number
                Timber.d("Own company details - CompanyNumber: $ownCompanyNumber, VatNumber: $ownCompanyVatNumber")
            }
        } else {
            ownCompanyNumber = null
            ownCompanyVatNumber = null
        }
    }
    
    // Get processing queue state (for Skip/Stop buttons)
    val processingQueue by fileImportViewModel.processingQueue.collectAsState()
    val currentIndex by fileImportViewModel.currentIndex.collectAsState()
    
    // Get background processing progress
    val backgroundProgress by fileImportViewModel.backgroundProcessingProgress.collectAsState()
    
    // Calculate if buttons should be shown (queue has multiple items = batch processing)
    // Show buttons when queue has more than 1 item (batch processing mode)
    // This means user is processing multiple invoices (e.g., PDF with multiple pages)
    val shouldShowButtons = processingQueue.size > 1
    
    // Debug: Log queue state - log on every recomposition to track state changes
    LaunchedEffect(processingQueue.size, currentIndex, imageUri) {
        Timber.d("ReviewScreen: Processing queue size = ${processingQueue.size}, isEmpty = ${processingQueue.isEmpty()}, currentIndex = $currentIndex")
        Timber.d("ReviewScreen: Current imageUri = $imageUri")
        Timber.d("ReviewScreen: Queue contents = ${processingQueue.map { it.toString() }}")
        Timber.d("ReviewScreen: Should show buttons = $shouldShowButtons")
    }
    
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var fields by remember {
        mutableStateOf(
            mapOf(
                "Invoice_ID" to "",
                "Date" to "",
                "Company_name" to "",
                "Amount_without_VAT_EUR" to "",
                "VAT_amount_EUR" to "",
                "VAT_number" to "",
                "Company_number" to "",
                "Invoice_type" to "", // P = Purchase, S = Sales
                "VAT_rate" to "", // VAT rate as percentage (e.g., "21", "9", "5", "0")
                "Tax_code" to "" // Tax code (e.g., "PVM1", "PVM25")
            )
        )
    }
    // Get invoice type from FileImportViewModel (set before scanning)
    val invoiceTypeFromViewModel by fileImportViewModel.invoiceType.collectAsState()
    var invoiceType by remember { mutableStateOf<String?>(invoiceTypeFromViewModel) } // Track selected invoice type
    
    // Update invoice type when ViewModel changes
    LaunchedEffect(invoiceTypeFromViewModel) {
        invoiceType = invoiceTypeFromViewModel
        if (invoiceTypeFromViewModel != null) {
            fields = fields + ("Invoice_type" to invoiceTypeFromViewModel!!)
        }
    }
    var showCompanySuggestions by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var isCompanySelected by remember { mutableStateOf(false) } // Track if company is selected from existing
    var isReanalyzing by remember { mutableStateOf(false) } // Track if re-analysis is in progress
    var ocrMethod by remember { mutableStateOf<String?>(null) } // Track which OCR method was used: "Azure" or "Local"
    
    // Merge state
    var showMergeDialog by remember { mutableStateOf(false) }
    var isMerging by remember { mutableStateOf(false) }
    var nextInvoiceFields by remember { mutableStateOf<Map<String, String>?>(null) }
    var isMergedInvoice by remember { mutableStateOf(false) } // Track if current invoice is merged
    
    // Cancel/back confirmation dialog
    var showCancelDialog by remember { mutableStateOf(false) }
    
    // Coroutine scope for merge operations
    val mergeScope = rememberCoroutineScope()
    
    // Calculate initial date (previous month from today)
    val initialDateMillis = remember {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, -1)
        calendar.timeInMillis
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis
    )

    // Load companies when screen loads
    LaunchedEffect(Unit) {
        viewModel.loadCompanies()
        // Cleanup old cache entries (older than 24 hours) when screen loads
        fileImportViewModel.cleanupOldCache(olderThanHours = 24)
    }
    
    // Track if background processing has been triggered for this screen load
    var backgroundProcessingTriggered by remember(imageUri) { mutableStateOf(false) }
    // Track if current invoice processing has started (to ensure it gets priority)
    var currentInvoiceProcessingStarted by remember(imageUri) { mutableStateOf(false) }
    
    // Trigger background processing AFTER current invoice processing has started
    // This ensures current invoice gets priority and fills immediately when ready
    // Background processing runs in parallel but with a delay to give current invoice priority
    LaunchedEffect(processingQueue.size, currentIndex, ownCompanyNumber, ownCompanyVatNumber, activeCompanyId, currentInvoiceProcessingStarted) {
        // Only trigger if we have multiple invoices AND current invoice processing has started
        // This ensures current invoice gets priority and starts processing first
        if (processingQueue.size > 1 && currentIndex < processingQueue.size - 1 && !backgroundProcessingTriggered && currentInvoiceProcessingStarted) {
            // Check if background processing hasn't started yet
            val remainingUris = processingQueue.subList(currentIndex + 1, processingQueue.size)
            val needsProcessing = remainingUris.any { uri ->
                fileImportViewModel.getCachedOcrResult(uri) == null &&
                !fileImportViewModel.isProcessingInBackground(uri)
            }
            
            if (needsProcessing) {
                backgroundProcessingTriggered = true
                Timber.d("ReviewScreen: Starting background processing for ${remainingUris.size} remaining invoices (current invoice processing has started)")
                launch {
                    // Add a small delay (200ms) to ensure current invoice gets priority and starts first
                    kotlinx.coroutines.delay(200)
                    fileImportViewModel.triggerBackgroundProcessing(imageUri) { uri, index, onDone ->
                        // Minimal stagger: 50ms per invoice to allow maximum parallel processing while preventing rate limits
                        val staggeredDelay = index * 50L
                        viewModel.runOcrWithDocumentAi(
                            uri,
                            firstPassCompanyNumber = null,
                            firstPassVatNumber = null,
                            excludeCompanyId = activeCompanyId,
                            excludeOwnCompanyNumber = ownCompanyNumber, // Can be null, that's OK
                            excludeOwnVatNumber = ownCompanyVatNumber, // Can be null, that's OK
                            onDone = onDone,
                            onMethodUsed = { },
                            initialDelayMs = staggeredDelay // Staggered delay to prevent rate limiting
                        )
                    }
                }
            }
        }
    }

    // Track which URIs have been processed
    var processedUris by remember(allImageUris) { mutableStateOf<Set<Uri>>(emptySet()) }
    
    // Track if OCR has been triggered (keyed by imageUri to handle multiple images)
    var ocrTriggeredForUri by remember(imageUri) { mutableStateOf(false) }
    
    // Helper function to merge OCR results intelligently
    val mergeOcrResults: (String, Map<String, String>, (Map<String, String>) -> Unit) -> Unit = { newParsed, currentFields, onMerged ->
        val lines = newParsed.split("\n")
        val mergedFields = currentFields.toMutableMap()
        
        // Update text with new parsed content for tax code determination
        text = if (text.isBlank()) newParsed else "$text\n$newParsed"
        
        lines.forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                
                when (key) {
                    // Only update if field is empty or value is more complete
                    "Invoice_ID" -> {
                        if (mergedFields["Invoice_ID"].isNullOrBlank() && value.isNotBlank()) {
                            mergedFields["Invoice_ID"] = value
                        }
                    }
                    "Date" -> {
                        if (mergedFields["Date"].isNullOrBlank() && value.isNotBlank()) {
                            mergedFields["Date"] = value
                        }
                    }
                    "Company_name" -> {
                        if (value.isNotBlank() && (mergedFields["Company_name"].isNullOrBlank() || value.length > (mergedFields["Company_name"]?.length ?: 0))) {
                            mergedFields["Company_name"] = value
                        }
                    }
                    "Amount_without_VAT_EUR" -> {
                        // Sum amounts from multiple pages
                        val currentValue = mergedFields["Amount_without_VAT_EUR"]?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                        val newValue = value.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val total = currentValue + newValue
                        if (total > 0) {
                            mergedFields["Amount_without_VAT_EUR"] = total.toString()
                        }
                    }
                    "VAT_amount_EUR" -> {
                        // Sum VAT amounts from multiple pages
                        val currentValue = mergedFields["VAT_amount_EUR"]?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                        val newValue = value.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val total = currentValue + newValue
                        if (total > 0) {
                            mergedFields["VAT_amount_EUR"] = total.toString()
                            // Recalculate VAT rate
                            val amountWithoutVat = mergedFields["Amount_without_VAT_EUR"]?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                            if (amountWithoutVat > 0 && total > 0) {
                                val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, total)
                                if (calculatedRate != null) {
                                    mergedFields["VAT_rate"] = calculatedRate.toInt().toString()
                                    val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                    mergedFields["Tax_code"] = taxCode
                                }
                            }
                        }
                    }
                    "VAT_number" -> {
                        val normalizedValue = value.replace(" ", "").uppercase()
                        val normalizedOwnVat = ownCompanyVatNumber?.replace(" ", "")?.uppercase()
                        if (normalizedOwnVat == null || !normalizedValue.equals(normalizedOwnVat, ignoreCase = true)) {
                            if (mergedFields["VAT_number"].isNullOrBlank() && normalizedValue.isNotBlank()) {
                                mergedFields["VAT_number"] = normalizedValue
                            }
                        }
                    }
                    "Company_number" -> {
                        val ownCompanyNum = ownCompanyNumber
                        val trimmedValue = value.trim()
                        if (ownCompanyNum == null || trimmedValue != ownCompanyNum.trim()) {
                            if (mergedFields["Company_number"].isNullOrBlank() && trimmedValue.isNotBlank()) {
                                mergedFields["Company_number"] = trimmedValue
                            }
                        }
                    }
                }
            }
        }
        
        onMerged(mergedFields.toMap())
    }
    
    // Helper function to process a page with Azure
    val processPageWithAzure: (Uri, Boolean) -> Unit = { uri, isSinglePage ->
        viewModel.runOcrWithDocumentAi(
            uri,
            firstPassCompanyNumber = null,
            firstPassVatNumber = null,
            excludeCompanyId = activeCompanyId,
            excludeOwnCompanyNumber = ownCompanyNumber,
            excludeOwnVatNumber = ownCompanyVatNumber,
            onDone = { result ->
                result.onSuccess { parsed ->
                    if (isSinglePage) {
                        // Single page - fill fields directly
                        val lines = parsed.split("\n")
                        val newFields = fields.toMutableMap()
                        lines.forEach { line ->
                            val parts = line.split(":", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim()
                                val value = parts[1].trim()
                                when (key) {
                                    "Invoice_ID" -> if (newFields["Invoice_ID"].isNullOrBlank()) newFields["Invoice_ID"] = value
                                    "Date" -> if (newFields["Date"].isNullOrBlank()) newFields["Date"] = value
                                    "Company_name" -> {
                                        if (value.isNotBlank()) {
                                            newFields["Company_name"] = value
                                            Timber.d("processPageWithAzure - Setting company name: '$value'")
                                        }
                                    }
                                    "Amount_without_VAT_EUR" -> {
                                        if (newFields["Amount_without_VAT_EUR"].isNullOrBlank()) {
                                            newFields["Amount_without_VAT_EUR"] = value
                                            // Auto-calculate VAT rate if VAT amount is already set
                                            val vatAmountStr = newFields["VAT_amount_EUR"]?.replace(",", ".")
                                            val amountStr = value.replace(",", ".")
                                            val amountWithoutVat = amountStr.toDoubleOrNull()
                                            val vatAmount = vatAmountStr?.toDoubleOrNull()
                                            if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                                val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                                if (calculatedRate != null) {
                                                    newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                                    val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, parsed)
                                                    newFields["Tax_code"] = taxCode
                                                }
                                            }
                                        }
                                    }
                                    "VAT_amount_EUR" -> {
                                        if (newFields["VAT_amount_EUR"].isNullOrBlank()) {
                                            newFields["VAT_amount_EUR"] = value
                                            // Auto-calculate VAT rate if amount without VAT is already set
                                            val amountStr = newFields["Amount_without_VAT_EUR"]?.replace(",", ".")
                                            val vatAmountStr = value.replace(",", ".")
                                            val amountWithoutVat = amountStr?.toDoubleOrNull()
                                            val vatAmount = vatAmountStr.toDoubleOrNull()
                                            if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                                val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                                if (calculatedRate != null) {
                                                    newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                                    val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, parsed)
                                                    newFields["Tax_code"] = taxCode
                                                }
                                            }
                                        }
                                    }
                                    "VAT_number" -> {
                                        val normalizedValue = value.replace(" ", "").uppercase()
                                        val normalizedOwnVat = ownCompanyVatNumber?.replace(" ", "")?.uppercase()
                                        if (normalizedOwnVat == null || !normalizedValue.equals(normalizedOwnVat, ignoreCase = true)) {
                                            newFields["VAT_number"] = normalizedValue
                                            Timber.d("processPageWithAzure - Setting VAT_number: '$normalizedValue'")
                                        }
                                    }
                                    "Company_number" -> {
                                        val ownCompanyNum = ownCompanyNumber
                                        val trimmedValue = value.trim()
                                        if (ownCompanyNum == null || trimmedValue != ownCompanyNum.trim()) {
                                            newFields["Company_number"] = trimmedValue
                                            Timber.d("processPageWithAzure - Setting Company_number: '$trimmedValue'")
                                        }
                                    }
                                }
                            }
                        }
                        // Set text for tax code determination
                        text = parsed
                        fields = newFields.toMap()
                        processedUris = processedUris + uri
                        if (processedUris.size == allImageUris.size) {
                            isReanalyzing = false
                            currentInvoiceProcessingStarted = true
                        }
                        Timber.d("processPageWithAzure - Single page processing complete - Company_name: '${newFields["Company_name"]}', Invoice_ID: '${newFields["Invoice_ID"]}', Date: '${newFields["Date"]}'")
                        // Cache the result
                        fileImportViewModel.cacheOcrResult(uri, Result.success(parsed))
                    } else {
                        // Multi-page - merge results
                        mergeOcrResults(parsed, fields) { mergedFields ->
                            fields = mergedFields
                        }
                        processedUris = processedUris + uri
                        if (processedUris.size == allImageUris.size) {
                            isReanalyzing = false
                        }
                    }
                }.onFailure { error ->
                    Timber.e(error, "Failed to process page: $uri")
                    processedUris = processedUris + uri
                    if (processedUris.size == allImageUris.size) {
                        isReanalyzing = false
                    }
                }
            },
            onMethodUsed = { method ->
                if (processedUris.isEmpty()) {
                    ocrMethod = method
                }
            }
        )
    }
    
    // Process all pages when URIs change (handles both single and multi-page)
    LaunchedEffect(allImageUris) {
        // Process any new URIs that haven't been processed yet
        val newUris = allImageUris.filter { it !in processedUris }
        if (newUris.isNotEmpty() && !isReanalyzing && !ocrTriggeredForUri) {
            Timber.d("Processing ${newUris.size} new page(s) for invoice (total pages: ${allImageUris.size})")
            isReanalyzing = true
            isLoading = false
            ocrTriggeredForUri = true // Mark as triggered to prevent duplicate processing
            
            // Process each new page sequentially or in parallel
            newUris.forEachIndexed { index, uri ->
                val cached = fileImportViewModel.getCachedOcrResult(uri)
                if (cached != null) {
                    cached.onSuccess { parsed ->
                        if (allImageUris.size == 1) {
                            // Single page - use existing logic
                            val lines = parsed.split("\n")
                            val newFields = fields.toMutableMap()
                            lines.forEach { line ->
                                val parts = line.split(":", limit = 2)
                                if (parts.size == 2) {
                                    val key = parts[0].trim()
                                    val value = parts[1].trim()
                                    when (key) {
                                        "Invoice_ID" -> if (newFields["Invoice_ID"].isNullOrBlank()) newFields["Invoice_ID"] = value
                                        "Date" -> if (newFields["Date"].isNullOrBlank()) newFields["Date"] = value
                                        "Company_name" -> {
                                            if (value.isNotBlank()) {
                                                newFields["Company_name"] = value
                                            }
                                        }
                                        "Amount_without_VAT_EUR" -> {
                                            if (newFields["Amount_without_VAT_EUR"].isNullOrBlank()) {
                                                newFields["Amount_without_VAT_EUR"] = value
                                            }
                                        }
                                        "VAT_amount_EUR" -> {
                                            if (newFields["VAT_amount_EUR"].isNullOrBlank()) {
                                                newFields["VAT_amount_EUR"] = value
                                            }
                                        }
                                        "VAT_number" -> {
                                            val normalizedValue = value.replace(" ", "").uppercase()
                                            val normalizedOwnVat = ownCompanyVatNumber?.replace(" ", "")?.uppercase()
                                            if (normalizedOwnVat == null || !normalizedValue.equals(normalizedOwnVat, ignoreCase = true)) {
                                                newFields["VAT_number"] = normalizedValue
                                            }
                                        }
                                        "Company_number" -> {
                                            val ownCompanyNum = ownCompanyNumber
                                            val trimmedValue = value.trim()
                                            if (ownCompanyNum == null || trimmedValue != ownCompanyNum.trim()) {
                                                newFields["Company_number"] = trimmedValue
                                            }
                                        }
                                    }
                                }
                            }
                            fields = newFields.toMap()
                            text = parsed
                            Timber.d("Cached result applied (allImageUris) - Company_name: '${newFields["Company_name"]}', Invoice_ID: '${newFields["Invoice_ID"]}', Date: '${newFields["Date"]}'")
                        } else {
                            // Multi-page - merge results
                            mergeOcrResults(parsed, fields) { mergedFields ->
                                fields = mergedFields
                            }
                            text = if (text.isBlank()) parsed else "$text\n$parsed"
                        }
                        processedUris = processedUris + uri
                        if (processedUris.size == allImageUris.size) {
                            isReanalyzing = false
                            ocrMethod = "Cached"
                            currentInvoiceProcessingStarted = true
                        }
                    }.onFailure {
                        // Process with Azure if cache failed
                        processPageWithAzure(uri, index == 0 && allImageUris.size == 1)
                    }
                } else {
                    processPageWithAzure(uri, index == 0 && allImageUris.size == 1)
                }
            }
        }
    }
    
    // Start Azure Document Intelligence immediately (skip unreliable first local OCR pass)
    // Only depend on imageUri - ownCompanyNumber/VatNumber can be null initially, we'll use current values when calling
    // This LaunchedEffect handles single-page invoices for backward compatibility
    LaunchedEffect(imageUri) {
        // Skip if already processed by multi-page logic or if we have multiple pages
        if (allImageUris.size > 1 || imageUri in processedUris) {
            return@LaunchedEffect
        }
        
        // Trigger OCR once when image is available
        // Note: ownCompanyNumber and ownCompanyVatNumber may be null initially, but that's OK - we'll pass current values to exclude
        Timber.d("ReviewScreen LaunchedEffect triggered - imageUri: $imageUri, ownCompanyNumber: $ownCompanyNumber, ownCompanyVatNumber: $ownCompanyVatNumber, ocrTriggeredForUri: $ocrTriggeredForUri, isReanalyzing: $isReanalyzing")
        
        // Check if we have a cached result
        val cached = fileImportViewModel.getCachedOcrResult(imageUri)
        if (cached != null && !ocrTriggeredForUri) {
            Timber.d("Using cached OCR result for invoice (PRIORITY - immediate fill)")
            ocrTriggeredForUri = true
            isReanalyzing = true
            isLoading = false
            ocrMethod = "Cached"
            // Don't set currentInvoiceProcessingStarted here - set it after fields are filled
            
            // Use cached result
            cached.onSuccess { parsed ->
                val lines = parsed.split("\n")
                val newFields = fields.toMutableMap()
                lines.forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (key) {
                            "Invoice_ID" -> if (newFields["Invoice_ID"].isNullOrBlank()) newFields["Invoice_ID"] = value
                            "Date" -> if (newFields["Date"].isNullOrBlank()) newFields["Date"] = value
                            "Company_name" -> {
                                if (value.isNotBlank()) {
                                    newFields["Company_name"] = value
                                    Timber.d("Cached - Setting company name: '$value'")
                                }
                            }
                            "Amount_without_VAT_EUR" -> {
                                if (newFields["Amount_without_VAT_EUR"].isNullOrBlank()) {
                                    newFields["Amount_without_VAT_EUR"] = value
                                    // Auto-calculate VAT rate if VAT amount is already set
                                    val vatAmountStr = newFields["VAT_amount_EUR"]?.replace(",", ".")
                                    val amountStr = value.replace(",", ".")
                                    val amountWithoutVat = amountStr.toDoubleOrNull()
                                    val vatAmount = vatAmountStr?.toDoubleOrNull()
                                    if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                        val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                        if (calculatedRate != null) {
                                            newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                            val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                            newFields["Tax_code"] = taxCode
                                        }
                                    }
                                }
                            }
                            "VAT_amount_EUR" -> {
                                if (newFields["VAT_amount_EUR"].isNullOrBlank()) {
                                    newFields["VAT_amount_EUR"] = value
                                    // Auto-calculate VAT rate if amount without VAT is already set
                                    val amountStr = newFields["Amount_without_VAT_EUR"]?.replace(",", ".")
                                    val vatAmountStr = value.replace(",", ".")
                                    val amountWithoutVat = amountStr?.toDoubleOrNull()
                                    val vatAmount = vatAmountStr.toDoubleOrNull()
                                    if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                        val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                        if (calculatedRate != null) {
                                            newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                            val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                            newFields["Tax_code"] = taxCode
                                        }
                                    }
                                }
                            }
                            "VAT_number" -> {
                                val normalizedValue = value.replace(" ", "").uppercase()
                                val normalizedOwnVat = ownCompanyVatNumber?.replace(" ", "")?.uppercase()
                                if (normalizedOwnVat == null || !normalizedValue.equals(normalizedOwnVat, ignoreCase = true)) {
                                    newFields["VAT_number"] = normalizedValue
                                    Timber.d("Cached - Setting VAT_number: '$normalizedValue'")
                                }
                            }
                            "Company_number" -> {
                                val ownCompanyNum = ownCompanyNumber
                                val trimmedValue = value.trim()
                                if (ownCompanyNum == null || trimmedValue != ownCompanyNum.trim()) {
                                    newFields["Company_number"] = trimmedValue
                                    Timber.d("Cached - Setting Company_number: '$trimmedValue'")
                                }
                            }
                        }
                    }
                }
                fields = newFields.toMap()
                isReanalyzing = false
                Timber.d("Cached OCR result applied - All fields extracted")
                
                // NOW mark that current invoice processing has completed and data is filled
                // This will trigger background processing for remaining invoices
                currentInvoiceProcessingStarted = true
            }.onFailure { error ->
                Timber.e(error, "Cached OCR result failed, will process normally")
                // Remove failed cache entry and retry processing
                fileImportViewModel.removeCachedResult(imageUri)
                // Fall through to normal processing
                ocrTriggeredForUri = false
            }
        }
        
        if (imageUri != null && !ocrTriggeredForUri && !isReanalyzing && cached == null) {
            Timber.d("Starting Azure Document Intelligence OCR for current invoice (PRIORITY - no delay)...")
            ocrTriggeredForUri = true
            isReanalyzing = true
            isLoading = false // Set to false since we're starting immediately
            ocrMethod = "Azure" // Use Azure directly
            // Don't set currentInvoiceProcessingStarted here - set it only AFTER data is filled
            
            // Start processing current invoice immediately (non-blocking, no delay)
            // No first pass - go directly to Azure Document Intelligence
            // initialDelayMs = 0 ensures this starts immediately with highest priority
            viewModel.runOcrWithDocumentAi(
                imageUri,
                firstPassCompanyNumber = null, // No first pass
                firstPassVatNumber = null, // No first pass
                excludeCompanyId = activeCompanyId,
                excludeOwnCompanyNumber = ownCompanyNumber,
                excludeOwnVatNumber = ownCompanyVatNumber,
                onDone = { result ->
                    result.onSuccess { parsed ->
                        // If Azure Document Intelligence succeeded, use it
                        // The parsed string already has the correct company name from database lookup
                        val lines = parsed.split("\n")
                        val newFields = fields.toMutableMap()
                        lines.forEach { line ->
                            val parts = line.split(":", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim()
                                val value = parts[1].trim()
                                when (key) {
                                    "Invoice_ID" -> if (newFields["Invoice_ID"].isNullOrBlank()) newFields["Invoice_ID"] = value
                                    "Date" -> if (newFields["Date"].isNullOrBlank()) newFields["Date"] = value
                                    "Company_name" -> {
                                        // Always update company name from Azure Document Intelligence result (it has database lookup applied)
                                        if (value.isNotBlank()) {
                                            newFields["Company_name"] = value
                                            Timber.d("Azure - Setting company name from result: '$value'")
                                        }
                                    }
                                    "Amount_without_VAT_EUR" -> {
                                        if (newFields["Amount_without_VAT_EUR"].isNullOrBlank()) {
                                            newFields["Amount_without_VAT_EUR"] = value
                                            // Auto-calculate VAT rate if VAT amount is already set
                                            val vatAmountStr = newFields["VAT_amount_EUR"]?.replace(",", ".")
                                            val amountStr = value.replace(",", ".")
                                            val amountWithoutVat = amountStr.toDoubleOrNull()
                                            val vatAmount = vatAmountStr?.toDoubleOrNull()
                                            if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                                val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                                if (calculatedRate != null) {
                                                    newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                                    val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                                    newFields["Tax_code"] = taxCode
                                                }
                                            }
                                        }
                                    }
                                    "VAT_amount_EUR" -> {
                                        if (newFields["VAT_amount_EUR"].isNullOrBlank()) {
                                            newFields["VAT_amount_EUR"] = value
                                            // Auto-calculate VAT rate if amount without VAT is already set
                                            val amountStr = newFields["Amount_without_VAT_EUR"]?.replace(",", ".")
                                            val vatAmountStr = value.replace(",", ".")
                                            val amountWithoutVat = amountStr?.toDoubleOrNull()
                                            val vatAmount = vatAmountStr.toDoubleOrNull()
                                            if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                                val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                                if (calculatedRate != null) {
                                                    newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                                    val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                                    newFields["Tax_code"] = taxCode
                                                }
                                            }
                                        }
                                    }
                                    "VAT_number" -> {
                                        // Normalize VAT number (remove spaces)
                                        val normalizedValue = value.replace(" ", "").uppercase()
                                        val normalizedOwnVat = ownCompanyVatNumber?.replace(" ", "")?.uppercase()
                                        // Azure results have database lookup applied - ALWAYS use them (they ensure VAT/company number match)
                                        // Exclude own company VAT number
                                        if (normalizedOwnVat == null || !normalizedValue.equals(normalizedOwnVat, ignoreCase = true)) {
                                            newFields["VAT_number"] = normalizedValue
                                            Timber.d("Azure - Setting VAT_number from database: '$normalizedValue'")
                                        } else {
                                            Timber.d("Skipped own company VAT number from Azure result: $normalizedValue")
                                        }
                                    }
                                    "Company_number" -> {
                                        // Azure results have database lookup applied - ALWAYS use them (they ensure VAT/company number match)
                                        val ownCompanyNum = ownCompanyNumber // Store in local variable for smart cast
                                        val trimmedValue = value.trim()
                                        if (ownCompanyNum == null || trimmedValue != ownCompanyNum.trim()) {
                                            newFields["Company_number"] = trimmedValue
                                            Timber.d("Azure - Setting Company_number from database: '$trimmedValue'")
                                        } else {
                                            Timber.d("Skipped own company number from Azure result: $trimmedValue")
                                        }
                                    }
                                }
                            }
                        }
                        // Set text for tax code determination
                        text = parsed
                        fields = newFields.toMap()
                        isReanalyzing = false
                        Timber.d("Current invoice processing complete - All fields extracted, Company_name: '${newFields["Company_name"]}', Invoice_ID: '${newFields["Invoice_ID"]}', Date: '${newFields["Date"]}', Amount: '${newFields["Amount_without_VAT_EUR"]}', VAT: '${newFields["VAT_amount_EUR"]}'")
                        // Cache the result for potential reuse
                        fileImportViewModel.cacheOcrResult(imageUri, Result.success(parsed))
                        
                        // NOW mark that current invoice processing has completed and data is filled
                        // This will trigger background processing for remaining invoices
                        currentInvoiceProcessingStarted = true
                        
                        // Background processing is already triggered by LaunchedEffect when screen loads
                        // No need to trigger again here
                    }.onFailure {
                        // Fallback to local OCR if Azure Document Intelligence fails
                        Timber.w("Azure Document Intelligence failed, falling back to local OCR")
                        ocrMethod = "Local" // Mark as using local OCR
                        // Use runOcr instead of runOcrSecondPass since we don't have a lookup key (no first pass)
                        viewModel.runOcr(
                            imageUri,
                            excludeCompanyId = activeCompanyId,
                            excludeOwnCompanyNumber = ownCompanyNumber,
                            excludeOwnVatNumber = ownCompanyVatNumber,
                            onDone = { localResult ->
                                localResult.onSuccess { parsed ->
                                // Parse the text to populate all fields
                                // The parsed string already has the correct company name from database lookup
                                val lines = parsed.split("\n")
                                val newFields = fields.toMutableMap()
                                lines.forEach { line ->
                                    val parts = line.split(":", limit = 2)
                                    if (parts.size == 2) {
                                        val key = parts[0].trim()
                                        val value = parts[1].trim()
                                        when (key) {
                                            "Invoice_ID" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["Invoice_ID"].isNullOrBlank()) {
                                                    newFields["Invoice_ID"] = value
                                                    Timber.d("Local OCR fallback - Setting Invoice_ID: '$value'")
                                                }
                                            }
                                            "Date" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["Date"].isNullOrBlank()) {
                                                    newFields["Date"] = value
                                                    Timber.d("Local OCR fallback - Setting Date: '$value'")
                                                }
                                            }
                                            "Company_name" -> {
                                                // Always update company name from local OCR result (it has database lookup applied)
                                                if (value.isNotBlank()) {
                                                    newFields["Company_name"] = value
                                                    Timber.d("Local OCR fallback - Setting company name from result: '$value'")
                                                }
                                            }
                                            "Amount_without_VAT_EUR" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["Amount_without_VAT_EUR"].isNullOrBlank()) {
                                                    newFields["Amount_without_VAT_EUR"] = value
                                                    Timber.d("Local OCR fallback - Setting Amount_without_VAT_EUR: '$value'")
                                                    // Auto-calculate VAT rate if VAT amount is already set
                                                    val vatAmountStr = newFields["VAT_amount_EUR"]?.replace(",", ".")
                                                    val amountStr = value.replace(",", ".")
                                                    val amountWithoutVat = amountStr.toDoubleOrNull()
                                                    val vatAmount = vatAmountStr?.toDoubleOrNull()
                                                    if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                                        val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                                        if (calculatedRate != null) {
                                                            newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                                            val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                                            newFields["Tax_code"] = taxCode
                                                        }
                                                    }
                                                }
                                            }
                                            "VAT_amount_EUR" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["VAT_amount_EUR"].isNullOrBlank()) {
                                                    newFields["VAT_amount_EUR"] = value
                                                    Timber.d("Local OCR fallback - Setting VAT_amount_EUR: '$value'")
                                                    // Auto-calculate VAT rate if amount without VAT is already set
                                                    val amountStr = newFields["Amount_without_VAT_EUR"]?.replace(",", ".")
                                                    val vatAmountStr = value.replace(",", ".")
                                                    val amountWithoutVat = amountStr?.toDoubleOrNull()
                                                    val vatAmount = vatAmountStr.toDoubleOrNull()
                                                    if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                                                        val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                                                        if (calculatedRate != null) {
                                                            newFields["VAT_rate"] = calculatedRate.toInt().toString()
                                                            val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                                                            newFields["Tax_code"] = taxCode
                                                        }
                                                    }
                                                }
                                            }
                                            "VAT_number" -> {
                                                // Normalize VAT number (remove spaces)
                                                val normalizedValue = value.replace(" ", "").uppercase()
                                                val normalizedOwnVat = ownCompanyVatNumber?.replace(" ", "")?.uppercase()
                                                // Local OCR fallback can replace if Azure didn't find it
                                                // Exclude own company VAT number
                                                if (normalizedValue.isNotBlank() && newFields["VAT_number"].isNullOrBlank() &&
                                                    (normalizedOwnVat == null || !normalizedValue.equals(normalizedOwnVat, ignoreCase = true))) {
                                                    newFields["VAT_number"] = normalizedValue
                                                    Timber.d("Local OCR fallback - Setting VAT_number: '$normalizedValue'")
                                                } else if (normalizedOwnVat != null && normalizedValue.equals(normalizedOwnVat, ignoreCase = true)) {
                                                    Timber.d("Skipped own company VAT number from local OCR result: $normalizedValue")
                                                }
                                            }
                                            "Company_number" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                // Exclude own company number
                                                val ownCompanyNum = ownCompanyNumber // Store in local variable for smart cast
                                                val trimmedValue = value.trim()
                                                if (trimmedValue.isNotBlank() && newFields["Company_number"].isNullOrBlank() &&
                                                    (ownCompanyNum == null || trimmedValue != ownCompanyNum.trim())) {
                                                    newFields["Company_number"] = trimmedValue
                                                    Timber.d("Local OCR fallback - Setting Company_number: '$trimmedValue'")
                                                } else if (ownCompanyNum != null && trimmedValue == ownCompanyNum.trim()) {
                                                    Timber.d("Skipped own company number from local OCR result: $trimmedValue")
                                                }
                                            }
                                        }
                                    }
                                }
                                fields = newFields.toMap()
                                isReanalyzing = false
                                Timber.d("Current invoice processing complete (local OCR) - All fields extracted, Company_name: '${newFields["Company_name"]}'")
                                // Cache the result for potential reuse
                                fileImportViewModel.cacheOcrResult(imageUri, localResult)
                                
                                // NOW mark that current invoice processing has completed and data is filled
                                // This will trigger background processing for remaining invoices
                                currentInvoiceProcessingStarted = true
                                
                                // Background processing is already triggered by LaunchedEffect when screen loads
                                // No need to trigger again here
                            }.onFailure { error ->
                                Timber.e(error, "Local OCR fallback failed")
                                isReanalyzing = false
                                // Cache the failure result too
                                fileImportViewModel.cacheOcrResult(imageUri, Result.failure(error))
                                
                                // Background processing is already triggered by LaunchedEffect when screen loads
                                // No need to trigger again here
                            }
                        }
                    ) // Close runOcr call
                    } // Close .onFailure block of Azure
                },
                onMethodUsed = { method ->
                    ocrMethod = method // Update OCR method indicator
                }
            )
        }
    }

    // Check if company is selected from existing companies
    val isCompanyFromExisting = remember(fields["Company_name"]) {
        viewModel.companies.any { it.company_name?.equals(fields["Company_name"], ignoreCase = true) == true }
    }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding() // Allow scrolling when keyboard is active
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp), // Add top padding to avoid status bar
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Background processing progress indicator (only show if there are invoices processing in background)
        // Don't show if we're still processing the current invoice - only show background progress
        if (backgroundProgress.total > 0 && !backgroundProgress.isComplete && !isReanalyzing) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Processing ${backgroundProgress.inProgress} invoice(s) in background",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${backgroundProgress.completed + backgroundProgress.failed}/${backgroundProgress.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { backgroundProgress.progressPercentage / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (backgroundProgress.failed > 0) {
                            Text(
                                text = "⚠ ${backgroundProgress.failed} failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLoading || isReanalyzing) {
                        CircularProgressIndicator()
                        // Progress indicator (X of Y) next to loading circle
                        if (processingQueue.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "${currentIndex + 1} of ${processingQueue.size}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Verify fields")
                            // Show page indicator for multi-page invoices
                            if (allImageUris.size > 1) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = "${allImageUris.size} page${if (allImageUris.size > 1) "s" else ""}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            // Show progress indicator when queue is active (even if not loading)
                            if (processingQueue.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Text(
                                        text = "${currentIndex + 1} of ${processingQueue.size}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Invoice type label in top right
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Invoice type label
                    invoiceType?.let { type ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = if (type == "P") "Purchase" else "Sales",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Company name field (FIRST)
        item {
            Box {
                OutlinedTextField(
                    value = fields["Company_name"] ?: "",
                    onValueChange = { newValue ->
                        fields = fields + ("Company_name" to newValue)
                        showCompanySuggestions = newValue.isNotBlank()
                        isCompanySelected = false // Reset when user types
                    },
                    label = { Text("Company_name") },
                    modifier = Modifier.fillMaxWidth()
                )
                // Show suggestions dropdown
                if (showCompanySuggestions && fields["Company_name"]?.isNotBlank() == true) {
                    val query = fields["Company_name"]?.lowercase() ?: ""
                    val matchingCompanies: List<CompanyRecord> = viewModel.companies.toList().filter { company: CompanyRecord ->
                        company.company_name?.lowercase()?.contains(query) == true
                    }.take(5) // Limit to 5 suggestions

                    if (matchingCompanies.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .padding(top = 56.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                            )
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(matchingCompanies) { company ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // Auto-fill company fields and mark as selected
                                                fields = fields + mapOf(
                                                    "Company_name" to (company.company_name ?: ""),
                                                    "VAT_number" to (company.vat_number ?: ""),
                                                    "Company_number" to (company.company_number ?: "")
                                                )
                                                showCompanySuggestions = false
                                                isCompanySelected = true
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = company.company_name ?: "",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // VAT number (SECOND) - optional/smaller when company selected
        item {
            OptionalFieldEditor(
                label = "VAT_number",
                value = fields["VAT_number"] ?: "",
                onChange = { fields = fields + ("VAT_number" to it) },
                isOptional = isCompanyFromExisting || isCompanySelected,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Company number (THIRD) - optional/smaller when company selected
        item {
            OptionalFieldEditor(
                label = "Company_number",
                value = fields["Company_number"] ?: "",
                onChange = { fields = fields + ("Company_number" to it) },
                isOptional = isCompanyFromExisting || isCompanySelected,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Invoice ID
        item {
            FieldEditor(
                label = "Invoice_ID",
                value = fields["Invoice_ID"] ?: "",
                onChange = { fields = fields + ("Invoice_ID" to it) }
            )
        }
        
        // Date field with date picker
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            ) {
                OutlinedTextField(
                    value = fields["Date"] ?: "",
                    onValueChange = { },
                    label = { Text("Date") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    enabled = false
                )
            }
        }
        
        // Amount without VAT
        item {
            FieldEditor(
                label = "Amount_without_VAT_EUR",
                value = fields["Amount_without_VAT_EUR"] ?: "",
                onChange = { newValue ->
                    fields = fields + ("Amount_without_VAT_EUR" to newValue)
                    // Auto-calculate VAT rate when amount without VAT changes
                    // Handle comma as decimal separator
                    val amountWithoutVat = newValue.replace(",", ".").toDoubleOrNull()
                    val vatAmountStr = fields["VAT_amount_EUR"]?.replace(",", ".")
                    val vatAmount = vatAmountStr?.toDoubleOrNull()
                    if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                        val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                        if (calculatedRate != null) {
                            fields = fields + ("VAT_rate" to calculatedRate.toInt().toString())
                            // Auto-determine tax code
                            val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                            fields = fields + ("Tax_code" to taxCode)
                        }
                    }
                }
            )
        }
        
        // VAT amount
        item {
            FieldEditor(
                label = "VAT_amount_EUR",
                value = fields["VAT_amount_EUR"] ?: "",
                onChange = { newValue ->
                    fields = fields + ("VAT_amount_EUR" to newValue)
                    // Auto-calculate VAT rate when VAT amount changes
                    // Handle comma as decimal separator
                    val amountWithoutVatStr = fields["Amount_without_VAT_EUR"]?.replace(",", ".")
                    val amountWithoutVat = amountWithoutVatStr?.toDoubleOrNull()
                    val vatAmount = newValue.replace(",", ".").toDoubleOrNull()
                    if (amountWithoutVat != null && vatAmount != null && amountWithoutVat > 0) {
                        val calculatedRate = TaxCodeDeterminer.calculateVatRate(amountWithoutVat, vatAmount)
                        if (calculatedRate != null) {
                            fields = fields + ("VAT_rate" to calculatedRate.toInt().toString())
                            // Auto-determine tax code
                            val taxCode = TaxCodeDeterminer.determineTaxCode(calculatedRate, text)
                            fields = fields + ("Tax_code" to taxCode)
                        }
                    }
                }
            )
        }
        
        // VAT rate and Tax code (side by side, small fields)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // VAT rate (small field)
                OutlinedTextField(
                    value = fields["VAT_rate"] ?: "",
                    onValueChange = { newValue ->
                        fields = fields + ("VAT_rate" to newValue)
                        // Auto-determine tax code when VAT rate changes
                        val vatRate = newValue.toDoubleOrNull()
                        if (vatRate != null) {
                            val taxCode = TaxCodeDeterminer.determineTaxCode(vatRate, text)
                            fields = fields + ("Tax_code" to taxCode)
                        }
                    },
                    label = { Text("VAT, %", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                
                // Tax code (small field)
                OutlinedTextField(
                    value = fields["Tax_code"] ?: "",
                    onValueChange = { fields = fields + ("Tax_code" to it) },
                    label = { Text("Tax Code", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        // Error message
        item {
            if (errorMessage != null) {
                Text(
                    text = "Error: $errorMessage",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        
        // Confirm button
        item {
            ElevatedButton(
                onClick = {
                    // Format date before saving
                    val formattedDate = DateFormatter.formatDateForDatabase(fields["Date"])
                    if (fields["Date"]?.isNotBlank() == true && formattedDate == null) {
                        errorMessage = "Invalid date format. Please enter date as YYYY-MM-DD (e.g., 2024-03-04)"
                        return@ElevatedButton
                    }
                    
                    // Invoice type should already be set from ViewModel, but validate just in case
                    val finalInvoiceType = invoiceType ?: invoiceTypeFromViewModel
                    if (finalInvoiceType == null) {
                        errorMessage = "Invoice type is required. Please go back and select invoice type."
                        return@ElevatedButton
                    }
                    
                    isSaving = true
                    errorMessage = null
                    // Parse amounts, handling both comma and dot decimal separators
                    val amountWithoutVatStr = fields["Amount_without_VAT_EUR"]?.replace(",", ".")?.replace(" ", "")
                    val vatAmountStr = fields["VAT_amount_EUR"]?.replace(",", ".")?.replace(" ", "")
                    
                    viewModel.confirm(
                        InvoiceRecord(
                            invoice_id = fields["Invoice_ID"],
                            date = formattedDate,
                            company_name = fields["Company_name"],
                            amount_without_vat_eur = amountWithoutVatStr?.toDoubleOrNull(),
                            vat_amount_eur = vatAmountStr?.toDoubleOrNull(),
                            vat_number = fields["VAT_number"],
                            company_number = fields["Company_number"],
                            invoice_type = finalInvoiceType,
                            vat_rate = fields["VAT_rate"]?.replace(",", ".")?.toDoubleOrNull(),
                            tax_code = fields["Tax_code"]?.takeIf { it.isNotBlank() } ?: "PVM1"
                        ),
                        CompanyRecord(
                            company_number = fields["Company_number"],
                            company_name = fields["Company_name"],
                            vat_number = fields["VAT_number"]
                        )
                    ) { success ->
                        isSaving = false
                        if (success) {
                            // If this was a merged invoice, skip the next one (it was already merged)
                            if (isMergedInvoice) {
                                if (fileImportViewModel.hasNext()) {
                                    // Skip the next invoice (it was merged into this one)
                                    fileImportViewModel.moveToNext()
                                }
                                isMergedInvoice = false // Reset flag
                            }
                            
                            // Check if there are more items in processing queue
                            if (fileImportViewModel.hasNext()) {
                                // Move to next item in queue
                                fileImportViewModel.moveToNext()
                                // Navigate back - ScanScreen will handle navigating to next item
                                navController?.popBackStack()
                            } else {
                                // No more items, clear queue and navigate back to home screen
                                fileImportViewModel.clearQueue()
                                navController?.popBackStack()
                            }
                        } else {
                            errorMessage = "Failed to save invoice. Please try again."
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    Text("Confirm")
                }
            }
        }
        
        // Redo button - navigate back to camera
        // Only show when invoice is from camera (not from import)
        if (isFromCamera) {
            item {
                OutlinedButton(
                    onClick = {
                        // Navigate back to Scan screen to take a new photo
                        navController?.navigate(Routes.Scan) {
                            // Pop current screen and navigate to Scan
                            popUpTo(Routes.Scan) { inclusive = false }
                        }
                    },
                    enabled = !isSaving && !isMerging,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Redo")
                    }
                }
            }
        }
        
        // Add Page button - navigate to camera to add another page
        // Only show when invoice is from camera (not from import)
        if (isFromCamera) {
            item {
                OutlinedButton(
                    onClick = {
                        // Navigate to Scan screen to capture additional page
                        // Pass current pages so we can add to them
                        val encodedUris = encodeUris(allImageUris)
                        navController?.navigate("${Routes.Scan}/addPage/$encodedUris")
                    },
                    enabled = !isSaving && !isMerging && !isLoading && !isReanalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Add Page")
                    }
                }
            }
        }
        
        // Merge with next invoice button (only show if there's a next invoice) - moved to bottom
        item {
            if (fileImportViewModel.hasNext() && !isSaving && !isMerging) {
                OutlinedButton(
                    onClick = {
                        // Load next invoice data for merging
                        val nextIndex = currentIndex + 1
                        if (nextIndex < processingQueue.size) {
                            val nextUri = processingQueue[nextIndex]
                            isMerging = true
                            
                            // Check if we have cached result for next invoice
                            val cached = fileImportViewModel.getCachedOcrResult(nextUri)
                            if (cached != null) {
                                cached.onSuccess { parsed ->
                                    val nextFields = parseOcrResultToFields(parsed)
                                    nextInvoiceFields = nextFields
                                    showMergeDialog = true
                                    isMerging = false
                                }.onFailure {
                                    // If cached result failed, process next invoice
                                    mergeScope.launch {
                                        viewModel.runOcrWithDocumentAi(
                                            nextUri,
                                            firstPassCompanyNumber = null,
                                            firstPassVatNumber = null,
                                            excludeCompanyId = activeCompanyId,
                                            excludeOwnCompanyNumber = ownCompanyNumber,
                                            excludeOwnVatNumber = ownCompanyVatNumber,
                                            onDone = { result ->
                                                result.onSuccess { parsed ->
                                                    val nextFields = parseOcrResultToFields(parsed)
                                                    nextInvoiceFields = nextFields
                                                    showMergeDialog = true
                                                    isMerging = false
                                                }.onFailure { error ->
                                                    Timber.e(error, "Failed to process next invoice for merge")
                                                    errorMessage = "Failed to load next invoice: ${error.message}"
                                                    isMerging = false
                                                }
                                            },
                                            onMethodUsed = { }
                                        )
                                    }
                                }
                            } else {
                                // Process next invoice
                                mergeScope.launch {
                                    viewModel.runOcrWithDocumentAi(
                                        nextUri,
                                        firstPassCompanyNumber = null,
                                        firstPassVatNumber = null,
                                        excludeCompanyId = activeCompanyId,
                                        excludeOwnCompanyNumber = ownCompanyNumber,
                                        excludeOwnVatNumber = ownCompanyVatNumber,
                                        onDone = { result ->
                                            result.onSuccess { parsed ->
                                                val nextFields = parseOcrResultToFields(parsed)
                                                nextInvoiceFields = nextFields
                                                showMergeDialog = true
                                                isMerging = false
                                            }.onFailure { error ->
                                                Timber.e(error, "Failed to process next invoice for merge")
                                                errorMessage = "Failed to load next invoice: ${error.message}"
                                                isMerging = false
                                            }
                                        },
                                        onMethodUsed = { }
                                    )
                                }
                            }
                        }
                    },
                    enabled = !isSaving && !isMerging && fileImportViewModel.hasNext(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isMerging) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp).widthIn(16.dp).heightIn(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MergeType,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Text(if (isMerging) "Loading next invoice..." else "Merge with next invoice")
                    }
                }
            }
        }
        
        // Skip and Stop buttons (only shown when processing queue is active)
        // Show buttons when there are multiple items in the queue (batch import mode)
        item {
            // Always render the item, but conditionally show buttons
            // This ensures the item is in the composition tree
            // Always show buttons when queue is not empty (for batch processing)
            // Buttons are enabled only when queue has multiple items (size > 1)
            if (processingQueue.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Previous button
                    OutlinedButton(
                        onClick = {
                            Timber.d("ReviewScreen: Previous button clicked - queue size = ${processingQueue.size}, currentIndex = $currentIndex")
                            if (currentIndex > 0) {
                                // Get the previous URI before moving
                                val previousIndex = currentIndex - 1
                                if (previousIndex >= 0 && previousIndex < processingQueue.size) {
                                    val previousUri = processingQueue[previousIndex]
                                    // Move to previous item in queue
                                    fileImportViewModel.moveToPrevious()
                                    // Navigate to previous invoice
                                    val encodedPreviousUri = android.net.Uri.encode(previousUri.toString())
                                    val encodedCurrentUris = encodeUris(allImageUris)
                                    navController?.navigate("review/$encodedPreviousUri") {
                                        // Pop current screen and navigate to previous
                                        popUpTo("review/$encodedCurrentUris") { inclusive = true }
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && currentIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = "Previous",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    
                    // Skip button
                    OutlinedButton(
                        onClick = {
                            Timber.d("ReviewScreen: Skip button clicked - queue size = ${processingQueue.size}, currentIndex = $currentIndex")
                            // Skip current invoice without saving
                            if (fileImportViewModel.hasNext()) {
                                // Move to next item in queue
                                fileImportViewModel.moveToNext()
                                // Navigate back - ScanScreen will handle navigating to next item
                                navController?.popBackStack()
                            } else {
                                // No more items, clear queue and navigate back to home screen
                                fileImportViewModel.clearQueue()
                                navController?.popBackStack()
                            }
                        },
                        enabled = !isSaving && shouldShowButtons,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = "Skip",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    
                    // Stop button
                    OutlinedButton(
                        onClick = {
                            Timber.d("ReviewScreen: Stop button clicked - queue size = ${processingQueue.size}")
                            // Stop processing queue and return to home
                            fileImportViewModel.clearQueue()
                            // Navigate back to home screen
                            navController?.navigate(Routes.Home) {
                                // Clear back stack to prevent going back to review screen
                                popUpTo(Routes.Home) { inclusive = false }
                            }
                        },
                        enabled = !isSaving && shouldShowButtons,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = "Stop",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Handle back button press
    BackHandler(enabled = !isSaving && !isMerging) {
        showCancelDialog = true
    }
    
    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Verification?") },
            text = { Text("Are you sure you want to cancel? Any unsaved changes will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        // Clear queue and navigate back to home screen
                        fileImportViewModel.clearQueue()
                        navController?.navigate(Routes.Home) {
                            // Clear back stack to prevent going back to review screen
                            popUpTo(Routes.Home) { inclusive = false }
                        }
                    }
                ) {
                    Text("Yes, Cancel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("No, Continue")
                }
            }
        )
    }
    
    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            val selectedDate = dateFormat.format(Date(millis))
                            fields = fields + ("Date" to selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    // Merge confirmation dialog
    if (showMergeDialog && nextInvoiceFields != null) {
        AlertDialog(
            onDismissRequest = {
                showMergeDialog = false
                nextInvoiceFields = null
            },
            title = { Text("Merge Invoices") },
            text = {
                Column {
                    Text("This will combine the current invoice with the next invoice. Fields will be merged intelligently (preferring non-empty values).")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Current Invoice:", fontWeight = FontWeight.Bold)
                    Text("  Invoice ID: ${fields["Invoice_ID"] ?: "(empty)"}")
                    Text("  Company: ${fields["Company_name"] ?: "(empty)"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Next Invoice:", fontWeight = FontWeight.Bold)
                    Text("  Invoice ID: ${nextInvoiceFields?.get("Invoice_ID") ?: "(empty)"}")
                    Text("  Company: ${nextInvoiceFields?.get("Company_name") ?: "(empty)"}")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Merge fields intelligently
                        val mergedFields = mergeFieldsIntelligently(fields, nextInvoiceFields!!)
                        fields = mergedFields
                        showMergeDialog = false
                        nextInvoiceFields = null
                        isMerging = false
                        isMergedInvoice = true // Mark as merged so we skip next invoice on save
                    }
                ) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMergeDialog = false
                        nextInvoiceFields = null
                        isMerging = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Helper function to parse OCR result string to fields map
private fun parseOcrResultToFields(parsed: String): Map<String, String> {
    val fieldsMap = mutableMapOf<String, String>()
    val lines = parsed.split("\n")
    lines.forEach { line ->
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            val value = parts[1].trim()
            fieldsMap[key] = value
        }
    }
    return fieldsMap
}

// Helper function to intelligently merge fields from two invoices
private fun mergeFieldsIntelligently(
    currentFields: Map<String, String>,
    nextFields: Map<String, String>
): Map<String, String> {
    val merged = currentFields.toMutableMap()
    
    // Merge strategy: prefer non-empty values, prefer current if both are non-empty
    // For specific fields, use smarter logic
    
    // Invoice_ID: prefer current (first page usually has the main ID)
    merged["Invoice_ID"] = currentFields["Invoice_ID"]?.takeIf { it.isNotBlank() }
        ?: nextFields["Invoice_ID"] ?: ""
    
    // Date: prefer current (first page usually has the date)
    merged["Date"] = currentFields["Date"]?.takeIf { it.isNotBlank() }
        ?: nextFields["Date"] ?: ""
    
    // Company_name: prefer non-empty, prefer current
    merged["Company_name"] = currentFields["Company_name"]?.takeIf { it.isNotBlank() }
        ?: nextFields["Company_name"] ?: ""
    
    // VAT_number: prefer non-empty, prefer current
    merged["VAT_number"] = currentFields["VAT_number"]?.takeIf { it.isNotBlank() }
        ?: nextFields["VAT_number"] ?: ""
    
    // Company_number: prefer non-empty, prefer current
    merged["Company_number"] = currentFields["Company_number"]?.takeIf { it.isNotBlank() }
        ?: nextFields["Company_number"] ?: ""
    
    // Amount_without_VAT_EUR: prefer non-empty, prefer next (totals often on second page)
    merged["Amount_without_VAT_EUR"] = nextFields["Amount_without_VAT_EUR"]?.takeIf { it.isNotBlank() }
        ?: currentFields["Amount_without_VAT_EUR"] ?: ""
    
    // VAT_amount_EUR: prefer non-empty, prefer next (totals often on second page)
    merged["VAT_amount_EUR"] = nextFields["VAT_amount_EUR"]?.takeIf { it.isNotBlank() }
        ?: currentFields["VAT_amount_EUR"] ?: ""
    
    return merged
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val recognizer: InvoiceTextRecognizer,
    private val repo: SupabaseRepository,
    private val client: io.github.jan.supabase.SupabaseClient?,
    private val templateLearner: TemplateLearner,
    private val templateStore: TemplateStore,
    app: Application
) : AndroidViewModel(app) {
    val companies = mutableStateListOf<CompanyRecord>()
    
    // Store OCR blocks and image URI for template learning
    private var currentOcrBlocks: List<OcrBlock> = emptyList()
    private var currentImageUri: Uri? = null
    
    // Azure Document Intelligence service
    private val documentAiService = AzureDocumentIntelligenceService(getApplication())

    fun loadCompanies() {
        viewModelScope.launch {
            runCatching {
                // select() without parameters selects all columns including id
                client?.from("companies")?.select()?.decodeList<CompanyRecord>() ?: emptyList<CompanyRecord>()
            }.onSuccess { list: List<CompanyRecord> ->
                companies.clear()
                companies.addAll(list)
            }
        }
    }
    /**
     * First pass: Identify company NUMBER only (fast, for initial display)
     * Does NOT extract company name - only company number and VAT number
     * @param excludeOwnCompanyNumber Own company number to exclude (partner's company only)
     * @param excludeOwnVatNumber Own company VAT number to exclude (partner's company only)
     */
    fun runOcrFirstPass(uri: Uri, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null, onDone: (Result<CompanyRecognition.Candidate>) -> Unit) {
        viewModelScope.launch {
            val result = recognizer.recognize(uri)
                .map { blocks ->
                    // Store blocks and URI for later use
                    currentOcrBlocks = blocks
                    currentImageUri = uri
                    blocks
                }
                .map { blocks ->
                    // Try to identify company NUMBER only (not name)
                    val lines = blocks.map { it.text }
                    val companyCandidate = CompanyRecognition.recognize(lines, excludeOwnCompanyNumber, excludeOwnVatNumber)
                    // Return candidate with company name set to null (we don't want it in first pass)
                    val candidateWithoutName = companyCandidate.copy(companyName = null)
                    Timber.d("First pass - Company recognition: companyNumber=${candidateWithoutName.companyNumber}, vatNumber=${candidateWithoutName.vatNumber} (company name skipped)")
                    candidateWithoutName
                }
            onDone(result)
        }
    }
    
    /**
     * Second pass: Re-analyze with template knowledge after company is confirmed
     * @param excludeOwnCompanyNumber Own company number to exclude (partner's company only)
     * @param excludeOwnVatNumber Own company VAT number to exclude (partner's company only)
     */
    fun runOcrSecondPass(uri: Uri, lookupKey: String, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = recognizer.recognize(uri)
                .map { blocks ->
                    // Get image dimensions
                    val imageSize = getImageSize(uri)
                    
                    // Try to load template for this company using multiple possible keys
                    val lines = blocks.map { it.text }
                    val companyCandidate = CompanyRecognition.recognize(lines, excludeOwnCompanyNumber, excludeOwnVatNumber)
                    // Use company number/VAT number for template lookup (not company name)
                    val possibleKeys = listOfNotNull(
                        normalizeCompanyKey(lookupKey), // Company number or VAT number from first pass
                        normalizeCompanyKey(companyCandidate.companyNumber),
                        normalizeCompanyKey(companyCandidate.vatNumber)
                    ).filterNotNull().distinct()
                    
                    Timber.d("Second pass - Lookup key: '$lookupKey', normalized keys: $possibleKeys")
                    
                    // Try to load template for this company using multiple possible keys
                    var template = emptyList<FieldRegion>()
                    var matchedKey: String? = null
                    
                    for (key in possibleKeys) {
                        val loaded = templateStore.loadTemplate(key)
                        if (loaded.isNotEmpty()) {
                            template = loaded
                            matchedKey = key
                            Timber.d("Template found for key '$key': ${loaded.size} regions")
                            break
                        }
                    }
                    
                    // Parse using template if available, otherwise use keyword matching
                    val parsed = if (template.isNotEmpty() && imageSize != null) {
                        Timber.d("Using template for company key '$matchedKey' (${template.size} regions, image: ${imageSize.width}x${imageSize.height})")
                        InvoiceParser.parseWithTemplate(blocks, imageSize.width, imageSize.height, template)
                    } else {
                        if (template.isEmpty()) {
                            Timber.d("Using keyword matching (no template found for keys: $possibleKeys)")
                        } else {
                            Timber.d("Using keyword matching (no image size available)")
                        }
                        InvoiceParser.parse(blocks.map { it.text }, excludeOwnCompanyNumber, excludeOwnVatNumber)
                    }
                    
                    parsed
                }
                .map { parsed ->
                    "Invoice_ID: ${parsed.invoiceId ?: ""}\n" +
                            "Date: ${parsed.date ?: ""}\n" +
                            "Company_name: ${parsed.companyName ?: ""}\n" +
                            "Amount_without_VAT_EUR: ${parsed.amountWithoutVatEur ?: ""}\n" +
                            "VAT_amount_EUR: ${parsed.vatAmountEur ?: ""}\n" +
                            "VAT_number: ${parsed.vatNumber ?: ""}\n" +
                            "Company_number: ${parsed.companyNumber ?: ""}"
                }
            onDone(result)
        }
    }
    
    fun runOcr(uri: Uri, excludeCompanyId: String? = null, excludeOwnCompanyNumber: String? = null, excludeOwnVatNumber: String? = null, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = recognizer.recognize(uri)
                .map { blocks ->
                    // Store blocks and URI for template learning
                    currentOcrBlocks = blocks
                    currentImageUri = uri
                    blocks
                }
                .map { blocks ->
                    // Get image dimensions
                    val imageSize = getImageSize(uri)
                    
                    // Try to identify company first
                    val lines = blocks.map { it.text }
                    val companyCandidate = CompanyRecognition.recognize(lines, excludeOwnCompanyNumber, excludeOwnVatNumber)
                    
                    // Try multiple keys in order of preference: company number, company name, VAT number
                    // This ensures we find the template even if company recognition varies between invoices
                    val possibleKeys = listOfNotNull(
                        companyCandidate.companyNumber,
                        companyCandidate.companyName,
                        companyCandidate.vatNumber
                    ).map { normalizeCompanyKey(it) }.filterNotNull().distinct()
                    
                    Timber.d("Company recognition - companyNumber: '${companyCandidate.companyNumber}', companyName: '${companyCandidate.companyName}', vatNumber: '${companyCandidate.vatNumber}'")
                    Timber.d("Normalized keys to try: $possibleKeys")
                    
                    // Try to load template for this company using multiple possible keys
                    var template = emptyList<FieldRegion>()
                    var matchedKey: String? = null
                    
                    for (key in possibleKeys) {
                        val loaded = templateStore.loadTemplate(key)
                        if (loaded.isNotEmpty()) {
                            template = loaded
                            matchedKey = key
                            Timber.d("Template found for key '$key': ${loaded.size} regions")
                            break
                        } else {
                            Timber.d("No template found for key '$key'")
                        }
                    }
                    
                    if (template.isEmpty() && possibleKeys.isNotEmpty()) {
                        Timber.d("No template found for any of the keys: $possibleKeys")
                    } else if (possibleKeys.isEmpty()) {
                        Timber.d("No company key available, skipping template lookup")
                    }
                    
                    // Parse using template if available, otherwise use keyword matching
                    val parsed = if (template.isNotEmpty() && imageSize != null) {
                        Timber.d("Using template for company key '$matchedKey' (${template.size} regions, image: ${imageSize.width}x${imageSize.height})")
                        InvoiceParser.parseWithTemplate(blocks, imageSize.width, imageSize.height, template)
                    } else {
                        if (template.isEmpty()) {
                            Timber.d("Using keyword matching (no template found for keys: $possibleKeys)")
                        } else {
                            Timber.d("Using keyword matching (no image size available)")
                        }
                        Timber.d("Parsing ${lines.size} lines with InvoiceParser.parse")
                        val parsedResult = InvoiceParser.parse(lines, excludeOwnCompanyNumber, excludeOwnVatNumber)
                        Timber.d("Parsed result - InvoiceID: '${parsedResult.invoiceId}', Date: '${parsedResult.date}', " +
                                "Company: '${parsedResult.companyName}', AmountNoVat: '${parsedResult.amountWithoutVatEur}', " +
                                "VatAmount: '${parsedResult.vatAmountEur}', VatNumber: '${parsedResult.vatNumber}', " +
                                "CompanyNumber: '${parsedResult.companyNumber}'")
                        parsedResult
                    }
                    
                    // Normalize VAT numbers (remove spaces) for consistent comparison
                    val normalizedParsedVat = parsed.vatNumber?.replace(" ", "")?.uppercase()
                    
                    // ALWAYS look up company from database when VAT or company number is found
                    // This ensures we get the correct company name and validates VAT/company number match
                    // VAT number is more reliable - if we have VAT, ONLY use VAT for lookup (don't pass company number)
                    val lookupVatNumber = normalizedParsedVat?.takeIf { it.isNotBlank() }
                    val lookupCompanyNumber = if (lookupVatNumber == null) {
                        parsed.companyNumber?.takeIf { it.isNotBlank() }
                    } else {
                        null // Don't pass company number if we have VAT - VAT is more reliable
                    }
                    
                    val finalParsed = if (lookupCompanyNumber != null || lookupVatNumber != null) {
                        try {
                            Timber.d("Local OCR - Looking up company in database - CompanyName: '${parsed.companyName}', CompanyNumber: '${parsed.companyNumber}', VatNumber: '${parsed.vatNumber}', NormalizedVatNumber: '$normalizedParsedVat', LookupCompanyNumber: '$lookupCompanyNumber', LookupVatNumber: '$lookupVatNumber', ExcludingOwnCompany: '$excludeCompanyId'")
                            val companyFromDb = repo.findCompanyByNumberOrVat(lookupCompanyNumber, lookupVatNumber, excludeCompanyId = excludeCompanyId)
                            if (companyFromDb != null) {
                                Timber.d("Local OCR - Found company in database: '${companyFromDb.company_name}', CompanyNumber: '${companyFromDb.company_number}', VatNumber: '${companyFromDb.vat_number}'")
                                // CRITICAL: Always use BOTH VAT and company number from database to ensure they match
                                // Never mix extracted values with database values - they must be a pair
                                val dbVatNumber = companyFromDb.vat_number?.replace(" ", "")?.uppercase()
                                val dbCompanyNumber = companyFromDb.company_number
                                parsed.copy(
                                    companyName = companyFromDb.company_name ?: parsed.companyName,
                                    companyNumber = dbCompanyNumber, // Always use DB value
                                    vatNumber = dbVatNumber // Always use DB value
                                )
                            } else {
                                Timber.d("Local OCR - No company found in database for CompanyNumber: '$lookupCompanyNumber', VatNumber: '$lookupVatNumber'")
                                // If company name is invalid and not found in DB, set to null so it can be filled manually
                                if (isInvalidCompanyName(parsed.companyName)) {
                                    Timber.d("Local OCR - Company name '${parsed.companyName}' is invalid, setting to null")
                                    parsed.copy(
                                        companyName = null,
                                        vatNumber = normalizedParsedVat
                                    )
                                } else {
                                    parsed.copy(vatNumber = normalizedParsedVat)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to look up company from database")
                            parsed.copy(vatNumber = normalizedParsedVat)
                        }
                    } else {
                        Timber.d("Local OCR - No VAT or company number found, skipping database lookup")
                        parsed
                    }
                    
                    // Format the result string
                    "Invoice_ID: ${finalParsed.invoiceId ?: ""}\n" +
                            "Date: ${finalParsed.date ?: ""}\n" +
                            "Company_name: ${finalParsed.companyName ?: ""}\n" +
                            "Amount_without_VAT_EUR: ${finalParsed.amountWithoutVatEur ?: ""}\n" +
                            "VAT_amount_EUR: ${finalParsed.vatAmountEur ?: ""}\n" +
                            "VAT_number: ${finalParsed.vatNumber ?: ""}\n" +
                            "Company_number: ${finalParsed.companyNumber ?: ""}"
                }
            onDone(result)
        }
    }
    
    /**
     * Process invoice using Azure Document Intelligence (online, more accurate).
     * Falls back to local OCR if Document AI fails or is not configured.
     * @param firstPassCompanyNumber Company number from first pass (more reliable than Azure extraction)
     * @param firstPassVatNumber VAT number from first pass (more reliable than Azure extraction)
     * @param onMethodUsed Optional callback to notify which OCR method was used ("Azure" or "Local")
     */
    fun runOcrWithDocumentAi(
        uri: Uri, 
        firstPassCompanyNumber: String? = null,
        firstPassVatNumber: String? = null,
        excludeCompanyId: String? = null,
        excludeOwnCompanyNumber: String? = null,
        excludeOwnVatNumber: String? = null,
        onDone: (Result<String>) -> Unit, 
        onMethodUsed: ((String) -> Unit)? = null,
        initialDelayMs: Long = 0 // Delay before starting (0 for current invoice, staggered for background)
    ) {
        viewModelScope.launch {
            val result = try {
                Timber.d("Starting Azure Document Intelligence processing for invoice")
                
                // Only add delay if specified (for background processing to prevent rate limiting)
                if (initialDelayMs > 0) {
                    kotlinx.coroutines.delay(initialDelayMs)
                }
                
                // Try Azure Document Intelligence first
                val parsed = documentAiService.processInvoice(uri, excludeOwnCompanyNumber, excludeOwnVatNumber)
                
                if (parsed != null) {
                    Timber.d("Azure Document Intelligence processing successful")
                    onMethodUsed?.invoke("Azure") // Notify that Azure Document Intelligence was used
                    
                    // Store blocks for template learning (create dummy blocks from text)
                    currentOcrBlocks = parsed.lines.mapIndexed { index, text ->
                        OcrBlock(
                            text = text,
                            boundingBox = android.graphics.Rect(0, index * 20, 100, (index + 1) * 20)
                        )
                    }
                    currentImageUri = uri
                    
                    // Normalize VAT numbers (remove spaces) for consistent comparison
                    val normalizedFirstPassVat = firstPassVatNumber?.replace(" ", "")?.uppercase()
                    val normalizedParsedVat = parsed.vatNumber?.replace(" ", "")?.uppercase()
                    
                    // VAT number is more reliable - prioritize it for lookup
                    // Use first pass values if available (more reliable), otherwise use Azure extracted values
                    // CRITICAL: If we have VAT number, ONLY use VAT for lookup (don't pass company number)
                    // This ensures we get the correct matching pair from database
                    val lookupVatNumber = normalizedFirstPassVat?.takeIf { it.isNotBlank() } 
                        ?: normalizedParsedVat?.takeIf { it.isNotBlank() }
                    // Only use company number for lookup if we DON'T have VAT number
                    val lookupCompanyNumber = if (lookupVatNumber == null) {
                        firstPassCompanyNumber?.takeIf { it.isNotBlank() } 
                            ?: parsed.companyNumber?.takeIf { it.isNotBlank() }
                    } else {
                        null // Don't pass company number if we have VAT - VAT is more reliable
                    }
                    
                    // ALWAYS look up company from database when VAT or company number is found
                    // This ensures we get the correct company name and validates VAT/company number match
                    val finalParsed = if (lookupCompanyNumber != null || lookupVatNumber != null) {
                        try {
                            Timber.d("Azure - Looking up company in database - FirstPassCompanyNumber: '$firstPassCompanyNumber', FirstPassVatNumber: '$firstPassVatNumber', ParsedCompanyNumber: '${parsed.companyNumber}', ParsedVatNumber: '${parsed.vatNumber}', LookupCompanyNumber: '$lookupCompanyNumber', LookupVatNumber: '$lookupVatNumber', ExcludingOwnCompany: '$excludeCompanyId'")
                            val companyFromDb = repo.findCompanyByNumberOrVat(
                                lookupCompanyNumber, 
                                lookupVatNumber,
                                excludeCompanyId = excludeCompanyId
                            )
                            if (companyFromDb != null) {
                                Timber.d("Azure - Found company in database: '${companyFromDb.company_name}', CompanyNumber: '${companyFromDb.company_number}', VatNumber: '${companyFromDb.vat_number}'")
                                // CRITICAL: Always use BOTH VAT and company number from database to ensure they match
                                // Never mix extracted values with database values - they must be a pair
                                val dbVatNumber = companyFromDb.vat_number?.replace(" ", "")?.uppercase()
                                val dbCompanyNumber = companyFromDb.company_number
                                parsed.copy(
                                    companyName = companyFromDb.company_name ?: parsed.companyName,
                                    companyNumber = dbCompanyNumber, // Always use DB value
                                    vatNumber = dbVatNumber // Always use DB value
                                )
                            } else {
                                Timber.d("Azure - No company found in database for CompanyNumber: '$lookupCompanyNumber', VatNumber: '$lookupVatNumber'")
                                // If both VAT and company number are found but don't match any company, 
                                // this might indicate an error - but we'll keep the extracted values
                                // Keep the extracted company name if it's valid (contains UAB, MB, IĮ, AB, etc.)
                                // Only set to null if it's invalid (like "SASKAITA", "PARDAVEJAS", etc.)
                                val normalizedVat = lookupVatNumber ?: normalizedParsedVat
                                if (isInvalidCompanyName(parsed.companyName)) {
                                    Timber.d("Azure - Company name '${parsed.companyName}' is invalid, setting to null")
                                    parsed.copy(
                                        companyName = null,
                                        vatNumber = normalizedVat
                                    )
                                } else {
                                    // Keep the extracted company name even if not in database
                                    Timber.d("Azure - Keeping extracted company name '${parsed.companyName}' (not in database but valid)")
                                    parsed.copy(vatNumber = normalizedVat)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to look up company from database")
                            parsed
                        }
                    } else {
                        Timber.d("Azure - No VAT or company number found, skipping database lookup")
                        parsed
                    }
                    
                    // Format result
                    "Invoice_ID: ${finalParsed.invoiceId ?: ""}\n" +
                    "Date: ${finalParsed.date ?: ""}\n" +
                    "Company_name: ${finalParsed.companyName ?: ""}\n" +
                    "Amount_without_VAT_EUR: ${finalParsed.amountWithoutVatEur ?: ""}\n" +
                    "VAT_amount_EUR: ${finalParsed.vatAmountEur ?: ""}\n" +
                    "VAT_number: ${finalParsed.vatNumber ?: ""}\n" +
                    "Company_number: ${finalParsed.companyNumber ?: ""}"
                } else {
                    Timber.w("Azure Document Intelligence processing returned null, falling back to local OCR")
                    onMethodUsed?.invoke("Local") // Notify that local OCR is being used
                    // Fallback to local OCR
                    runOcr(uri, excludeCompanyId, excludeOwnCompanyNumber, excludeOwnVatNumber) { localResult ->
                        localResult.onSuccess { _ ->
                            Timber.d("Local OCR fallback successful, extracted fields")
                        }.onFailure { error ->
                            Timber.e(error, "Local OCR fallback also failed")
                        }
                        onDone(localResult)
                    }
                    return@launch
                }
            } catch (e: Exception) {
                Timber.e(e, "Azure Document Intelligence processing failed with exception, falling back to local OCR")
                onMethodUsed?.invoke("Local") // Notify that local OCR is being used
                // Fallback to local OCR on error
                runOcr(uri, excludeCompanyId, excludeOwnCompanyNumber, excludeOwnVatNumber) { localResult ->
                    localResult.onSuccess { _ ->
                        Timber.d("Local OCR fallback successful after exception, extracted fields")
                    }.onFailure { error ->
                        Timber.e(error, "Local OCR fallback also failed after exception")
                    }
                    onDone(localResult)
                }
                return@launch
            }
            
            onDone(Result.success(result))
        }
    }
    
    private fun isInvalidCompanyName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val lower = name.lowercase().trim()
        
        // Explicitly reject common invoice labels
        if (lower == "saskaita" || lower == "faktura" || lower == "invoice" ||
            lower.matches(Regex("^(pardavejas|tiekejas|gavejas|pirkėjas|seller|buyer|recipient|supplier)$"))) {
            return true
        }
        
        // Reject if it contains invoice-related words
        if (lower.contains("saskaita") || lower.contains("faktura") || lower.contains("invoice")) {
            return true
        }
        
        // Must have reasonable length
        if (name.length < 5) {
            return true
        }
        
        // Must contain Lithuanian company type suffix (UAB, MB, IĮ, AB, etc.)
        val hasCompanyType = lower.contains("uab") || lower.contains("mb") || lower.contains("iį") || 
                            lower.contains("ab") || lower.contains("ltd") || lower.contains("as") || 
                            lower.contains("sp") || lower.contains("oy")
        
        return !hasCompanyType
    }
    
    /**
     * Normalize company key for consistent template storage/retrieval.
     * Removes whitespace, converts to lowercase, and removes special characters.
     */
    private fun normalizeCompanyKey(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return key.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
    }
    
    private suspend fun getImageSize(uri: Uri): ImageSize? {
        return try {
            val stream: InputStream? = getApplication<Application>().contentResolver.openInputStream(uri)
            stream?.use {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(it, null, options)
                ImageSize(options.outWidth, options.outHeight)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get image size")
            null
        }
    }
    
    private data class ImageSize(val width: Int, val height: Int)
    fun confirm(invoice: InvoiceRecord, company: CompanyRecord, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                if (client == null) {
                    Timber.e("Supabase client is null. Check your Supabase configuration in gradle.properties")
                    onResult(false)
                    return@launch
                }
                // Only upsert company if it has required fields
                if (!company.company_name.isNullOrBlank() || !company.company_number.isNullOrBlank()) {
                    repo.upsertCompany(company)
                }
                repo.insertInvoice(invoice)
                Timber.d("Invoice and company saved successfully")
                
                // Learn template from confirmed values
                val imageUri = currentImageUri
                if (imageUri != null && currentOcrBlocks.isNotEmpty()) {
                    // Save template with multiple keys (company number, company name, VAT number)
                    // This ensures the template can be found even if company recognition varies between invoices
                    val possibleKeys = listOfNotNull(
                        company.company_number,
                        company.company_name,
                        company.vat_number
                    ).map { normalizeCompanyKey(it) }.filterNotNull().distinct()
                    
                    Timber.d("Learning template - company_number: '${company.company_number}', company_name: '${company.company_name}', vat_number: '${company.vat_number}'")
                    Timber.d("Normalized keys for saving: $possibleKeys")
                    
                    if (possibleKeys.isNotEmpty()) {
                        val confirmedFields = mapOf(
                            "Invoice_ID" to invoice.invoice_id,
                            "Date" to invoice.date,
                            "Company_name" to invoice.company_name,
                            "Amount_without_VAT_EUR" to invoice.amount_without_vat_eur?.toString(),
                            "VAT_amount_EUR" to invoice.vat_amount_eur?.toString(),
                            "VAT_number" to invoice.vat_number,
                            "Company_number" to invoice.company_number
                        )
                        // Save template with all available keys so it can be found regardless of recognition method
                        templateLearner.learnTemplate(imageUri, currentOcrBlocks, confirmedFields, possibleKeys)
                    } else {
                        Timber.w("Cannot learn template: no valid company keys available")
                    }
                } else {
                    Timber.d("Skipping template learning: imageUri=${imageUri != null}, blocks=${currentOcrBlocks.isNotEmpty()}")
                }
                
                onResult(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save invoice: ${e.message}")
                // Log the full stack trace for debugging
                e.printStackTrace()
                onResult(false)
            }
        }
    }
}

private fun List<com.vitol.inv3.ocr.OcrBlock>.joinTogether(): String =
    joinToString(separator = "\n") { it.text }

@Composable
private fun FieldEditor(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OptionalFieldEditor(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") isOptional: Boolean, // Reserved for future styling
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { 
            Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        },
        modifier = modifier
    )
}


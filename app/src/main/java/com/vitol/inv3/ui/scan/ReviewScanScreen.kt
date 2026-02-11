package com.vitol.inv3.ui.scan

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vitol.inv3.Routes
import com.vitol.inv3.ui.subscription.SubscriptionViewModel
import com.vitol.inv3.data.local.getActiveOwnCompanyIdFlow
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ocr.AzureDocumentIntelligenceService
import com.vitol.inv3.ocr.ParsedInvoice
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class ProcessingState(
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val parsedInvoice: ParsedInvoice? = null,
    val errorMessage: String? = null,
    val ownCompanyNumber: String? = null,
    val ownCompanyVatNumber: String? = null,
    val ownCompanyName: String? = null
)

data class MergedFormData(
    val invoiceId: String = "",
    val date: String = "",
    val companyName: String = "",
    val amountWithoutVat: String = "",
    val vatAmount: String = "",
    val vatNumber: String = "",
    val companyNumber: String = "",
    val vatRate: String = "",
    val taxCode: String = ""
)

@HiltViewModel
class ReviewScanViewModel @Inject constructor(
    private val repo: SupabaseRepository
) : androidx.lifecycle.ViewModel() {

    private val _processingState = MutableStateFlow(ProcessingState())
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()
    
    private var mergedFormData: MergedFormData? = null
    
    fun setMergedFormData(data: MergedFormData) {
        mergedFormData = data
    }
    
    fun clearMergedFormData() {
        mergedFormData = null
    }
    
    fun getMergedFormData(): MergedFormData? {
        return mergedFormData
    }

    suspend fun getOwnCompany(companyId: String?): CompanyRecord? {
        if (companyId == null) return null
        return try {
            repo.getCompanyById(companyId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get own company")
            null
        }
    }

    suspend fun findPartnerCompany(vatNumber: String?, companyNumber: String?): InvoiceRecord? {
        return try {
            repo.findInvoiceByVatOrCompanyNumber(vatNumber, companyNumber)
        } catch (e: Exception) {
            Timber.e(e, "Failed to find partner company")
            null
        }
    }
    
    suspend fun loadInvoice(invoiceId: String): InvoiceRecord? {
        return try {
            val allInvoices = repo.getAllInvoices()
            allInvoices.find { it.id == invoiceId }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load invoice")
            null
        }
    }
    
    fun updateInvoice(invoice: InvoiceRecord, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        viewModelScope.launch {
            try {
                repo.updateInvoice(invoice)
                onSuccess()
            } catch (e: Exception) {
                Timber.e(e, "Failed to update invoice")
                onError(e)
            }
        }
    }

    fun processInvoice(
        context: Context,
        imageUri: Uri,
        ownCompanyId: String?,
        invoiceType: String
    ) {
        viewModelScope.launch {
            _processingState.value = _processingState.value.copy(
                isLoading = true,
                isProcessing = false,
                errorMessage = null
            )

            try {
                // Get own company info for exclusion
                val ownCompany = if (ownCompanyId != null) {
                    getOwnCompany(ownCompanyId)
                } else {
                    null
                }

                val ownCompanyNumber = ownCompany?.company_number
                val ownCompanyVatNumber = ownCompany?.vat_number
                val ownCompanyName = ownCompany?.company_name

                Timber.d("Processing invoice with own company exclusion - Number: $ownCompanyNumber, VAT: $ownCompanyVatNumber, Name: $ownCompanyName")

                // Process invoice with Azure Document Intelligence
                _processingState.value = _processingState.value.copy(
                    isProcessing = true,
                    ownCompanyNumber = ownCompanyNumber,
                    ownCompanyVatNumber = ownCompanyVatNumber,
                    ownCompanyName = ownCompanyName
                )

                val azureService = AzureDocumentIntelligenceService(context)
                val parsedInvoice = azureService.processInvoice(
                    imageUri = imageUri,
                    excludeOwnCompanyNumber = ownCompanyNumber,
                    excludeOwnVatNumber = ownCompanyVatNumber,
                    excludeOwnCompanyName = ownCompanyName,
                    invoiceType = invoiceType
                )

                val noUsefulData = parsedInvoice != null && parsedInvoice.lines.isEmpty() &&
                    parsedInvoice.invoiceId.isNullOrBlank() && parsedInvoice.companyName.isNullOrBlank() &&
                    parsedInvoice.amountWithoutVatEur.isNullOrBlank() && parsedInvoice.vatAmountEur.isNullOrBlank() &&
                    parsedInvoice.vatNumber.isNullOrBlank() && parsedInvoice.companyNumber.isNullOrBlank()
                _processingState.value = _processingState.value.copy(
                    isLoading = false,
                    isProcessing = false,
                    parsedInvoice = parsedInvoice,
                    errorMessage = when {
                        parsedInvoice == null -> "Failed to extract invoice data. Please try again."
                        noUsefulData -> (parsedInvoice.extractionMessage ?: "No data could be extracted. Try a clearer or larger image.")
                        else -> null
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to process invoice")
                _processingState.value = _processingState.value.copy(
                    isLoading = false,
                    isProcessing = false,
                    errorMessage = "Error processing invoice: ${e.message}"
                )
            }
        }
    }

    fun saveInvoice(invoice: InvoiceRecord, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        viewModelScope.launch {
            try {
                repo.insertInvoice(invoice)
                onSuccess()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save invoice")
                onError(e)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScanScreen(
    imageUri: Uri? = null,
    navController: NavController?,
    invoiceType: String = "P",
    invoiceId: String? = null, // For editing existing invoice
    fromImport: Boolean = false,
    viewModel: ReviewScanViewModel = hiltViewModel(),
    subscriptionViewModel: SubscriptionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val importSessionViewModel: ImportSessionViewModel? = if (fromImport) {
        hiltViewModel(viewModelStoreOwner = context as ComponentActivity)
    } else null
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCancelImportDialog by remember { mutableStateOf(false) }
    
    // Own company info for exclusion
    var ownCompanyNumber by remember { mutableStateOf<String?>(null) }
    var ownCompanyVatNumber by remember { mutableStateOf<String?>(null) }
    
    // Form fields
    var invoiceIdField by remember { mutableStateOf("") }
    var dateField by remember { mutableStateOf("") }
    var companyNameField by remember { mutableStateOf("") }
    var amountWithoutVatField by remember { mutableStateOf("") }
    var vatAmountField by remember { mutableStateOf("") }
    var vatNumberField by remember { mutableStateOf("") }
    var companyNumberField by remember { mutableStateOf("") }
    var vatRateField by remember { mutableStateOf("") }
    var taxCodeField by remember { mutableStateOf("PVM1") }
    
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.parse(dateField)?.time
        } catch (e: Exception) {
            null
        } ?: Calendar.getInstance().timeInMillis
    )
    
    // Get active own company ID
    val activeCompanyIdFlow = remember { context.getActiveOwnCompanyIdFlow() }
    val activeCompanyId by activeCompanyIdFlow.collectAsState(initial = null)
    // Wait for company id to be resolved before first processInvoice (same as camera: single call with correct exclusion)
    var companyIdResolved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(500) { activeCompanyIdFlow.first() }
        companyIdResolved = true
    }
    
    // Observe processing state from ViewModel
    val processingState by viewModel.processingState.collectAsState()
    
    // Load existing invoice if invoiceId is provided (edit mode)
    LaunchedEffect(invoiceId) {
        if (invoiceId != null && invoiceId.isNotBlank()) {
            isLoading = true
            try {
                val existingInvoice = viewModel.loadInvoice(invoiceId)
                if (existingInvoice != null) {
                    invoiceIdField = existingInvoice.invoice_id ?: ""
                    dateField = existingInvoice.date ?: ""
                    companyNameField = existingInvoice.company_name ?: ""
                    amountWithoutVatField = existingInvoice.amount_without_vat_eur?.toString() ?: ""
                    vatAmountField = existingInvoice.vat_amount_eur?.toString() ?: ""
                    vatNumberField = existingInvoice.vat_number ?: ""
                    companyNumberField = existingInvoice.company_number ?: ""
                    vatRateField = existingInvoice.vat_rate?.toString() ?: ""
                    taxCodeField = existingInvoice.tax_code ?: "PVM1"
                    isLoading = false
                } else {
                    errorMessage = "Invoice not found"
                    isLoading = false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load invoice")
                errorMessage = "Failed to load invoice: ${e.message}"
                isLoading = false
            }
        }
    }
    
    // Camera only: extract from URI when we have imageUri
    LaunchedEffect(imageUri, companyIdResolved) {
        if (invoiceId == null && imageUri != null && companyIdResolved) {
            viewModel.processInvoice(context, imageUri, activeCompanyId, invoiceType)
        }
    }
    
    // Track if we've already processed this invoice to avoid duplicate lookups
    var hasProcessedInvoice by remember { mutableStateOf(false) }
    
    // Update local state from ViewModel processing state
    LaunchedEffect(processingState) {
        isLoading = processingState.isLoading
        isProcessing = processingState.isProcessing
        errorMessage = processingState.errorMessage
        ownCompanyNumber = processingState.ownCompanyNumber
        ownCompanyVatNumber = processingState.ownCompanyVatNumber
        
        // Fill form fields when invoice is parsed (only once)
        processingState.parsedInvoice?.let { parsedInvoice ->
            if (!hasProcessedInvoice) {
                val mergedData = viewModel.getMergedFormData()
                val isMerge = mergedData != null
                
                // If merging, only fill empty fields; otherwise fill all fields
                invoiceIdField = if (isMerge && invoiceIdField.isNotBlank()) invoiceIdField else (parsedInvoice.invoiceId ?: "")
                dateField = if (isMerge && dateField.isNotBlank()) dateField else (parsedInvoice.date ?: "")
                companyNameField = if (isMerge && companyNameField.isNotBlank()) companyNameField else (parsedInvoice.companyName ?: "")
                amountWithoutVatField = if (isMerge && amountWithoutVatField.isNotBlank()) amountWithoutVatField else (parsedInvoice.amountWithoutVatEur ?: "")
                vatAmountField = if (isMerge && vatAmountField.isNotBlank()) vatAmountField else (parsedInvoice.vatAmountEur ?: "")
                vatNumberField = if (isMerge && vatNumberField.isNotBlank()) vatNumberField else (parsedInvoice.vatNumber ?: "")
                companyNumberField = if (isMerge && companyNumberField.isNotBlank()) companyNumberField else (parsedInvoice.companyNumber ?: "")
                vatRateField = if (isMerge && vatRateField.isNotBlank()) vatRateField else (parsedInvoice.vatRate ?: "")
                
                // If merging, restore other fields from merged data
                if (isMerge && mergedData != null) {
                    taxCodeField = mergedData.taxCode
                }
                
                // Extract VAT code from invoice text if available
                val invoiceText = parsedInvoice.lines.joinToString(" ")
                val detectedTaxCode = com.vitol.inv3.export.TaxCodeDeterminer.determineTaxCode(
                    vatRateField.toDoubleOrNull(),
                    invoiceText
                )
                if (detectedTaxCode.isNotBlank() && taxCodeField == "PVM1") {
                    taxCodeField = detectedTaxCode
                }
            
            Timber.d("Extracted invoice data - Company: $companyNameField, VAT: $vatNumberField, Company Number: $companyNumberField, VAT Rate: $vatRateField (merge: $isMerge)")
            
            // CRITICAL: Exclude own company parameters - NEVER fill them
            if (ownCompanyNumber != null && companyNumberField == ownCompanyNumber) {
                Timber.d("Excluding own company number: $companyNumberField")
                companyNumberField = ""
            }
            if (ownCompanyVatNumber != null && vatNumberField.equals(ownCompanyVatNumber, ignoreCase = true)) {
                Timber.d("Excluding own VAT number: $vatNumberField")
                vatNumberField = ""
            }
            if (processingState.ownCompanyName != null && companyNameField.equals(processingState.ownCompanyName, ignoreCase = true)) {
                Timber.d("Excluding own company name: $companyNameField")
                companyNameField = ""
            }
            
            // Lookup partner company from existing invoices
            val partnerInvoice = if (!vatNumberField.isBlank() || !companyNumberField.isBlank()) {
                viewModel.findPartnerCompany(
                    vatNumber = vatNumberField.takeIf { it.isNotBlank() },
                    companyNumber = companyNumberField.takeIf { it.isNotBlank() }
                )
            } else {
                null
            }
            
            // Auto-fill partner company parameters if found
            if (partnerInvoice != null) {
                Timber.d("Found partner company from existing invoice: ${partnerInvoice.company_name}")
                if (companyNameField.isBlank() && !partnerInvoice.company_name.isNullOrBlank()) {
                    companyNameField = partnerInvoice.company_name
                }
                if (vatNumberField.isBlank() && !partnerInvoice.vat_number.isNullOrBlank()) {
                    vatNumberField = partnerInvoice.vat_number
                }
                if (companyNumberField.isBlank() && !partnerInvoice.company_number.isNullOrBlank()) {
                    companyNumberField = partnerInvoice.company_number
                }
            }
            
                hasProcessedInvoice = true
                // Clear merged data after processing
                if (isMerge) {
                    viewModel.clearMergedFormData()
                }
            }
        }
    }
    
    // Reset processed flag when imageUri changes (camera)
    LaunchedEffect(imageUri) {
        hasProcessedInvoice = false
    }

    // Import flow: fill form from pre-extracted parsedInvoices (no processInvoice call)
    val parsedInvoices by importSessionViewModel?.parsedInvoices?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
    val importCurrentIndex by importSessionViewModel?.currentIndex?.collectAsState(initial = 0) ?: remember { mutableStateOf(0) }
    val extractionState by importSessionViewModel?.extractionState?.collectAsState(initial = ImportExtractionState.Idle) ?: remember { mutableStateOf<ImportExtractionState>(ImportExtractionState.Idle) }
    val isWaitingForNextInvoice = fromImport && importSessionViewModel != null && importSessionViewModel.getCurrentParsedInvoice() == null
    LaunchedEffect(fromImport, parsedInvoices, importCurrentIndex, activeCompanyId) {
        if (!fromImport || importSessionViewModel == null || invoiceId != null) return@LaunchedEffect
        val parsed = importSessionViewModel.getCurrentParsedInvoice() ?: return@LaunchedEffect
        val ownCompany = activeCompanyId?.let { viewModel.getOwnCompany(it) }
        invoiceIdField = parsed.invoiceId ?: ""
        dateField = parsed.date ?: ""
        companyNameField = parsed.companyName ?: ""
        amountWithoutVatField = parsed.amountWithoutVatEur ?: ""
        vatAmountField = parsed.vatAmountEur ?: ""
        vatNumberField = parsed.vatNumber ?: ""
        companyNumberField = parsed.companyNumber ?: ""
        vatRateField = parsed.vatRate ?: ""
        val invoiceText = parsed.lines.joinToString(" ")
        val detectedTaxCode = com.vitol.inv3.export.TaxCodeDeterminer.determineTaxCode(
            vatRateField.toDoubleOrNull(),
            invoiceText
        )
        if (detectedTaxCode.isNotBlank()) taxCodeField = detectedTaxCode
        if (ownCompany != null) {
            if (companyNumberField == ownCompany.company_number) companyNumberField = ""
            if (vatNumberField.equals(ownCompany.vat_number, ignoreCase = true)) vatNumberField = ""
            if (companyNameField.equals(ownCompany.company_name, ignoreCase = true)) companyNameField = ""
        }
        val partnerInvoice = viewModel.findPartnerCompany(
            vatNumber = vatNumberField.takeIf { it.isNotBlank() },
            companyNumber = companyNumberField.takeIf { it.isNotBlank() }
        )
        if (partnerInvoice != null) {
            if (companyNameField.isBlank() && !partnerInvoice.company_name.isNullOrBlank()) companyNameField = partnerInvoice.company_name
            if (vatNumberField.isBlank() && !partnerInvoice.vat_number.isNullOrBlank()) vatNumberField = partnerInvoice.vat_number
            if (companyNumberField.isBlank() && !partnerInvoice.company_number.isNullOrBlank()) companyNumberField = partnerInvoice.company_number
        }
        errorMessage = parsed.extractionMessage
    }
    
    Scaffold(
        topBar = {
            val currentIndex by importSessionViewModel?.currentIndex?.collectAsState(initial = 0) ?: remember { mutableStateOf(0) }
            val totalCount = importSessionViewModel?.totalCount ?: 1
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                invoiceId != null -> "Edit Invoice"
                                fromImport && totalCount > 1 -> "Invoice ${currentIndex + 1} of $totalCount"
                                else -> "Review Scanned Invoice"
                            }
                        )
                        if (fromImport && extractionState is ImportExtractionState.Extracting) {
                            val state = extractionState as ImportExtractionState.Extracting
                            Text(
                                "${state.current} of ${state.total} analyzing…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (fromImport) {
                            showCancelImportDialog = true
                        } else {
                            navController?.popBackStack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (fromImport && (importSessionViewModel?.hasPrevious == true)) {
                        TextButton(onClick = { importSessionViewModel?.advanceToPrevious() }) {
                            Text("Previous")
                        }
                    }
                    Text(
                        text = if (invoiceType == "S") "Sale" else "Purchase",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        if (((isLoading || isProcessing) && invoiceId == null && !fromImport) || isWaitingForNextInvoice) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.padding(16.dp))
                    Text(
                        text = when {
                            isWaitingForNextInvoice -> {
                                val total = importSessionViewModel?.totalCount ?: 1
                                "Extracting invoice ${importCurrentIndex + 1} of $total…"
                            }
                            isProcessing -> "Processing invoice..."
                            else -> "Loading..."
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                
                // Get color scheme once
                val colorScheme = MaterialTheme.colorScheme
                
                // Helper function to check if field is required and empty
                val isRequiredAndEmpty: (String) -> Boolean = { it.isBlank() }
                
                // Helper function to get border color for required fields
                val getBorderColor: (Boolean) -> androidx.compose.ui.graphics.Color = { isEmpty ->
                    if (isEmpty) {
                        colorScheme.error
                    } else {
                        colorScheme.outline
                    }
                }
                
                OutlinedTextField(
                    value = invoiceIdField,
                    onValueChange = { invoiceIdField = it },
                    label = { Text("Invoice ID") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(invoiceIdField))
                    )
                )
                
                OutlinedTextField(
                    value = dateField,
                    onValueChange = { dateField = it },
                    label = { Text("Date") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = "Pick date"
                            )
                        }
                    },
                    readOnly = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(dateField))
                    )
                )
                
                OutlinedTextField(
                    value = companyNameField,
                    onValueChange = { companyNameField = it },
                    label = { Text("Company Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(companyNameField))
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = amountWithoutVatField,
                        onValueChange = { amountWithoutVatField = it },
                        label = { Text("Amount w/o VAT") },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(amountWithoutVatField))
                        )
                    )
                    
                    OutlinedTextField(
                        value = vatAmountField,
                        onValueChange = { vatAmountField = it },
                        label = { Text("VAT Amount") },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(vatAmountField))
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = vatNumberField,
                        onValueChange = { vatNumberField = it },
                        label = { Text("VAT Number") },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(vatNumberField))
                        )
                    )
                    
                    OutlinedTextField(
                        value = companyNumberField,
                        onValueChange = { companyNumberField = it },
                        label = { Text("Company No.") },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(companyNumberField))
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = vatRateField,
                        onValueChange = { vatRateField = it },
                        label = { Text("VAT Rate %") },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = getBorderColor(isRequiredAndEmpty(vatRateField))
                        )
                    )
                    
                    OutlinedTextField(
                        value = taxCodeField,
                        onValueChange = { taxCodeField = it },
                        label = { Text("Tax Code") },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Action buttons
                if (fromImport && importSessionViewModel != null) {
                    // Import flow: Skip | Save and next | Cancel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCancelImportDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        OutlinedButton(
                            onClick = {
                                if (importSessionViewModel.hasNext) {
                                    importSessionViewModel.advanceToNextIndex()
                                } else {
                                    importSessionViewModel.clear()
                                    navController?.navigate(Routes.Home) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                    scope.launch { snackbarHostState.showSnackbar("Import complete") }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (importSessionViewModel.hasNext) "Skip" else "Skip and finish")
                        }
                        Button(
                            onClick = {
                                isSaving = true
                                errorMessage = null
                                val amountWithoutVat = amountWithoutVatField.replace(",", ".").toDoubleOrNull()
                                val vatAmount = vatAmountField.replace(",", ".").toDoubleOrNull()
                                val vatRate = vatRateField.replace(",", ".").toDoubleOrNull()
                                val invoice = InvoiceRecord(
                                    id = null,
                                    invoice_id = invoiceIdField.takeIf { it.isNotBlank() },
                                    date = dateField.takeIf { it.isNotBlank() },
                                    company_name = companyNameField.takeIf { it.isNotBlank() },
                                    amount_without_vat_eur = amountWithoutVat,
                                    vat_amount_eur = vatAmount,
                                    vat_number = vatNumberField.takeIf { it.isNotBlank() },
                                    company_number = companyNumberField.takeIf { it.isNotBlank() },
                                    invoice_type = invoiceType.takeIf { it.isNotBlank() },
                                    vat_rate = vatRate,
                                    tax_code = taxCodeField.takeIf { it.isNotBlank() }
                                )
                                viewModel.saveInvoice(
                                    invoice = invoice,
                                    onSuccess = {
                                        isSaving = false
                                        scope.launch {
                                            subscriptionViewModel.trackPageUsage()
                                            snackbarHostState.showSnackbar("Invoice saved successfully")
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                importSessionViewModel.advanceToNext(context)
                                            }
                                            if (!importSessionViewModel.hasNext) {
                                                importSessionViewModel.clear()
                                                navController?.navigate(Routes.Home) {
                                                    popUpTo(0) { inclusive = true }
                                                }
                                                snackbarHostState.showSnackbar("All invoices saved")
                                            }
                                        }
                                    },
                                    onError = { e ->
                                        isSaving = false
                                        errorMessage = "Failed to save invoice: ${e.message}"
                                        scope.launch { snackbarHostState.showSnackbar("Failed to save invoice") }
                                    }
                                )
                            },
                            enabled = !isSaving,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(
                                if (importSessionViewModel.hasNext) "Save and next" else "Confirm and Save"
                            )
                        }
                    }
                } else {
                    // Non-import flow: Redo, Cancel, Merge, Save
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (invoiceId == null && !fromImport) {
                            OutlinedButton(
                                onClick = {
                                    if (imageUri != null) {
                                        navController?.navigate("${Routes.ScanCamera}/$invoiceType") {
                                            popUpTo("${Routes.ReviewScan}/$imageUri/$invoiceType") { inclusive = true }
                                        }
                                    } else {
                                        navController?.popBackStack()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Redo")
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        OutlinedButton(
                            onClick = {
                                if (fromImport) importSessionViewModel?.clear()
                                navController?.navigate(Routes.Home) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (invoiceId == null && imageUri != null && !fromImport) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.setMergedFormData(
                                        MergedFormData(
                                            invoiceId = invoiceIdField,
                                            date = dateField,
                                            companyName = companyNameField,
                                            amountWithoutVat = amountWithoutVatField,
                                            vatAmount = vatAmountField,
                                            vatNumber = vatNumberField,
                                            companyNumber = companyNumberField,
                                            vatRate = vatRateField,
                                            taxCode = taxCodeField
                                        )
                                    )
                                    navController?.navigate("${Routes.ScanCamera}/$invoiceType") {
                                        popUpTo("${Routes.ReviewScan}/$imageUri/$invoiceType") { inclusive = false }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Merge with next")
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Button(
                        onClick = {
                            isSaving = true
                            errorMessage = null
                            
                            // Convert amounts properly (handle comma as decimal separator)
                            val amountWithoutVat = amountWithoutVatField.replace(",", ".").toDoubleOrNull()
                            val vatAmount = vatAmountField.replace(",", ".").toDoubleOrNull()
                            val vatRate = vatRateField.replace(",", ".").toDoubleOrNull()
                            
                            val invoice = InvoiceRecord(
                                id = invoiceId, // Include id for updates
                                invoice_id = invoiceIdField.takeIf { it.isNotBlank() },
                                date = dateField.takeIf { it.isNotBlank() },
                                company_name = companyNameField.takeIf { it.isNotBlank() },
                                amount_without_vat_eur = amountWithoutVat,
                                vat_amount_eur = vatAmount,
                                vat_number = vatNumberField.takeIf { it.isNotBlank() },
                                company_number = companyNumberField.takeIf { it.isNotBlank() },
                                invoice_type = invoiceType.takeIf { it.isNotBlank() },
                                vat_rate = vatRate,
                                tax_code = taxCodeField.takeIf { it.isNotBlank() }
                            )
                            
                            if (invoiceId != null && invoiceId.isNotBlank()) {
                                // Update existing invoice
                                viewModel.updateInvoice(
                                    invoice = invoice,
                                    onSuccess = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Invoice updated successfully")
                                            navController?.popBackStack()
                                        }
                                    },
                                    onError = { e ->
                                        isSaving = false
                                        errorMessage = "Failed to update invoice: ${e.message}"
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Failed to update invoice")
                                        }
                                    }
                                )
                            } else {
                                // Save new invoice
                                val isImportFlow = fromImport && importSessionViewModel != null
                                val shouldOpenCamera = imageUri != null && !isImportFlow
                                if (shouldOpenCamera) {
                                    navController?.navigate("${Routes.ScanCamera}/$invoiceType") {
                                        popUpTo("${Routes.ReviewScan}/$imageUri/$invoiceType") { inclusive = true }
                                    }
                                }
                                
                                viewModel.saveInvoice(
                                    invoice = invoice,
                                    onSuccess = {
                                        scope.launch {
                                            subscriptionViewModel.trackPageUsage()
                                            snackbarHostState.showSnackbar("Invoice saved successfully")
                                            if (isImportFlow && importSessionViewModel != null) {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    importSessionViewModel.advanceToNext(context)
                                                }
                                                if (!importSessionViewModel.hasNext) {
                                                    importSessionViewModel.clear()
                                                    navController?.navigate(Routes.Home) {
                                                        popUpTo(0) { inclusive = true }
                                                    }
                                                    snackbarHostState.showSnackbar("All invoices saved")
                                                }
                                                // else: stay on screen; form refills from next parsed invoice
                                            }
                                        }
                                    },
                                    onError = { e ->
                                        isSaving = false
                                        errorMessage = "Failed to save invoice: ${e.message}"
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Failed to save invoice")
                                            if (shouldOpenCamera) {
                                                navController?.popBackStack()
                                            }
                                        }
                                    }
                                )
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text(
                            when {
                                invoiceId != null -> "Save Changes"
                                fromImport && (importSessionViewModel?.hasNext == true) -> "Save and next"
                                else -> "Confirm and Save"
                            }
                        )
                    }
                }
            }
        }
        }
        
        // Date picker dialog
        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                dateField = sdf.format(millis)
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

        // Cancel import confirmation
        if (showCancelImportDialog) {
            AlertDialog(
                onDismissRequest = { showCancelImportDialog = false },
                title = { Text("Discard import?") },
                text = { Text("Unsaved invoices will be lost.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCancelImportDialog = false
                            importSessionViewModel?.clear()
                            navController?.navigate(Routes.Home) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    ) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelImportDialog = false }) {
                        Text("Stay")
                    }
                }
            )
        }
    }
}


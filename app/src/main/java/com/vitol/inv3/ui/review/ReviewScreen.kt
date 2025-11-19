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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import android.graphics.BitmapFactory
import java.io.InputStream
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    imageUri: Uri,
    navController: NavController? = null,
    viewModel: ReviewViewModel = hiltViewModel(),
    fileImportViewModel: com.vitol.inv3.ui.scan.FileImportViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Get active own company ID to exclude from matching
    val activeCompanyIdFlow = remember { context.getActiveOwnCompanyIdFlow() }
    val activeCompanyId by activeCompanyIdFlow.collectAsState(initial = null)
    
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
                "Company_number" to ""
            )
        )
    }
    var showCompanySuggestions by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var isCompanySelected by remember { mutableStateOf(false) } // Track if company is selected from existing
    var isReanalyzing by remember { mutableStateOf(false) } // Track if re-analysis is in progress
    var ocrMethod by remember { mutableStateOf<String?>(null) } // Track which OCR method was used: "Azure" or "Local"
    
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
    }

    // First pass: Identify company NUMBER only (uses local OCR)
    // Does NOT extract company name - only company number for template lookup
    LaunchedEffect(imageUri) {
        ocrMethod = "Local" // First pass always uses local OCR
        viewModel.runOcrFirstPass(imageUri) { result ->
            result.onSuccess { companyInfo ->
                // Only populate company number and VAT number (not company name)
                if (companyInfo.companyNumber != null) {
                    fields = fields + ("Company_number" to companyInfo.companyNumber)
                    Timber.d("First pass complete - Company number: ${companyInfo.companyNumber}")
                }
                if (companyInfo.vatNumber != null) {
                    fields = fields + ("VAT_number" to companyInfo.vatNumber)
                    Timber.d("First pass complete - VAT number: ${companyInfo.vatNumber}")
                }
                isLoading = false
            }.onFailure {
                text = it.message ?: "Error"
                isLoading = false
            }
        }
    }
    
    // Track if second pass has been triggered
    var secondPassTriggered by remember { mutableStateOf(false) }
    
    // Second pass: Re-analyze with template after company number is found
    // Try Azure Document Intelligence first (more accurate), fallback to local OCR
    // Trigger when company number OR VAT number is found (for template lookup)
    LaunchedEffect(fields["Company_number"], fields["VAT_number"]) {
        val companyNumber = fields["Company_number"] ?: ""
        val vatNumber = fields["VAT_number"] ?: ""
        val lookupKey = companyNumber.ifBlank { vatNumber }
        // Trigger re-analysis once when company number or VAT number is found
        if (lookupKey.isNotBlank() && !isReanalyzing && !isLoading && !secondPassTriggered) {
            secondPassTriggered = true
            isReanalyzing = true
            // Store first pass values for database lookup
            val firstPassCompanyNumber = fields["Company_number"]
            val firstPassVatNumber = fields["VAT_number"]
            
            // Try Azure Document Intelligence first
            viewModel.runOcrWithDocumentAi(
                imageUri,
                firstPassCompanyNumber,
                firstPassVatNumber,
                excludeCompanyId = activeCompanyId,
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
                                        // Always update company name from Azure result (it has database lookup applied)
                                        if (value.isNotBlank()) {
                                            newFields["Company_name"] = value
                                            Timber.d("Azure - Setting company name from result: '$value'")
                                        }
                                    }
                                    "Amount_without_VAT_EUR" -> if (newFields["Amount_without_VAT_EUR"].isNullOrBlank()) newFields["Amount_without_VAT_EUR"] = value
                                    "VAT_amount_EUR" -> if (newFields["VAT_amount_EUR"].isNullOrBlank()) newFields["VAT_amount_EUR"] = value
                                    "VAT_number" -> if (newFields["VAT_number"].isNullOrBlank()) newFields["VAT_number"] = value
                                    "Company_number" -> if (newFields["Company_number"].isNullOrBlank()) newFields["Company_number"] = value
                                }
                            }
                        }
                        fields = newFields.toMap()
                        isReanalyzing = false
                        Timber.d("Azure second pass complete - All fields extracted, Company_name: '${newFields["Company_name"]}'")
                    }.onFailure {
                        // Fallback to local OCR if Azure Document Intelligence fails
                        Timber.w("Azure Document Intelligence failed, falling back to local OCR")
                        ocrMethod = "Local" // Mark as using local OCR
                        viewModel.runOcrSecondPass(imageUri, lookupKey) { localResult ->
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
                                                }
                                            }
                                            "VAT_amount_EUR" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["VAT_amount_EUR"].isNullOrBlank()) {
                                                    newFields["VAT_amount_EUR"] = value
                                                    Timber.d("Local OCR fallback - Setting VAT_amount_EUR: '$value'")
                                                }
                                            }
                                            "VAT_number" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["VAT_number"].isNullOrBlank()) {
                                                    newFields["VAT_number"] = value
                                                    Timber.d("Local OCR fallback - Setting VAT_number: '$value'")
                                                }
                                            }
                                            "Company_number" -> {
                                                // Local OCR fallback can replace if Azure didn't find it
                                                if (value.isNotBlank() && newFields["Company_number"].isNullOrBlank()) {
                                                    newFields["Company_number"] = value
                                                    Timber.d("Local OCR fallback - Setting Company_number: '$value'")
                                                }
                                            }
                                        }
                                    }
                                }
                                fields = newFields.toMap()
                                isReanalyzing = false
                                Timber.d("Local OCR second pass complete - All fields extracted, Company_name: '${newFields["Company_name"]}'")
                            }.onFailure {
                                Timber.e(it, "Second pass failed")
                                isReanalyzing = false
                            }
                        }
                    }
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                        val processingQueue by fileImportViewModel.processingQueue.collectAsState()
                        val currentIndex by fileImportViewModel.currentIndex.collectAsState()
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
                        Text("Verify fields")
                    }
                }
                
                // OCR method indicator in top right
                ocrMethod?.let { method ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (method) {
                            "Azure" -> androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                            "Local" -> androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                            else -> androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = method,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = when (method) {
                                "Azure" -> androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                                "Local" -> androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                                else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
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
                onChange = { fields = fields + ("Amount_without_VAT_EUR" to it) }
            )
        }
        
        // VAT amount
        item {
            FieldEditor(
                label = "VAT_amount_EUR",
                value = fields["VAT_amount_EUR"] ?: "",
                onChange = { fields = fields + ("VAT_amount_EUR" to it) }
            )
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
                    
                    isSaving = true
                    errorMessage = null
                    viewModel.confirm(
                        InvoiceRecord(
                            invoice_id = fields["Invoice_ID"],
                            date = formattedDate,
                            company_name = fields["Company_name"],
                            amount_without_vat_eur = fields["Amount_without_VAT_EUR"]?.toDoubleOrNull(),
                            vat_amount_eur = fields["VAT_amount_EUR"]?.toDoubleOrNull(),
                            vat_number = fields["VAT_number"],
                            company_number = fields["Company_number"]
                        ),
                        CompanyRecord(
                            company_number = fields["Company_number"],
                            company_name = fields["Company_name"],
                            vat_number = fields["VAT_number"]
                        )
                    ) { success ->
                        isSaving = false
                        if (success) {
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
     */
    fun runOcrFirstPass(uri: Uri, onDone: (Result<CompanyRecognition.Candidate>) -> Unit) {
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
                    val companyCandidate = CompanyRecognition.recognize(lines)
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
     */
    fun runOcrSecondPass(uri: Uri, lookupKey: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = recognizer.recognize(uri)
                .map { blocks ->
                    // Get image dimensions
                    val imageSize = getImageSize(uri)
                    
                    // Try to load template for this company using multiple possible keys
                    val lines = blocks.map { it.text }
                    val companyCandidate = CompanyRecognition.recognize(lines)
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
                        InvoiceParser.parse(blocks.map { it.text })
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
    
    fun runOcr(uri: Uri, excludeCompanyId: String? = null, onDone: (Result<String>) -> Unit) {
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
                    val companyCandidate = CompanyRecognition.recognize(lines)
                    
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
                        val parsedResult = InvoiceParser.parse(lines)
                        Timber.d("Parsed result - InvoiceID: '${parsedResult.invoiceId}', Date: '${parsedResult.date}', " +
                                "Company: '${parsedResult.companyName}', AmountNoVat: '${parsedResult.amountWithoutVatEur}', " +
                                "VatAmount: '${parsedResult.vatAmountEur}', VatNumber: '${parsedResult.vatNumber}', " +
                                "CompanyNumber: '${parsedResult.companyNumber}'")
                        parsedResult
                    }
                    
                    // ALWAYS look up company from database when VAT or company number is found
                    // This ensures we get the correct company name from the database
                    val finalParsed = if (parsed.companyNumber != null || parsed.vatNumber != null) {
                        try {
                            Timber.d("Local OCR - Looking up company in database - CompanyName: '${parsed.companyName}', CompanyNumber: '${parsed.companyNumber}', VatNumber: '${parsed.vatNumber}', ExcludingOwnCompany: '$excludeCompanyId'")
                            val companyFromDb = repo.findCompanyByNumberOrVat(parsed.companyNumber, parsed.vatNumber, excludeCompanyId = excludeCompanyId)
                            if (companyFromDb != null) {
                                Timber.d("Local OCR - Found company in database: '${companyFromDb.company_name}', replacing extracted name '${parsed.companyName}'")
                                parsed.copy(
                                    companyName = companyFromDb.company_name ?: parsed.companyName,
                                    companyNumber = companyFromDb.company_number ?: parsed.companyNumber,
                                    vatNumber = companyFromDb.vat_number ?: parsed.vatNumber
                                )
                            } else {
                                Timber.d("Local OCR - No company found in database for CompanyNumber: '${parsed.companyNumber}', VatNumber: '${parsed.vatNumber}'")
                                // If company name is invalid and not found in DB, set to null so it can be filled manually
                                if (isInvalidCompanyName(parsed.companyName)) {
                                    Timber.d("Local OCR - Company name '${parsed.companyName}' is invalid, setting to null")
                                    parsed.copy(companyName = null)
                                } else {
                                    parsed
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to look up company from database")
                            parsed
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
        onDone: (Result<String>) -> Unit, 
        onMethodUsed: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val result = try {
                Timber.d("Starting Azure Document Intelligence processing for invoice")
                
                // Try Azure Document Intelligence first
                val parsed = documentAiService.processInvoice(uri)
                
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
                    
                    // Use first pass values for database lookup (more reliable than Azure extraction)
                    // If first pass values are not available, fall back to Azure extracted values
                    val lookupCompanyNumber = firstPassCompanyNumber?.takeIf { it.isNotBlank() } ?: parsed.companyNumber
                    val lookupVatNumber = firstPassVatNumber?.takeIf { it.isNotBlank() } ?: parsed.vatNumber
                    
                    // ALWAYS look up company from database when VAT or company number is found
                    // This ensures we get the correct company name from the database
                    val finalParsed = if (lookupCompanyNumber != null || lookupVatNumber != null) {
                        try {
                            Timber.d("Azure - Looking up company in database using first pass values - CompanyName: '${parsed.companyName}', FirstPassCompanyNumber: '$firstPassCompanyNumber', FirstPassVatNumber: '$firstPassVatNumber', LookupCompanyNumber: '$lookupCompanyNumber', LookupVatNumber: '$lookupVatNumber', ExcludingOwnCompany: '$excludeCompanyId'")
                            val companyFromDb = repo.findCompanyByNumberOrVat(
                                lookupCompanyNumber, 
                                lookupVatNumber,
                                excludeCompanyId = excludeCompanyId
                            )
                            if (companyFromDb != null) {
                                Timber.d("Azure - Found company in database: '${companyFromDb.company_name}', replacing extracted name '${parsed.companyName}'")
                                parsed.copy(
                                    companyName = companyFromDb.company_name ?: parsed.companyName,
                                    companyNumber = companyFromDb.company_number ?: parsed.companyNumber,
                                    vatNumber = companyFromDb.vat_number ?: parsed.vatNumber
                                )
                            } else {
                                Timber.d("Azure - No company found in database for CompanyNumber: '${parsed.companyNumber}', VatNumber: '${parsed.vatNumber}'")
                                // Keep the extracted company name if it's valid (contains UAB, MB, IĮ, AB, etc.)
                                // Only set to null if it's invalid (like "SASKAITA", "PARDAVEJAS", etc.)
                                if (isInvalidCompanyName(parsed.companyName)) {
                                    Timber.d("Azure - Company name '${parsed.companyName}' is invalid, setting to null")
                                    parsed.copy(companyName = null)
                                } else {
                                    // Keep the extracted company name even if not in database
                                    Timber.d("Azure - Keeping extracted company name '${parsed.companyName}' (not in database but valid)")
                                    parsed
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
                    runOcr(uri, excludeCompanyId) { localResult ->
                        localResult.onSuccess { parsed ->
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
                runOcr(uri, excludeCompanyId) { localResult ->
                    localResult.onSuccess { parsed ->
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
    isOptional: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { 
            Text(
                text = if (isOptional) "$label (optional)" else label,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        },
        modifier = modifier
    )
}


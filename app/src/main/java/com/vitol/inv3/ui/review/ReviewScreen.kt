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
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    viewModel: ReviewViewModel = hiltViewModel()
) {
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

    // First pass: Identify company only
    LaunchedEffect(imageUri) {
        viewModel.runOcrFirstPass(imageUri) { result ->
            result.onSuccess { companyInfo ->
                // Populate only company name initially
                fields = fields + ("Company_name" to (companyInfo.companyName ?: ""))
                isLoading = false
                Timber.d("First pass complete - Company: ${companyInfo.companyName}")
            }.onFailure {
                text = it.message ?: "Error"
                isLoading = false
            }
        }
    }
    
    // Track if second pass has been triggered
    var secondPassTriggered by remember { mutableStateOf(false) }
    
    // Second pass: Re-analyze with template after company is confirmed
    LaunchedEffect(fields["Company_name"]) {
        val companyName = fields["Company_name"]
        // Trigger re-analysis once when company name is set and not empty, and we're not already re-analyzing
        if (!companyName.isNullOrBlank() && !isReanalyzing && !isLoading && !secondPassTriggered) {
            secondPassTriggered = true
            isReanalyzing = true
            viewModel.runOcrSecondPass(imageUri, companyName) { result ->
                result.onSuccess { parsed ->
                    // Parse the text to populate all fields (but don't overwrite company name)
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
                                "Amount_without_VAT_EUR" -> if (newFields["Amount_without_VAT_EUR"].isNullOrBlank()) newFields["Amount_without_VAT_EUR"] = value
                                "VAT_amount_EUR" -> if (newFields["VAT_amount_EUR"].isNullOrBlank()) newFields["VAT_amount_EUR"] = value
                                "VAT_number" -> if (newFields["VAT_number"].isNullOrBlank()) newFields["VAT_number"] = value
                                "Company_number" -> if (newFields["Company_number"].isNullOrBlank()) newFields["Company_number"] = value
                            }
                        }
                    }
                    fields = newFields.toMap()
                    isReanalyzing = false
                    Timber.d("Second pass complete - All fields extracted")
                }.onFailure {
                    Timber.e(it, "Second pass failed")
                    isReanalyzing = false
                }
            }
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
            if (isLoading || isReanalyzing) {
                CircularProgressIndicator()
            } else {
                Text("Verify fields")
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
                            // Navigate back to home screen after successful save
                            navController?.popBackStack()
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
     * First pass: Identify company only (fast, for initial display)
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
                    // Try to identify company first
                    val lines = blocks.map { it.text }
                    val companyCandidate = CompanyRecognition.recognize(lines)
                    Timber.d("First pass - Company recognition: ${companyCandidate.companyName}, ${companyCandidate.companyNumber}")
                    companyCandidate
                }
            onDone(result)
        }
    }
    
    /**
     * Second pass: Re-analyze with template knowledge after company is confirmed
     */
    fun runOcrSecondPass(uri: Uri, companyName: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = recognizer.recognize(uri)
                .map { blocks ->
                    // Get image dimensions
                    val imageSize = getImageSize(uri)
                    
                    // Try to load template for this company using multiple possible keys
                    val lines = blocks.map { it.text }
                    val companyCandidate = CompanyRecognition.recognize(lines)
                    val possibleKeys = listOfNotNull(
                        normalizeCompanyKey(companyName),
                        normalizeCompanyKey(companyCandidate.companyNumber),
                        normalizeCompanyKey(companyCandidate.vatNumber)
                    ).filterNotNull().distinct()
                    
                    Timber.d("Second pass - Company: '$companyName', normalized keys: $possibleKeys")
                    
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
    
    fun runOcr(uri: Uri, onDone: (Result<String>) -> Unit) {
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
                        InvoiceParser.parse(lines)
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


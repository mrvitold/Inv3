package com.vitol.inv3.ui.review

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.vitol.inv3.Routes
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ocr.InvoiceParser
import com.vitol.inv3.ocr.InvoiceTextRecognizer
import com.vitol.inv3.utils.DateFormatter
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

    LaunchedEffect(imageUri) {
        viewModel.runOcr(imageUri) { result ->
            result.onSuccess { parsed ->
                text = parsed
                // Parse the text to populate fields
                val lines = parsed.split("\n")
                val newFields = fields.toMutableMap()
                lines.forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (key) {
                            "Invoice_ID" -> newFields["Invoice_ID"] = value
                            "Date" -> newFields["Date"] = value
                            "Company_name" -> newFields["Company_name"] = value
                            "Amount_without_VAT_EUR" -> newFields["Amount_without_VAT_EUR"] = value
                            "VAT_amount_EUR" -> newFields["VAT_amount_EUR"] = value
                            "VAT_number" -> newFields["VAT_number"] = value
                            "Company_number" -> newFields["Company_number"] = value
                        }
                    }
                }
                fields = newFields.toMap()
                isLoading = false
            }.onFailure {
                text = it.message ?: "Error"
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Text("Verify fields")
            FieldEditor(
                label = "Invoice_ID",
                value = fields["Invoice_ID"] ?: "",
                onChange = { fields = fields + ("Invoice_ID" to it) }
            )
            // Date field with date picker - wrapped in clickable Box
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
            // Company name with autocomplete
            Box {
                OutlinedTextField(
                    value = fields["Company_name"] ?: "",
                    onValueChange = { newValue ->
                        fields = fields + ("Company_name" to newValue)
                        showCompanySuggestions = newValue.isNotBlank()
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
                                                // Auto-fill all company fields
                                                fields = fields + mapOf(
                                                    "Company_name" to (company.company_name ?: ""),
                                                    "VAT_number" to (company.vat_number ?: ""),
                                                    "Company_number" to (company.company_number ?: "")
                                                )
                                                showCompanySuggestions = false
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
            FieldEditor(
                label = "Amount_without_VAT_EUR",
                value = fields["Amount_without_VAT_EUR"] ?: "",
                onChange = { fields = fields + ("Amount_without_VAT_EUR" to it) }
            )
            FieldEditor(
                label = "VAT_amount_EUR",
                value = fields["VAT_amount_EUR"] ?: "",
                onChange = { fields = fields + ("VAT_amount_EUR" to it) }
            )
            FieldEditor(
                label = "VAT_number",
                value = fields["VAT_number"] ?: "",
                onChange = { fields = fields + ("VAT_number" to it) }
            )
            FieldEditor(
                label = "Company_number",
                value = fields["Company_number"] ?: "",
                onChange = { fields = fields + ("Company_number" to it) }
            )
            if (errorMessage != null) {
                Text(
                    text = "Error: $errorMessage",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
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
                enabled = !isSaving
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
    private val client: io.github.jan.supabase.SupabaseClient?
) : ViewModel() {
    val companies = mutableStateListOf<CompanyRecord>()

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
    fun runOcr(uri: Uri, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = recognizer.recognize(uri)
                .map { blocks -> blocks.map { it.text } }
                .map { lines -> InvoiceParser.parse(lines) }
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
        label = { Text(label) }
    )
}


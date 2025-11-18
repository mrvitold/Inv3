package com.vitol.inv3.ui.exports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vitol.inv3.Routes
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class EditInvoiceViewModel @Inject constructor(
    private val repo: SupabaseRepository
) : androidx.lifecycle.ViewModel() {

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

    fun deleteInvoice(invoice: InvoiceRecord, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        viewModelScope.launch {
            try {
                repo.deleteInvoice(invoice)
                onSuccess()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete invoice")
                onError(e)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditInvoiceScreen(
    invoiceId: String,
    navController: NavController?,
    viewModel: EditInvoiceViewModel = hiltViewModel()
) {
    var invoice by remember { mutableStateOf<InvoiceRecord?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Form fields
    var invoiceIdField by remember { mutableStateOf("") }
    var dateField by remember { mutableStateOf("") }
    var companyNameField by remember { mutableStateOf("") }
    var amountWithoutVatField by remember { mutableStateOf("") }
    var vatAmountField by remember { mutableStateOf("") }
    var vatNumberField by remember { mutableStateOf("") }
    var companyNumberField by remember { mutableStateOf("") }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.parse(dateField)?.time
        } catch (e: Exception) {
            null
        } ?: Calendar.getInstance().timeInMillis
    )

    // Load invoice data
    LaunchedEffect(invoiceId) {
        try {
            val foundInvoice = viewModel.loadInvoice(invoiceId)
            if (foundInvoice != null) {
                invoice = foundInvoice
                invoiceIdField = foundInvoice.invoice_id ?: ""
                dateField = foundInvoice.date ?: ""
                companyNameField = foundInvoice.company_name ?: ""
                amountWithoutVatField = foundInvoice.amount_without_vat_eur?.toString() ?: ""
                vatAmountField = foundInvoice.vat_amount_eur?.toString() ?: ""
                vatNumberField = foundInvoice.vat_number ?: ""
                companyNumberField = foundInvoice.company_number ?: ""
            } else {
                errorMessage = "Invoice not found"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load invoice")
            errorMessage = "Failed to load invoice: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Invoice") },
                navigationIcon = {
                    IconButton(onClick = { navController?.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(paddingValues))
        } else if (invoice == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage ?: "Invoice not found",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.padding(16.dp))
                Button(onClick = { navController?.popBackStack() }) {
                    Text("Go Back")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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

                OutlinedTextField(
                    value = invoiceIdField,
                    onValueChange = { invoiceIdField = it },
                    label = { Text("Invoice ID") },
                    modifier = Modifier.fillMaxWidth()
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
                    readOnly = true
                )

                OutlinedTextField(
                    value = companyNameField,
                    onValueChange = { companyNameField = it },
                    label = { Text("Company Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = amountWithoutVatField,
                    onValueChange = { amountWithoutVatField = it },
                    label = { Text("Amount without VAT (EUR)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = vatAmountField,
                    onValueChange = { vatAmountField = it },
                    label = { Text("VAT Amount (EUR)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = vatNumberField,
                    onValueChange = { vatNumberField = it },
                    label = { Text("VAT Number") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = companyNumberField,
                    onValueChange = { companyNumberField = it },
                    label = { Text("Company Number") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete")
                    }
                    Button(
                        onClick = {
                            if (invoice != null) {
                                isSaving = true
                                errorMessage = null
                                val updatedInvoice = invoice!!.copy(
                                    invoice_id = invoiceIdField.takeIf { it.isNotBlank() },
                                    date = dateField.takeIf { it.isNotBlank() },
                                    company_name = companyNameField.takeIf { it.isNotBlank() },
                                    amount_without_vat_eur = amountWithoutVatField.toDoubleOrNull(),
                                    vat_amount_eur = vatAmountField.toDoubleOrNull(),
                                    vat_number = vatNumberField.takeIf { it.isNotBlank() },
                                    company_number = companyNumberField.takeIf { it.isNotBlank() }
                                )
                                viewModel.updateInvoice(
                                    invoice = updatedInvoice,
                                    onSuccess = {
                                        isSaving = false
                                        // Refresh will happen automatically when returning to ExportsScreen
                                        navController?.popBackStack()
                                    },
                                    onError = { e ->
                                        isSaving = false
                                        errorMessage = "Failed to save: ${e.message}"
                                    }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("Save")
                    }
                }

                if (showDatePicker) {
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                    dateField = sdf.format(Date(millis))
                                }
                                showDatePicker = false
                            }) {
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

                if (showDeleteDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("Delete Invoice") },
                        text = { Text("Are you sure you want to delete this invoice? This action cannot be undone.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (invoice != null) {
                                        viewModel.deleteInvoice(
                                            invoice = invoice!!,
                                            onSuccess = {
                                                // Refresh will happen automatically when returning to ExportsScreen
                                                navController?.popBackStack()
                                            },
                                            onError = { e ->
                                                errorMessage = "Failed to delete: ${e.message}"
                                                showDeleteDialog = false
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text("Delete")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}


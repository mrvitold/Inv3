package com.vitol.inv3.ui.review

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ocr.InvoiceParser
import com.vitol.inv3.ocr.InvoiceTextRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@Composable
fun ReviewScreen(imageUri: Uri, viewModel: ReviewViewModel = hiltViewModel()) {
    var isLoading by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf(
        mutableMapOf(
            "Invoice_ID" to "",
            "Date" to "",
            "Company_name" to "",
            "Amount_without_VAT_EUR" to "",
            "VAT_amount_EUR" to "",
            "VAT_number" to "",
            "Company_number" to ""
        )
    ) }

    LaunchedEffect(imageUri) {
        viewModel.runOcr(imageUri) { result ->
            val parsedText = result.getOrElse { it.message ?: "Error" }.toString()
            text = parsedText
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Text("Verify fields")
            FieldEditor(label = "Invoice_ID", value = fields["Invoice_ID"] ?: "", onChange = { fields["Invoice_ID"] = it })
            FieldEditor(label = "Date", value = fields["Date"] ?: "", onChange = { fields["Date"] = it })
            FieldEditor(label = "Company_name", value = fields["Company_name"] ?: "", onChange = { fields["Company_name"] = it })
            FieldEditor(label = "Amount_without_VAT_EUR", value = fields["Amount_without_VAT_EUR"] ?: "", onChange = { fields["Amount_without_VAT_EUR"] = it })
            FieldEditor(label = "VAT_amount_EUR", value = fields["VAT_amount_EUR"] ?: "", onChange = { fields["VAT_amount_EUR"] = it })
            FieldEditor(label = "VAT_number", value = fields["VAT_number"] ?: "", onChange = { fields["VAT_number"] = it })
            FieldEditor(label = "Company_number", value = fields["Company_number"] ?: "", onChange = { fields["Company_number"] = it })
            ElevatedButton(onClick = {
                viewModel.confirm(
                    InvoiceRecord(
                        invoice_id = fields["Invoice_ID"],
                        date = fields["Date"],
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
                )
            }) {
                Text("Confirm")
            }
        }
    }
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val recognizer: InvoiceTextRecognizer,
    private val repo: SupabaseRepository
) : ViewModel() {
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
    fun confirm(invoice: InvoiceRecord, company: CompanyRecord) {
        viewModelScope.launch {
            repo.upsertCompany(company)
            repo.insertInvoice(invoice)
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


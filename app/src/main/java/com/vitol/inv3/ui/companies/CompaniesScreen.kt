package com.vitol.inv3.ui.companies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@Composable
fun CompaniesScreen(viewModel: CompaniesViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("") }
    val companies = viewModel.items
    var companyToDelete by remember { mutableStateOf<CompanyRecord?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Companies")
        if (companies.isEmpty()) {
            Text("No companies yet or Supabase not configured.")
        }
        companies.forEach { c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            name = c.company_name.orEmpty()
                            number = c.company_number.orEmpty()
                            vat = c.vat_number.orEmpty()
                        }
                ) {
                    Text("${c.company_name} • ${c.company_number} • ${c.vat_number}")
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { companyToDelete = c }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove company"
                    )
                }
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Company name") })
        OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Company number") })
        OutlinedTextField(value = vat, onValueChange = { vat = it }, label = { Text("VAT number") })
        Button(onClick = {
            viewModel.upsert(CompanyRecord(company_name = name, company_number = number, vat_number = vat))
            name = ""; number = ""; vat = ""
        }) { Text("Save") }
    }

    // Confirmation dialog for deletion
    companyToDelete?.let { company ->
        AlertDialog(
            onDismissRequest = { companyToDelete = null },
            title = { Text("Confirm Removal") },
            text = {
                Text("Are you sure you want to remove ${company.company_name ?: "this company"}?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(company)
                        companyToDelete = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { companyToDelete = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@HiltViewModel
class CompaniesViewModel @Inject constructor(
    private val repo: SupabaseRepository,
    private val client: io.github.jan.supabase.SupabaseClient?
) : ViewModel() {
    val items = mutableStateListOf<CompanyRecord>()

    fun load() {
        viewModelScope.launch {
            runCatching {
                // select() without parameters selects all columns including id
                client?.from("companies")?.select()?.decodeList<CompanyRecord>() ?: emptyList()
            }.onSuccess { list ->
                items.clear(); items.addAll(list)
            }
        }
    }

    fun upsert(company: CompanyRecord) {
        viewModelScope.launch {
            repo.upsertCompany(company)
            load()
        }
    }

    fun delete(company: CompanyRecord) {
        viewModelScope.launch {
            try {
                repo.deleteCompany(company)
                load()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete company: ${company.company_name ?: company.company_number}")
                // Error is logged, but we still reload to refresh the list
                load()
            }
        }
    }
}


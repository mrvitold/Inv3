package com.vitol.inv3.ui.companies

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vitol.inv3.Routes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@Composable
fun CompaniesScreen(
    markAsOwnCompany: Boolean = false,
    onCompanySaved: ((String?) -> Unit)? = null,
    navController: NavController? = null,
    viewModel: CompaniesViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("") }
    // Observe partner companies from ViewModel using StateFlow
    // CompaniesViewModel.load() already filters to partner companies (user-specific, is_own_company = false)
    val companies by viewModel.items.collectAsState()
    var companyToDelete by remember { mutableStateOf<CompanyRecord?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (markAsOwnCompany) "Add Your Company" else "Companies")
        if (companies.isEmpty()) {
            Text("No companies yet or Supabase not configured.")
        }
        for (company in companies) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${company.company_name} • ${company.company_number} • ${company.vat_number}",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { 
                        // Navigate to edit screen with company ID
                        company.id?.let { companyId ->
                            navController?.navigate("${Routes.EditCompany}/$companyId")
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit company"
                    )
                }
                IconButton(
                    onClick = { companyToDelete = company }
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
            val company = CompanyRecord(
                company_name = name, 
                company_number = number, 
                vat_number = vat,
                is_own_company = markAsOwnCompany
            )
            viewModel.upsert(company) { savedCompanyId ->
                // Call callback with saved company ID
                onCompanySaved?.invoke(savedCompanyId)
                // If marking as own company, navigate back
                if (markAsOwnCompany) {
                    navController?.popBackStack()
                }
            }
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
    private val _items = MutableStateFlow<List<CompanyRecord>>(emptyList())
    val items: StateFlow<List<CompanyRecord>> = _items.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                // Load partner companies (user-specific, not own companies)
                val list = repo.getPartnerCompanies()
                Timber.d("Loaded ${list.size} partner companies from Supabase")
                _items.value = list
                Timber.d("Updated items list, now contains ${_items.value.size} partner companies")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load partner companies from Supabase")
            }
        }
    }

    fun upsert(company: CompanyRecord, onComplete: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val savedCompany = repo.upsertCompany(company)
            load()
            // If marking as own company and company already existed, ensure it's marked
            if (company.is_own_company && savedCompany != null) {
                ensureMarkedAsOwn(savedCompany.company_name, savedCompany.company_number, savedCompany.vat_number)
            }
            onComplete(savedCompany?.id)
        }
    }

    fun delete(company: CompanyRecord) {
        viewModelScope.launch {
            try {
                Timber.d("Attempting to delete company: ${company.company_name ?: company.company_number}, id: ${company.id}")
                
                // Delete from Supabase first
                repo.deleteCompany(company)
                Timber.d("Delete call completed, waiting for Supabase to commit...")
                
                // Wait longer to ensure deletion is committed in Supabase
                kotlinx.coroutines.delay(500)
                
                // Reload to get the updated list from Supabase
                load()
                
                // Verify the company was actually deleted
                val stillExists = _items.value.any { it.id == company.id || 
                    (it.company_number == company.company_number && !company.company_number.isNullOrBlank()) }
                
                if (stillExists) {
                    Timber.w("Company still exists after deletion attempt - may be RLS policy issue or deletion failed silently")
                    // Try one more reload after a longer delay
                    kotlinx.coroutines.delay(500)
                    load()
                } else {
                    Timber.d("Company successfully removed from list")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete company: ${company.company_name ?: company.company_number}")
                // If deletion failed, reload to restore the correct state
                load()
            }
        }
    }

    fun markAsOwnCompany(companyId: String) {
        viewModelScope.launch {
            try {
                repo.markAsOwnCompany(companyId)
                load()
            } catch (e: Exception) {
                Timber.e(e, "Failed to mark company as own: $companyId")
            }
        }
    }

    fun ensureMarkedAsOwn(companyName: String?, companyNumber: String?, vatNumber: String?) {
        viewModelScope.launch {
            try {
                // Find the company by name, number, or VAT
                val company = if (!companyNumber.isNullOrBlank()) {
                    client?.from("companies")
                        ?.select {
                            filter { eq("company_number", companyNumber) }
                        }
                        ?.decodeSingleOrNull<CompanyRecord>()
                } else if (!vatNumber.isNullOrBlank()) {
                    client?.from("companies")
                        ?.select {
                            filter { eq("vat_number", vatNumber) }
                        }
                        ?.decodeSingleOrNull<CompanyRecord>()
                } else if (!companyName.isNullOrBlank()) {
                    client?.from("companies")
                        ?.select {
                            filter { eq("company_name", companyName) }
                        }
                        ?.decodeSingleOrNull<CompanyRecord>()
                } else {
                    null
                }
                
                company?.id?.let { id ->
                    repo.markAsOwnCompany(id)
                    load()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to mark newly created company as own")
            }
        }
    }
}


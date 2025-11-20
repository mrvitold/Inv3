package com.vitol.inv3.ui.companies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vitol.inv3.data.remote.CompanyRecord
import timber.log.Timber

@Composable
fun EditCompanyScreen(
    companyId: String,
    navController: NavController? = null,
    viewModel: CompaniesViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var company by remember { mutableStateOf<CompanyRecord?>(null) }
    
    // Observe companies from ViewModel
    val allCompanies by viewModel.items.collectAsState()

    // Load company data when screen opens
    LaunchedEffect(companyId, allCompanies) {
        val foundCompany = allCompanies.find { it.id == companyId }
        if (foundCompany != null && company == null) {
            company = foundCompany
            name = foundCompany.company_name.orEmpty()
            number = foundCompany.company_number.orEmpty()
            vat = foundCompany.vat_number.orEmpty()
            isLoading = false
        } else if (foundCompany == null && isLoading) {
            // Company not found, try loading
            viewModel.load()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Edit Company",
            style = MaterialTheme.typography.headlineSmall
        )

        if (isLoading) {
            Text("Loading company data...")
        } else if (company == null) {
            Text("Company not found")
        } else {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Company name") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Company number") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vat,
                onValueChange = { vat = it },
                label = { Text("VAT number") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        // Cancel - navigate back
                        navController?.popBackStack()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        // Save changes
                        if (company != null) {
                            val updatedCompany = company!!.copy(
                                company_name = name.ifBlank { null },
                                company_number = number.ifBlank { null },
                                vat_number = vat.ifBlank { null }
                            )
                            viewModel.upsert(updatedCompany) {
                                // Navigate back after saving
                                navController?.popBackStack()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}

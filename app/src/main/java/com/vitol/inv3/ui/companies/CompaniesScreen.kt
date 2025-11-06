package com.vitol.inv3.ui.companies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import javax.inject.Inject

@Composable
fun CompaniesScreen(viewModel: CompaniesViewModel = hiltViewModel()) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("") }
    val companies = viewModel.items

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Companies")
        if (companies.isEmpty()) {
            Text("No companies yet or Supabase not configured.")
        }
        companies.forEach { c ->
            Row(modifier = Modifier.fillMaxWidth().clickable {
                name = c.company_name.orEmpty(); number = c.company_number.orEmpty(); vat = c.vat_number.orEmpty()
            }.padding(vertical = 4.dp)) {
                Text("${c.company_name} • ${c.company_number} • ${c.vat_number}")
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
}


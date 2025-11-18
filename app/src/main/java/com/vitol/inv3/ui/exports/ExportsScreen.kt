package com.vitol.inv3.ui.exports

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitol.inv3.Routes
import com.vitol.inv3.export.ExcelExporter
import com.vitol.inv3.export.ExportInvoice
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ExportsScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: ExportsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val monthlySummaries by viewModel.monthlySummaries.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val expandedMonths by viewModel.expandedMonths.collectAsState()
    val expandedCompanies by viewModel.expandedCompanies.collectAsState()
    val availableYears = viewModel.getAvailableYears()
    
    var exportDialogState by remember { mutableStateOf<ExportDialogState?>(null) }
    
    // Auto-refresh when screen is resumed (after returning from EditInvoice)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Refresh invoices when screen is first composed or recomposed
        viewModel.loadInvoices()
    }
    
    // Show export dialog if state is set
    exportDialogState?.let { state ->
        ExportDialog(
            context = context,
            invoices = state.invoices,
            month = state.month,
            onDismiss = { exportDialogState = null },
            onRefresh = { viewModel.loadInvoices() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        YearFilterDropdown(
            selectedYear = selectedYear,
            availableYears = availableYears,
            onYearSelected = { viewModel.setSelectedYear(it) }
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(monthlySummaries) { summary ->
                    MonthlySummaryCard(
                        summary = summary,
                        isExpanded = expandedMonths.contains(summary.month),
                        onExpandClick = { viewModel.toggleMonthExpansion(summary.month) },
                        onExportClick = {
                            val invoices = viewModel.getInvoicesForMonth(summary.month)
                            val exportInvoices = invoices.map { invoice ->
                                ExportInvoice(
                                    invoiceId = invoice.invoice_id,
                                    date = invoice.date,
                                    companyName = invoice.company_name,
                                    amountWithoutVatEur = invoice.amount_without_vat_eur,
                                    vatAmountEur = invoice.vat_amount_eur,
                                    vatNumber = invoice.vat_number,
                                    companyNumber = invoice.company_number
                                )
                            }
                            exportDialogState = ExportDialogState(
                                invoices = exportInvoices,
                                month = summary.month
                            )
                        },
                        companySummaries = if (expandedMonths.contains(summary.month)) {
                            viewModel.getCompanySummariesForMonth(summary.month)
                        } else {
                            emptyList()
                        },
                        viewModel = viewModel,
                        navController = navController,
                        month = summary.month,
                        expandedCompanies = expandedCompanies
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearFilterDropdown(
    selectedYear: Int,
    availableYears: List<Int>,
    onYearSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedYear.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Year") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableYears.forEach { year ->
                DropdownMenuItem(
                    text = { Text(year.toString()) },
                    onClick = {
                        onYearSelected(year)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun MonthlySummaryCard(
    summary: MonthlySummary,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    onExportClick: () -> Unit,
    companySummaries: List<CompanySummary>,
    viewModel: ExportsViewModel,
    navController: androidx.navigation.NavController?,
    month: String,
    expandedCompanies: Set<String>
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row with month and buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onExpandClick),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary.month,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand"
                    )
                }
                IconButton(onClick = onExpandClick) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Check companies"
                    )
                }
                IconButton(onClick = onExportClick) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Export"
                    )
                }
            }

            // Summary details
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "invoice q-ty: ${summary.invoiceCount}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "amount sum: ${numberFormat.format(summary.totalAmount)} EUR",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "vat amount sum: ${numberFormat.format(summary.totalVat)} EUR",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Expanded company breakdown
            if (isExpanded && companySummaries.isNotEmpty()) {
                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Companies:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                companySummaries.forEach { company ->
                    val companyKey = "$month|${company.companyName}"
                    val isCompanyExpanded = expandedCompanies.contains(companyKey)
                    val companyInvoices = if (isCompanyExpanded) {
                        viewModel.getInvoicesForCompany(month, company.companyName)
                    } else {
                        emptyList()
                    }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleCompanyExpansion(month, company.companyName) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = company.companyName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "invoices: ${company.invoiceCount}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "amount without vat: ${numberFormat.format(company.totalAmount)} EUR",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "vat amount: ${numberFormat.format(company.totalVat)} EUR",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(
                                    imageVector = if (isCompanyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isCompanyExpanded) "Collapse" else "Expand"
                                )
                            }
                            
                            // Expanded invoices list
                            if (isCompanyExpanded && companyInvoices.isNotEmpty()) {
                                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                                companyInvoices.forEach { invoice ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = invoice.invoice_id ?: "No ID",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = invoice.date ?: "",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    text = "${numberFormat.format(invoice.amount_without_vat_eur ?: 0.0)} EUR",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    invoice.id?.let { id ->
                                                        navController?.navigate("${Routes.EditInvoice}/$id")
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Edit invoice"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class ExportDialogState(
    val invoices: List<ExportInvoice>,
    val month: String
)

@Composable
fun ExportDialog(
    context: android.content.Context,
    invoices: List<ExportInvoice>,
    month: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    var isSaving by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Options") },
        text = {
            Column {
                Text("Choose how to export invoices for $month")
                if (isSaving) {
                    Spacer(modifier = Modifier.padding(8.dp))
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSaving = true
                    val exporter = ExcelExporter(context)
                    val result = exporter.saveToDownloads(invoices, month)
                    isSaving = false
                    onDismiss()
                    if (result != null) {
                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isSaving
            ) {
                Text("Save to Downloads")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    val exporter = ExcelExporter(context)
                    val uri = exporter.export(invoices, month)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share $month.xlsx"))
                    onDismiss()
                },
                enabled = !isSaving
            ) {
                Text("Share")
            }
        }
    )
}

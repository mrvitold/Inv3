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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitol.inv3.Routes
import com.vitol.inv3.MainActivityViewModel
import com.vitol.inv3.export.ExcelExporter
import com.vitol.inv3.export.ExportInvoice
import com.vitol.inv3.export.ISafXmlExporter
import com.vitol.inv3.data.local.getActiveOwnCompanyIdFlow
import com.vitol.inv3.data.remote.CompanyRecord
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
    var invoiceToDelete by remember { mutableStateOf<com.vitol.inv3.data.remote.InvoiceRecord?>(null) }
    var monthToDelete by remember { mutableStateOf<String?>(null) }
    
    // Auto-refresh when screen is resumed (after returning from EditInvoice)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Refresh invoices when screen is first composed or recomposed
        viewModel.loadInvoices()
    }
    
    // Get repo for getting own company
    val mainActivityViewModel: com.vitol.inv3.MainActivityViewModel = hiltViewModel()
    val repo = mainActivityViewModel.repo
    
    // Show export dialog if state is set
    exportDialogState?.let { state ->
        ExportDialog(
            context = context,
            invoices = state.invoices,
            invoiceRecords = state.invoiceRecords,
            month = state.month,
            onDismiss = { exportDialogState = null },
            onRefresh = { viewModel.loadInvoices() },
            repo = repo
        )
    }
    
    // Show delete confirmation dialog for single invoice
    invoiceToDelete?.let { invoice ->
        AlertDialog(
            onDismissRequest = { invoiceToDelete = null },
            title = { Text("Delete Invoice") },
            text = {
                Text("Are you sure you want to delete invoice \"${invoice.invoice_id ?: "this invoice"}\"? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteInvoice(invoice) {
                            invoiceToDelete = null
                            viewModel.loadInvoices()
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { invoiceToDelete = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Show delete confirmation dialog for month
    monthToDelete?.let { month ->
        val monthInvoices = viewModel.getInvoicesForMonth(month)
        AlertDialog(
            onDismissRequest = { monthToDelete = null },
            title = { Text("Delete Month") },
            text = {
                Text("Are you sure you want to delete all ${monthInvoices.size} invoice(s) for $month? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        monthInvoices.forEach { invoice ->
                            viewModel.deleteInvoice(invoice) {}
                        }
                        monthToDelete = null
                        viewModel.loadInvoices()
                    }
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { monthToDelete = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YearFilterDropdown(
                selectedYear = selectedYear,
                availableYears = availableYears,
                onYearSelected = { viewModel.setSelectedYear(it) },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val allInvoices = viewModel.getAllInvoicesForYear(selectedYear)
                    val exportInvoices = allInvoices.map { invoice ->
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
                    val allInvoiceRecords = viewModel.getAllInvoicesForYear(selectedYear)
                    exportDialogState = ExportDialogState(
                        invoices = exportInvoices,
                        invoiceRecords = allInvoiceRecords,
                        month = "$selectedYear-All"
                    )
                }
            ) {
                Text("Export All")
            }
        }

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
                                invoiceRecords = invoices,
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
                        expandedCompanies = expandedCompanies,
                        onDeleteInvoice = { invoice -> invoiceToDelete = invoice },
                        onDeleteMonth = { month -> monthToDelete = month }
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
    onYearSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
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
    expandedCompanies: Set<String>,
    onDeleteInvoice: (com.vitol.inv3.data.remote.InvoiceRecord) -> Unit,
    onDeleteMonth: (String) -> Unit
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    val darkRed = Color(0xFF8B0000)

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
                IconButton(
                    onClick = { onDeleteMonth(month) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete month",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Summary details
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("invoice q-ty: ${summary.invoiceCount}")
                        if (summary.errorCount > 0) {
                            withStyle(style = SpanStyle(color = darkRed)) {
                                append(" [${summary.errorCount} to check]")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${numberFormat.format(summary.totalAmount)} EUR (without VAT)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${numberFormat.format(summary.totalVat)} EUR (VAT)",
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
                                        text = buildAnnotatedString {
                                            append("invoices: ${company.invoiceCount}")
                                            if (company.errorCount > 0) {
                                                withStyle(style = SpanStyle(color = darkRed)) {
                                                    append(" [${company.errorCount} to check]")
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${numberFormat.format(company.totalAmount)} EUR (without VAT)",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${numberFormat.format(company.totalVat)} EUR (VAT)",
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
                                    val invoiceErrors = viewModel.getInvoiceErrors(invoice)
                                    val hasErrors = invoiceErrors.isNotEmpty()
                                    val textColor = if (hasErrors) darkRed else Color.Unspecified
                                    
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
                                                    fontWeight = FontWeight.Medium,
                                                    color = textColor
                                                )
                                                Text(
                                                    text = invoice.date ?: "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = textColor
                                                )
                                                Text(
                                                    text = "${numberFormat.format(invoice.amount_without_vat_eur ?: 0.0)} EUR (without VAT)",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = textColor
                                                )
                                                Text(
                                                    text = "${numberFormat.format(invoice.vat_amount_eur ?: 0.0)} EUR (VAT)",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = textColor
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
                                            IconButton(
                                                onClick = {
                                                    onDeleteInvoice(invoice)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete invoice",
                                                    tint = MaterialTheme.colorScheme.error
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
    val invoiceRecords: List<com.vitol.inv3.data.remote.InvoiceRecord>,
    val month: String
)

@Composable
fun ExportDialog(
    context: android.content.Context,
    invoices: List<ExportInvoice>,
    invoiceRecords: List<com.vitol.inv3.data.remote.InvoiceRecord>,
    month: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    repo: com.vitol.inv3.data.remote.SupabaseRepository
) {
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activeCompanyIdFlow = remember { context.getActiveOwnCompanyIdFlow() }
    val activeCompanyId by activeCompanyIdFlow.collectAsState(initial = null)
    var ownCompany by remember { mutableStateOf<CompanyRecord?>(null) }
    
    // Load own company
    LaunchedEffect(activeCompanyId) {
        if (activeCompanyId != null) {
            ownCompany = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.getCompanyById(activeCompanyId!!)
            }
        } else {
            ownCompany = null
        }
    }
    
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
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Excel export
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
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Excel")
                }
                
                // XML export (i.SAF)
                Button(
                    onClick = {
                        if (ownCompany == null) {
                            Toast.makeText(context, "Please select an own company first", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        isSaving = true
                        scope.launch {
                            try {
                                val exporter = ISafXmlExporter(context)
                                val result = exporter.saveToDownloads(invoiceRecords, ownCompany, month)
                                isSaving = false
                                onDismiss()
                                if (result != null) {
                                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to save XML file", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                isSaving = false
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isSaving && ownCompany != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export XML (i.SAF)")
                }
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
                Text("Share Excel")
            }
        }
    )
}

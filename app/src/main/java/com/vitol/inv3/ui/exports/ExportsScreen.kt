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
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
    val expandedSalesPurchase by viewModel.expandedSalesPurchase.collectAsState()
    val availableYears = viewModel.getAvailableYears()
    
    var exportDialogState by remember { mutableStateOf<ExportDialogState?>(null) }
    var invoiceToDelete by remember { mutableStateOf<com.vitol.inv3.data.remote.InvoiceRecord?>(null) }
    var monthToDelete by remember { mutableStateOf<String?>(null) }
    var invoiceWithErrorsToShow by remember { mutableStateOf<com.vitol.inv3.data.remote.InvoiceRecord?>(null) }
    var monthToRemoveDuplicates by remember { mutableStateOf<String?>(null) }
    var duplicateCountToRemove by remember { mutableStateOf(0) }
    
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
    
    // Show validation errors dialog
    invoiceWithErrorsToShow?.let { invoice ->
        val errors = viewModel.getInvoiceErrors(invoice)
        val errorDialogDarkRed = Color(0xFF8B0000)
        AlertDialog(
            onDismissRequest = { invoiceWithErrorsToShow = null },
            title = {
                Column {
                    Text(
                        text = "Validation Errors",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Invoice: ${invoice.invoice_id ?: "Unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (errors.isEmpty()) {
                        Text("No errors found")
                    } else {
                        errors.forEachIndexed { index, error ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${index + 1}.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = errorDialogDarkRed
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = error.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Field: ${error.fieldName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { invoiceWithErrorsToShow = null }
                ) {
                    Text("Close")
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

    // Show remove duplicates confirmation dialog
    monthToRemoveDuplicates?.let { month ->
        AlertDialog(
            onDismissRequest = { 
                monthToRemoveDuplicates = null
                duplicateCountToRemove = 0
            },
            title = { Text("Remove Duplicates") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Found $duplicateCountToRemove duplicate invoice(s) in $month.")
                    Text(
                        text = "This will keep the most recent invoice for each duplicate group and remove the rest. This action cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val monthToProcess = month
                        monthToRemoveDuplicates = null
                        duplicateCountToRemove = 0
                        viewModel.removeDuplicatesForMonth(monthToProcess) { removedCount ->
                            if (removedCount > 0) {
                                Toast.makeText(context, "Removed $removedCount duplicate(s)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No duplicates found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Remove Duplicates")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { 
                        monthToRemoveDuplicates = null
                        duplicateCountToRemove = 0
                    }
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
                            companyNumber = invoice.company_number,
                            invoiceType = invoice.invoice_type,
                            taxCode = invoice.tax_code
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
                                    companyNumber = invoice.company_number,
                                    invoiceType = invoice.invoice_type,
                                    taxCode = invoice.tax_code
                                )
                            }
                            exportDialogState = ExportDialogState(
                                invoices = exportInvoices,
                                invoiceRecords = invoices,
                                month = summary.month
                            )
                        },
                        onShowInvoiceErrors = { invoice -> invoiceWithErrorsToShow = invoice },
                        salesPurchaseSummaries = if (expandedMonths.contains(summary.month)) {
                            viewModel.getSalesPurchaseSummariesForMonth(summary.month)
                        } else {
                            emptyList()
                        },
                        viewModel = viewModel,
                        navController = navController,
                        month = summary.month,
                        expandedCompanies = expandedCompanies,
                        expandedSalesPurchase = expandedSalesPurchase,
                        onDeleteInvoice = { invoice -> invoiceToDelete = invoice },
                        onDeleteMonth = { month -> monthToDelete = month },
                        onRemoveDuplicates = { month ->
                            // Count duplicates first
                            val invoices = viewModel.getInvoicesForMonth(month)
                            val invoicesByInvoiceId = mutableMapOf<String, MutableList<com.vitol.inv3.data.remote.InvoiceRecord>>()
                            invoices.forEach { invoice ->
                                val invoiceId = invoice.invoice_id
                                if (!invoiceId.isNullOrBlank()) {
                                    invoicesByInvoiceId.getOrPut(invoiceId) { mutableListOf() }.add(invoice)
                                }
                            }
                            val duplicateCount = invoicesByInvoiceId.values.sumOf { if (it.size > 1) it.size - 1 else 0 }
                            if (duplicateCount > 0) {
                                duplicateCountToRemove = duplicateCount
                                monthToRemoveDuplicates = month
                            } else {
                                Toast.makeText(context, "No duplicates found", Toast.LENGTH_SHORT).show()
                            }
                        }
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
    onShowInvoiceErrors: (com.vitol.inv3.data.remote.InvoiceRecord) -> Unit,
    salesPurchaseSummaries: List<SalesPurchaseSummary>,
    viewModel: ExportsViewModel,
    navController: androidx.navigation.NavController?,
    month: String,
    expandedCompanies: Set<String>,
    expandedSalesPurchase: Set<String>,
    onDeleteInvoice: (com.vitol.inv3.data.remote.InvoiceRecord) -> Unit,
    onDeleteMonth: (String) -> Unit,
    onRemoveDuplicates: (String) -> Unit
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
                // Only show remove duplicates button if duplicates exist
                if (viewModel.hasDuplicatesForMonth(month)) {
                    IconButton(
                        onClick = { onRemoveDuplicates(month) },
                        modifier = Modifier
                    ) {
                        Icon(
                            imageVector = Icons.Default.MergeType,
                            contentDescription = "Remove duplicates",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
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

            // Expanded Sales/Purchase breakdown
            if (isExpanded && salesPurchaseSummaries.isNotEmpty()) {
                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                salesPurchaseSummaries.forEach { salesPurchase ->
                    val salesPurchaseKey = "$month|${salesPurchase.type}"
                    val isSalesPurchaseExpanded = expandedSalesPurchase.contains(salesPurchaseKey)
                    val companySummariesForType = if (isSalesPurchaseExpanded) {
                        viewModel.getCompanySummariesForMonth(month, salesPurchase.type)
                    } else {
                        emptyList()
                    }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
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
                                    .clickable { viewModel.toggleSalesPurchaseExpansion(month, salesPurchase.type) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = salesPurchase.typeLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = buildAnnotatedString {
                                            append("invoices: ${salesPurchase.invoiceCount}")
                                            if (salesPurchase.errorCount > 0) {
                                                withStyle(style = SpanStyle(color = darkRed)) {
                                                    append(" [${salesPurchase.errorCount} to check]")
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${numberFormat.format(salesPurchase.totalAmount)} EUR (without VAT)",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${numberFormat.format(salesPurchase.totalVat)} EUR (VAT)",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(
                                    imageVector = if (isSalesPurchaseExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isSalesPurchaseExpanded) "Collapse" else "Expand"
                                )
                            }
                            
                            // Expanded company breakdown for this type
                            if (isSalesPurchaseExpanded && companySummariesForType.isNotEmpty()) {
                                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    text = "Companies:",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                companySummariesForType.forEach { company ->
                                    val companyKey = "$month|${salesPurchase.type}|${company.companyName}"
                                    val isCompanyExpanded = expandedCompanies.contains(companyKey)
                                    val companyInvoices = if (isCompanyExpanded) {
                                        viewModel.getInvoicesForCompany(month, company.companyName, salesPurchase.type)
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
                                                    .clickable { viewModel.toggleCompanyExpansion(month, company.companyName, salesPurchase.type) },
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
                                                            // Info button for invoices with errors
                                                            if (hasErrors) {
                                                                IconButton(
                                                                    onClick = {
                                                                        onShowInvoiceErrors(invoice)
                                                                    }
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Info,
                                                                        contentDescription = "Show validation errors",
                                                                        tint = darkRed
                                                                    )
                                                                }
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
        title = { 
            Text(
                text = "Export Options",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Choose how to export invoices for $month",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 16.dp)
                    )
                } else {
                    // Export Excel
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
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Export Excel")
                    }
                    
                    // Share Excel
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
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Share Excel")
                    }
                    
                    // Export XML (i.SAF)
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
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Export XML (i.SAF)")
                    }
                    
                    // Share XML (i.SAF)
                    OutlinedButton(
                        onClick = {
                            if (ownCompany == null) {
                                Toast.makeText(context, "Please select an own company first", Toast.LENGTH_LONG).show()
                                return@OutlinedButton
                            }
                            try {
                                val exporter = ISafXmlExporter(context)
                                val uri = exporter.export(invoiceRecords, ownCompany, month)
                                val dataType = exporter.determineDataType(invoiceRecords)
                                val fileName = "isaf_${month}_${dataType}.xml"
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/xml"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Share $fileName"))
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = !isSaving && ownCompany != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Share XML (i.SAF)")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

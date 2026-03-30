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
import androidx.compose.ui.res.stringResource
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
import com.vitol.inv3.ui.home.OwnCompanyViewModel
import com.vitol.inv3.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ExportsScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: ExportsViewModel = hiltViewModel(),
    ownCompanyViewModel: OwnCompanyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val monthlySummaries by viewModel.monthlySummaries.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val expandedMonths by viewModel.expandedMonths.collectAsState()
    val expandedCompanies by viewModel.expandedCompanies.collectAsState()
    val expandedSalesPurchase by viewModel.expandedSalesPurchase.collectAsState()
    val availableYears = viewModel.getAvailableYears()
    
    val activeCompanyIdFlow = remember { context.getActiveOwnCompanyIdFlow() }
    val activeCompanyId by activeCompanyIdFlow.collectAsState(initial = null)
    val ownCompanies by ownCompanyViewModel.ownCompanies.collectAsState()
    var selectedExportCompanyId by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        ownCompanyViewModel.loadOwnCompanies()
    }
    LaunchedEffect(activeCompanyId, ownCompanies) {
        when {
            ownCompanies.size == 1 -> {
                selectedExportCompanyId = ownCompanies.first().id
                viewModel.setSelectedOwnCompanyId(selectedExportCompanyId)
            }
            activeCompanyId != null -> {
                selectedExportCompanyId = activeCompanyId
                viewModel.setSelectedOwnCompanyId(activeCompanyId)
            }
            else -> {
                selectedExportCompanyId = null
                viewModel.setSelectedOwnCompanyId(null)
            }
        }
    }
    
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
            selectedCompanyId = selectedExportCompanyId,
            onDismiss = { exportDialogState = null },
            onRefresh = { viewModel.loadInvoices() },
            repo = repo
        )
    }
    
    // Show delete confirmation dialog for single invoice
    invoiceToDelete?.let { invoice ->
        AlertDialog(
            onDismissRequest = { invoiceToDelete = null },
            title = { Text(stringResource(R.string.exports_delete_title)) },
            text = {
                val fallback = stringResource(R.string.exports_this_invoice)
                Text(stringResource(R.string.exports_delete_body, invoice.invoice_id ?: fallback))
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
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { invoiceToDelete = null }
                ) {
                    Text(stringResource(R.string.common_cancel))
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
                val invId = invoice.invoice_id ?: stringResource(R.string.common_unknown)
                Column {
                    Text(
                        text = stringResource(R.string.exports_validation_errors),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.exports_invoice_label, invId),
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
                        Text(stringResource(R.string.exports_no_errors))
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
                                            text = error.localizedMessage(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.exports_error_field, error.fieldName),
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
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
    
    // Show delete confirmation dialog for month
    monthToDelete?.let { month ->
        val monthInvoices = viewModel.getInvoicesForMonth(month)
        AlertDialog(
            onDismissRequest = { monthToDelete = null },
            title = { Text(stringResource(R.string.exports_delete_month_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.exports_delete_month_body,
                        monthInvoices.size,
                        month
                    )
                )
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
                    Text(stringResource(R.string.exports_delete_all))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { monthToDelete = null }
                ) {
                    Text(stringResource(R.string.common_cancel))
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
            title = { Text(stringResource(R.string.exports_remove_dup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.exports_remove_dup_body, duplicateCountToRemove, month))
                    Text(
                        text = stringResource(R.string.exports_remove_dup_detail),
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
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.exports_dup_removed, removedCount),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.exports_no_dup),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.exports_remove_dup_action))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { 
                        monthToRemoveDuplicates = null
                        duplicateCountToRemove = 0
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
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
        if (ownCompanies.size > 1) {
            CompanyFilterDropdown(
                ownCompanies = ownCompanies,
                selectedCompanyId = selectedExportCompanyId,
                onCompanySelected = {
                    selectedExportCompanyId = it
                    viewModel.setSelectedOwnCompanyId(it)
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else if (ownCompanies.isEmpty()) {
            Text(
                text = stringResource(R.string.exports_add_company_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
                Text(stringResource(R.string.exports_export_all))
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
                                Toast.makeText(context, context.getString(R.string.exports_no_dup), Toast.LENGTH_SHORT).show()
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
            label = { Text(stringResource(R.string.exports_year)) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyFilterDropdown(
    ownCompanies: List<CompanyRecord>,
    selectedCompanyId: String?,
    onCompanySelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedCompany = ownCompanies.find { it.id == selectedCompanyId }
    val displayText = selectedCompany?.company_name ?: stringResource(R.string.exports_select_company)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.exports_for_company)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ownCompanies.forEach { company ->
                DropdownMenuItem(
                    text = {
                        Text(company.company_name ?: company.company_number ?: stringResource(R.string.common_unknown))
                    },
                    onClick = {
                        onCompanySelected(company.id)
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
    val invoiceNoIdLabel = stringResource(R.string.invoice_no_id)
    val cdShowErrors = stringResource(R.string.cd_show_errors)
    val cdEditInvoice = stringResource(R.string.cd_edit)
    val cdDeleteInvoice = stringResource(R.string.common_delete)

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
                        contentDescription = if (isExpanded) stringResource(R.string.cd_collapse)
                        else stringResource(R.string.cd_expand)
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
                            contentDescription = stringResource(R.string.cd_remove_duplicates),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onExportClick) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.cd_export)
                    )
                }
                IconButton(
                    onClick = { onDeleteMonth(month) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_delete_month),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Summary details
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val invoiceQtyLine = stringResource(R.string.exports_invoice_qty, summary.invoiceCount)
                val toCheckSuffix = if (summary.errorCount > 0) {
                    stringResource(R.string.exports_to_check_bracket, summary.errorCount)
                } else null
                Text(
                    text = buildAnnotatedString {
                        append(invoiceQtyLine)
                        if (toCheckSuffix != null) {
                            withStyle(style = SpanStyle(color = darkRed)) {
                                append(toCheckSuffix)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.exports_eur_without_vat,
                        numberFormat.format(summary.totalAmount)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.exports_eur_vat,
                        numberFormat.format(summary.totalVat)
                    ),
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
                                    val spInvLine = stringResource(R.string.exports_invoices_count, salesPurchase.invoiceCount)
                                    val spCheck = if (salesPurchase.errorCount > 0) {
                                        stringResource(R.string.exports_to_check_bracket, salesPurchase.errorCount)
                                    } else null
                                    Text(
                                        text = when (salesPurchase.type) {
                                            "S" -> stringResource(R.string.invoice_type_sales_label)
                                            else -> stringResource(R.string.invoice_type_purchase_label)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = buildAnnotatedString {
                                            append(spInvLine)
                                            if (spCheck != null) {
                                                withStyle(style = SpanStyle(color = darkRed)) {
                                                    append(spCheck)
                                                }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.exports_eur_without_vat,
                                            numberFormat.format(salesPurchase.totalAmount)
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.exports_eur_vat,
                                            numberFormat.format(salesPurchase.totalVat)
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(
                                    imageVector = if (isSalesPurchaseExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isSalesPurchaseExpanded) stringResource(R.string.cd_collapse)
                                    else stringResource(R.string.cd_expand)
                                )
                            }
                            
                            // Expanded company breakdown for this type
                            if (isSalesPurchaseExpanded && companySummariesForType.isNotEmpty()) {
                                Spacer(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    text = stringResource(R.string.exports_companies_header),
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
                                                    val coInvLine = stringResource(R.string.exports_invoices_count, company.invoiceCount)
                                                    val coCheck = if (company.errorCount > 0) {
                                                        stringResource(R.string.exports_to_check_bracket, company.errorCount)
                                                    } else null
                                                    Text(
                                                        text = buildAnnotatedString {
                                                            append(coInvLine)
                                                            if (coCheck != null) {
                                                                withStyle(style = SpanStyle(color = darkRed)) {
                                                                    append(coCheck)
                                                                }
                                                            }
                                                        },
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    Text(
                                                        text = stringResource(
                                                            R.string.exports_eur_without_vat,
                                                            numberFormat.format(company.totalAmount)
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    Text(
                                                        text = stringResource(
                                                            R.string.exports_eur_vat,
                                                            numberFormat.format(company.totalVat)
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                Icon(
                                                    imageVector = if (isCompanyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                    contentDescription = if (isCompanyExpanded) stringResource(R.string.cd_collapse)
                                                    else stringResource(R.string.cd_expand)
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
                                                                    text = invoice.invoice_id ?: invoiceNoIdLabel,
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
                                                                    text = stringResource(
                                                                        R.string.exports_eur_without_vat,
                                                                        numberFormat.format(invoice.amount_without_vat_eur ?: 0.0)
                                                                    ),
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = textColor
                                                                )
                                                                Text(
                                                                    text = stringResource(
                                                                        R.string.exports_eur_vat,
                                                                        numberFormat.format(invoice.vat_amount_eur ?: 0.0)
                                                                    ),
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
                                                                        contentDescription = cdShowErrors,
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
                                                                    contentDescription = cdEditInvoice
                                                                )
                                                            }
                                                            IconButton(
                                                                onClick = {
                                                                    onDeleteInvoice(invoice)
                                                                }
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Delete,
                                                                    contentDescription = cdDeleteInvoice,
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
    selectedCompanyId: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    repo: com.vitol.inv3.data.remote.SupabaseRepository
) {
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var ownCompany by remember { mutableStateOf<CompanyRecord?>(null) }
    
    // Load own company from selected company for export
    LaunchedEffect(selectedCompanyId) {
        if (selectedCompanyId != null) {
            ownCompany = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.getCompanyById(selectedCompanyId)
            }
        } else {
            ownCompany = null
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = stringResource(R.string.exports_export_options_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.exports_choose_how, month),
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
                            val result = exporter.saveToDownloads(invoices, month, ownCompany)
                            isSaving = false
                            onDismiss()
                            if (result != null) {
                                Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.exports_failed_save), Toast.LENGTH_SHORT).show()
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
                        Text(stringResource(R.string.exports_excel))
                    }
                    
                    // Share Excel
                    OutlinedButton(
                        onClick = {
                            val exporter = ExcelExporter(context)
                            val uri = exporter.export(invoices, month, ownCompany)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(share, context.getString(R.string.exports_share_chooser_excel, month))
                            )
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
                        Text(stringResource(R.string.exports_share_excel))
                    }
                    
                    // Export XML (i.SAF)
                    Button(
                        onClick = {
                            if (ownCompany == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.exports_select_company_first),
                                    Toast.LENGTH_LONG
                                ).show()
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
                                        Toast.makeText(context, context.getString(R.string.exports_failed_save_xml), Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    isSaving = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.exports_error_message, e.message ?: ""),
                                        Toast.LENGTH_LONG
                                    ).show()
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
                        Text(stringResource(R.string.exports_xml))
                    }
                    
                    // Share XML (i.SAF)
                    OutlinedButton(
                        onClick = {
                            if (ownCompany == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.exports_select_company_first),
                                    Toast.LENGTH_LONG
                                ).show()
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
                                context.startActivity(Intent.createChooser(share, context.getString(R.string.exports_share_chooser_file, fileName)))
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.exports_error_message, e.message ?: ""),
                                    Toast.LENGTH_LONG
                                ).show()
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
                        Text(stringResource(R.string.exports_share_xml))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

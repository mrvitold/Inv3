package com.vitol.inv3.ui.exports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ocr.InvoiceValidator
import com.vitol.inv3.ui.exports.InvoiceError
import com.vitol.inv3.ui.exports.InvoiceValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ExportsViewModel @Inject constructor(
    private val repo: SupabaseRepository,
    private val invoiceValidator: InvoiceValidator
) : ViewModel() {

    private val _invoices = MutableStateFlow<List<InvoiceRecord>>(emptyList())
    val invoices: StateFlow<List<InvoiceRecord>> = _invoices.asStateFlow()

    private val _monthlySummaries = MutableStateFlow<List<MonthlySummary>>(emptyList())
    val monthlySummaries: StateFlow<List<MonthlySummary>> = _monthlySummaries.asStateFlow()

    private val _selectedYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    val selectedYear: StateFlow<Int> = _selectedYear.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _expandedMonths = MutableStateFlow<Set<String>>(emptySet())
    val expandedMonths: StateFlow<Set<String>> = _expandedMonths.asStateFlow()

    private val _expandedCompanies = MutableStateFlow<Set<String>>(emptySet())
    val expandedCompanies: StateFlow<Set<String>> = _expandedCompanies.asStateFlow()

    private val _expandedSalesPurchase = MutableStateFlow<Set<String>>(emptySet())
    val expandedSalesPurchase: StateFlow<Set<String>> = _expandedSalesPurchase.asStateFlow()

    // Cache validation results to avoid recalculating
    private val _validationResults = MutableStateFlow<Map<String, InvoiceValidationResult>>(emptyMap())
    private val validationResults: Map<String, InvoiceValidationResult>
        get() = _validationResults.value

    init {
        loadInvoices()
    }

    fun loadInvoices() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val allInvoices = repo.getAllInvoices()
                _invoices.value = allInvoices
                validateAllInvoices(allInvoices)
                calculateMonthlySummaries(allInvoices)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load invoices")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun validateAllInvoices(allInvoices: List<InvoiceRecord>) {
        val results = mutableMapOf<String, InvoiceValidationResult>()
        
        allInvoices.forEach { invoice ->
            val invoiceId = invoice.id ?: return@forEach
            val errors = invoiceValidator.validateInvoice(invoice, allInvoices)
            results[invoiceId] = InvoiceValidationResult(invoice, errors)
        }
        
        _validationResults.value = results
        Timber.d("Validated ${results.size} invoices, found ${results.values.sumOf { it.errors.size }} total errors")
    }

    fun getInvoiceErrors(invoice: InvoiceRecord): List<InvoiceError> {
        val invoiceId = invoice.id ?: return emptyList()
        return validationResults[invoiceId]?.errors ?: emptyList()
    }

    fun setSelectedYear(year: Int) {
        _selectedYear.value = year
        calculateMonthlySummaries(_invoices.value)
    }

    private fun calculateMonthlySummaries(invoices: List<InvoiceRecord>) {
        val year = _selectedYear.value
        val monthMap = mutableMapOf<String, MutableList<InvoiceRecord>>()

        // Group invoices by month (YYYY-MM format)
        invoices.forEach { invoice ->
            val dateStr = invoice.date
            if (!dateStr.isNullOrBlank()) {
                try {
                    // Parse date - could be YYYY-MM-DD or DD.MM.YYYY
                    val month = when {
                        dateStr.contains("-") -> {
                            // YYYY-MM-DD format
                            dateStr.substring(0, 7) // "2025-01"
                        }
                        dateStr.contains(".") -> {
                            // DD.MM.YYYY format
                            val parts = dateStr.split(".")
                            if (parts.size == 3) {
                                val yearPart = parts[2].trim()
                                val monthPart = parts[1].trim().padStart(2, '0')
                                "$yearPart-$monthPart"
                            } else {
                                null
                            }
                        }
                        else -> null
                    }

                    if (month != null && month.startsWith("$year-")) {
                        monthMap.getOrPut(month) { mutableListOf() }.add(invoice)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse date: $dateStr")
                }
            }
        }

        // Calculate summaries for each month
        val summaries = monthMap.map { (month, monthInvoices) ->
            val totalAmount = monthInvoices.sumOf { it.amount_without_vat_eur ?: 0.0 }
            val totalVat = monthInvoices.sumOf { it.vat_amount_eur ?: 0.0 }
            // Count invoices with errors
            val errorCount = monthInvoices.count { invoice ->
                val invoiceId = invoice.id ?: return@count false
                validationResults[invoiceId]?.hasErrors == true
            }
            MonthlySummary(
                month = month,
                invoiceCount = monthInvoices.size,
                totalAmount = totalAmount,
                totalVat = totalVat,
                errorCount = errorCount
            )
        }.sortedByDescending { it.month } // Most recent first

        _monthlySummaries.value = summaries
        Timber.d("Calculated ${summaries.size} monthly summaries for year $year")
    }

    fun getSalesPurchaseSummariesForMonth(month: String): List<SalesPurchaseSummary> {
        val monthInvoices = getInvoicesForMonth(month)

        // Group by invoice type (S = Sales, P = Purchase)
        val typeMap = mutableMapOf<String, MutableList<InvoiceRecord>>()
        monthInvoices.forEach { invoice ->
            val invoiceType = invoice.invoice_type ?: "P" // Default to Purchase if not set
            typeMap.getOrPut(invoiceType) { mutableListOf() }.add(invoice)
        }

        // Calculate summaries for each type
        return typeMap.map { (type, typeInvoices) ->
            val totalAmount = typeInvoices.sumOf { it.amount_without_vat_eur ?: 0.0 }
            val totalVat = typeInvoices.sumOf { it.vat_amount_eur ?: 0.0 }
            // Count invoices with errors
            val errorCount = typeInvoices.count { invoice ->
                val invoiceId = invoice.id ?: return@count false
                validationResults[invoiceId]?.hasErrors == true
            }
            SalesPurchaseSummary(
                type = type,
                typeLabel = if (type == "S") "Sales" else "Purchase",
                invoiceCount = typeInvoices.size,
                totalAmount = totalAmount,
                totalVat = totalVat,
                errorCount = errorCount
            )
        }.sortedByDescending { it.type } // Sales (S) first, then Purchase (P)
    }

    fun getCompanySummariesForMonth(month: String, invoiceType: String? = null): List<CompanySummary> {
        val monthInvoices = getInvoicesForMonth(month).filter { invoice ->
            if (invoiceType != null) {
                (invoice.invoice_type ?: "P") == invoiceType
            } else {
                true
            }
        }

        // Group by company name
        val companyMap = mutableMapOf<String, MutableList<InvoiceRecord>>()
        monthInvoices.forEach { invoice ->
            val companyName = invoice.company_name ?: "Unknown"
            companyMap.getOrPut(companyName) { mutableListOf() }.add(invoice)
        }

        // Calculate summaries for each company
        return companyMap.map { (companyName, companyInvoices) ->
            val totalAmount = companyInvoices.sumOf { it.amount_without_vat_eur ?: 0.0 }
            val totalVat = companyInvoices.sumOf { it.vat_amount_eur ?: 0.0 }
            // Count invoices with errors
            val errorCount = companyInvoices.count { invoice ->
                val invoiceId = invoice.id ?: return@count false
                validationResults[invoiceId]?.hasErrors == true
            }
            CompanySummary(
                companyName = companyName,
                invoiceCount = companyInvoices.size,
                totalAmount = totalAmount,
                totalVat = totalVat,
                errorCount = errorCount
            )
        }.sortedBy { it.companyName }
    }

    fun getInvoicesForMonth(month: String): List<InvoiceRecord> {
        return _invoices.value.filter { invoice ->
            val dateStr = invoice.date
            if (!dateStr.isNullOrBlank()) {
                try {
                    val invoiceMonth = when {
                        dateStr.contains("-") -> dateStr.substring(0, 7)
                        dateStr.contains(".") -> {
                            val parts = dateStr.split(".")
                            if (parts.size == 3) {
                                val yearPart = parts[2].trim()
                                val monthPart = parts[1].trim().padStart(2, '0')
                                "$yearPart-$monthPart"
                            } else null
                        }
                        else -> null
                    }
                    invoiceMonth == month
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }

    fun toggleMonthExpansion(month: String) {
        val current = _expandedMonths.value.toMutableSet()
        if (current.contains(month)) {
            current.remove(month)
        } else {
            current.add(month)
        }
        _expandedMonths.value = current
    }

    fun getInvoicesForCompany(month: String, companyName: String, invoiceType: String? = null): List<InvoiceRecord> {
        val monthInvoices = getInvoicesForMonth(month)
        return monthInvoices.filter { invoice ->
            val matchesCompany = (invoice.company_name ?: "Unknown") == companyName
            val matchesType = invoiceType == null || (invoice.invoice_type ?: "P") == invoiceType
            matchesCompany && matchesType
        }
    }

    fun toggleSalesPurchaseExpansion(month: String, invoiceType: String) {
        val key = "$month|$invoiceType"
        val current = _expandedSalesPurchase.value.toMutableSet()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        _expandedSalesPurchase.value = current
    }

    fun isSalesPurchaseExpanded(month: String, invoiceType: String): Boolean {
        val key = "$month|$invoiceType"
        return _expandedSalesPurchase.value.contains(key)
    }

    fun toggleCompanyExpansion(month: String, companyName: String, invoiceType: String? = null) {
        val key = if (invoiceType != null) {
            "$month|$invoiceType|$companyName"
        } else {
            "$month|$companyName"
        }
        val current = _expandedCompanies.value.toMutableSet()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        _expandedCompanies.value = current
    }

    fun isCompanyExpanded(month: String, companyName: String, invoiceType: String? = null): Boolean {
        val key = if (invoiceType != null) {
            "$month|$invoiceType|$companyName"
        } else {
            "$month|$companyName"
        }
        return _expandedCompanies.value.contains(key)
    }

    fun getAvailableYears(): List<Int> {
        val years = mutableSetOf<Int>()
        _invoices.value.forEach { invoice ->
            val dateStr = invoice.date
            if (!dateStr.isNullOrBlank()) {
                try {
                    val year = when {
                        dateStr.contains("-") -> {
                            dateStr.substring(0, 4).toIntOrNull()
                        }
                        dateStr.contains(".") -> {
                            val parts = dateStr.split(".")
                            if (parts.size == 3) {
                                parts[2].trim().toIntOrNull()
                            } else null
                        }
                        else -> null
                    }
                    year?.let { years.add(it) }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
        }
        return years.sortedDescending().ifEmpty { listOf(Calendar.getInstance().get(Calendar.YEAR)) }
    }

    fun deleteInvoice(invoice: InvoiceRecord, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                repo.deleteInvoice(invoice)
                Timber.d("Invoice deleted successfully: ${invoice.invoice_id}")
                // Reload invoices to refresh the UI
                loadInvoices()
                onComplete()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete invoice: ${invoice.invoice_id}")
                // Still reload to ensure UI is in sync
                loadInvoices()
                onComplete()
            }
        }
    }

    fun getAllInvoicesForYear(year: Int): List<InvoiceRecord> {
        return _invoices.value.filter { invoice ->
            val dateStr = invoice.date
            if (!dateStr.isNullOrBlank()) {
                try {
                    val invoiceYear = when {
                        dateStr.contains("-") -> {
                            dateStr.substring(0, 4).toIntOrNull()
                        }
                        dateStr.contains(".") -> {
                            val parts = dateStr.split(".")
                            if (parts.size == 3) {
                                parts[2].trim().toIntOrNull()
                            } else null
                        }
                        else -> null
                    }
                    invoiceYear == year
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }

    /**
     * Check if there are duplicate invoices (same invoice_id) for a given month.
     * Returns true if duplicates are found, false otherwise.
     */
    fun hasDuplicatesForMonth(month: String): Boolean {
        val monthInvoices = getInvoicesForMonth(month)
        
        // Group invoices by invoice_id
        val invoicesByInvoiceId = mutableMapOf<String, MutableList<InvoiceRecord>>()
        monthInvoices.forEach { invoice ->
            val invoiceId = invoice.invoice_id
            if (!invoiceId.isNullOrBlank()) {
                invoicesByInvoiceId.getOrPut(invoiceId) { mutableListOf() }.add(invoice)
            }
        }
        
        // Check if any group has more than 1 invoice (duplicates)
        return invoicesByInvoiceId.values.any { it.size > 1 }
    }

    /**
     * Find and remove duplicate invoices (same invoice_id) for a given month.
     * Keeps the most recent invoice (by date, then by created_at if available).
     * Returns the count of duplicates removed.
     */
    fun removeDuplicatesForMonth(month: String, onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val monthInvoices = getInvoicesForMonth(month)
                
                // Group invoices by invoice_id
                val invoicesByInvoiceId = mutableMapOf<String, MutableList<InvoiceRecord>>()
                monthInvoices.forEach { invoice ->
                    val invoiceId = invoice.invoice_id
                    if (!invoiceId.isNullOrBlank()) {
                        invoicesByInvoiceId.getOrPut(invoiceId) { mutableListOf() }.add(invoice)
                    }
                }
                
                // Find duplicates (groups with more than 1 invoice)
                var duplicatesRemoved = 0
                invoicesByInvoiceId.values.forEach { group ->
                    if (group.size > 1) {
                        // Sort by date (most recent first), then by id (as a tiebreaker)
                        val sorted = group.sortedWith(compareByDescending<InvoiceRecord> { invoice ->
                            // Parse date for comparison
                            val dateStr = invoice.date
                            when {
                                dateStr.isNullOrBlank() -> 0L
                                dateStr.contains("-") -> {
                                    // YYYY-MM-DD format
                                    try {
                                        dateStr.replace("-", "").toLongOrNull() ?: 0L
                                    } catch (e: Exception) {
                                        0L
                                    }
                                }
                                dateStr.contains(".") -> {
                                    // DD.MM.YYYY format
                                    try {
                                        val parts = dateStr.split(".")
                                        if (parts.size == 3) {
                                            val year = parts[2].trim()
                                            val monthPart = parts[1].trim().padStart(2, '0')
                                            val day = parts[0].trim().padStart(2, '0')
                                            "$year$monthPart$day".toLongOrNull() ?: 0L
                                        } else {
                                            0L
                                        }
                                    } catch (e: Exception) {
                                        0L
                                    }
                                }
                                else -> 0L
                            }
                        }.thenByDescending { it.id ?: "" })
                        
                        // Keep the first (most recent), delete the rest
                        val toKeep = sorted.first()
                        val toDelete = sorted.drop(1)
                        
                        toDelete.forEach { duplicate ->
                            try {
                                repo.deleteInvoice(duplicate)
                                duplicatesRemoved++
                                Timber.d("Deleted duplicate invoice: ${duplicate.invoice_id} (id: ${duplicate.id}), kept: ${toKeep.id}")
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to delete duplicate invoice: ${duplicate.invoice_id}")
                            }
                        }
                    }
                }
                
                if (duplicatesRemoved > 0) {
                    Timber.d("Removed $duplicatesRemoved duplicate invoice(s) for month $month")
                    // Reload invoices to refresh the UI
                    loadInvoices()
                }
                
                onComplete(duplicatesRemoved)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove duplicates for month: $month")
                onComplete(0)
            }
        }
    }
}


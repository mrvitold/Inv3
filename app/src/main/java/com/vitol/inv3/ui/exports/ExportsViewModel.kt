package com.vitol.inv3.ui.exports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.data.remote.SupabaseRepository
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
    private val repo: SupabaseRepository
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

    init {
        loadInvoices()
    }

    fun loadInvoices() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val allInvoices = repo.getAllInvoices()
                _invoices.value = allInvoices
                calculateMonthlySummaries(allInvoices)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load invoices")
            } finally {
                _isLoading.value = false
            }
        }
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
            MonthlySummary(
                month = month,
                invoiceCount = monthInvoices.size,
                totalAmount = totalAmount,
                totalVat = totalVat
            )
        }.sortedByDescending { it.month } // Most recent first

        _monthlySummaries.value = summaries
        Timber.d("Calculated ${summaries.size} monthly summaries for year $year")
    }

    fun getCompanySummariesForMonth(month: String): List<CompanySummary> {
        val year = _selectedYear.value
        val monthInvoices = _invoices.value.filter { invoice ->
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
            CompanySummary(
                companyName = companyName,
                invoiceCount = companyInvoices.size,
                totalAmount = totalAmount,
                totalVat = totalVat
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

    fun getInvoicesForCompany(month: String, companyName: String): List<InvoiceRecord> {
        val monthInvoices = getInvoicesForMonth(month)
        return monthInvoices.filter { invoice ->
            (invoice.company_name ?: "Unknown") == companyName
        }
    }

    fun toggleCompanyExpansion(month: String, companyName: String) {
        val key = "$month|$companyName"
        val current = _expandedCompanies.value.toMutableSet()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        _expandedCompanies.value = current
    }

    fun isCompanyExpanded(month: String, companyName: String): Boolean {
        val key = "$month|$companyName"
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
}


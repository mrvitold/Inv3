package com.vitol.inv3.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.analytics.AppAnalytics
import com.vitol.inv3.data.remote.LithuanianOpenDataApi
import com.vitol.inv3.data.remote.LtOpenDataCompanySuggestion
import com.vitol.inv3.billing.SubscriptionLimitsProvider
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OwnCompanyViewModel @Inject constructor(
    private val repo: SupabaseRepository,
    private val limitsProvider: SubscriptionLimitsProvider,
    private val appAnalytics: AppAnalytics
) : ViewModel() {
    
    private val _ownCompanies = MutableStateFlow<List<CompanyRecord>>(emptyList())
    val ownCompanies: StateFlow<List<CompanyRecord>> = _ownCompanies.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _companyNameQuery = MutableStateFlow("")
    val companyNameQuery: StateFlow<String> = _companyNameQuery.asStateFlow()

    private val _nameSuggestions = MutableStateFlow<List<LtOpenDataCompanySuggestion>>(emptyList())
    val nameSuggestions: StateFlow<List<LtOpenDataCompanySuggestion>> = _nameSuggestions.asStateFlow()

    private val _isSearchingSuggestions = MutableStateFlow(false)
    val isSearchingSuggestions: StateFlow<Boolean> = _isSearchingSuggestions.asStateFlow()

    private var suggestionSearchJob: Job? = null

    val maxOwnCompanies: Int
        get() = limitsProvider.getMaxOwnCompanies()

    fun loadOwnCompanies() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val companies = repo.getAllOwnCompanies()
                _ownCompanies.value = companies
                Timber.d("Loaded ${companies.size} own companies")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load own companies")
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    sealed class SaveCompanyResult {
        data class Success(val company: CompanyRecord) : SaveCompanyResult()
        data object LimitReached : SaveCompanyResult()
        data object Error : SaveCompanyResult()
    }

    suspend fun saveCompany(company: CompanyRecord, source: String = "unknown"): SaveCompanyResult {
        if (company.is_own_company) {
            val ownCompanies = repo.getAllOwnCompanies()
            val isEditingExistingOwn = company.id != null && ownCompanies.any { it.id == company.id }
            if (!isEditingExistingOwn && !limitsProvider.canAddOwnCompany(ownCompanies.size)) {
                return SaveCompanyResult.LimitReached
            }
        }
        return try {
            val saved = repo.upsertCompany(company)
            loadOwnCompanies()
            saved?.let {
                if (it.is_own_company && !it.company_name.isNullOrBlank() && !it.company_number.isNullOrBlank()) {
                    appAnalytics.trackOwnCompanyFilled(
                        mode = if (company.id.isNullOrBlank()) "create" else "edit",
                        hasVatNumber = !it.vat_number.isNullOrBlank(),
                        source = source
                    )
                }
                SaveCompanyResult.Success(it)
            } ?: SaveCompanyResult.Error
        } catch (e: Exception) {
            Timber.e(e, "Failed to save company")
            SaveCompanyResult.Error
        }
    }
    
    suspend fun removeOwnCompany(companyId: String?) {
        if (companyId.isNullOrBlank()) return
        try {
            repo.unmarkAsOwnCompany(companyId)
            loadOwnCompanies()
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove own company")
            throw e
        }
    }

    fun onCompanyNameInputChanged(query: String) {
        _companyNameQuery.value = query
        val trimmed = query.trim()
        suggestionSearchJob?.cancel()

        if (trimmed.length < 2) {
            _isSearchingSuggestions.value = false
            _nameSuggestions.value = emptyList()
            return
        }

        suggestionSearchJob = viewModelScope.launch {
            _isSearchingSuggestions.value = true
            try {
                delay(280)
                val suggestions = LithuanianOpenDataApi.searchCompaniesByName(trimmed)
                _nameSuggestions.value = suggestions
            } catch (_: CancellationException) {
                // Expected while user is still typing.
            } catch (e: Exception) {
                Timber.w(e, "Own company name suggestion lookup failed")
                _nameSuggestions.value = emptyList()
            } finally {
                _isSearchingSuggestions.value = false
            }
        }
    }

    fun clearCompanyNameSuggestions() {
        suggestionSearchJob?.cancel()
        _isSearchingSuggestions.value = false
        _nameSuggestions.value = emptyList()
    }
}


package com.vitol.inv3.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.data.remote.CompanyRecord
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OwnCompanyViewModel @Inject constructor(
    private val repo: SupabaseRepository
) : ViewModel() {
    
    private val _ownCompanies = MutableStateFlow<List<CompanyRecord>>(emptyList())
    val ownCompanies: StateFlow<List<CompanyRecord>> = _ownCompanies.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
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
    
    suspend fun saveCompany(company: CompanyRecord): CompanyRecord? {
        return try {
            val saved = repo.upsertCompany(company)
            loadOwnCompanies()
            saved
        } catch (e: Exception) {
            Timber.e(e, "Failed to save company")
            null
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
}


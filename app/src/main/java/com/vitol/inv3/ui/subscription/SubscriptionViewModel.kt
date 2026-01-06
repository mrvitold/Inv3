package com.vitol.inv3.ui.subscription

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.vitol.inv3.billing.BillingManager
import com.vitol.inv3.billing.SubscriptionPlan
import com.vitol.inv3.billing.SubscriptionStatus
import com.vitol.inv3.billing.UsageTracker
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val billingManager: BillingManager,
    private val usageTracker: UsageTracker,
    private val supabaseRepository: SupabaseRepository
) : ViewModel() {
    
    private val _subscriptionStatus = MutableStateFlow<SubscriptionStatus?>(null)
    val subscriptionStatus: StateFlow<SubscriptionStatus?> = _subscriptionStatus.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    val canScan: StateFlow<Boolean> = _subscriptionStatus
        .map { status ->
            status?.canScan ?: false
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    
    val errorMessage = billingManager.errorMessage
    
    init {
        viewModelScope.launch {
            // Combine billing status with usage tracking
            combine(
                billingManager.subscriptionStatus,
                usageTracker.getPagesUsedFlow(),
                usageTracker.getResetDateFlow(),
                usageTracker.getSubscriptionStartDateFlow()
            ) { billingStatus, pagesUsed, resetDate, startDate ->
                val plan = billingStatus?.plan ?: SubscriptionPlan.FREE
                val isActive = billingStatus?.isActive ?: (plan == SubscriptionPlan.FREE)
                
                val pagesRemaining = plan.pagesPerMonth - pagesUsed
                
                SubscriptionStatus(
                    plan = plan,
                    isActive = isActive,
                    pagesUsed = pagesUsed,
                    pagesRemaining = maxOf(0, pagesRemaining),
                    resetDate = resetDate
                )
            }.collect { status ->
                _subscriptionStatus.value = status
                
                // Sync to Supabase when status changes
                syncToSupabase(status)
            }
        }
        
        // Handle purchase completion
        viewModelScope.launch {
            billingManager.purchaseComplete.collect { purchase ->
                purchase?.let {
                    val planId = it.products.firstOrNull()
                    val plan = SubscriptionPlan.fromPlanId(planId)
                    handlePurchaseComplete(plan)
                    // Clear the purchase complete signal
                    billingManager.clearPurchaseComplete()
                }
            }
        }
        
        // Query purchases on init (automatic restoration)
        billingManager.queryPurchases()
    }
    
    fun checkCanScan(): Boolean {
        return _subscriptionStatus.value?.canScan ?: false
    }
    
    fun canScanPages(pageCount: Int): Boolean {
        val status = _subscriptionStatus.value
        // If status is null, assume FREE plan with 20 pages (default for new users)
        if (status == null) {
            // Default to FREE plan: 20 pages per month
            return pageCount <= 20
        }
        return status.canScanPages(pageCount)
    }
    
    fun trackPageUsage() {
        viewModelScope.launch {
            usageTracker.trackPageUsage()
            // Sync usage to Supabase
            syncUsageToSupabase()
        }
    }
    
    fun purchasePlan(activity: Activity, plan: SubscriptionPlan) {
        _isLoading.value = true
        billingManager.launchBillingFlow(activity, plan) { result ->
            viewModelScope.launch {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Purchase flow started - wait for purchase callback
                    // Status will be updated automatically via billingManager.subscriptionStatus
                    Timber.d("Billing flow launched successfully")
                } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    // User canceled - not an error
                    Timber.d("User canceled purchase")
                } else {
                    Timber.e("Purchase failed: ${result.debugMessage}")
                }
                _isLoading.value = false
            }
        }
    }
    
    fun refreshSubscriptionStatus() {
        viewModelScope.launch {
            billingManager.queryPurchases()
        }
    }
    
    private suspend fun syncToSupabase(status: SubscriptionStatus) {
        try {
            supabaseRepository.updateSubscriptionStatus(
                plan = status.plan.planId,
                isActive = status.isActive,
                startDate = usageTracker.getSubscriptionStartDate()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync subscription status to Supabase")
        }
    }
    
    private suspend fun syncUsageToSupabase() {
        try {
            val pagesUsed = usageTracker.getPagesUsed()
            supabaseRepository.updateUsageCount(pagesUsed)
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync usage count to Supabase")
        }
    }
    
    fun handlePurchaseComplete(plan: SubscriptionPlan) {
        viewModelScope.launch {
            // Set subscription start date when purchase completes
            usageTracker.setSubscriptionStartDate(System.currentTimeMillis())
            // Reset usage when upgrading (user gets full new plan limit)
            val currentStatus = _subscriptionStatus.value
            if (currentStatus != null && currentStatus.plan != plan) {
                // Upgrading - reset usage to give user full new plan limit
                usageTracker.resetUsage()
            }
            // Refresh subscription status
            billingManager.queryPurchases()
        }
    }
}


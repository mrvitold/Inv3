package com.vitol.inv3.billing

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides subscription limits for use across ViewModels.
 * Reads from BillingManager's subscription status.
 */
@Singleton
class SubscriptionLimitsProvider @Inject constructor(
    private val billingManager: BillingManager
) {
    val subscriptionStatus: StateFlow<SubscriptionStatus?> = billingManager.subscriptionStatus

    fun getMaxOwnCompanies(): Int {
        return billingManager.subscriptionStatus.value?.plan?.maxOwnCompanies
            ?: SubscriptionPlan.FREE.maxOwnCompanies
    }

    fun canAddOwnCompany(currentOwnCount: Int): Boolean {
        return currentOwnCount < getMaxOwnCompanies()
    }
}

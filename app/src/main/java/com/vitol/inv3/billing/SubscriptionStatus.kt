package com.vitol.inv3.billing

data class SubscriptionStatus(
    val plan: SubscriptionPlan,
    val isActive: Boolean,
    val pagesUsed: Int,
    val pagesRemaining: Int,
    val resetDate: Long // Timestamp when usage resets
) {
    val canScan: Boolean
        get() = pagesRemaining > 0 && isActive
    
    fun canScanPages(pageCount: Int): Boolean {
        return pagesRemaining >= pageCount && isActive
    }
}


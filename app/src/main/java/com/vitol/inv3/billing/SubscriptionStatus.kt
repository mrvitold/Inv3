package com.vitol.inv3.billing

data class SubscriptionStatus(
    val plan: SubscriptionPlan,
    val isActive: Boolean,
    val invoicesUsed: Int,
    val invoicesRemaining: Int,
    val invoiceLimit: Int,
    val resetDate: Long, // Timestamp when usage resets
    val isFirstMonth: Boolean = false // For Free plan: true if in first 30 days
) {
    val canScan: Boolean
        get() = invoicesRemaining > 0 && isActive

    fun canScanPages(pageCount: Int): Boolean {
        return invoicesRemaining >= pageCount && isActive
    }
}

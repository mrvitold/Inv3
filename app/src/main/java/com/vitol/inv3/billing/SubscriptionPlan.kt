package com.vitol.inv3.billing

enum class SubscriptionPlan(
    val planId: String,
    val invoicesFirstMonth: Int?,
    val invoicesPerMonth: Int,
    val maxOwnCompanies: Int,
) {
    FREE("free", 30, 5, 1),
    BASIC("basic_monthly", null, 60, 1),
    PRO("pro_monthly", null, 400, 6),
    ACCOUNTING("accounting_monthly", null, 3000, 50);

    /**
     * Get the invoice limit for the current period.
     * For FREE: 30 in first month, 5 thereafter.
     * For paid plans: invoicesPerMonth.
     */
    fun getInvoiceLimit(subscriptionStartDate: Long): Int {
        return when (this) {
            FREE -> {
                val isFirstMonth = (System.currentTimeMillis() - subscriptionStartDate) < 30L * 24 * 60 * 60 * 1000
                if (isFirstMonth) (invoicesFirstMonth ?: invoicesPerMonth) else invoicesPerMonth
            }
            else -> invoicesPerMonth
        }
    }

    companion object {
        fun fromPlanId(planId: String?): SubscriptionPlan {
            return values().find { it.planId == planId } ?: FREE
        }
    }
}

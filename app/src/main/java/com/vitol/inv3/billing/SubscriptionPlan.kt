package com.vitol.inv3.billing

enum class SubscriptionPlan(
    val planId: String,
    val invoicesFirstMonth: Int?,
    val invoicesPerMonth: Int,
    val maxOwnCompanies: Int,
    val price: String
) {
    FREE("free", 30, 5, 1, "Free"),
    BASIC("basic_monthly", null, 60, 1, "€7/month"),
    PRO("pro_monthly", null, 400, 6, "€17/month"),
    ACCOUNTING("accounting_monthly", null, 3000, 50, "€39/month");

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

    val invoicesDisplayText: String
        get() = when (this) {
            FREE -> "30 invoices first month, then 5/month"
            else -> "Up to $invoicesPerMonth invoices/month"
        }

    companion object {
        fun fromPlanId(planId: String?): SubscriptionPlan {
            return values().find { it.planId == planId } ?: FREE
        }
    }
}

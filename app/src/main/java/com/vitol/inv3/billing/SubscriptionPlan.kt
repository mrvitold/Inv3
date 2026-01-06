package com.vitol.inv3.billing

enum class SubscriptionPlan(val planId: String, val pagesPerMonth: Int, val price: String) {
    FREE("free", 20, "Free"),
    BASIC("basic_monthly", 100, "€5/month"),
    PRO("pro_monthly", 600, "€17/month");
    
    companion object {
        fun fromPlanId(planId: String?): SubscriptionPlan {
            return values().find { it.planId == planId } ?: FREE
        }
    }
}


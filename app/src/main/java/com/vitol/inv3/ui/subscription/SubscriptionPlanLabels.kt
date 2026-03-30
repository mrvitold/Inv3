package com.vitol.inv3.ui.subscription

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vitol.inv3.R
import com.vitol.inv3.billing.SubscriptionPlan

@Composable
fun SubscriptionPlan.localizedName(): String = stringResource(
    when (this) {
        SubscriptionPlan.FREE -> R.string.plan_name_free
        SubscriptionPlan.BASIC -> R.string.plan_name_basic
        SubscriptionPlan.PRO -> R.string.plan_name_pro
        SubscriptionPlan.ACCOUNTING -> R.string.plan_name_accounting
    }
)

@Composable
fun SubscriptionPlan.localizedPrice(): String = stringResource(
    when (this) {
        SubscriptionPlan.FREE -> R.string.plan_price_free
        SubscriptionPlan.BASIC -> R.string.plan_price_basic
        SubscriptionPlan.PRO -> R.string.plan_price_pro
        SubscriptionPlan.ACCOUNTING -> R.string.plan_price_accounting
    }
)

@Composable
fun SubscriptionPlan.localizedInvoicesSummary(): String = when (this) {
    SubscriptionPlan.FREE -> stringResource(R.string.plan_invoices_free)
    else -> stringResource(R.string.plan_invoices_paid, invoicesPerMonth)
}

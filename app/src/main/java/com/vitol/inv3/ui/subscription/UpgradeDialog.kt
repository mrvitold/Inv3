package com.vitol.inv3.ui.subscription

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitol.inv3.R
import com.vitol.inv3.billing.SubscriptionPlan
import com.vitol.inv3.billing.SubscriptionStatus

@Composable
fun UpgradeDialog(
    subscriptionStatus: SubscriptionStatus?,
    onDismiss: () -> Unit,
    onUpgradeClick: (SubscriptionPlan) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.subscription_upgrade_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (subscriptionStatus != null) {
                    Text(
                        text = stringResource(
                            R.string.subscription_upgrade_body_with_plan,
                            subscriptionStatus.invoiceLimit,
                            subscriptionStatus.plan.localizedName()
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.subscription_upgrade_prompt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = stringResource(R.string.subscription_upgrade_limit),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                SubscriptionPlanCard(
                    plan = SubscriptionPlan.BASIC,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.BASIC,
                    onClick = { onUpgradeClick(SubscriptionPlan.BASIC) }
                )

                SubscriptionPlanCard(
                    plan = SubscriptionPlan.PRO,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.PRO,
                    onClick = { onUpgradeClick(SubscriptionPlan.PRO) }
                )

                SubscriptionPlanCard(
                    plan = SubscriptionPlan.ACCOUNTING,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.ACCOUNTING,
                    onClick = { onUpgradeClick(SubscriptionPlan.ACCOUNTING) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subscription_maybe_later))
            }
        }
    )
}

@Composable
private fun SubscriptionPlanCard(
    plan: SubscriptionPlan,
    isCurrentPlan: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentPlan)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        onClick = onClick,
        enabled = !isCurrentPlan
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plan.localizedName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = plan.localizedInvoicesSummary(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = plan.localizedPrice(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isCurrentPlan) {
                    Text(
                        text = stringResource(R.string.subscription_current_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

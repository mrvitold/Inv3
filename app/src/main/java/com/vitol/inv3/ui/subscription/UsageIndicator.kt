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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UsageIndicator(
    subscriptionStatus: SubscriptionStatus?,
    modifier: Modifier = Modifier,
    onUpgradeClick: () -> Unit = {}
) {
    if (subscriptionStatus == null) {
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.subscription_plan_line,
                        subscriptionStatus.plan.localizedName()
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (subscriptionStatus.plan != SubscriptionPlan.FREE) {
                        Text(
                            text = subscriptionStatus.plan.localizedPrice(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = onUpgradeClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.subscription_change),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            Column {
                val usageText = if (subscriptionStatus.isFirstMonth) {
                    stringResource(
                        R.string.subscription_first_month_usage,
                        subscriptionStatus.invoicesUsed,
                        subscriptionStatus.invoiceLimit
                    )
                } else {
                    stringResource(
                        R.string.subscription_invoices_used,
                        subscriptionStatus.invoicesUsed,
                        subscriptionStatus.invoiceLimit
                    )
                }
                Text(
                    text = usageText,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                LinearProgressIndicator(
                    progress = {
                        if (subscriptionStatus.invoiceLimit > 0) {
                            subscriptionStatus.invoicesUsed.toFloat() / subscriptionStatus.invoiceLimit.toFloat()
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        subscriptionStatus.invoicesRemaining < 10 -> MaterialTheme.colorScheme.error
                        subscriptionStatus.invoicesRemaining < subscriptionStatus.invoiceLimit * 0.2 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }

            Text(
                text = stringResource(
                    R.string.subscription_resets,
                    formatDate(subscriptionStatus.resetDate)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (subscriptionStatus.plan == SubscriptionPlan.FREE ||
                subscriptionStatus.invoicesRemaining < 10) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onUpgradeClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text(
                        if (subscriptionStatus.plan == SubscriptionPlan.FREE) {
                            stringResource(R.string.subscription_upgrade_free)
                        } else {
                            stringResource(R.string.subscription_upgrade_more)
                        }
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

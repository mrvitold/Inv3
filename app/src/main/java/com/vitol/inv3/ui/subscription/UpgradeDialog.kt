package com.vitol.inv3.ui.subscription

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                text = "Upgrade Required",
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
                        text = "You've used all ${subscriptionStatus.plan.pagesPerMonth} pages in your ${subscriptionStatus.plan.name} plan.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Upgrade to continue scanning invoices:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "You've reached your page limit. Upgrade to continue scanning invoices:",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Basic Plan
                SubscriptionPlanCard(
                    plan = SubscriptionPlan.BASIC,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.BASIC,
                    onClick = { onUpgradeClick(SubscriptionPlan.BASIC) }
                )
                
                // Pro Plan
                SubscriptionPlanCard(
                    plan = SubscriptionPlan.PRO,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.PRO,
                    onClick = { onUpgradeClick(SubscriptionPlan.PRO) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Maybe Later")
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
                    text = plan.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${plan.pagesPerMonth} pages/month",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = plan.price,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isCurrentPlan) {
                    Text(
                        text = "Current",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}


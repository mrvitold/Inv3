package com.vitol.inv3.ui.subscription

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitol.inv3.billing.SubscriptionStatus
import java.text.SimpleDateFormat
import java.util.*

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
                    text = "Plan: ${subscriptionStatus.plan.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                if (subscriptionStatus.plan != com.vitol.inv3.billing.SubscriptionPlan.FREE) {
                    Text(
                        text = subscriptionStatus.plan.price,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Usage progress
            Column {
                Text(
                    text = "Pages used: ${subscriptionStatus.pagesUsed}/${subscriptionStatus.plan.pagesPerMonth}",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                LinearProgressIndicator(
                    progress = {
                        if (subscriptionStatus.plan.pagesPerMonth > 0) {
                            subscriptionStatus.pagesUsed.toFloat() / subscriptionStatus.plan.pagesPerMonth.toFloat()
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        subscriptionStatus.pagesRemaining < 10 -> MaterialTheme.colorScheme.error
                        subscriptionStatus.pagesRemaining < subscriptionStatus.plan.pagesPerMonth * 0.2 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            
            // Reset date
            Text(
                text = "Resets: ${formatDate(subscriptionStatus.resetDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Upgrade button for free users or users near limit
            if (subscriptionStatus.plan == com.vitol.inv3.billing.SubscriptionPlan.FREE || 
                subscriptionStatus.pagesRemaining < 10) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onUpgradeClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text(if (subscriptionStatus.plan == com.vitol.inv3.billing.SubscriptionPlan.FREE) {
                        "Upgrade Plan"
                    } else {
                        "Upgrade for More Pages"
                    })
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}


package com.vitol.inv3.ui.subscription

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import android.net.Uri
import com.vitol.inv3.R
import com.vitol.inv3.billing.SubscriptionPlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onNavigateBack: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                actionLabel = context.getString(R.string.subscription_dismiss),
                withDismissAction = true
            )
            viewModel.clearBillingError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subscription_plans_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                subscriptionStatus?.let { status ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.subscription_current_plan),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = status.plan.localizedName(),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(
                                    R.string.subscription_invoices_used,
                                    status.invoicesUsed,
                                    status.invoiceLimit
                                ),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(
                                    R.string.subscription_resets,
                                    formatDate(status.resetDate)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.subscription_available_plans),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                SubscriptionPlanCard(
                    plan = SubscriptionPlan.BASIC,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.BASIC,
                    isLoading = isLoading,
                    onClick = {
                        if (context is Activity) {
                            viewModel.purchasePlan(context, SubscriptionPlan.BASIC)
                        }
                    }
                )

                SubscriptionPlanCard(
                    plan = SubscriptionPlan.PRO,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.PRO,
                    isLoading = isLoading,
                    onClick = {
                        if (context is Activity) {
                            viewModel.purchasePlan(context, SubscriptionPlan.PRO)
                        }
                    }
                )

                SubscriptionPlanCard(
                    plan = SubscriptionPlan.ACCOUNTING,
                    isCurrentPlan = subscriptionStatus?.plan == SubscriptionPlan.ACCOUNTING,
                    isLoading = isLoading,
                    onClick = {
                        if (context is Activity) {
                            viewModel.purchasePlan(context, SubscriptionPlan.ACCOUNTING)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = { viewModel.refreshSubscriptionStatus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.subscription_refresh))
                }

                if (subscriptionStatus?.plan != SubscriptionPlan.FREE) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://play.google.com/store/account/subscriptions")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.subscription_manage_play))
                    }
                    Text(
                        text = stringResource(R.string.subscription_manage_play_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SubscriptionPlanCard(
    plan: SubscriptionPlan,
    isCurrentPlan: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentPlan)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.localizedName(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = plan.localizedInvoicesSummary(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = plan.localizedPrice(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onClick,
                enabled = !isCurrentPlan && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (isCurrentPlan) stringResource(R.string.subscription_current_badge)
                    else stringResource(R.string.subscription_subscribe)
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

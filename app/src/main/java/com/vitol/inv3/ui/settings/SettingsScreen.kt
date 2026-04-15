package com.vitol.inv3.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.vitol.inv3.R
import com.vitol.inv3.data.local.LANGUAGE_EN
import com.vitol.inv3.data.local.LANGUAGE_LT
import com.vitol.inv3.data.local.applyAppLocalesForTag
import com.vitol.inv3.data.local.getLanguageOverrideFlow
import com.vitol.inv3.data.local.setActiveOwnCompanyId
import com.vitol.inv3.data.local.setLanguageOverride
import com.vitol.inv3.ui.auth.AuthViewModel
import com.vitol.inv3.ui.subscription.SubscriptionViewModel
import com.vitol.inv3.ui.subscription.localizedName
import com.vitol.inv3.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun RowScope.LanguageChoiceButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val mod = Modifier.weight(1f)
    if (selected) {
        Button(modifier = mod, onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(modifier = mod, onClick = onClick) { Text(label) }
    }
}

@Composable
fun SettingsScreen(
    navController: NavHostController,
    authViewModel: AuthViewModel = hiltViewModel(),
    subscriptionViewModel: SubscriptionViewModel = hiltViewModel()
) {
    val uiState by authViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val languageOverride by context.getLanguageOverrideFlow().collectAsState(initial = null)
    val passwordChangedMessage = stringResource(R.string.auth_password_changed)

    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newPasswordVisibility by remember { mutableStateOf(false) }
    var confirmPasswordVisibility by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back)
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        uiState.errorMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        uiState.successMessage?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val englishSelected =
                        languageOverride == LANGUAGE_EN || languageOverride == null
                    val lithuanianSelected = languageOverride == LANGUAGE_LT
                    // Filled = chosen language; outlined = other option (clearer than FilterChip outline).
                    LanguageChoiceButton(
                        selected = englishSelected,
                        label = stringResource(R.string.settings_language_english),
                        onClick = {
                            scope.launch {
                                context.setLanguageOverride(LANGUAGE_EN)
                                applyAppLocalesForTag(LANGUAGE_EN)
                            }
                        }
                    )
                    LanguageChoiceButton(
                        selected = lithuanianSelected,
                        label = stringResource(R.string.settings_language_lithuanian),
                        onClick = {
                            scope.launch {
                                context.setLanguageOverride(LANGUAGE_LT)
                                applyAppLocalesForTag(LANGUAGE_LT)
                            }
                        }
                    )
                }
            }
        }

        val subscriptionStatus by subscriptionViewModel.subscriptionStatus.collectAsState()
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !uiState.isLoading) {
                    navController.navigate(Routes.Subscription)
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(R.string.cd_subscription),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_subscription),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    subscriptionStatus?.let { status ->
                        Text(
                            text = stringResource(
                                R.string.settings_plan_usage_line,
                                status.plan.localizedName(),
                                status.invoicesUsed,
                                status.invoiceLimit
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    } ?: run {
                        Text(
                            text = stringResource(R.string.settings_manage_subscription),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !uiState.isLoading) {
                    showChangePasswordDialog = true
                    authViewModel.clearMessages()
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = stringResource(R.string.cd_change_password),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_change_password),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_change_password_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !uiState.isLoading) {
                    scope.launch {
                        context.setActiveOwnCompanyId(null)
                        authViewModel.signOut()
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ExitToApp,
                    contentDescription = stringResource(R.string.cd_log_out),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_log_out),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_log_out_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !uiState.isLoading) {
                    showDeleteAccountDialog = true
                    authViewModel.clearMessages()
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_account),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_delete_account),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.settings_delete_account_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    if (showChangePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showChangePasswordDialog = false
                newPassword = ""
                confirmPassword = ""
                authViewModel.clearMessages()
            },
            title = { Text(stringResource(R.string.settings_change_password)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_change_password_dialog_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            authViewModel.clearMessages()
                        },
                        label = { Text(stringResource(R.string.settings_new_password_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (newPasswordVisibility) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisibility = !newPasswordVisibility }) {
                                Icon(
                                    imageVector = if (newPasswordVisibility) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = stringResource(R.string.cd_toggle_password)
                                )
                            }
                        },
                        enabled = !uiState.isLoading
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            authViewModel.clearMessages()
                        },
                        label = { Text(stringResource(R.string.settings_confirm_new_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (confirmPasswordVisibility) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisibility = !confirmPasswordVisibility }) {
                                Icon(
                                    imageVector = if (confirmPasswordVisibility) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = stringResource(R.string.cd_toggle_password)
                                )
                            }
                        },
                        enabled = !uiState.isLoading
                    )
                    if (newPassword.isNotBlank() && confirmPassword.isNotBlank() && newPassword != confirmPassword) {
                        Text(
                            text = stringResource(R.string.settings_passwords_no_match),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (newPassword.isNotBlank() && newPassword.length < 6) {
                        Text(
                            text = stringResource(R.string.settings_password_min_length),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPassword.length >= 6 && newPassword == confirmPassword) {
                            authViewModel.changePassword(newPassword)
                        } else {
                            authViewModel.setError(context.getString(R.string.auth_password_mismatch_dialog))
                        }
                    },
                    enabled = !uiState.isLoading && newPassword.isNotBlank() && confirmPassword.isNotBlank() &&
                        newPassword == confirmPassword && newPassword.length >= 6
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.settings_change_password))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showChangePasswordDialog = false
                        newPassword = ""
                        confirmPassword = ""
                        authViewModel.clearMessages()
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text(stringResource(R.string.settings_delete_account_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_delete_account_confirm),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_delete_account_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        authViewModel.deleteAccount()
                        showDeleteAccountDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Text(stringResource(R.string.settings_delete_account_title))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    LaunchedEffect(uiState.successMessage) {
        val currentSuccessMessage = uiState.successMessage
        if (currentSuccessMessage != null && showChangePasswordDialog && currentSuccessMessage == passwordChangedMessage) {
            delay(2000)
            showChangePasswordDialog = false
            newPassword = ""
            confirmPassword = ""
            authViewModel.clearMessages()
        }
    }
}

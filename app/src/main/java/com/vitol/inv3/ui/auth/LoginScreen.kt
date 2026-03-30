package com.vitol.inv3.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.vitol.inv3.BuildConfig
import com.vitol.inv3.R
import com.vitol.inv3.auth.AuthManager
import timber.log.Timber

@Composable
fun LoginScreen(
    authManager: AuthManager,
    onNavigateToHome: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var showResetPassword by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var isGoogleSignInInProgress by remember { mutableStateOf(false) }

    val isGoogleSignInConfigured = remember {
        BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()
    }

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            isGoogleSignInInProgress = false
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isGoogleSignInInProgress = false
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.idToken?.let { idToken ->
                    Timber.d("Google Sign-In successful, ID token received")
                    viewModel.handleGoogleSignInResult(idToken)
                } ?: run {
                    Timber.e("Failed to get ID token from Google account")
                    viewModel.setError(context.getString(R.string.auth_google_token_failed))
                }
            } catch (e: ApiException) {
                Timber.e(e, "Google sign in failed with ApiException: statusCode=${e.statusCode}, message=${e.message}")
                val isOAuthNotRegistered = e.statusCode == 10 ||
                    (e.message?.contains("not registered", ignoreCase = true) == true)
                val errorMessage = when {
                    e.statusCode == 12501 -> context.getString(R.string.auth_google_cancelled)
                    isOAuthNotRegistered -> context.getString(R.string.auth_google_not_registered)
                    e.statusCode == 7 -> context.getString(R.string.auth_network_error)
                    e.statusCode == 8 -> context.getString(R.string.auth_internal_error)
                    else -> context.getString(
                        R.string.auth_google_failed_code,
                        e.message ?: context.getString(R.string.common_unknown) + " (${e.statusCode})"
                    )
                }
                viewModel.setError(errorMessage)
            } catch (e: Exception) {
                Timber.e(e, "Google sign in error: ${e.javaClass.simpleName}")
                viewModel.setError(
                    context.getString(R.string.auth_google_failed_generic, e.message ?: context.getString(R.string.common_unknown))
                )
            }
        } else {
            Timber.d("Google sign in cancelled or failed: resultCode = ${result.resultCode}")
            if (result.resultCode != Activity.RESULT_CANCELED) {
                viewModel.setError(context.getString(R.string.auth_google_cancelled))
            }
        }
    }

    fun startGoogleSignIn() {
        val googleClientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        if (googleClientId.isBlank()) {
            Timber.w("Google Sign-In attempted but GOOGLE_OAUTH_CLIENT_ID is not configured")
            viewModel.setError(context.getString(R.string.auth_google_not_configured))
            return
        }

        if (!googleClientId.endsWith(".apps.googleusercontent.com")) {
            Timber.w("Google Client ID format may be incorrect: $googleClientId")
            viewModel.setError(context.getString(R.string.auth_invalid_client_id))
            return
        }

        try {
            isGoogleSignInInProgress = true
            Timber.d("Starting Google Sign-In with client ID: ${googleClientId.take(20)}...")

            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(googleClientId)
                .requestEmail()
                .build()

            val googleSignInClient = GoogleSignIn.getClient(context, gso)
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        } catch (e: Exception) {
            isGoogleSignInInProgress = false
            Timber.e(e, "Error launching Google Sign-In intent")
            viewModel.setError(
                context.getString(R.string.auth_failed_start_google, e.message ?: context.getString(R.string.common_unknown))
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isSignUp) stringResource(R.string.auth_sign_up) else stringResource(R.string.auth_sign_in),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

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

        if (uiState.showEmailConfirmation) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = stringResource(R.string.auth_check_email_confirm),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (isGoogleSignInConfigured) {
            Button(
                onClick = { startGoogleSignIn() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                enabled = !uiState.isLoading && !isGoogleSignInInProgress,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF3C4043),
                    disabledContainerColor = Color(0xFFEEEEEE),
                    disabledContentColor = Color(0xFF9E9E9E),
                ),
                border = BorderStroke(1.dp, Color(0xFFDADCE0)),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 3.dp,
                    pressedElevation = 1.dp,
                    disabledElevation = 0.dp,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                if (isGoogleSignInInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF4285F4),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.auth_signing_in))
                } else {
                    Image(
                        painter = painterResource(R.drawable.ic_google_logo),
                        contentDescription = stringResource(R.string.cd_google),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.auth_sign_in_google),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = if (isSignUp) stringResource(R.string.auth_or_sign_up_email)
                    else stringResource(R.string.auth_or_continue_email),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                viewModel.clearMessages()
            },
            label = { Text(stringResource(R.string.common_email)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !uiState.isLoading
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                viewModel.clearMessages()
            },
            label = { Text(stringResource(R.string.common_password)) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.cd_toggle_password)
                    )
                }
            },
            enabled = !uiState.isLoading
        )

        if (isSignUp) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    viewModel.clearMessages()
                },
                label = { Text(stringResource(R.string.common_confirm_password)) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                enabled = !uiState.isLoading
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        val emailAuthAction: () -> Unit = {
            if (isSignUp) {
                if (password == confirmPassword && password.length >= 6) {
                    viewModel.signUp(email, password)
                } else {
                    viewModel.setError(context.getString(R.string.auth_password_mismatch_rule))
                }
            } else {
                viewModel.signIn(email, password)
            }
        }
        val emailAuthEnabled = !uiState.isLoading && email.isNotBlank() && password.isNotBlank() &&
            (!isSignUp || confirmPassword.isNotBlank())

        if (isGoogleSignInConfigured) {
            OutlinedButton(
                onClick = emailAuthAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = emailAuthEnabled,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(if (isSignUp) stringResource(R.string.auth_sign_up) else stringResource(R.string.auth_sign_in))
                }
            }
        } else {
            Button(
                onClick = emailAuthAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = emailAuthEnabled,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (isSignUp) stringResource(R.string.auth_sign_up) else stringResource(R.string.auth_sign_in))
                }
            }
        }

        if (!isSignUp) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showResetPassword = true },
                enabled = !uiState.isLoading
            ) {
                Text(stringResource(R.string.auth_forgot_password))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = {
                isSignUp = !isSignUp
                viewModel.clearMessages()
                password = ""
                confirmPassword = ""
            },
            enabled = !uiState.isLoading
        ) {
            Text(
                if (isSignUp) stringResource(R.string.auth_already_have_account)
                else stringResource(R.string.auth_no_account)
            )
        }
    }

    if (showResetPassword) {
        AlertDialog(
            onDismissRequest = { showResetPassword = false },
            title = { Text(stringResource(R.string.auth_reset_password_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = { Text(stringResource(R.string.common_email)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !uiState.isLoading
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (resetEmail.isNotBlank()) {
                            viewModel.resetPassword(resetEmail)
                            showResetPassword = false
                            resetEmail = ""
                        }
                    },
                    enabled = !uiState.isLoading && resetEmail.isNotBlank()
                ) {
                    Text(stringResource(R.string.auth_send_reset_email))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetPassword = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

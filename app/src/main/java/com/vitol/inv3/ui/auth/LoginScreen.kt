package com.vitol.inv3.ui.auth

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
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
    var showForgotPassword by remember { mutableStateOf(false) }
    var passwordVisibility by remember { mutableStateOf(false) }
    var confirmPasswordVisibility by remember { mutableStateOf(false) }

    // Google Sign-In
    val googleSignInClient = remember {
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken("592279498858-b3o6p8j8jaqu32ok4ggc4fvkgj8idqc2.apps.googleusercontent.com")
                .requestEmail()
                .build()
        )
    }

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.d("Google Sign-In result: resultCode=${result.resultCode}, data=${result.data}")
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                Timber.d("Google Sign-In account: email=${account?.email}, idToken present=${account?.idToken != null}")
                account?.idToken?.let { idToken ->
                    Timber.d("ID token received, length=${idToken.length}")
                    viewModel.handleGoogleSignInResult(idToken)
                } ?: run {
                    Timber.e("Failed to get ID token from Google account")
                    viewModel.setError("Failed to get ID token from Google. Please check your Google Console configuration.")
                }
            } catch (e: ApiException) {
                val errorCode = e.statusCode
                val errorMessage = when (errorCode) {
                    4 -> "Sign-in required. Please try again."
                    7 -> "Network error. Please check your connection."
                    8 -> "Internal error. Please try again later."
                    10 -> "Configuration error. Please check your Google Console OAuth client ID and SHA-1 fingerprint. This usually means your SHA-1 fingerprint is not registered in Google Cloud Console."
                    12501 -> "Sign-in cancelled by user."
                    else -> "Google sign in failed: ${e.message} (Error code: $errorCode)"
                }
                Timber.e(e, "Google sign in failed with error code: $errorCode, message: ${e.message}")
                viewModel.setError(errorMessage)
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error during Google sign in")
                viewModel.setError("Unexpected error: ${e.message}")
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Timber.d("Google Sign-In cancelled by user")
            // Don't show error for user cancellation
        } else {
            Timber.e("Google Sign-In failed with result code: ${result.resultCode}")
            viewModel.setError("Google Sign-In failed. Please check your Google Console configuration and SHA-1 fingerprint.")
        }
    }

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onNavigateToHome()
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
            text = when {
                showForgotPassword -> "Reset Password"
                isSignUp -> "Create Account"
                else -> "Sign In"
            },
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Error Message Card
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

        // Success Message Card
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

        // Email field
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                viewModel.clearMessages()
            },
            label = { Text("Email") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            enabled = !uiState.isLoading,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        if (!showForgotPassword) {
            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    viewModel.clearMessages()
                },
                label = { Text("Password") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                visualTransformation = if (passwordVisibility) VisualTransformation.None else PasswordVisualTransformation(),
                enabled = !uiState.isLoading,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (passwordVisibility) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { passwordVisibility = !passwordVisibility }) {
                        Icon(imageVector = image, contentDescription = "Toggle password visibility")
                    }
                }
            )

            // Confirm Password field (only for sign up)
            if (isSignUp) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        viewModel.clearMessages()
                    },
                    label = { Text("Confirm Password") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    visualTransformation = if (confirmPasswordVisibility) VisualTransformation.None else PasswordVisualTransformation(),
                    enabled = !uiState.isLoading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        val image = if (confirmPasswordVisibility) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { confirmPasswordVisibility = !confirmPasswordVisibility }) {
                            Icon(imageVector = image, contentDescription = "Toggle confirm password visibility")
                        }
                    }
                )
                if (confirmPassword.isNotBlank() && password != confirmPassword) {
                    Text(
                        text = "Passwords do not match",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Sign in/up button (only show if not in forgot password mode)
        if (!showForgotPassword) {
            Button(
                onClick = {
                    if (isSignUp) {
                        if (password != confirmPassword) {
                            viewModel.setError("Passwords do not match.")
                            return@Button
                        }
                        viewModel.signUp(email, password)
                    } else {
                        viewModel.signIn(email, password)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                enabled = !uiState.isLoading
                        && email.isNotBlank()
                        && password.isNotBlank()
                        && (!isSignUp || (confirmPassword.isNotBlank() && password == confirmPassword))
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isSignUp) "Sign Up" else "Sign In")
                }
            }
        } else {
            // Send Reset Link button for forgot password
            Button(
                onClick = { viewModel.resetPassword(email) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                enabled = !uiState.isLoading && email.isNotBlank()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Send Reset Link")
                }
            }
        }

        // Toggle sign up/sign in
        TextButton(
            onClick = {
                isSignUp = !isSignUp
                showForgotPassword = false // Reset forgot password mode
                viewModel.clearMessages()
            },
            enabled = !uiState.isLoading
        ) {
            Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
        }

        // Forgot Password link
        if (!isSignUp) {
            TextButton(
                onClick = {
                    showForgotPassword = !showForgotPassword
                    viewModel.clearMessages()
                },
                enabled = !uiState.isLoading
            ) {
                Text(if (showForgotPassword) "Back to Sign In" else "Forgot Password?")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "OR",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Google Sign-In button
        OutlinedButton(
            onClick = {
                val activity = context as? ComponentActivity
                if (activity != null) {
                    try {
                        Timber.d("Launching Google Sign-In")
                        val signInIntent = googleSignInClient.signInIntent
                        googleSignInLauncher.launch(signInIntent)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to launch Google Sign-In")
                        viewModel.setError("Failed to launch Google Sign-In: ${e.message}")
                    }
                } else {
                    Timber.e("Activity context not available for Google Sign-In")
                    viewModel.setError("Activity context not available for Google Sign-In.")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Text("Sign in with Google")
        }
    }
}


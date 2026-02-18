package com.vitol.inv3.ui.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    // Check if Google Sign-In is configured
    val isGoogleSignInConfigured = remember {
        BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()
    }

    // Navigate to home when authenticated
    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            isGoogleSignInInProgress = false
            onNavigateToHome()
        }
    }

    // Google Sign-In launcher
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
                    viewModel.setError("Failed to get ID token from Google. Please try again.")
                }
            } catch (e: ApiException) {
                Timber.e(e, "Google sign in failed with ApiException: statusCode=${e.statusCode}, message=${e.message}")
                val isOAuthNotRegistered = e.statusCode == 10 ||
                    (e.message?.contains("not registered", ignoreCase = true) == true)
                val errorMessage = when {
                    e.statusCode == 12501 -> "Google sign in was cancelled"
                    isOAuthNotRegistered -> {
                        "App not registered for Google Sign-In.\n\n" +
                        "Add your SHA-1 fingerprint to Firebase Console:\n" +
                        "Project settings → Your apps → com.vitol.inv3 → Add fingerprint\n\n" +
                        "Get SHA-1: keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android\n\n" +
                        "See docs/GOOGLE_SIGNIN_SETUP.md for details."
                    }
                    e.statusCode == 7 -> "Network error. Please check your internet connection and try again."
                    e.statusCode == 8 -> "Internal error. Please try again later."
                    else -> "Google sign in failed: ${e.message ?: "Unknown error (code: ${e.statusCode})"}"
                }
                viewModel.setError(errorMessage)
            } catch (e: Exception) {
                Timber.e(e, "Google sign in error: ${e.javaClass.simpleName}")
                viewModel.setError("Google sign in failed: ${e.message ?: "Unknown error"}")
            }
        } else {
            Timber.d("Google sign in cancelled or failed: resultCode = ${result.resultCode}")
            if (result.resultCode != Activity.RESULT_CANCELED) {
                viewModel.setError("Google sign in was cancelled")
            }
        }
    }

    fun startGoogleSignIn() {
        val googleClientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        if (googleClientId.isBlank()) {
            Timber.w("Google Sign-In attempted but GOOGLE_OAUTH_CLIENT_ID is not configured")
            viewModel.setError(
                "Google Sign-In is not configured.\n\n" +
                "Please set GOOGLE_OAUTH_CLIENT_ID in gradle.properties.\n" +
                "See GOOGLE_SIGNIN_SETUP.md for setup instructions."
            )
            return
        }

        // Validate client ID format (should be Web Client ID ending with .apps.googleusercontent.com)
        if (!googleClientId.endsWith(".apps.googleusercontent.com")) {
            Timber.w("Google Client ID format may be incorrect: $googleClientId")
            viewModel.setError(
                "Invalid Google Client ID format.\n\n" +
                "The Client ID must be a Web Client ID (ending with .apps.googleusercontent.com), " +
                "not an Android Client ID.\n" +
                "See GOOGLE_SIGNIN_SETUP.md for details."
            )
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
            viewModel.setError("Failed to start Google Sign-In: ${e.message ?: "Unknown error"}")
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
            text = if (isSignUp) "Sign Up" else "Sign In",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Error Message
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

        // Success Message
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

        // Email Confirmation Message
        if (uiState.showEmailConfirmation) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "Please check your email to confirm your account before signing in.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                viewModel.clearMessages()
            },
            label = { Text("Email") },
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
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle password visibility"
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
                label = { Text("Confirm Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                enabled = !uiState.isLoading
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isSignUp) {
                    if (password == confirmPassword && password.length >= 6) {
                        viewModel.signUp(email, password)
                    } else {
                        viewModel.setError("Passwords must match and be at least 6 characters long")
                    }
                } else {
                    viewModel.signIn(email, password)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading && email.isNotBlank() && password.isNotBlank() && (!isSignUp || confirmPassword.isNotBlank())
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

        if (!isSignUp) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showResetPassword = true },
                enabled = !uiState.isLoading
            ) {
                Text("Forgot Password?")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { startGoogleSignIn() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading && !isGoogleSignInInProgress && isGoogleSignInConfigured
        ) {
            if (isGoogleSignInInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isGoogleSignInInProgress) "Signing in..." else "Sign in with Google")
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
            Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
        }
    }

    // Reset Password Dialog
    if (showResetPassword) {
        AlertDialog(
            onDismissRequest = { showResetPassword = false },
            title = { Text("Reset Password") },
            text = {
                Column {
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = { Text("Email") },
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
                    Text("Send Reset Email")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetPassword = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


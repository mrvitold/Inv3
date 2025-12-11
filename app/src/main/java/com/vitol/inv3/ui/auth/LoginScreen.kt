package com.vitol.inv3.ui.auth

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import timber.log.Timber

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var showForgotPassword by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

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
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.idToken?.let { idToken ->
                    viewModel.handleGoogleSignInResult(idToken)
                } ?: run {
                    // This shouldn't happen, but handle it gracefully
                    Timber.w("Google sign in succeeded but no ID token received")
                    viewModel.setError("Failed to get ID token from Google")
                }
            } catch (e: ApiException) {
                Timber.e(e, "Google sign in failed")
                val errorMsg = when (e.statusCode) {
                    10 -> "Developer error. Please contact support."
                    12500 -> "Sign in was cancelled."
                    else -> "Google sign in failed: ${e.message}"
                }
                viewModel.setError(errorMsg)
            }
        } else {
            viewModel.setError("Sign in was cancelled")
        }
    }

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onLoginSuccess()
        }
    }

    // Reset form when switching between sign in/up
    LaunchedEffect(isSignUp) {
        if (!isSignUp) {
            confirmPassword = ""
            showForgotPassword = false
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
            text = if (isSignUp) "Create Account" else "Sign In",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email
            )
        )

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
                .padding(bottom = if (isSignUp) 16.dp else 8.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            enabled = !uiState.isLoading,
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
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
                    .padding(bottom = 8.dp),
                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                enabled = !uiState.isLoading,
                singleLine = true,
                isError = confirmPassword.isNotBlank() && password != confirmPassword,
                supportingText = if (confirmPassword.isNotBlank() && password != confirmPassword) {
                    { Text("Passwords do not match", color = MaterialTheme.colorScheme.error) }
                } else null,
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password"
                        )
                    }
                }
            )
        }

        // Forgot password link (only for sign in)
        if (!isSignUp && !showForgotPassword) {
            TextButton(
                onClick = { showForgotPassword = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                enabled = !uiState.isLoading
            ) {
                Text("Forgot Password?")
            }
        }

        // Forgot password email field
        if (showForgotPassword) {
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
                singleLine = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.resetPassword(email) },
                    modifier = Modifier.weight(1f),
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
                TextButton(
                    onClick = { 
                        showForgotPassword = false
                        viewModel.clearMessages()
                    },
                    enabled = !uiState.isLoading
                ) {
                    Text("Cancel")
                }
            }
        }

        // Success message
        if (uiState.successMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = uiState.successMessage!!,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Error message
        if (uiState.errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = uiState.errorMessage!!,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Sign in/up button (only show if not in forgot password mode)
        if (!showForgotPassword) {
            Button(
                onClick = {
                    if (isSignUp) {
                        if (password != confirmPassword) {
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
        }

        // Toggle sign up/sign in
        if (!showForgotPassword) {
            TextButton(
                onClick = { 
                    isSignUp = !isSignUp
                    viewModel.clearMessages()
                },
                enabled = !uiState.isLoading
            ) {
                Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider
        if (!showForgotPassword) {
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
                            val signInIntent = googleSignInClient.signInIntent
                            googleSignInLauncher.launch(signInIntent)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to launch Google Sign-In")
                            // Error will be handled by the launcher callback
                        }
                    } else {
                        viewModel.setError("Unable to start Google Sign-In")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            ) {
                // Google icon - using a colored circle with "G"
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        drawCircle(
                            color = Color(0xFF4285F4),
                            radius = size.minDimension / 2
                        )
                    }
                    Text(
                        text = "G",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("Sign in with Google")
            }
        }
    }
}

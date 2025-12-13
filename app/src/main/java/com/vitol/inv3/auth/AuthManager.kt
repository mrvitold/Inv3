package com.vitol.inv3.auth

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_session")
private val SESSION_KEY = stringPreferencesKey("session_token")
private val USER_ID_KEY = stringPreferencesKey("user_id")

class AuthManager(
    private val app: Application,
    private val supabaseClient: SupabaseClient?
) {
    private val dataStore = app.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    val currentUserId: Flow<String?> = _authState.map { state ->
        if (state is AuthState.Authenticated) state.userId else null
    }
    
    val isAuthenticated: Flow<Boolean> = _authState.map { it is AuthState.Authenticated }
    
    init {
        // Load session on initialization
        scope.launch {
            loadSession()
        }
    }
    
    private suspend fun loadSession() {
        try {
            if (supabaseClient == null) {
                _authState.value = AuthState.Unauthenticated
                return
            }
            
            // Try to get current user from Supabase
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                val userId = user.id.toString()
                saveSessionLocally(userId)
                _authState.value = AuthState.Authenticated(userId)
                Timber.d("Session loaded: User ID = $userId")
            } else {
                // Check if we have a local session
                val localUserId = dataStore.data.first()[USER_ID_KEY]
                if (localUserId != null) {
                    // Try to refresh the session
                    try {
                        supabaseClient.auth.refreshCurrentSession()
                        val refreshedUser = supabaseClient.auth.currentUserOrNull()
                        if (refreshedUser != null) {
                            val userId = refreshedUser.id.toString()
                            saveSessionLocally(userId)
                            _authState.value = AuthState.Authenticated(userId)
                            Timber.d("Session refreshed: User ID = $userId")
                        } else {
                            clearSession()
                            _authState.value = AuthState.Unauthenticated
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to refresh session")
                        clearSession()
                        _authState.value = AuthState.Unauthenticated
                    }
                } else {
                    _authState.value = AuthState.Unauthenticated
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading session")
            _authState.value = AuthState.Unauthenticated
        }
    }
    
    private suspend fun saveSessionLocally(userId: String) {
        dataStore.edit { preferences ->
            preferences[USER_ID_KEY] = userId
        }
    }
    
    private suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences.remove(USER_ID_KEY)
            preferences.remove(SESSION_KEY)
        }
    }
    
    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            _authState.value = AuthState.Loading
            supabaseClient.auth.signInWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                this.email = email
                this.password = password
            }
            
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                val userId = user.id.toString()
                saveSessionLocally(userId)
                _authState.value = AuthState.Authenticated(userId)
                Timber.d("Signed in: User ID = $userId")
                Result.success(Unit)
            } else {
                _authState.value = AuthState.Unauthenticated
                Result.failure(Exception("Sign in failed: No user returned"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Sign in error")
            _authState.value = AuthState.Unauthenticated
            Result.failure(e)
        }
    }
    
    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            _authState.value = AuthState.Loading
            supabaseClient.auth.signUpWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                this.email = email
                this.password = password
            }
            
            // Sign up doesn't automatically sign in, so we stay unauthenticated
            _authState.value = AuthState.Unauthenticated
            Timber.d("Signed up: $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Sign up error")
            _authState.value = AuthState.Unauthenticated
            Result.failure(e)
        }
    }
    
    suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            _authState.value = AuthState.Loading
            supabaseClient.auth.signInWith(IDToken) {
                this.idToken = idToken
            }
            
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                val userId = user.id.toString()
                saveSessionLocally(userId)
                _authState.value = AuthState.Authenticated(userId)
                Timber.d("Signed in with Google: User ID = $userId")
                Result.success(Unit)
            } else {
                _authState.value = AuthState.Unauthenticated
                Result.failure(Exception("Google sign in failed: No user returned"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Google sign in error")
            _authState.value = AuthState.Unauthenticated
            Result.failure(e)
        }
    }
    
    suspend fun signOut(): Result<Unit> {
        return try {
            if (supabaseClient != null) {
                try {
                    supabaseClient.auth.signOut()
                } catch (e: Exception) {
                    Timber.w(e, "Error signing out from Supabase, clearing local session anyway")
                }
            }
            clearSession()
            _authState.value = AuthState.Unauthenticated
            Timber.d("Signed out")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Sign out error")
            // Always clear local session even if server sign out fails
            clearSession()
            _authState.value = AuthState.Unauthenticated
            Result.success(Unit)
        }
    }
    
    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            supabaseClient.auth.resetPasswordForEmail(email)
            Timber.d("Password reset email sent to: $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Password reset error")
            Result.failure(e)
        }
    }
    
    suspend fun changePassword(newPassword: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            supabaseClient.auth.updateUser {
                password = newPassword
            }
            Timber.d("Password changed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Change password error")
            Result.failure(e)
        }
    }
    
    suspend fun deleteAccount(): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            // Try to call the Edge Function for account deletion
            // The Supabase client automatically includes the auth token from the current session
            try {
                val user = supabaseClient.auth.currentUserOrNull()
                if (user != null) {
                    // Call Edge Function for account deletion
                    // The client will automatically include the Authorization header
                    supabaseClient.functions.invoke("delete_user_account")
                }
            } catch (e: Exception) {
                Timber.w(e, "Edge Function call failed, proceeding with local sign out")
            }
            
            // Always clear local session
            try {
                supabaseClient.auth.signOut()
            } catch (e: Exception) {
                Timber.w(e, "Error signing out from Supabase, clearing local session anyway")
            }
            
            clearSession()
            _authState.value = AuthState.Unauthenticated
            Timber.d("Account deletion requested")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Delete account error")
            // Always clear local session even if deletion fails
            clearSession()
            _authState.value = AuthState.Unauthenticated
            Result.success(Unit)
        }
    }
    
    fun getCurrentUserId(): String? {
        return when (val state = _authState.value) {
            is AuthState.Authenticated -> state.userId
            else -> null
        }
    }
    
    suspend fun handleDeepLink(url: String): Result<String> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client not initialized"))
            }
            
            Timber.d("Handling deep link: $url")
            
            // Extract parameters from URL
            val uri = android.net.Uri.parse(url)
            val token = uri.getQueryParameter("token")
            val type = uri.getQueryParameter("type")
            val tokenHash = uri.getQueryParameter("token_hash")
            val email = uri.getQueryParameter("email")
            
            // Also check fragment for OAuth callbacks
            val fragment = uri.fragment
            
            Timber.d("Deep link params: token=$token, type=$type, token_hash=$tokenHash, email=$email, fragment=$fragment")
            
            // Handle password reset links
            if (type == "recovery") {
                Timber.d("Password reset link received")
                return Result.success("Password reset link received. Please use the reset password screen.")
            }
            
            // Handle email confirmation
            // Supabase email confirmation can work in different ways:
            // 1. Direct token verification (if token_hash and email are present)
            // 2. The link might already be verified by Supabase and we just need to sign in
            // 3. OAuth callback with access_token in fragment
            
            if (fragment != null) {
                // Check for OAuth callback with access_token
                val accessTokenMatch = Regex("access_token=([^&]+)").find(fragment)
                if (accessTokenMatch != null) {
                    val accessToken = accessTokenMatch.groupValues[1]
                    Timber.d("Found access token in fragment, attempting to set session")
                    // The Supabase client should handle this automatically via the Auth module
                    // But we can try to refresh the session
                    try {
                        supabaseClient.auth.refreshCurrentSession()
                        val user = supabaseClient.auth.currentUserOrNull()
                        if (user != null) {
                            val userId = user.id.toString()
                            saveSessionLocally(userId)
                            _authState.value = AuthState.Authenticated(userId)
                            Timber.d("Session established from OAuth callback: User ID = $userId")
                            return Result.success("Email confirmed successfully! You are now signed in.")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Could not establish session from OAuth callback")
                    }
                }
            }
            
            // For email confirmation, Supabase typically verifies the email server-side
            // before redirecting to the app. The deep link usually just indicates that
            // verification was successful. We check if a session was created.
            
            // Check if we have a user session (might have been created by Supabase)
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                val userId = user.id.toString()
                saveSessionLocally(userId)
                _authState.value = AuthState.Authenticated(userId)
                Timber.d("Email confirmed and user signed in: User ID = $userId")
                return Result.success("Email confirmed successfully! You are now signed in.")
            }
            
            // If no session, the email was confirmed but user needs to sign in
            if (token != null && (type == "signup" || type == "email")) {
                Timber.d("Email confirmation link detected (type=$type)")
                // The email should now be confirmed on the server
                // User needs to sign in manually
                return Result.success("Email confirmed successfully! Please sign in with your credentials.")
            }
            
            // If we reach here, we couldn't process the deep link
            Timber.w("Could not process deep link - unknown format")
            Result.failure(Exception("Could not process confirmation link. Please try signing in manually."))
        } catch (e: Exception) {
            Timber.e(e, "Error handling deep link")
            Result.failure(e)
        }
    }
}


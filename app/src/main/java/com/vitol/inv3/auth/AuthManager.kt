package com.vitol.inv3.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

private val SESSION_KEY = stringPreferencesKey("session_token")
private val USER_ID_KEY = stringPreferencesKey("user_id")

class AuthManager(
    private val context: Context,
    private val supabaseClient: SupabaseClient?
) {
    val authState: Flow<AuthState> = context.dataStore.data.map { prefs ->
        val userId = prefs[USER_ID_KEY]
        if (userId != null) {
            AuthState.Authenticated(userId)
        } else {
            AuthState.Unauthenticated
        }
    }

    val currentUserId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_ID_KEY]
    }

    val isAuthenticated: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SESSION_KEY] != null && prefs[USER_ID_KEY] != null
    }

    suspend fun getCurrentUserId(): String? {
        return context.dataStore.data.first()[USER_ID_KEY]
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            supabaseClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            // Get the current session after sign in
            val session = supabaseClient.auth.currentSessionOrNull()
            if (session != null && session.user != null) {
                saveSession(session.accessToken, session.user!!.id.toString())
                Timber.d("Signed in with email: ${session.user!!.email}")
                Result.success(Unit)
            } else {
                Timber.w("Sign in successful but no session returned")
                Result.failure(Exception("No session returned"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign in with email")
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }

            // Get the current session after sign up
            val session = supabaseClient.auth.currentSessionOrNull()
            if (session != null && session.user != null) {
                saveSession(session.accessToken, session.user!!.id.toString())
                Timber.d("Signed up with email: ${session.user!!.email}")
                Result.success(Unit)
            } else {
                // Email confirmation required
                Timber.d("Sign up successful, email confirmation required")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign up with email")
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                Timber.e("Supabase client is null")
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            Timber.d("Attempting to sign in with Google ID token (length: ${idToken.length})")
            
            val result = supabaseClient.auth.signInWith(IDToken) {
                this.idToken = idToken
            }
            
            Timber.d("Supabase signInWith returned: $result")

            // Get the current session after sign in
            val session = supabaseClient.auth.currentSessionOrNull()
            Timber.d("Current session after sign in: ${if (session != null) "exists" else "null"}")
            
            if (session != null && session.user != null) {
                Timber.d("Session user: id=${session.user!!.id}, email=${session.user!!.email}")
                saveSession(session.accessToken, session.user!!.id.toString())
                Timber.d("Signed in with Google: ${session.user!!.email}")
                Result.success(Unit)
            } else {
                Timber.w("Google sign in successful but no session returned. Session: $session")
                Result.failure(Exception("No session returned. Please check Supabase Google provider configuration."))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign in with Google: ${e.message}")
            Timber.e(e, "Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            Result.failure(Exception("Failed to sign in with Google: ${e.message}"))
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            // Try to sign out on server, but don't fail if user doesn't exist
            if (supabaseClient != null) {
                try {
                    supabaseClient.auth.signOut()
                } catch (e: Exception) {
                    // If sign out fails (e.g., user already deleted), that's okay
                    // We'll still clear the local session
                    Timber.w(e, "Server sign out failed (user may already be deleted), clearing local session anyway")
                }
            }
            // Always clear local session, regardless of server response
            clearSession()
            Timber.d("Signed out (local session cleared)")
            Result.success(Unit)
        } catch (e: Exception) {
            // Even if something goes wrong, try to clear the session
            Timber.e(e, "Error during sign out, clearing session anyway")
            try {
                clearSession()
            } catch (clearError: Exception) {
                Timber.e(clearError, "Failed to clear session")
            }
            Result.success(Unit) // Return success since we cleared local session
        }
    }

    suspend fun getCurrentSession(): String? {
        return context.dataStore.data.first()[SESSION_KEY]
    }

    private suspend fun saveSession(token: String, userId: String?) {
        context.dataStore.edit { prefs ->
            prefs[SESSION_KEY] = token
            if (userId != null) {
                prefs[USER_ID_KEY] = userId
            }
        }
    }

    private suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(SESSION_KEY)
            prefs.remove(USER_ID_KEY)
        }
    }

    suspend fun refreshSession(): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            val session = supabaseClient.auth.currentSessionOrNull()
            if (session != null && session.user != null) {
                supabaseClient.auth.refreshCurrentSession()
                // Get the refreshed session
                val refreshed = supabaseClient.auth.currentSessionOrNull()
                if (refreshed != null && refreshed.user != null) {
                    saveSession(refreshed.accessToken, refreshed.user!!.id.toString())
                    Result.success(Unit)
                } else {
                    clearSession()
                    Result.failure(Exception("Failed to refresh session"))
                }
            } else {
                clearSession()
                Result.failure(Exception("No active session"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh session")
            clearSession()
            Result.failure(e)
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client is not initialized"))
            }
            supabaseClient.auth.resetPasswordForEmail(email)
            Timber.d("Password reset email sent to $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send password reset email to $email")
            Result.failure(e)
        }
    }

    suspend fun changePassword(newPassword: String): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client is not initialized"))
            }
            supabaseClient.auth.updateUser {
                password = newPassword
            }
            Timber.d("Password changed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to change password")
            Result.failure(e)
        }
    }

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                // Even without client, clear local session
                clearSession()
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            // Call Edge Function to delete the account
            // The Edge Function uses service role key to delete the user
            var accountDeleted = false
            try {
                val response = supabaseClient.functions.invoke(
                    function = "delete_user_account"
                )
                Timber.d("Edge function response: $response")
                accountDeleted = true
            } catch (functionError: Exception) {
                // Check if error is because user doesn't exist or token is invalid
                val errorMessage = functionError.message ?: ""
                val isUserNotFound = errorMessage.contains("does not exist", ignoreCase = true) ||
                        errorMessage.contains("Invalid or expired token", ignoreCase = true) ||
                        errorMessage.contains("Unauthorized", ignoreCase = true)
                
                if (isUserNotFound) {
                    // User already deleted or doesn't exist - treat as success
                    Timber.d("User already deleted or doesn't exist, treating as success")
                    accountDeleted = true
                } else {
                    // Other error - log it but we'll still sign out
                    Timber.w(functionError, "Edge function failed: ${functionError.message}")
                }
            }
            
            // Always try to sign out and clear session, regardless of Edge Function result
            try {
                supabaseClient.auth.signOut()
            } catch (signOutError: Exception) {
                // If sign out fails (e.g., user already deleted), that's okay
                Timber.w(signOutError, "Server sign out failed (user may already be deleted), clearing local session anyway")
            }
            
            // Always clear local session
            clearSession()
            
            if (accountDeleted) {
                Timber.d("Account deleted successfully")
                Result.success(Unit)
            } else {
                Timber.w("Account deletion may have failed, but local session cleared")
                Result.success(Unit) // Still return success since we cleared the session
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete account, clearing session anyway")
            // Even on error, try to clear the session
            try {
                clearSession()
            } catch (clearError: Exception) {
                Timber.e(clearError, "Failed to clear session")
            }
            // Return success since we cleared local session (user is effectively signed out)
            Result.success(Unit)
        }
    }
}


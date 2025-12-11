package com.vitol.inv3.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jan.supabase.SupabaseClient
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
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            supabaseClient.auth.signInWith(IDToken) {
                this.idToken = idToken
            }

            // Get the current session after sign in
            val session = supabaseClient.auth.currentSessionOrNull()
            if (session != null && session.user != null) {
                saveSession(session.accessToken, session.user!!.id.toString())
                Timber.d("Signed in with Google: ${session.user!!.email}")
                Result.success(Unit)
            } else {
                Timber.w("Google sign in successful but no session returned")
                Result.failure(Exception("No session returned"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign in with Google")
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            if (supabaseClient != null) {
                supabaseClient.auth.signOut()
            }
            clearSession()
            Timber.d("Signed out")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to sign out")
            Result.failure(e)
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
            Timber.d("Password reset email sent to: $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send password reset email")
            Result.failure(e)
        }
    }

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            if (supabaseClient == null) {
                return Result.failure(Exception("Supabase client is not initialized"))
            }

            // Note: User deletion in Supabase typically requires admin privileges
            // For now, we'll sign out and clear the session
            // Full account deletion should be handled server-side or via admin API
            supabaseClient.auth.signOut()
            clearSession()
            Timber.d("User signed out. Account deletion may require admin action.")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete account")
            Result.failure(e)
        }
    }
}


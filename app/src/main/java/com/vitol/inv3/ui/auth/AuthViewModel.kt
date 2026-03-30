package com.vitol.inv3.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.R
import com.vitol.inv3.auth.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showEmailConfirmation: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authManager.signInWithEmail(email, password)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message,
                isAuthenticated = result.isSuccess
            )
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, showEmailConfirmation = false)
            val result = authManager.signUpWithEmail(email, password)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showEmailConfirmation = true,
                    successMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: appContext.getString(R.string.auth_sign_up_failed)
                )
            }
        }
    }

    fun handleGoogleSignInResult(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authManager.signInWithGoogle(idToken)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message,
                isAuthenticated = result.isSuccess
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authManager.signOut()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isAuthenticated = false,
                successMessage = if (result.isSuccess) appContext.getString(R.string.auth_signed_out) else null,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = authManager.resetPassword(email)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = appContext.getString(R.string.auth_password_reset_sent)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: appContext.getString(R.string.auth_failed_reset_email)
                )
            }
        }
    }

    fun changePassword(newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = authManager.changePassword(newPassword)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = appContext.getString(R.string.auth_password_changed)
                )
                authManager.signOut()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message
                        ?: appContext.getString(R.string.auth_failed_change_password)
                )
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            val result = authManager.deleteAccount()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isAuthenticated = false,
                successMessage = if (result.isSuccess) appContext.getString(R.string.auth_account_deleted) else null,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }
}

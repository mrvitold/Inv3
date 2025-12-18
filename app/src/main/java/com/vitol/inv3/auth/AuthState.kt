package com.vitol.inv3.auth

sealed class AuthState {
    object Unauthenticated : AuthState()
    data class Authenticated(val userId: String) : AuthState()
    object Loading : AuthState()
}









package com.vitol.inv3

import androidx.lifecycle.ViewModel
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    val repo: SupabaseRepository,
    val authManager: AuthManager
) : ViewModel()


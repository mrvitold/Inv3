package com.vitol.inv3.di

import android.app.Application
import com.vitol.inv3.auth.AuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideAuthManager(
        app: Application,
        supabaseClient: SupabaseClient?
    ): AuthManager {
        return AuthManager(app, supabaseClient)
    }
}


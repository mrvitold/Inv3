package com.vitol.inv3.di

import android.content.Context
import com.vitol.inv3.auth.AuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @Singleton
    fun provideAuthManager(
        @ApplicationContext context: Context,
        supabaseClient: SupabaseClient?
    ): AuthManager = AuthManager(context, supabaseClient)
}


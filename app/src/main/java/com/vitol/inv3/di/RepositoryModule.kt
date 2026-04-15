package com.vitol.inv3.di

import com.vitol.inv3.analytics.AppAnalytics
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.data.remote.SupabaseRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideSupabaseRepository(
        client: SupabaseClient?,
        authManager: AuthManager,
        appAnalytics: AppAnalytics
    ): SupabaseRepository = SupabaseRepository(client, authManager, appAnalytics)
}


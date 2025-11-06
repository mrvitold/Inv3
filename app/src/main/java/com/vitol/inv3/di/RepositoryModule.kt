package com.vitol.inv3.di

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
    fun provideSupabaseRepository(client: SupabaseClient?): SupabaseRepository = SupabaseRepository(client)
}


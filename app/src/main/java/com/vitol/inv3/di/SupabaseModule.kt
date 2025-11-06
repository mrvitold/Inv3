package com.vitol.inv3.di

import android.app.Application
import com.vitol.inv3.data.remote.SupabaseFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    @Provides
    @Singleton
    fun provideSupabaseClient(app: Application): SupabaseClient? = SupabaseFactory.create(app)
}


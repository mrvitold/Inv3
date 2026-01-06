package com.vitol.inv3.di

import android.content.Context
import com.vitol.inv3.billing.BillingManager
import com.vitol.inv3.billing.UsageTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    
    @Provides
    @Singleton
    fun provideBillingManager(
        @ApplicationContext context: Context
    ): BillingManager {
        return BillingManager(context)
    }
    
    @Provides
    @Singleton
    fun provideUsageTracker(
        @ApplicationContext context: Context
    ): UsageTracker {
        return UsageTracker(context)
    }
}


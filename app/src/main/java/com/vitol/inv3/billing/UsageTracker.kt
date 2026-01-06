package com.vitol.inv3.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(name = "usage_tracking")

@Singleton
class UsageTracker @Inject constructor(
    private val context: Context
) {
    private val dataStore = context.usageDataStore
    
    private val PAGES_USED_KEY = intPreferencesKey("pages_used")
    private val SUBSCRIPTION_START_DATE_KEY = longPreferencesKey("subscription_start_date")
    private val RESET_DATE_KEY = longPreferencesKey("reset_date")
    
    /**
     * Track page usage. This should be called after successfully processing each page.
     */
    suspend fun trackPageUsage() {
        ensurePeriodReset()
        dataStore.edit { preferences ->
            val current = preferences[PAGES_USED_KEY] ?: 0
            preferences[PAGES_USED_KEY] = current + 1
        }
        Timber.d("Tracked page usage. Total pages used: ${getPagesUsed()}")
    }
    
    /**
     * Get current pages used in the current period.
     */
    suspend fun getPagesUsed(): Int {
        ensurePeriodReset()
        return dataStore.data.first()[PAGES_USED_KEY] ?: 0
    }
    
    /**
     * Get the reset date for the current period (rolling 30 days from subscription start).
     */
    suspend fun getResetDate(): Long {
        ensurePeriodReset()
        return dataStore.data.first()[RESET_DATE_KEY] ?: getNextResetDate()
    }
    
    /**
     * Get subscription start date. If not set, returns current time.
     */
    suspend fun getSubscriptionStartDate(): Long {
        val startDate = dataStore.data.first()[SUBSCRIPTION_START_DATE_KEY]
        return startDate ?: System.currentTimeMillis()
    }
    
    /**
     * Set subscription start date. This is called when a subscription is purchased or upgraded.
     */
    suspend fun setSubscriptionStartDate(startDate: Long) {
        dataStore.edit { preferences ->
            preferences[SUBSCRIPTION_START_DATE_KEY] = startDate
            // Calculate reset date: startDate + 30 days
            preferences[RESET_DATE_KEY] = startDate + (30L * 24 * 60 * 60 * 1000)
        }
        Timber.d("Set subscription start date: $startDate, reset date: ${getResetDate()}")
    }
    
    /**
     * Reset usage count. Called when period resets or subscription changes.
     */
    suspend fun resetUsage() {
        dataStore.edit { preferences ->
            preferences[PAGES_USED_KEY] = 0
        }
        Timber.d("Usage reset")
    }
    
    /**
     * Ensure period reset if needed. Checks if current date >= reset date and resets if so.
     */
    private suspend fun ensurePeriodReset() {
        dataStore.edit { preferences ->
            val resetDate = preferences[RESET_DATE_KEY] ?: getNextResetDate()
            val currentTime = System.currentTimeMillis()
            
            if (currentTime >= resetDate) {
                // Period expired - reset usage and calculate new reset date
                val startDate = preferences[SUBSCRIPTION_START_DATE_KEY] ?: currentTime
                val newResetDate = startDate + (30L * 24 * 60 * 60 * 1000)
                
                // If new reset date is still in the past, set it to 30 days from now
                val finalResetDate = if (newResetDate <= currentTime) {
                    currentTime + (30L * 24 * 60 * 60 * 1000)
                } else {
                    newResetDate
                }
                
                preferences[PAGES_USED_KEY] = 0
                preferences[RESET_DATE_KEY] = finalResetDate
                preferences[SUBSCRIPTION_START_DATE_KEY] = currentTime
                
                Timber.d("Period reset: reset usage, new reset date: $finalResetDate")
            }
        }
    }
    
    /**
     * Calculate next reset date (30 days from now).
     */
    private fun getNextResetDate(): Long {
        return System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)
    }
    
    /**
     * Flow of pages used for reactive UI updates.
     */
    fun getPagesUsedFlow(): Flow<Int> = dataStore.data.map { it[PAGES_USED_KEY] ?: 0 }
    
    /**
     * Flow of reset date for reactive UI updates.
     */
    fun getResetDateFlow(): Flow<Long> = dataStore.data.map { it[RESET_DATE_KEY] ?: getNextResetDate() }
    
    /**
     * Flow of subscription start date.
     */
    fun getSubscriptionStartDateFlow(): Flow<Long> = dataStore.data.map { 
        it[SUBSCRIPTION_START_DATE_KEY] ?: System.currentTimeMillis() 
    }
}


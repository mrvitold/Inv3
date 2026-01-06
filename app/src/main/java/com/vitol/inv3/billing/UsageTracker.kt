package com.vitol.inv3.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vitol.inv3.data.remote.SupabaseRepository
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
    private val context: Context,
    private val supabaseRepository: SupabaseRepository
) {
    private val dataStore = context.usageDataStore
    private var isInitialized = false
    
    private val PAGES_USED_KEY = intPreferencesKey("pages_used")
    private val SUBSCRIPTION_START_DATE_KEY = longPreferencesKey("subscription_start_date")
    private val RESET_DATE_KEY = longPreferencesKey("reset_date")
    
    /**
     * Initialize usage tracker by loading data from Supabase (if available).
     * This should be called once when the app starts or user logs in.
     */
    suspend fun initialize() {
        if (isInitialized) return
        
        try {
            // Try to load from Supabase first
            val supabaseUsage = supabaseRepository.getUsageCount()
            if (supabaseUsage != null) {
                val (pagesUsed, resetDate) = supabaseUsage
                dataStore.edit { preferences ->
                    preferences[PAGES_USED_KEY] = pagesUsed
                    if (resetDate != null) {
                        preferences[RESET_DATE_KEY] = resetDate
                    }
                }
                Timber.d("Initialized usage tracker from Supabase: pagesUsed=$pagesUsed, resetDate=$resetDate")
            } else {
                // Fallback to local storage if Supabase is unavailable
                Timber.d("Supabase unavailable, using local storage")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize from Supabase, using local storage")
        }
        
        // Ensure period reset is applied
        ensurePeriodReset()
        isInitialized = true
    }
    
    /**
     * Track page usage. This should be called after successfully processing each page.
     * Updates both local storage and Supabase.
     */
    suspend fun trackPageUsage() {
        ensurePeriodReset()
        var newPagesUsed = 0
        dataStore.edit { preferences ->
            val current = preferences[PAGES_USED_KEY] ?: 0
            newPagesUsed = current + 1
            preferences[PAGES_USED_KEY] = newPagesUsed
        }
        
        // Sync to Supabase
        try {
            val resetDate = getResetDate()
            supabaseRepository.updateUsageCount(newPagesUsed, resetDate)
            Timber.d("Tracked page usage. Total pages used: $newPagesUsed (synced to Supabase)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync usage to Supabase, but local storage updated")
        }
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
     * Updates both local storage and Supabase.
     */
    suspend fun setSubscriptionStartDate(startDate: Long) {
        val resetDate = startDate + (30L * 24 * 60 * 60 * 1000)
        dataStore.edit { preferences ->
            preferences[SUBSCRIPTION_START_DATE_KEY] = startDate
            preferences[RESET_DATE_KEY] = resetDate
        }
        
        // Sync reset date to Supabase
        try {
            val pagesUsed = getPagesUsed()
            supabaseRepository.updateUsageCount(pagesUsed, resetDate)
            Timber.d("Set subscription start date: $startDate, reset date: $resetDate (synced to Supabase)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync subscription start date to Supabase, but local storage updated")
        }
    }
    
    /**
     * Reset usage count. Called when period resets or subscription changes.
     * Updates both local storage and Supabase.
     */
    suspend fun resetUsage() {
        val resetDate = getNextResetDate()
        dataStore.edit { preferences ->
            preferences[PAGES_USED_KEY] = 0
            preferences[RESET_DATE_KEY] = resetDate
        }
        
        // Sync to Supabase
        try {
            supabaseRepository.updateUsageCount(0, resetDate)
            Timber.d("Usage reset (synced to Supabase)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync reset to Supabase, but local storage updated")
        }
    }
    
    /**
     * Ensure period reset if needed. Checks if current date >= reset date and resets if so.
     * Updates both local storage and Supabase.
     */
    private suspend fun ensurePeriodReset() {
        val shouldReset = dataStore.data.first().let { preferences ->
            val resetDate = preferences[RESET_DATE_KEY] ?: getNextResetDate()
            val currentTime = System.currentTimeMillis()
            currentTime >= resetDate
        }
        
        if (shouldReset) {
            val currentTime = System.currentTimeMillis()
            val finalResetDate = currentTime + (30L * 24 * 60 * 60 * 1000)
            
            dataStore.edit { preferences ->
                preferences[PAGES_USED_KEY] = 0
                preferences[RESET_DATE_KEY] = finalResetDate
                preferences[SUBSCRIPTION_START_DATE_KEY] = currentTime
            }
            
            // Sync reset to Supabase
            try {
                supabaseRepository.updateUsageCount(0, finalResetDate)
                Timber.d("Period reset: reset usage, new reset date: $finalResetDate (synced to Supabase)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync period reset to Supabase, but local storage updated")
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


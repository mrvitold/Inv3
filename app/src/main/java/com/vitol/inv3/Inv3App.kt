package com.vitol.inv3

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.vitol.inv3.utils.CrashlyticsTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class Inv3App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        
        // Configure Crashlytics
        val crashlytics = FirebaseCrashlytics.getInstance()
        
        // Enable Crashlytics collection (disabled by default in debug builds)
        // In release builds, this is always enabled
        crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        
        // Plant Timber trees
        if (BuildConfig.DEBUG) {
            // In debug builds, use DebugTree for Logcat output
            Timber.plant(Timber.DebugTree())
        } else {
            // In release builds, use CrashlyticsTree for crash reporting
            Timber.plant(CrashlyticsTree())
        }
    }
}


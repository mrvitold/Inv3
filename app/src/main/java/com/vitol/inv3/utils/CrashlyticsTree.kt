package com.vitol.inv3.utils

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * A Timber tree that logs to Firebase Crashlytics in release builds.
 * In debug builds, logs are also printed to Logcat for easier debugging.
 */
class CrashlyticsTree : Timber.Tree() {
    
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Only log in release builds or when Crashlytics is available
        if (priority == Log.VERBOSE || priority == Log.DEBUG) {
            // Debug/Verbose logs are only sent to Crashlytics if there's an exception
            if (t != null) {
                FirebaseCrashlytics.getInstance().log("$tag: $message")
                FirebaseCrashlytics.getInstance().recordException(t)
            }
            return
        }

        // Log info, warning, and error messages to Crashlytics
        val crashlytics = FirebaseCrashlytics.getInstance()
        
        // Format the log message
        val logMessage = if (tag != null) {
            "$tag: $message"
        } else {
            message
        }

        // Send to Crashlytics
        crashlytics.log(logMessage)

        // Record exceptions
        if (t != null) {
            crashlytics.recordException(t)
        } else if (priority == Log.ERROR) {
            // For error logs without exceptions, create a non-fatal exception
            crashlytics.recordException(Exception(message))
        }

        // Set custom keys for better crash analysis
        crashlytics.setCustomKey("log_priority", priority)
        if (tag != null) {
            crashlytics.setCustomKey("log_tag", tag)
        }
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        // Log everything except verbose in release builds
        return priority != Log.VERBOSE
    }
}


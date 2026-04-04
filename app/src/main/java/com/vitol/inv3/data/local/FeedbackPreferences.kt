package com.vitol.inv3.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.feedbackDataStore by preferencesDataStore(name = "feedback_preferences")

private val FEEDBACK_PROMPT_OFFERED_COUNT = intPreferencesKey("feedback_prompt_offered_count")
private val PENDING_AFTER_IMPORT = booleanPreferencesKey("pending_feedback_after_import")

private const val MAX_FEEDBACK_PROMPTS = 2

suspend fun shouldOfferFeedbackPrompt(context: Context): Boolean {
    val count = context.feedbackDataStore.data
        .map { it[FEEDBACK_PROMPT_OFFERED_COUNT] ?: 0 }
        .first()
    return count < MAX_FEEDBACK_PROMPTS
}

suspend fun recordFeedbackPromptOffered(context: Context) {
    context.feedbackDataStore.edit { prefs ->
        val next = (prefs[FEEDBACK_PROMPT_OFFERED_COUNT] ?: 0) + 1
        prefs[FEEDBACK_PROMPT_OFFERED_COUNT] = next
    }
}

suspend fun setPendingFeedbackAfterImportComplete(context: Context) {
    context.feedbackDataStore.edit { prefs ->
        prefs[PENDING_AFTER_IMPORT] = true
    }
}

/** Returns whether a pending prompt was set, and clears the flag. */
suspend fun consumePendingFeedbackAfterImport(context: Context): Boolean {
    val pending = context.feedbackDataStore.data
        .map { it[PENDING_AFTER_IMPORT] == true }
        .first()
    if (pending) {
        context.feedbackDataStore.edit { prefs ->
            prefs.remove(PENDING_AFTER_IMPORT)
        }
    }
    return pending
}

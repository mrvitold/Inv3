package com.vitol.inv3.data.local

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.languageDataStore by preferencesDataStore(name = "language_preferences")

private val LANGUAGE_OVERRIDE_KEY = stringPreferencesKey("language_override")

/** Stored values: [LANGUAGE_EN] or [LANGUAGE_LT] only. Absent/legacy null is treated as English. */
const val LANGUAGE_EN = "en"
const val LANGUAGE_LT = "lt"

fun applyAppLocalesForTag(tag: String?) {
    val effective = when (tag) {
        LANGUAGE_LT -> LANGUAGE_LT
        else -> LANGUAGE_EN
    }
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(effective))
}

fun applyStoredAppLocales(context: Context) {
    runBlocking {
        val tag = context.languageDataStore.data.map { it[LANGUAGE_OVERRIDE_KEY] }.first()
        if (tag == null) {
            context.setLanguageOverride(LANGUAGE_EN)
        }
        applyAppLocalesForTag(tag ?: LANGUAGE_EN)
    }
}

suspend fun Context.getLanguageOverride(): String? {
    return languageDataStore.data.map { it[LANGUAGE_OVERRIDE_KEY] }.first()
}

fun Context.getLanguageOverrideFlow(): Flow<String?> {
    return languageDataStore.data.map { it[LANGUAGE_OVERRIDE_KEY] }
}

suspend fun Context.setLanguageOverride(languageTag: String?) {
    languageDataStore.edit { prefs ->
        if (languageTag != null) {
            prefs[LANGUAGE_OVERRIDE_KEY] = languageTag
        } else {
            prefs.remove(LANGUAGE_OVERRIDE_KEY)
        }
    }
}

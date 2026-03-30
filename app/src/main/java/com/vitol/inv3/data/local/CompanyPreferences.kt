package com.vitol.inv3.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.companyDataStore by preferencesDataStore(name = "company_preferences")
private val ACTIVE_OWN_COMPANY_ID_KEY = stringPreferencesKey("active_own_company_id")
/** User id for which the one-time Home company-setup prompt was dismissed or profile completed (legacy key name kept for DataStore compatibility). */
private val COMPANY_SETUP_HOME_PROMPT_ACK_USER_ID_KEY = stringPreferencesKey("company_setup_home_snackbar_ack_user_id")

suspend fun Context.getActiveOwnCompanyId(): String? {
    return companyDataStore.data.map { it[ACTIVE_OWN_COMPANY_ID_KEY] }.first()
}

suspend fun Context.setActiveOwnCompanyId(companyId: String?) {
    companyDataStore.edit { prefs ->
        if (companyId != null) {
            prefs[ACTIVE_OWN_COMPANY_ID_KEY] = companyId
        } else {
            prefs.remove(ACTIVE_OWN_COMPANY_ID_KEY)
        }
    }
}

fun Context.getActiveOwnCompanyIdFlow(): Flow<String?> {
    return companyDataStore.data.map { it[ACTIVE_OWN_COMPANY_ID_KEY] }
}

suspend fun Context.getCompanySetupHomePromptAckUserId(): String? {
    return companyDataStore.data.map { it[COMPANY_SETUP_HOME_PROMPT_ACK_USER_ID_KEY] }.first()
}

suspend fun Context.setCompanySetupHomePromptAckUserId(userId: String?) {
    companyDataStore.edit { prefs ->
        if (userId != null) {
            prefs[COMPANY_SETUP_HOME_PROMPT_ACK_USER_ID_KEY] = userId
        } else {
            prefs.remove(COMPANY_SETUP_HOME_PROMPT_ACK_USER_ID_KEY)
        }
    }
}


package com.vitol.inv3.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.templateDataStore by preferencesDataStore(name = "company_templates")

data class FieldRegion(val field: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

class TemplateStore(private val context: Context) {
    suspend fun saveTemplate(companyKey: String, regions: List<FieldRegion>) {
        val key = stringPreferencesKey(companyKey)
        val json = JSONObject().apply {
            put("regions", regions.map {
                JSONObject().apply {
                    put("field", it.field)
                    put("l", it.left)
                    put("t", it.top)
                    put("r", it.right)
                    put("b", it.bottom)
                }
            })
        }.toString()
        context.templateDataStore.edit { prefs ->
            prefs[key] = json
        }
    }

    suspend fun loadTemplate(companyKey: String): List<FieldRegion> {
        val key = stringPreferencesKey(companyKey)
        val json = context.templateDataStore.data.map { it[key] }.first() ?: return emptyList()
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("regions") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    FieldRegion(
                        field = o.getString("field"),
                        left = o.getDouble("l").toFloat(),
                        top = o.getDouble("t").toFloat(),
                        right = o.getDouble("r").toFloat(),
                        bottom = o.getDouble("b").toFloat()
                    )
                )
            }
        }
    }
}


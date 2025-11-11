package com.vitol.inv3.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import timber.log.Timber

private val Context.templateDataStore by preferencesDataStore(name = "company_templates")

data class FieldRegion(
    val field: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1.0f,  // How reliable this position is (0.0-1.0)
    val sampleCount: Int = 1       // How many invoices contributed to this position
)

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
                    put("c", it.confidence)  // confidence
                    put("n", it.sampleCount) // sample count
                }
            })
        }.toString()
        context.templateDataStore.edit { prefs ->
            prefs[key] = json
        }
    }
    
    /**
     * Merge new regions with existing template (incremental learning).
     * Averages positions from multiple invoices for better stability.
     */
    suspend fun mergeTemplate(companyKey: String, newRegions: List<FieldRegion>) {
        val existing = loadTemplate(companyKey)
        if (existing.isEmpty()) {
            // No existing template, just save the new one
            saveTemplate(companyKey, newRegions)
            return
        }
        
        val merged = mutableListOf<FieldRegion>()
        val existingMap = existing.associateBy { it.field }
        
        // Merge existing and new regions
        newRegions.forEach { newRegion ->
            val existingRegion = existingMap[newRegion.field]
            if (existingRegion != null) {
                // Merge: weighted average based on sample count
                val totalSamples = existingRegion.sampleCount + 1
                val existingWeight = existingRegion.sampleCount.toFloat() / totalSamples
                val newWeight = 1.0f / totalSamples
                
                val mergedRegion = FieldRegion(
                    field = newRegion.field,
                    left = existingRegion.left * existingWeight + newRegion.left * newWeight,
                    top = existingRegion.top * existingWeight + newRegion.top * newWeight,
                    right = existingRegion.right * existingWeight + newRegion.right * newWeight,
                    bottom = existingRegion.bottom * existingWeight + newRegion.bottom * newWeight,
                    confidence = (existingRegion.confidence + 1.0f) / 2.0f, // Average confidence
                    sampleCount = totalSamples
                )
                merged.add(mergedRegion)
                Timber.d("Merged ${newRegion.field}: ${existingRegion.sampleCount} -> $totalSamples samples")
            } else {
                // New field, add it
                merged.add(newRegion)
                Timber.d("Added new field ${newRegion.field}")
            }
        }
        
        // Keep existing fields that weren't in new regions (with reduced confidence)
        existing.forEach { existingRegion ->
            if (!newRegions.any { it.field == existingRegion.field }) {
                // Field not found in new invoice, reduce confidence slightly
                val updatedRegion = existingRegion.copy(
                    confidence = existingRegion.confidence * 0.95f  // Slight decay
                )
                merged.add(updatedRegion)
            }
        }
        
        saveTemplate(companyKey, merged)
        Timber.d("Merged template for '$companyKey': ${merged.size} regions")
    }

    suspend fun loadTemplate(companyKey: String): List<FieldRegion> {
        val key = stringPreferencesKey(companyKey)
        val json = context.templateDataStore.data.map { it[key] }.first() ?: run {
            Timber.d("No template found for company key: '$companyKey'")
            return emptyList()
        }
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("regions") ?: return emptyList()
        val regions = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    FieldRegion(
                        field = o.getString("field"),
                        left = o.getDouble("l").toFloat(),
                        top = o.getDouble("t").toFloat(),
                        right = o.getDouble("r").toFloat(),
                        bottom = o.getDouble("b").toFloat(),
                        confidence = o.optDouble("c", 1.0).toFloat(),  // Default to 1.0 for backward compatibility
                        sampleCount = o.optInt("n", 1)  // Default to 1 for backward compatibility
                    )
                )
            }
        }
        Timber.d("Loaded template for '$companyKey': ${regions.size} regions")
        return regions
    }
}


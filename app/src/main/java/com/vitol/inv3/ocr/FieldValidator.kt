package com.vitol.inv3.ocr

import com.vitol.inv3.utils.DateFormatter

/**
 * Validates extracted field values using domain knowledge.
 * Helps identify incorrect extractions and improve template learning.
 */
object FieldValidator {
    /**
     * Validate an extracted field value.
     * @return true if the value appears valid, false otherwise
     */
    fun validate(fieldName: String, value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }
        
        return when (fieldName) {
            "Date" -> DateFormatter.isValidYearMonthDay(value)
            "Amount_without_VAT_EUR", "VAT_amount_EUR" -> {
                // Should be a valid number
                value.replace(",", ".").replace(Regex("[^0-9.]"), "").toDoubleOrNull() != null
            }
            "VAT_number" -> {
                // VAT numbers typically follow patterns like LT123456789 or 123456789
                value.matches(Regex("(LT)?[0-9A-Z]{8,12}"))
            }
            "Company_number" -> {
                // Company numbers are typically 7-14 digits
                value.matches(Regex("[0-9]{7,14}"))
            }
            "Invoice_ID" -> {
                // Invoice IDs should not be empty and have reasonable length
                value.isNotBlank() && value.length <= 100
            }
            "Company_name" -> {
                // Company names should not be empty and have reasonable length
                value.isNotBlank() && value.length <= 200
            }
            else -> true  // Unknown fields, assume valid
        }
    }
    
    /**
     * Calculate match quality between confirmed value and matched OCR blocks.
     * @return quality score from 0.0 to 1.0
     */
    fun calculateMatchQuality(confirmedValue: String, matchedBlocks: List<OcrBlock>): Float {
        if (matchedBlocks.isEmpty()) return 0.0f
        
        val extractedText = matchedBlocks.joinToString(" ") { it.text }.trim()
        val normalizedConfirmed = confirmedValue.trim().lowercase()
        val normalizedExtracted = extractedText.trim().lowercase()
        
        // Exact match
        if (normalizedConfirmed == normalizedExtracted) return 1.0f
        
        // Contains match
        if (normalizedConfirmed in normalizedExtracted || normalizedExtracted in normalizedConfirmed) {
            return 0.8f
        }
        
        // Fuzzy match (remove special chars)
        val cleanedConfirmed = normalizedConfirmed.replace(Regex("[^a-z0-9.,]"), "")
        val cleanedExtracted = normalizedExtracted.replace(Regex("[^a-z0-9.,]"), "")
        if (cleanedConfirmed == cleanedExtracted) return 0.7f
        
        // Calculate similarity using Levenshtein-like approach (simplified)
        val similarity = calculateSimilarity(normalizedConfirmed, normalizedExtracted)
        return similarity.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Simple similarity calculation (Jaccard-like).
     */
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        
        val set1 = s1.toSet()
        val set2 = s2.toSet()
        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size
        
        return if (union == 0) 0.0f else intersection.toFloat() / union
    }
}


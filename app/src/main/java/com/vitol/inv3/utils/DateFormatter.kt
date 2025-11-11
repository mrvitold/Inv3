package com.vitol.inv3.utils

import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    /**
     * Formats a date string to ISO format (YYYY-MM-DD) for PostgreSQL.
     * Tries to parse various date formats and converts to ISO.
     * Returns null if the date cannot be parsed or is invalid.
     */
    fun formatDateForDatabase(dateString: String?): String? {
        if (dateString.isNullOrBlank()) return null
        
        val trimmed = dateString.trim()
        
        // If already in ISO format (YYYY-MM-DD), validate and return
        if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return if (isValidDate(trimmed)) trimmed else null
        }
        
        // Try common date formats
        val dateFormats = listOf(
            "dd.MM.yyyy",
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "yyyy.MM.dd",
            "yyyy/MM/dd",
            "yyyy-MM-dd",
            "dd.MM.yy",
            "dd/MM/yy",
            "dd-MM-yy",
            "yyyyMMdd",  // Format like "20240304"
            "ddMMyyyy",  // Format like "04032024"
            "ddMMyy",    // Format like "040324"
        )
        
        for (format in dateFormats) {
            try {
                val parser = SimpleDateFormat(format, Locale.US)
                parser.isLenient = false
                val date = parser.parse(trimmed)
                if (date != null) {
                    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    return formatter.format(date)
                }
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        // Try to parse malformed dates like "2024344" (might be "2024-03-44" or similar)
        // Extract year, month, day if possible
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length >= 6) {
            try {
                when (digitsOnly.length) {
                    8 -> {
                        // Format: YYYYMMDD or DDMMYYYY
                        val year = digitsOnly.substring(0, 4).toInt()
                        val month = digitsOnly.substring(4, 6).toInt()
                        val day = digitsOnly.substring(6, 8).toInt()
                        if (isValidYearMonthDay(year, month, day)) {
                            return String.format("%04d-%02d-%02d", year, month, day)
                        }
                        // Try DDMMYYYY format
                        val day2 = digitsOnly.substring(0, 2).toInt()
                        val month2 = digitsOnly.substring(2, 4).toInt()
                        val year2 = digitsOnly.substring(4, 8).toInt()
                        if (isValidYearMonthDay(year2, month2, day2)) {
                            return String.format("%04d-%02d-%02d", year2, month2, day2)
                        }
                    }
                    7 -> {
                        // Handle malformed dates like "2024344" - might be "2024-03-44" (invalid day)
                        // Try YYYYMMD or YYYYMDD
                        val year = digitsOnly.substring(0, 4).toInt()
                        if (year in 1900..2100) {
                            // Try YYYYMMD format
                            val month = digitsOnly.substring(4, 6).toInt()
                            val day = digitsOnly.substring(6, 7).toInt()
                            if (month in 1..12 && day in 1..9) {
                                return String.format("%04d-%02d-%02d", year, month, day)
                            }
                            // Try YYYYMDD format
                            val month2 = digitsOnly.substring(4, 5).toInt()
                            val day2 = digitsOnly.substring(5, 7).toInt()
                            if (month2 in 1..9 && day2 in 1..31) {
                                return String.format("%04d-%02d-%02d", year, month2, day2)
                            }
                        }
                    }
                    6 -> {
                        // Format: YYMMDD or DDMMYY
                        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                        val century = (currentYear / 100) * 100
                        val year1 = century + digitsOnly.substring(0, 2).toInt()
                        val month1 = digitsOnly.substring(2, 4).toInt()
                        val day1 = digitsOnly.substring(4, 6).toInt()
                        if (isValidYearMonthDay(year1, month1, day1)) {
                            return String.format("%04d-%02d-%02d", year1, month1, day1)
                        }
                        // Try DDMMYY format
                        val day2 = digitsOnly.substring(0, 2).toInt()
                        val month2 = digitsOnly.substring(2, 4).toInt()
                        val year2 = century + digitsOnly.substring(4, 6).toInt()
                        if (isValidYearMonthDay(year2, month2, day2)) {
                            return String.format("%04d-%02d-%02d", year2, month2, day2)
                        }
                    }
                }
            } catch (e: Exception) {
                // Invalid format
            }
        }
        
        Timber.w("Could not parse date: $dateString")
        return null
    }
    
    private fun isValidDate(dateString: String): Boolean {
        return try {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formatter.isLenient = false
            formatter.parse(dateString) != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidYearMonthDay(year: Int, month: Int, day: Int): Boolean {
        return try {
            year in 1900..2100 && month in 1..12 && day in 1..31
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validates if a date string is in valid YYYY-MM-DD format.
     * Used for field validation in template learning.
     */
    fun isValidYearMonthDay(dateString: String?): Boolean {
        if (dateString.isNullOrBlank()) return false
        // Try to format it - if it returns non-null, it's valid
        return formatDateForDatabase(dateString) != null
    }
}


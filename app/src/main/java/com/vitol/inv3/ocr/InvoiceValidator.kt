package com.vitol.inv3.ocr

import com.vitol.inv3.data.remote.InvoiceRecord
import com.vitol.inv3.ui.exports.InvoiceError
import com.vitol.inv3.ui.exports.InvoiceErrorType
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class InvoiceValidator {
    
    // Lithuanian VAT rates: 21%, 9%, 5%, and 0%
    private val LITHUANIAN_VAT_RATES = listOf(0.21, 0.09, 0.05, 0.0)
    private val VAT_TOLERANCE = 0.03 // ±0.03 EUR
    private val MAX_AMOUNT = 1_000_000.0 // 1,000,000 EUR
    private val MAX_FUTURE_MONTHS = 2 // Maximum 2 months in future
    
    /**
     * Validate an invoice against all rules and return list of errors.
     * @param invoice The invoice to validate
     * @param allInvoices All invoices in database for comparison
     */
    fun validateInvoice(invoice: InvoiceRecord, allInvoices: List<InvoiceRecord>): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        // Check empty required fields
        errors.addAll(checkEmptyFields(invoice))
        
        // Check negative amounts
        errors.addAll(checkNegativeAmounts(invoice))
        
        // Check amounts too large
        errors.addAll(checkAmountTooLarge(invoice))
        
        // Check date validity
        errors.addAll(checkDateValidity(invoice))
        
        // Check VAT amount mismatch
        errors.addAll(checkVatAmountMismatch(invoice))
        
        // Check duplicate invoice ID
        errors.addAll(checkDuplicateInvoiceId(invoice, allInvoices))
        
        // Check VAT format mismatch
        errors.addAll(checkVatFormatMismatch(invoice, allInvoices))
        
        // Check company number format mismatch
        errors.addAll(checkCompanyNumberFormatMismatch(invoice, allInvoices))
        
        // Check company name/VAT number mismatch
        errors.addAll(checkCompanyVatMismatch(invoice, allInvoices))
        
        return errors
    }
    
    private fun checkEmptyFields(invoice: InvoiceRecord): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        if (invoice.invoice_id.isNullOrBlank()) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "invoice_id",
                "Invoice ID is required"
            ))
        }
        
        if (invoice.date.isNullOrBlank()) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "date",
                "Date is required"
            ))
        }
        
        if (invoice.company_name.isNullOrBlank()) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "company_name",
                "Company name is required"
            ))
        }
        
        if (invoice.amount_without_vat_eur == null) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "amount_without_vat_eur",
                "Amount without VAT is required"
            ))
        }
        
        if (invoice.vat_amount_eur == null) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "vat_amount_eur",
                "VAT amount is required"
            ))
        }
        
        if (invoice.vat_number.isNullOrBlank()) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "vat_number",
                "VAT number is required"
            ))
        }
        
        if (invoice.company_number.isNullOrBlank()) {
            errors.add(InvoiceError(
                InvoiceErrorType.EMPTY_FIELD,
                "company_number",
                "Company number is required"
            ))
        }
        
        return errors
    }
    
    private fun checkNegativeAmounts(invoice: InvoiceRecord): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        invoice.amount_without_vat_eur?.let { amount ->
            if (amount < 0) {
                errors.add(InvoiceError(
                    InvoiceErrorType.NEGATIVE_AMOUNT,
                    "amount_without_vat_eur",
                    "Amount without VAT cannot be negative"
                ))
            }
        }
        
        invoice.vat_amount_eur?.let { vat ->
            if (vat < 0) {
                errors.add(InvoiceError(
                    InvoiceErrorType.NEGATIVE_AMOUNT,
                    "vat_amount_eur",
                    "VAT amount cannot be negative"
                ))
            }
        }
        
        return errors
    }
    
    private fun checkAmountTooLarge(invoice: InvoiceRecord): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        invoice.amount_without_vat_eur?.let { amount ->
            if (amount > MAX_AMOUNT) {
                errors.add(InvoiceError(
                    InvoiceErrorType.AMOUNT_TOO_LARGE,
                    "amount_without_vat_eur",
                    "Amount without VAT exceeds maximum of ${MAX_AMOUNT.toInt()} EUR"
                ))
            }
        }
        
        invoice.vat_amount_eur?.let { vat ->
            if (vat > MAX_AMOUNT) {
                errors.add(InvoiceError(
                    InvoiceErrorType.AMOUNT_TOO_LARGE,
                    "vat_amount_eur",
                    "VAT amount exceeds maximum of ${MAX_AMOUNT.toInt()} EUR"
                ))
            }
        }
        
        return errors
    }
    
    private fun checkDateValidity(invoice: InvoiceRecord): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        val dateStr = invoice.date ?: return errors
        
        val date = parseDate(dateStr)
        if (date == null) {
            errors.add(InvoiceError(
                InvoiceErrorType.INVALID_DATE,
                "date",
                "Invalid date format: $dateStr"
            ))
            return errors
        }
        
        // Check if date is not more than 2 months in future
        val calendar = Calendar.getInstance()
        val currentDate = calendar.time
        
        calendar.add(Calendar.MONTH, MAX_FUTURE_MONTHS)
        val maxFutureDate = calendar.time
        
        if (date.after(maxFutureDate)) {
            errors.add(InvoiceError(
                InvoiceErrorType.INVALID_DATE,
                "date",
                "Date is more than $MAX_FUTURE_MONTHS months in the future"
            ))
        }
        
        return errors
    }
    
    private fun parseDate(dateStr: String): java.util.Date? {
        if (dateStr.isBlank()) return null
        
        // Try YYYY-MM-DD format
        try {
            val format1 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format1.isLenient = false
            return format1.parse(dateStr)
        } catch (e: Exception) {
            // Try DD.MM.YYYY format
            try {
                val format2 = SimpleDateFormat("dd.MM.yyyy", Locale.US)
                format2.isLenient = false
                return format2.parse(dateStr)
            } catch (e2: Exception) {
                Timber.w("Failed to parse date: $dateStr")
                return null
            }
        }
    }
    
    private fun checkVatAmountMismatch(invoice: InvoiceRecord): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        val amountWithoutVat = invoice.amount_without_vat_eur ?: return errors
        val vatAmount = invoice.vat_amount_eur ?: return errors
        
        // Check if VAT amount matches any Lithuanian VAT rate
        var matchesAnyRate = false
        
        for (rate in LITHUANIAN_VAT_RATES) {
            val expectedVat = amountWithoutVat * rate
            val difference = abs(vatAmount - expectedVat)
            
            if (difference <= VAT_TOLERANCE) {
                matchesAnyRate = true
                break
            }
        }
        
        if (!matchesAnyRate) {
            errors.add(InvoiceError(
                InvoiceErrorType.VAT_AMOUNT_MISMATCH,
                "vat_amount_eur",
                "VAT amount does not match any standard rate (0%, 5%, 9%, 21%) within ±${VAT_TOLERANCE} EUR tolerance"
            ))
        }
        
        return errors
    }
    
    private fun checkDuplicateInvoiceId(invoice: InvoiceRecord, allInvoices: List<InvoiceRecord>): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        val invoiceId = invoice.invoice_id ?: return errors
        
        // Count how many invoices have the same invoice_id (excluding current invoice)
        val duplicateCount = allInvoices.count { other ->
            other.id != invoice.id && other.invoice_id == invoiceId
        }
        
        if (duplicateCount > 0) {
            errors.add(InvoiceError(
                InvoiceErrorType.DUPLICATE_INVOICE_ID,
                "invoice_id",
                "Duplicate invoice ID found: $invoiceId ($duplicateCount other invoice(s) with same ID)"
            ))
        }
        
        return errors
    }
    
    private fun checkVatFormatMismatch(invoice: InvoiceRecord, allInvoices: List<InvoiceRecord>): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        val vatNumber = invoice.vat_number ?: return errors
        
        // Get all other VAT numbers from database (excluding current invoice)
        val otherVatNumbers = allInvoices
            .filter { it.id != invoice.id && !it.vat_number.isNullOrBlank() }
            .mapNotNull { it.vat_number }
        
        if (otherVatNumbers.isEmpty()) {
            // No historical data to compare, skip check
            return errors
        }
        
        // Extract format pattern: prefix, length, character pattern
        val currentFormat = extractVatFormat(vatNumber)
        
        // Check if any other VAT number has the same format
        val hasMatchingFormat = otherVatNumbers.any { otherVat ->
            val otherFormat = extractVatFormat(otherVat)
            currentFormat == otherFormat
        }
        
        if (!hasMatchingFormat) {
            errors.add(InvoiceError(
                InvoiceErrorType.VAT_FORMAT_MISMATCH,
                "vat_number",
                "VAT number format/length does not match any historical VAT numbers in database"
            ))
        }
        
        return errors
    }
    
    private fun checkCompanyNumberFormatMismatch(invoice: InvoiceRecord, allInvoices: List<InvoiceRecord>): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        val companyNumber = invoice.company_number ?: return errors
        
        // Get all other company numbers from database (excluding current invoice)
        val otherCompanyNumbers = allInvoices
            .filter { it.id != invoice.id && !it.company_number.isNullOrBlank() }
            .mapNotNull { it.company_number }
        
        if (otherCompanyNumbers.isEmpty()) {
            // No historical data to compare, skip check
            return errors
        }
        
        // Extract format pattern: length, character pattern
        val currentFormat = extractCompanyNumberFormat(companyNumber)
        
        // Check if any other company number has the same format
        val hasMatchingFormat = otherCompanyNumbers.any { otherNumber ->
            val otherFormat = extractCompanyNumberFormat(otherNumber)
            currentFormat == otherFormat
        }
        
        if (!hasMatchingFormat) {
            errors.add(InvoiceError(
                InvoiceErrorType.COMPANY_NUMBER_FORMAT_MISMATCH,
                "company_number",
                "Company number format/length does not match any historical company numbers in database"
            ))
        }
        
        return errors
    }
    
    private fun checkCompanyVatMismatch(invoice: InvoiceRecord, allInvoices: List<InvoiceRecord>): List<InvoiceError> {
        val errors = mutableListOf<InvoiceError>()
        
        val companyName = invoice.company_name ?: return errors
        val vatNumber = invoice.vat_number ?: return errors
        
        // Find all other invoices with the same company name but different VAT number
        val mismatches = allInvoices.filter { other ->
            other.id != invoice.id &&
            other.company_name == companyName &&
            !other.vat_number.isNullOrBlank() &&
            other.vat_number != vatNumber
        }
        
        if (mismatches.isNotEmpty()) {
            val differentVatNumbers = mismatches.mapNotNull { it.vat_number }.distinct()
            errors.add(InvoiceError(
                InvoiceErrorType.COMPANY_VAT_MISMATCH,
                "vat_number",
                "Company '$companyName' has different VAT numbers in database: ${differentVatNumbers.joinToString(", ")}"
            ))
        }
        
        return errors
    }
    
    /**
     * Extract format pattern from VAT number.
     * Format includes: prefix (e.g., "LT"), length, and character pattern (letters/digits).
     */
    private fun extractVatFormat(vatNumber: String): String {
        val prefix = if (vatNumber.length >= 2 && vatNumber.substring(0, 2).all { it.isLetter() }) {
            vatNumber.substring(0, 2).uppercase()
        } else {
            ""
        }
        
        val length = vatNumber.length
        val charPattern = vatNumber.map { char ->
            when {
                char.isLetter() -> 'L'
                char.isDigit() -> 'D'
                else -> char
            }
        }.joinToString("")
        
        return "$prefix|$length|$charPattern"
    }
    
    /**
     * Extract format pattern from company number.
     * Format includes: length and character pattern (letters/digits).
     */
    private fun extractCompanyNumberFormat(companyNumber: String): String {
        val length = companyNumber.length
        val charPattern = companyNumber.map { char ->
            when {
                char.isLetter() -> 'L'
                char.isDigit() -> 'D'
                else -> char
            }
        }.joinToString("")
        
        return "$length|$charPattern"
    }
}


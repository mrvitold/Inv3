package com.vitol.inv3.ui.exports

import com.vitol.inv3.data.remote.InvoiceRecord

enum class InvoiceErrorType {
    EMPTY_FIELD,
    VAT_FORMAT_MISMATCH,
    COMPANY_NUMBER_FORMAT_MISMATCH,
    VAT_AMOUNT_MISMATCH,
    INVALID_DATE,
    NEGATIVE_AMOUNT,
    AMOUNT_TOO_LARGE,
    DUPLICATE_INVOICE_ID,
    COMPANY_VAT_MISMATCH
}

data class InvoiceError(
    val errorType: InvoiceErrorType,
    val fieldName: String,
    val message: String
)

data class InvoiceValidationResult(
    val invoice: InvoiceRecord,
    val errors: List<InvoiceError>
) {
    val hasErrors: Boolean
        get() = errors.isNotEmpty()
}


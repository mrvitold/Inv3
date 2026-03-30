package com.vitol.inv3.ui.exports

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vitol.inv3.R

/**
 * Localized user-facing text for [InvoiceError] (matches [InvoiceValidator] rules).
 */
@Composable
fun InvoiceError.localizedMessage(): String {
    val a = formatArgs
    return when (errorType) {
        InvoiceErrorType.EMPTY_FIELD -> when (fieldName) {
            "invoice_id" -> stringResource(R.string.validation_empty_invoice_id)
            "date" -> stringResource(R.string.validation_empty_date)
            "company_name" -> stringResource(R.string.validation_empty_company_name)
            "amount_without_vat_eur" -> stringResource(R.string.validation_empty_amount_without_vat)
            "vat_amount_eur" -> stringResource(R.string.validation_empty_vat_amount)
            "vat_number" -> stringResource(R.string.validation_empty_vat_number)
            "company_number" -> stringResource(R.string.validation_empty_company_number)
            else -> stringResource(R.string.validation_empty_field_generic, fieldName)
        }
        InvoiceErrorType.NEGATIVE_AMOUNT -> when (fieldName) {
            "amount_without_vat_eur" -> stringResource(R.string.validation_negative_amount_without_vat)
            "vat_amount_eur" -> stringResource(R.string.validation_negative_vat_amount)
            else -> stringResource(R.string.validation_negative_amount_generic, fieldName)
        }
        InvoiceErrorType.AMOUNT_TOO_LARGE -> when (fieldName) {
            "amount_without_vat_eur" -> stringResource(
                R.string.validation_amount_without_vat_too_large,
                a[0] as Int
            )
            "vat_amount_eur" -> stringResource(
                R.string.validation_vat_amount_too_large,
                a[0] as Int
            )
            else -> stringResource(R.string.validation_amount_too_large_generic, fieldName)
        }
        InvoiceErrorType.INVALID_DATE -> {
            when (val first = a.firstOrNull()) {
                is Int -> stringResource(R.string.validation_date_future, first)
                is String -> stringResource(R.string.validation_date_invalid_format, first)
                else -> stringResource(R.string.validation_date_invalid_format, "")
            }
        }
        InvoiceErrorType.VAT_AMOUNT_MISMATCH -> stringResource(
            R.string.validation_vat_amount_mismatch,
            (a[0] as Number).toFloat()
        )
        InvoiceErrorType.DUPLICATE_INVOICE_ID -> stringResource(
            R.string.validation_duplicate_invoice_id,
            a[0] as String,
            a[1] as Int
        )
        InvoiceErrorType.VAT_FORMAT_MISMATCH -> stringResource(R.string.validation_vat_format_invalid)
        InvoiceErrorType.COMPANY_NUMBER_FORMAT_MISMATCH -> stringResource(
            R.string.validation_company_number_format_mismatch
        )
        InvoiceErrorType.COMPANY_VAT_MISMATCH -> stringResource(
            R.string.validation_company_vat_mismatch,
            a[0] as String,
            a[1] as String
        )
    }
}

package com.vitol.inv3.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vitol.inv3.data.remote.CompanyRecord
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

data class ExportInvoice(
    val invoiceId: String?,
    val date: String?,
    val companyName: String?,
    val amountWithoutVatEur: Double?,
    val vatAmountEur: Double?,
    val vatNumber: String?,
    val companyNumber: String?,
    val invoiceType: String? = null, // P = Purchase/Received, S = Sales/Issued
    val taxCode: String? = null // PVM1, PVM2, PVM25, etc.
)

/**
 * Exports invoices to CSV format (opens in Excel, Google Sheets, etc.).
 * Uses semicolon delimiter and UTF-8 BOM for European Excel compatibility.
 */
class ExcelExporter(private val context: Context) {

    private companion object {
        const val DELIMITER = ";"
        const val UTF8_BOM = "\uFEFF"
    }

    private fun escapeCsvField(value: String): String {
        return if (value.contains(DELIMITER) || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun buildCsvContent(invoices: List<ExportInvoice>, company: CompanyRecord?): String {
        val sb = StringBuilder()
        sb.append(UTF8_BOM)
        if (company != null) {
            sb.append(escapeCsvField("Export for: ${company.company_name ?: company.company_number ?: "Unknown"}"))
            sb.append("\n")
        }
        val header = listOf(
            "Date", "Invoice_ID", "Company_name", "Amount_without_VAT_EUR", "VAT_amount_EUR",
            "VAT_number", "Company_number", "Invoice_Type", "Tax_Code"
        )
        sb.append(header.joinToString(DELIMITER) { escapeCsvField(it) })
        sb.append("\n")
        invoices.forEach { inv ->
            val row = listOf(
                inv.date ?: "",
                inv.invoiceId ?: "",
                inv.companyName ?: "",
                (inv.amountWithoutVatEur ?: 0.0).toString(),
                (inv.vatAmountEur ?: 0.0).toString(),
                inv.vatNumber ?: "",
                inv.companyNumber ?: "",
                inv.invoiceType ?: "",
                inv.taxCode ?: ""
            )
            sb.append(row.joinToString(DELIMITER) { escapeCsvField(it) })
            sb.append("\n")
        }
        return sb.toString()
    }

    fun export(invoices: List<ExportInvoice>, month: String? = null, company: CompanyRecord? = null): Uri {
        val fileName = if (month != null) "invoices_$month.csv" else {
            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            "invoices_$ts.csv"
        }
        val content = buildCsvContent(invoices, company)
        val outFile = File(context.cacheDir, fileName)
        OutputStreamWriter(outFile.outputStream(), Charsets.UTF_8).use { it.write(content) }
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    fun saveToDownloads(invoices: List<ExportInvoice>, month: String? = null, company: CompanyRecord? = null): String? {
        val fileName = if (month != null) "invoices_$month.csv" else {
            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            "invoices_$ts.csv"
        }
        val content = buildCsvContent(invoices, company)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out, Charsets.UTF_8).use { it.write(content) }
                    }
                    Timber.d("File saved to Downloads: $fileName")
                    "Saved to Downloads/$fileName"
                } else {
                    Timber.e("Failed to create file in Downloads")
                    null
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                OutputStreamWriter(file.outputStream(), Charsets.UTF_8).use { it.write(content) }
                Timber.d("File saved to Downloads: ${file.absolutePath}")
                "Saved to Downloads/$fileName"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save file to Downloads")
            null
        }
    }
}

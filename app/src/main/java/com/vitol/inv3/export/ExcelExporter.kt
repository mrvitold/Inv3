package com.vitol.inv3.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vitol.inv3.data.remote.CompanyRecord
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
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

private const val XLSX_EXT = ".xlsx"
private const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * Exports invoices to Excel (.xlsx) format using Apache POI.
 */
class ExcelExporter(private val context: Context) {

    /** Strip decorative quotes from company names for export (e.g. UAB "Bauen" -> UAB Bauen). */
    private fun stripDecorativeQuotes(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name!!.replace("\"", "").trim()
    }

    private fun buildWorkbook(invoices: List<ExportInvoice>, company: CompanyRecord?): XSSFWorkbook {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("Invoices")
        var rowIdx = 0

        if (company != null) {
            val companyDisplay = stripDecorativeQuotes(company.company_name ?: company.company_number ?: "Unknown")
            val row = sheet.createRow(rowIdx++)
            row.createCell(0).setCellValue("Export for: $companyDisplay")
        }

        val header = listOf(
            "Date", "Invoice_ID", "Company_name", "Amount_without_VAT_EUR", "VAT_amount_EUR",
            "VAT_number", "Company_number", "Invoice_Type", "Tax_Code"
        )
        val headerRow = sheet.createRow(rowIdx++)
        header.forEachIndexed { i, h -> headerRow.createCell(i).setCellValue(h) }

        invoices.forEach { inv ->
            val row = sheet.createRow(rowIdx++)
            row.createCell(0).setCellValue(inv.date ?: "")
            row.createCell(1).setCellValue(inv.invoiceId ?: "")
            row.createCell(2).setCellValue(stripDecorativeQuotes(inv.companyName))
            row.createCell(3).setCellValue(inv.amountWithoutVatEur ?: 0.0)
            row.createCell(4).setCellValue(inv.vatAmountEur ?: 0.0)
            row.createCell(5).setCellValue(inv.vatNumber ?: "")
            row.createCell(6).setCellValue(inv.companyNumber ?: "")
            row.createCell(7).setCellValue(inv.invoiceType ?: "")
            row.createCell(8).setCellValue(inv.taxCode ?: "")
        }

        return wb
    }

    private fun getFileName(month: String?): String {
        return if (month != null) "invoices_$month$XLSX_EXT" else {
            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            "invoices_$ts$XLSX_EXT"
        }
    }

    fun export(invoices: List<ExportInvoice>, month: String? = null, company: CompanyRecord? = null): Uri {
        val fileName = getFileName(month)
        val outFile = File(context.cacheDir, fileName)
        buildWorkbook(invoices, company).use { wb ->
            FileOutputStream(outFile).use { wb.write(it) }
        }
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    fun saveToDownloads(invoices: List<ExportInvoice>, month: String? = null, company: CompanyRecord? = null): String? {
        val fileName = getFileName(month)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, MIME_XLSX)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        buildWorkbook(invoices, company).use { wb -> wb.write(out) }
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
                FileOutputStream(file).use { out ->
                    buildWorkbook(invoices, company).use { wb -> wb.write(out) }
                }
                Timber.d("File saved to Downloads: ${file.absolutePath}")
                "Saved to Downloads/$fileName"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save file to Downloads")
            null
        }
    }
}

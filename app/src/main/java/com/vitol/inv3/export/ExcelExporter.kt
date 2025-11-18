package com.vitol.inv3.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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
    val companyNumber: String?
)

class ExcelExporter(private val context: Context) {
    fun export(invoices: List<ExportInvoice>, month: String? = null): Uri {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("Invoices")

        val header = listOf(
            "Invoice_ID", "Date", "Company_name", "Amount_without_VAT_EUR", "VAT_amount_EUR", "VAT_number", "Company_number"
        )
        val headerRow = sheet.createRow(0)
        header.forEachIndexed { idx, title -> headerRow.createCell(idx).setCellValue(title) }

        invoices.forEachIndexed { i, inv ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(inv.invoiceId ?: "")
            row.createCell(1).setCellValue(inv.date ?: "")
            row.createCell(2).setCellValue(inv.companyName ?: "")
            row.createCell(3).setCellValue(inv.amountWithoutVatEur ?: 0.0)
            row.createCell(4).setCellValue(inv.vatAmountEur ?: 0.0)
            row.createCell(5).setCellValue(inv.vatNumber ?: "")
            row.createCell(6).setCellValue(inv.companyNumber ?: "")
        }

        // Use month-specific filename if provided, otherwise use timestamp
        val fileName = if (month != null) {
            "invoices_$month.xlsx"
        } else {
            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            "invoices_$ts.xlsx"
        }
        
        val outFile = File(context.cacheDir, fileName)
        FileOutputStream(outFile).use { wb.write(it) }
        wb.close()
        // Use FileProvider for Android 7.0+ compatibility
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    fun saveToDownloads(invoices: List<ExportInvoice>, month: String? = null): String? {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("Invoices")

        val header = listOf(
            "Invoice_ID", "Date", "Company_name", "Amount_without_VAT_EUR", "VAT_amount_EUR", "VAT_number", "Company_number"
        )
        val headerRow = sheet.createRow(0)
        header.forEachIndexed { idx, title -> headerRow.createCell(idx).setCellValue(title) }

        invoices.forEachIndexed { i, inv ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(inv.invoiceId ?: "")
            row.createCell(1).setCellValue(inv.date ?: "")
            row.createCell(2).setCellValue(inv.companyName ?: "")
            row.createCell(3).setCellValue(inv.amountWithoutVatEur ?: 0.0)
            row.createCell(4).setCellValue(inv.vatAmountEur ?: 0.0)
            row.createCell(5).setCellValue(inv.vatNumber ?: "")
            row.createCell(6).setCellValue(inv.companyNumber ?: "")
        }

        // Use month-specific filename if provided, otherwise use timestamp
        val fileName = if (month != null) {
            "invoices_$month.xlsx"
        } else {
            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            "invoices_$ts.xlsx"
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        wb.write(outputStream)
                    }
                    wb.close()
                    Timber.d("File saved to Downloads: $fileName")
                    "Saved to Downloads/$fileName"
                } else {
                    wb.close()
                    Timber.e("Failed to create file in Downloads")
                    null
                }
            } else {
                // Android 9 and below - Use direct file access
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { wb.write(it) }
                wb.close()
                Timber.d("File saved to Downloads: ${file.absolutePath}")
                "Saved to Downloads/$fileName"
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save file to Downloads")
            wb.close()
            null
        }
    }
}


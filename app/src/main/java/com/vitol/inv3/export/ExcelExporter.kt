package com.vitol.inv3.export

import android.content.Context
import android.net.Uri
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    fun export(invoices: List<ExportInvoice>): Uri {
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

        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "invoices_$ts.xlsx")
        FileOutputStream(outFile).use { wb.write(it) }
        wb.close()
        // Use FileProvider for Android 7.0+ compatibility
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }
}


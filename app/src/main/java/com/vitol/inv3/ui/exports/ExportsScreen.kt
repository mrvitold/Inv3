package com.vitol.inv3.ui.exports

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vitol.inv3.export.ExcelExporter
import com.vitol.inv3.export.ExportInvoice

@Composable
fun ExportsScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Export confirmed invoices")
        Button(onClick = {
            val exporter = ExcelExporter(context)
            val sample = listOf(
                ExportInvoice("INV-1", "2025-01-01", "Sample Co", 100.0, 21.0, "LT123456789", "1234567")
            )
            val uri = exporter.export(sample)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(share, "Share invoices.xlsx"))
        }) {
            Text("Export .xlsx")
        }
    }
}


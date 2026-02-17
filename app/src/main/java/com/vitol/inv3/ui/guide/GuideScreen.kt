package com.vitol.inv3.ui.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.vitol.inv3.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Guide") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GuideSection(
                step = 1,
                title = "Fill your own company data",
                icon = Icons.Default.Business,
                content = "Use the \"Your Company\" selector on the Home screen. Add your company in Companies if needed. Required first – without it, the app can mismatch counterparty and own company data. Own company data is also required in the export XML file."
            )

            GuideSection(
                step = 2,
                title = "Taking photos",
                icon = Icons.Default.CameraAlt,
                content = "1. Tap Scan with Camera\n2. Choose Purchase or Sales\n3. Tap Capture to take a photo\n4. Review extracted data, edit if needed\n5. Tap Confirm and Save"
            )

            GuideSection(
                step = 3,
                title = "Importing files",
                icon = Icons.Default.Upload,
                content = "1. Tap Import files\n2. Choose Purchase or Sales\n3. Pick images or PDF files\n4. Wait for extraction\n5. Review each invoice, edit if needed\n6. Tap Save and next or Confirm and Save"
            )

            GuideSection(
                step = 4,
                title = "Verifying invoices",
                icon = Icons.Default.Edit,
                content = "1. Go to Exports\n2. Expand a month to see invoices\n3. Tap the Edit icon on any invoice to fix fields\n4. Check invoices marked with [to check] for validation errors\n5. Tap Save Changes"
            )

            GuideSection(
                step = 5,
                title = "Export XML or Excel",
                icon = Icons.Default.FileDownload,
                content = "1. Go to Exports\n2. Select a year and tap Export on a month (or Export All)\n3. Choose Export Excel or Export XML (i.SAF)\n4. Save to Downloads or Share to another app"
            )

            GuideSection(
                step = 6,
                title = "Submit to i.SAF",
                icon = null,
                content = "1. Export XML from the app (Share or Save)\n2. Log in to the VMI i.SAF portal (vmi.lt)\n3. Go to i.SAF → Įkelti rinkmeną (Upload file)\n4. Drag and drop the XML file or tap Pasirinkite rinkmeną iš kompiuterio to select it\n5. The file will appear in the list once accepted",
                imageResId = null  // guide_isaf_upload.png removed - use valid PNG if re-adding
            )
        }
    }
}

@Composable
private fun GuideSection(
    step: Int,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    content: String,
    imageResId: Int? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "$step. $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            imageResId?.let { resId ->
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    painter = painterResource(resId),
                    contentDescription = "VMI i.SAF upload page",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

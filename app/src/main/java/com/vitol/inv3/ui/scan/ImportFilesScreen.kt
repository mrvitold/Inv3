package com.vitol.inv3.ui.scan

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitol.inv3.Routes
import com.vitol.inv3.ui.subscription.SubscriptionViewModel
import com.vitol.inv3.ui.subscription.UpgradeDialog
import com.vitol.inv3.utils.ImportImageCache
import com.vitol.inv3.utils.PdfPageResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFilesScreen(
    navController: NavController,
    invoiceType: String,
    importSessionViewModel: ImportSessionViewModel = hiltViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity),
    subscriptionViewModel: SubscriptionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var isBuildingPages by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var pendingUris by remember { mutableStateOf<List<Uri>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        Timber.d("ImportFiles: picker returned ${uris.size} URI(s)")
        if (uris.isEmpty()) {
            Timber.w("ImportFiles: picker returned empty list")
            return@rememberLauncherForActivityResult
        }
        pendingUris = uris
    }

    LaunchedEffect(pendingUris) {
        val uris = pendingUris ?: return@LaunchedEffect
        pendingUris = null
        isBuildingPages = true
        errorMessage = null
        Timber.d("ImportFiles: building pages for ${uris.size} URI(s)")
        try {
            val pages = withContext(Dispatchers.IO) {
                buildImportPages(context, uris)
            }
            Timber.d("ImportFiles: built ${pages.size} page(s)")
            isBuildingPages = false
            if (pages.isEmpty()) {
                errorMessage = if (uris.size == 1) {
                    "Could not read the selected file. Use an image or PDF."
                } else {
                    "Could not read the selected files. Use images or PDFs."
                }
                return@LaunchedEffect
            }
            val totalCount = pages.size
            if (!subscriptionViewModel.canScanPages(totalCount)) {
                showUpgradeDialog = true
                return@LaunchedEffect
            }
            importSessionViewModel.startSession(pages, invoiceType)
            navController.navigate(Routes.ImportPrepare) {
                popUpTo("${Routes.ImportFiles}/$invoiceType") { inclusive = true }
            }
        } catch (e: Exception) {
            Timber.e(e, "ImportFiles: failed to build pages")
            isBuildingPages = false
            errorMessage = "Error reading files: ${e.message ?: "Please try again."}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (invoiceType == "S") "Import Sales Invoices" else "Import Purchase Invoices") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isBuildingPages) {
                Text("Preparing files…", style = MaterialTheme.typography.bodyLarge)
                return@Scaffold
            }
            errorMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = "Select images or PDF files to import as invoices. Each image or PDF page will be reviewed one by one.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(
                onClick = { launcher.launch(arrayOf("image/*", "application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Select files")
            }
        }
    }

    val subscriptionStatus by subscriptionViewModel.subscriptionStatus.collectAsState(initial = null)
    if (showUpgradeDialog) {
        UpgradeDialog(
            subscriptionStatus = subscriptionStatus,
            onDismiss = { showUpgradeDialog = false },
            onUpgradeClick = { _ ->
                showUpgradeDialog = false
                navController.navigate(Routes.Subscription) {
                    popUpTo(Routes.Home) { inclusive = false }
                }
            }
        )
    }
}

internal suspend fun buildImportPages(context: android.content.Context, uris: List<Uri>): List<ImportPage> =
    withContext(Dispatchers.IO) {
        val pages = mutableListOf<ImportPage>()
        for (uri in uris) {
            try {
                val mime = context.contentResolver.getType(uri)?.lowercase() ?: ""
                val path = uri.toString().lowercase()
                val isImage = mime.startsWith("image/") ||
                    path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".bmp") ||
                    (mime.isEmpty() && isImageByContent(context, uri))
                val isPdf = mime == "application/pdf" || path.endsWith(".pdf") ||
                    (mime.isEmpty() && isPdfByContent(context, uri))
                when {
                    isImage -> {
                        val cacheUri = ImportImageCache.copyToCache(context, uri)
                        pages.add(ImportPage.ImagePage(cacheUri))
                    }
                    isPdf -> {
                        val count = PdfPageResolver.getPageCount(context, uri)
                        if (count > 0) {
                            for (i in 0 until count) {
                                pages.add(ImportPage.PdfPage(uri, i))
                            }
                        } else {
                            Timber.w("ImportFiles: PDF page count 0 for $uri")
                        }
                    }
                    else -> Timber.d("ImportFiles: skipped unsupported type mime=$mime path=$path")
                }
            } catch (e: Exception) {
                Timber.w(e, "ImportFiles: failed to process URI $uri")
            }
        }
        pages
    }

internal fun isPdfByContent(context: android.content.Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(5)
            input.read(header) >= 4 && String(header, Charsets.US_ASCII).startsWith("%PDF")
        } ?: false
    } catch (e: Exception) {
        Timber.w(e, "Could not read URI for PDF check: $uri")
        false
    }
}

internal fun isImageByContent(context: android.content.Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(8)
            val n = input.read(header)
            if (n >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return true // JPEG
            if (n >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) return true // PNG
            false
        } ?: false
    } catch (e: Exception) {
        Timber.w(e, "Could not read URI for image check: $uri")
        false
    }
}

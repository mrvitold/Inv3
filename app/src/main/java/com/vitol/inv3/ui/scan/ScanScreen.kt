package com.vitol.inv3.ui.scan

import android.Manifest
import android.content.Context
import android.net.Uri
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import com.vitol.inv3.utils.FileImportService
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    existingPages: List<Uri> = emptyList(),
    fileImportViewModel: FileImportViewModel = run {
        // Use Activity-scoped ViewModel to share state across navigation routes
        val activity = LocalContext.current as? ComponentActivity
        if (activity != null) {
            viewModel<FileImportViewModel>(
                viewModelStoreOwner = activity,
                factory = activity.defaultViewModelProviderFactory
            )
        } else {
            hiltViewModel()
        }
    }
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fileImportService = remember { FileImportService(context) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var hasPermission by remember { mutableStateOf(false) }
    var isProcessingFile by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf<String?>(null) }
    var showProcessingDialog by remember { mutableStateOf(false) }
    var cameraEnabled by remember { mutableStateOf(false) } // Only enable camera after type is selected
    
    // Get current invoice type from ViewModel
    val currentInvoiceType by fileImportViewModel.invoiceType.collectAsState()
    
    val processingQueue by fileImportViewModel.processingQueue.collectAsState()
    val currentIndex by fileImportViewModel.currentIndex.collectAsState()
    val invoiceType by fileImportViewModel.invoiceType.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasPermission = granted }
    )

    // File picker launcher - supports images and PDFs, multiple file selection
    // Filter by MIME types: images/* and application/pdf
    // Use custom contract to ensure multiple selection is enabled
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.GetMultipleContents() {
            override fun createIntent(context: android.content.Context, input: String): android.content.Intent {
                return super.createIntent(context, input).apply {
                    // Explicitly enable multiple selection
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
        }
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isProcessingFile = true
            showProcessingDialog = true
            processingMessage = "Processing ${uris.size} file(s)..."
            
            scope.launch {
                val allProcessedUris = mutableListOf<Uri>()
                var processedCount = 0
                var failedCount = 0
                var completedCount = 0
                
                // Process each selected file
                uris.forEachIndexed { index, uri ->
                    processingMessage = "Processing file ${index + 1} of ${uris.size}..."
                    
                    processSelectedFile(uri, fileImportService, snackbarHostState) { processedUris ->
                        completedCount++
                        if (processedUris.isNotEmpty()) {
                            allProcessedUris.addAll(processedUris)
                            processedCount++
                        } else {
                            failedCount++
                        }
                        
                        // Check if all files are processed
                        if (completedCount == uris.size) {
                            isProcessingFile = false
                            if (allProcessedUris.isNotEmpty()) {
                                fileImportViewModel.addToQueue(allProcessedUris)
                                processingMessage = "Found ${allProcessedUris.size} invoice(s) from ${processedCount} file(s). Processing first invoice..."
                                // Navigate to first item (invoice type is already set in ViewModel)
                                val firstUri = allProcessedUris[0]
                                showProcessingDialog = false
                                navController.navigate("review/${Uri.encode(firstUri.toString())}")
                            } else {
                                processingMessage = if (failedCount > 0) {
                                    "No invoices found in selected file(s)"
                                } else {
                                    "No invoices found in file(s)"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    
    // Enable camera automatically when invoice type is selected and permission is granted
    LaunchedEffect(currentInvoiceType, hasPermission) {
        if (currentInvoiceType != null && hasPermission && !cameraEnabled) {
            cameraEnabled = true
        }
    }

    // Check if there are more items in queue when screen is resumed
    LaunchedEffect(processingQueue.size, currentIndex) {
        // When returning from ReviewScreen, check if there are more items
        if (processingQueue.isNotEmpty()) {
            val nextUri = fileImportViewModel.getNextUri()
            if (nextUri != null && currentIndex < processingQueue.size) {
                // Small delay to ensure navigation is ready
                kotlinx.coroutines.delay(500)
                navController.navigate("review/${Uri.encode(nextUri.toString())}")
            } else if (processingQueue.isNotEmpty() && currentIndex >= processingQueue.size) {
                // Queue completed
                scope.launch {
                    snackbarHostState.showSnackbar("All invoices processed!")
                }
                fileImportViewModel.clearQueue()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Only show camera if permission granted AND type is selected
        if (hasPermission && cameraEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f) // 3:4 aspect ratio
                    .align(Alignment.Center)
            ) {
                CameraPreview(
                    onReady = { imageCapture = it }
                )
            }
            
            // Manual capture button
            FloatingActionButton(
                onClick = {
                    val capturer = imageCapture ?: return@FloatingActionButton
                    capturePhoto(context, capturer) { result ->
                        result.onSuccess { uri ->
                            scope.launch { snackbarHostState.showSnackbar("Saved: ${uri.lastPathSegment}") }
                            // If adding to existing pages, combine them; otherwise use single URI
                            val allPages = if (existingPages.isNotEmpty()) {
                                existingPages + uri
                            } else {
                                listOf(uri)
                            }
                            // Encode all URIs for navigation
                            val encodedUris = allPages.joinToString(",") { Uri.encode(it.toString()) }
                            // Invoice type is already set in ViewModel
                            navController.navigate("review/$encodedUris")
                        }.onFailure { e ->
                            scope.launch { snackbarHostState.showSnackbar("Capture failed: ${e.message}") }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Capture photo"
                )
            }
            
            // Show indicator if adding to existing pages
            if (existingPages.isNotEmpty()) {
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "Adding page ${existingPages.size + 1}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        } else if (!hasPermission) {
            // Show message when camera permission is not granted
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission required",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    text = "Please grant camera permission to scan invoices",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            // Show message when waiting for camera permission or invoice type
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentInvoiceType == null) {
                    Text(
                        text = "Please select invoice type first",
                        style = MaterialTheme.typography.titleLarge
                    )
                } else {
                    Text(
                        text = "Preparing camera...",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter))

    }
    
    // Processing dialog (outside Box to ensure proper rendering)
    if (showProcessingDialog) {
        AlertDialog(
            onDismissRequest = { showProcessingDialog = false },
            title = { Text("Processing File") },
            text = {
                if (isProcessingFile) {
                    Column {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(processingMessage ?: "Processing...")
                    }
                } else {
                    Text(processingMessage ?: "")
                }
            },
            confirmButton = {
                if (!isProcessingFile) {
                    Button(onClick = { showProcessingDialog = false }) {
                        Text("OK")
                    }
                }
            }
        )
    }
}

@Composable
private fun CameraPreview(onReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                // 3:4 aspect ratio: width 1080, height 1440 (1080 * 4/3 = 1440)
                val preview = Preview.Builder()
                    .setTargetResolution(Size(1080, 1440))
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1080, 1440))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    onReady(imageCapture)
                } catch (_: Exception) { }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onResult: (Result<Uri>) -> Unit
) {
    val outputDir = File(context.cacheDir, "captures").apply { mkdirs() }
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val photoFile = File(outputDir, "INV3_${name}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                onResult(Result.failure(exception))
            }

            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Use FileProvider for Android 7.0+ compatibility
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                onResult(Result.success(uri))
            }
        }
    )
}

/**
 * Processes selected file (PDF, image, etc.) and returns list of URIs ready for OCR
 */
suspend fun processSelectedFile(
    uri: Uri,
    fileImportService: FileImportService,
    snackbarHostState: SnackbarHostState,
    onSuccess: (List<Uri>) -> Unit
) = withContext(Dispatchers.Main) {
    try {
        // Show processing dialog
        val result = fileImportService.processFile(uri)
        
        result.onSuccess { uris ->
            Timber.d("Successfully processed file: ${uris.size} image(s) ready for processing")
            onSuccess(uris)
        }.onFailure { error ->
            val errorMessage = when {
                error.message?.contains("exceeds maximum") == true -> {
                    "File too large. Maximum size is 15 MB. ${error.message}"
                }
                error.message?.contains("Unsupported") == true -> {
                    error.message ?: "Unsupported file type"
                }
                else -> {
                    "Failed to process file: ${error.message ?: "Unknown error"}"
                }
            }
            Timber.e(error, "Failed to process file")
            // Show error in snackbar
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                snackbarHostState.showSnackbar(errorMessage)
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Exception while processing file")
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            snackbarHostState.showSnackbar("Error: ${e.message ?: "Unknown error"}")
        }
    }
}

package com.vitol.inv3.ui.scan

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.vitol.inv3.Routes
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectInvoiceTypeScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Invoice Type") },
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
            Text(
                text = "What type of invoice are you scanning?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Purchase Invoice Button
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    onClick = {
                        navController.navigate("${Routes.ScanCamera}/P") {
                            popUpTo(Routes.SelectInvoiceType) { inclusive = true }
                        }
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = "Purchase",
                            modifier = Modifier.padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Purchase",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Invoice from supplier",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Sales Invoice Button
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    onClick = {
                        navController.navigate("${Routes.ScanCamera}/S") {
                            popUpTo(Routes.SelectInvoiceType) { inclusive = true }
                        }
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = "Sales",
                            modifier = Modifier.padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Sales",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Invoice to customer",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(navController: NavController, invoiceType: String = "P") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    
    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            scope.launch {
                snackbarHostState.showSnackbar("Camera permission is required to scan invoices")
            }
        }
    }
    
    // Check and request camera permission
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.CAMERA
        
        val hasPermission = ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasPermission) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(permission)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Invoice") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            navController.navigate(Routes.Home) {
                                popUpTo(0) { inclusive = false }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Exit to Home")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!hasCameraPermission) {
                // Show permission request message
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Camera permission is required",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            } else {
                // Show camera preview with capture button
                CameraPreview(
                    onImageCaptured = { uri ->
                        if (uri != null) {
                            // URL-encode the URI string for navigation
                            val encodedUri = java.net.URLEncoder.encode(uri.toString(), "UTF-8")
                            val encodedInvoiceType = URLEncoder.encode(invoiceType, "UTF-8")
                            navController.navigate("${Routes.ReviewScan}/$encodedUri/$encodedInvoiceType") {
                                popUpTo(Routes.SelectInvoiceType) { inclusive = false }
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("Failed to capture image")
                            }
                            isCapturing = false
                        }
                    },
                    isCapturing = isCapturing,
                    onCapturingChanged = { isCapturing = it }
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    onImageCaptured: (Uri?) -> Unit,
    isCapturing: Boolean,
    onCapturingChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()
    
    // Use a mutable reference that can be updated from AndroidView
    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    var captureButtonEnabled by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Camera preview with 4:3 aspect ratio
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            capture
                        )
                        
                        // Store reference directly (not state, so no recomposition needed)
                        imageCaptureRef.value = capture
                        captureButtonEnabled = true
                        Timber.d("Camera initialized successfully")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to bind camera: ${e.message}")
                        captureButtonEnabled = false
                    }
                }, executor)
                
                previewView
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        )
        
        // Capture button overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
        if (isCapturing) {
            CircularProgressIndicator(
                modifier = Modifier.padding(24.dp)
            )
        } else {
            Button(
                onClick = {
                    val capture = imageCaptureRef.value
                    Timber.d("Capture button clicked, imageCapture: ${capture != null}, enabled: $captureButtonEnabled")
                    if (capture != null && captureButtonEnabled) {
                        onCapturingChanged(true)
                        captureImage(context, capture, executor) { uri ->
                            // Callback already runs on main executor thread
                            Timber.d("Image capture callback: uri=$uri")
                            onImageCaptured(uri)
                            onCapturingChanged(false)
                        }
                    } else {
                        Timber.w("Cannot capture: imageCapture=${capture != null}, enabled=$captureButtonEnabled")
                    }
                },
                enabled = captureButtonEnabled && !isCapturing && imageCaptureRef.value != null,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Capture",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Capture")
            }
        }
        }
    }
}

private fun captureImage(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onImageSaved: (Uri?) -> Unit
) {
    try {
        // Ensure captures directory exists
        val capturesDir = File(context.cacheDir, "captures")
        if (!capturesDir.exists()) {
            val created = capturesDir.mkdirs()
            Timber.d("Created captures directory: $created")
        }
        
        val photoFile = File(
            capturesDir,
            "${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())}.jpg"
        )
        
        Timber.d("Starting image capture to: ${photoFile.absolutePath}")
        
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputFileOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Timber.d("onImageSaved callback called")
                    try {
                        // Wait a bit for file to be written
                        Thread.sleep(100)
                        
                        if (!photoFile.exists()) {
                            Timber.e("Photo file does not exist after capture: ${photoFile.absolutePath}")
                            onImageSaved(null)
                            return
                        }
                        
                        Timber.d("Photo file exists, size: ${photoFile.length()} bytes")
                        
                        val photoUri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            photoFile
                        )
                        Timber.d("Image captured successfully: $photoUri")
                        onImageSaved(photoUri)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to create URI for captured image: ${e.message}")
                        e.printStackTrace()
                        onImageSaved(null)
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "Image capture failed: ${exception.message}")
                    Timber.e(exception, "Image capture error code: ${exception.imageCaptureError}")
                    exception.printStackTrace()
                    onImageSaved(null)
                }
            }
        )
        Timber.d("takePicture called successfully")
    } catch (e: Exception) {
        Timber.e(e, "Exception during image capture setup: ${e.message}")
        e.printStackTrace()
        onImageSaved(null)
    }
}


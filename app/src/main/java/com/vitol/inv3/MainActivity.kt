package com.vitol.inv3

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vitol.inv3.data.local.getActiveOwnCompanyIdFlow
import com.vitol.inv3.data.local.setActiveOwnCompanyId
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ui.home.OwnCompanySelector
import com.vitol.inv3.ui.home.OwnCompanyViewModel
import com.vitol.inv3.ui.scan.FileImportViewModel
import com.vitol.inv3.ui.subscription.UsageIndicator
import com.vitol.inv3.ui.subscription.SubscriptionViewModel
import com.vitol.inv3.ui.subscription.SubscriptionScreen
import com.vitol.inv3.ui.subscription.UpgradeDialog
import com.vitol.inv3.ui.scan.processSelectedFile
import com.vitol.inv3.utils.FileImportService
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.auth.AuthState
import com.vitol.inv3.ui.auth.LoginScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainActivityViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize ViewModel for lifecycle-aware access in onResume
        viewModel = androidx.lifecycle.ViewModelProvider(this)[MainActivityViewModel::class.java]
        
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val viewModel: MainActivityViewModel = hiltViewModel()
                
                // Handle deep links on creation
                LaunchedEffect(Unit) {
                    handleIntent(intent, viewModel.authManager)
                }
                
                // Refresh session when app comes to foreground (to work around free plan limitations)
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        viewModel.authManager.refreshSessionIfNeeded()
                    }
                }
                
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(navController, viewModel.authManager)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh session when app resumes (comes to foreground)
        // This helps work around free plan's 1-day refresh token expiration
        // By refreshing proactively when user opens the app, we extend the session
        // as long as the user uses the app at least once per day
        if (::viewModel.isInitialized) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch {
                viewModel.authManager.refreshSessionIfNeeded()
            }
        }
    }
    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle deep link in composable context
        setContent {
            val viewModel: MainActivityViewModel = hiltViewModel()
            LaunchedEffect(intent) {
                handleIntent(intent, viewModel.authManager)
            }
        }
    }
    
    private suspend fun handleIntent(intent: android.content.Intent?, authManager: AuthManager) {
        val data = intent?.data
        if (data != null) {
            Timber.d("Received deep link intent: ${data.toString()}")
            val result = authManager.handleDeepLink(data.toString())
            result.onSuccess { message ->
                Timber.d("Deep link handled successfully: $message")
                // The auth state will update automatically, triggering navigation
            }.onFailure { error ->
                Timber.e(error, "Failed to handle deep link: ${error.message}")
                // Show error to user - we could use a snackbar or update UI state
            }
        }
    }
}

object Routes {
    const val Login = "login"
    const val Home = "home"
    const val Scan = "scan"
    const val Review = "review"
    const val Companies = "companies"
    const val AddOwnCompany = "addOwnCompany"
    const val Exports = "exports"
    const val EditInvoice = "editInvoice"
    const val EditCompany = "editCompany"
    const val Settings = "settings"
    const val Subscription = "subscription"
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    authManager: AuthManager
) {
    val authState by authManager.authState.collectAsState(initial = AuthState.Loading)
    
    // Navigate to login when unauthenticated
    LaunchedEffect(authState) {
        if (authState is AuthState.Unauthenticated && navController.currentDestination?.route != Routes.Login) {
            navController.navigate(Routes.Login) {
                popUpTo(0) { inclusive = true }
            }
        } else if (authState is AuthState.Authenticated && navController.currentDestination?.route == Routes.Login) {
            navController.navigate(Routes.Home) {
                popUpTo(Routes.Login) { inclusive = true }
            }
        }
    }
    
    // Determine start destination based on auth state
    val startDestination = when (authState) {
        is AuthState.Authenticated -> Routes.Home
        else -> Routes.Login
    }
    
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.Login) {
            LoginScreen(
                authManager = authManager,
                onNavigateToHome = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Home) { HomeScreen(navController) }
        composable("${Routes.Scan}/addPage/{existingPages}") { backStackEntry ->
            val existingPagesParam = backStackEntry.arguments?.getString("existingPages")
            val existingPages = if (existingPagesParam.isNullOrBlank()) {
                emptyList<android.net.Uri>()
            } else {
                try {
                    existingPagesParam.split(",").mapNotNull { encodedUri ->
                        try {
                            android.net.Uri.parse(java.net.URLDecoder.decode(encodedUri, "UTF-8"))
                        } catch (e: Exception) {
                            null
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            com.vitol.inv3.ui.scan.ScanScreen(
                navController = navController,
                existingPages = existingPages
            )
        }
        composable(Routes.Scan) { com.vitol.inv3.ui.scan.ScanScreen(navController = navController) }
        composable("review/{uri}") { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uri")
            val uris = if (uriString.isNullOrBlank()) {
                emptyList<android.net.Uri>()
            } else {
                try {
                    // Support comma-separated URIs for multi-page invoices
                    uriString.split(",").mapNotNull { encodedUri ->
                        try {
                            android.net.Uri.parse(java.net.URLDecoder.decode(encodedUri, "UTF-8"))
                        } catch (e: Exception) {
                            null
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (uris.isNotEmpty()) {
                com.vitol.inv3.ui.review.ReviewScreen(imageUris = uris, navController = navController)
            } else {
                PlaceholderScreen("Missing image")
            }
        }
        composable(Routes.Companies) { 
            com.vitol.inv3.ui.companies.CompaniesScreen(
                markAsOwnCompany = false,
                navController = navController
            ) 
        }
        composable(Routes.AddOwnCompany) { 
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            
            com.vitol.inv3.ui.companies.CompaniesScreen(
                markAsOwnCompany = true,
                navController = navController,
                onCompanySaved = { companyId ->
                    scope.launch {
                        if (companyId != null) {
                            context.setActiveOwnCompanyId(companyId)
                        }
                    }
                }
            ) 
        }
        composable(Routes.Exports) { com.vitol.inv3.ui.exports.ExportsScreen(navController = navController) }
        composable(Routes.Settings) { com.vitol.inv3.ui.settings.SettingsScreen(navController = navController) }
        composable(Routes.Subscription) { 
            SubscriptionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("${Routes.EditInvoice}/{invoiceId}") { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getString("invoiceId") ?: ""
            if (invoiceId.isNotBlank()) {
                com.vitol.inv3.ui.exports.EditInvoiceScreen(invoiceId = invoiceId, navController = navController)
            } else {
                PlaceholderScreen("Invalid invoice ID")
            }
        }
        composable("${Routes.EditCompany}/{companyId}") { backStackEntry ->
            val companyId = backStackEntry.arguments?.getString("companyId") ?: ""
            if (companyId.isNotBlank()) {
                com.vitol.inv3.ui.companies.EditCompanyScreen(companyId = companyId, navController = navController)
            } else {
                PlaceholderScreen("Invalid company ID")
            }
        }
    }
}

@Composable
fun HomeScreen(
    navController: NavHostController,
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
    },
    ownCompanyViewModel: OwnCompanyViewModel = hiltViewModel(),
    subscriptionViewModel: SubscriptionViewModel = hiltViewModel(),
    repo: SupabaseRepository = hiltViewModel<MainActivityViewModel>().repo
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fileImportService = remember { FileImportService(context) }
    
    val processingQueue by fileImportViewModel.processingQueue.collectAsState()
    val currentIndex by fileImportViewModel.currentIndex.collectAsState()
    
    // Get active own company ID from DataStore
    val activeCompanyIdFlow = remember { context.getActiveOwnCompanyIdFlow() }
    val activeCompanyId by activeCompanyIdFlow.collectAsState(initial = null)
    
    // Load active company name
    var activeCompanyName by remember { mutableStateOf<String?>(null) }
    
    // Load own companies to check for auto-selection
    LaunchedEffect(Unit) {
        ownCompanyViewModel.loadOwnCompanies()
    }
    
    val ownCompanies by ownCompanyViewModel.ownCompanies.collectAsState()
    val isLoadingCompanies by ownCompanyViewModel.isLoading.collectAsState()
    
    // Validate activeCompanyId belongs to current user and load company name
    // Also auto-select if there's only one company and none is selected
    LaunchedEffect(activeCompanyId, ownCompanies, isLoadingCompanies) {
        if (activeCompanyId != null) {
            // Try to find company in loaded list first (faster)
            val company = ownCompanies.find { it.id == activeCompanyId }
            if (company != null) {
                activeCompanyName = company.company_name
            } else {
                // Not in loaded list, fetch from database
                val fetchedCompany = repo.getCompanyById(activeCompanyId!!)
                if (fetchedCompany != null) {
                    activeCompanyName = fetchedCompany.company_name
                } else {
                    // Company doesn't belong to current user or doesn't exist, clear it
                    context.setActiveOwnCompanyId(null)
                    activeCompanyName = null
                }
            }
        } else {
            activeCompanyName = null
            // Auto-select if there's only one company and none is selected
            if (ownCompanies.size == 1 && !isLoadingCompanies) {
                val singleCompany = ownCompanies.first()
                scope.launch {
                    context.setActiveOwnCompanyId(singleCompany.id)
                    activeCompanyName = singleCompany.company_name
                }
            }
        }
    }

    // File processing state
    var isProcessingFile by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf<String?>(null) }
    var showProcessingDialog by remember { mutableStateOf(false) }
    var showInvoiceTypeDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    
    // Get subscription status for limit checks and upgrade dialog
    val subscriptionStatus by subscriptionViewModel.subscriptionStatus.collectAsState()
    
    // Folder picker launcher for Import Folder button
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            // Take persistable URI permission in Activity context
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                Timber.w(e, "Could not take persistable URI permission in Activity (may already be granted)")
            }
            
            isProcessingFile = true
            showProcessingDialog = true
            processingMessage = "Scanning folder for invoices..."
            
            scope.launch {
                val result = fileImportService.scanFolderForInvoices(treeUri)
                result.onSuccess { uris ->
                    if (uris.isNotEmpty()) {
                        processingMessage = "Processing ${uris.size} file(s) from folder..."
                        
                        // Process each file sequentially (convert PDFs to images, etc.)
                        // Maintain mapping of original URI to processed URIs to preserve sorting by original filename
                        // Note: uris list is already sorted alphabetically by display name from scanFolderForInvoices
                        // Store original filename with each processed URI for verification
                        val uriToProcessedUris = mutableListOf<Pair<Uri, List<Uri>>>()
                        val processedUriToOriginalInfo = mutableMapOf<Uri, Pair<String, Int>>() // processedUri -> (originalFileName, originalIndex)
                        var processedCount = 0
                        var failedCount = 0
                        
                        // Process files sequentially to maintain order
                        for ((index, uri) in uris.withIndex()) {
                            val originalFileName = fileImportService.getDisplayName(uri) ?: uri.lastPathSegment ?: "unknown"
                            processingMessage = "Processing file ${index + 1} of ${uris.size}: $originalFileName"
                            Timber.d("Starting to process file [$index/${uris.size}]: '$originalFileName'")
                            
                            // Process file directly (it's a suspend function - will wait for completion)
                            val result = fileImportService.processFile(uri)
                            
                            when {
                                result.isSuccess -> {
                                    val processedUris = result.getOrNull() ?: emptyList()
                                    if (processedUris.isNotEmpty()) {
                                        uriToProcessedUris.add(uri to processedUris)
                                        // Store mapping of processed URIs to original file info
                                        processedUris.forEach { processedUri ->
                                            processedUriToOriginalInfo[processedUri] = originalFileName to index
                                        }
                                        processedCount++
                                        Timber.d("✓ Successfully processed file [$index]: '$originalFileName' -> ${processedUris.size} image(s)")
                                    } else {
                                        failedCount++
                                        Timber.w("✗ File [$index] produced no images: '$originalFileName'")
                                    }
                                }
                                result.isFailure -> {
                                    failedCount++
                                    val error = result.exceptionOrNull()
                                    Timber.e(error, "✗ Failed to process file [$index]: '$originalFileName'")
                                    // Show error in snackbar
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Failed to process file: ${error?.message ?: "Unknown error"}")
                                    }
                                }
                            }
                        }
                        
                        Timber.d("Finished processing all files. Total: ${uriToProcessedUris.size} successful, $failedCount failed")
                        
                        isProcessingFile = false
                        if (uriToProcessedUris.isNotEmpty()) {
                            // Log ALL original files with their display names BEFORE building the final list
                            Timber.d("=== ORIGINAL FILES SORTING VERIFICATION ===")
                            uriToProcessedUris.forEachIndexed { idx, (originalUri, processedUris) ->
                                val originalName = fileImportService.getDisplayName(originalUri) ?: originalUri.lastPathSegment ?: "unknown"
                                val lowerName = originalName.lowercase()
                                Timber.d("  [$idx] Original file: '$originalName' (lowercase: '$lowerName') -> ${processedUris.size} page(s)")
                            }
                            
                            // Build final list maintaining the original sorted order
                            val allProcessedUris = uriToProcessedUris.flatMap { (originalUri, processedUris) ->
                                processedUris
                            }
                            
                            Timber.d("=== FINAL QUEUE VERIFICATION ===")
                            Timber.d("Total processed URIs: ${allProcessedUris.size}")
                            Timber.d("Mapping original files to processed URIs (first 10):")
                            uriToProcessedUris.take(10).forEachIndexed { idx, (originalUri, processedUris) ->
                                val originalName = fileImportService.getDisplayName(originalUri) ?: originalUri.lastPathSegment ?: "unknown"
                                Timber.d("  [$idx] Original: '$originalName' -> ${processedUris.size} page(s)")
                                processedUris.take(3).forEachIndexed { pageIdx, processedUri ->
                                    val processedName = processedUri.lastPathSegment ?: "unknown"
                                    Timber.d("      Page ${pageIdx + 1}: $processedName")
                                }
                            }
                            
                            Timber.d("Final queue order with original file names (first 10 processed URIs):")
                            allProcessedUris.take(10).forEachIndexed { idx, uri ->
                                val originalInfo = processedUriToOriginalInfo[uri]
                                val originalName = originalInfo?.first ?: "UNKNOWN_ORIGINAL"
                                val originalIdx = originalInfo?.second ?: -1
                                val processedName = uri.lastPathSegment ?: "unknown"
                                Timber.d("  [$idx] Processed URI: $processedName -> FROM original file [$originalIdx]: '$originalName'")
                            }
                            
                            // Verify first file
                            if (uriToProcessedUris.isNotEmpty()) {
                                val (firstOriginalUri, firstProcessedUris) = uriToProcessedUris[0]
                                val firstOriginalName = fileImportService.getDisplayName(firstOriginalUri) ?: firstOriginalUri.lastPathSegment ?: "unknown"
                                val firstProcessedUri = allProcessedUris[0]
                                val firstProcessedName = firstProcessedUri.lastPathSegment ?: "unknown"
                                val firstProcessedOriginalInfo = processedUriToOriginalInfo[firstProcessedUri]
                                Timber.d("=== CRITICAL VERIFICATION ===")
                                Timber.d("First original file [0]: '$firstOriginalName'")
                                Timber.d("First processed URI [0]: $firstProcessedName")
                                Timber.d("First processed URI's original file: '${firstProcessedOriginalInfo?.first ?: "UNKNOWN"}' [index ${firstProcessedOriginalInfo?.second ?: -1}]")
                                Timber.d("First processed URI should come from: ${firstProcessedUris.firstOrNull()?.lastPathSegment}")
                                if (firstProcessedUris.firstOrNull() != firstProcessedUri) {
                                    Timber.e("ERROR: First processed URI does not match first original file's first page!")
                                } else if (firstProcessedOriginalInfo?.first != firstOriginalName || firstProcessedOriginalInfo?.second != 0) {
                                    Timber.e("ERROR: First processed URI mapping is incorrect! Expected: '$firstOriginalName' [0], Got: '${firstProcessedOriginalInfo?.first}' [${firstProcessedOriginalInfo?.second}]")
                                } else {
                                    Timber.d("✓ VERIFICATION PASSED: First processed URI matches first original file's first page")
                                }
                            }
                            
                            fileImportViewModel.addToQueue(allProcessedUris)
                            
                            // Use getNextUri() to ensure consistency with the queue system
                            val firstUri = fileImportViewModel.getNextUri()
                            if (firstUri != null) {
                                val firstUriName = firstUri.lastPathSegment ?: "unknown"
                                val firstUriOriginalInfo = processedUriToOriginalInfo[firstUri]
                                val firstUriOriginalName = firstUriOriginalInfo?.first ?: "UNKNOWN_ORIGINAL"
                                Timber.d("=== NAVIGATION VERIFICATION ===")
                                Timber.d("Queue set. getNextUri() returns: $firstUriName")
                                Timber.d("This URI comes from original file: '$firstUriOriginalName' [index ${firstUriOriginalInfo?.second ?: -1}]")
                                processingMessage = "Found ${allProcessedUris.size} invoice(s) from ${processedCount} file(s). Opening first invoice..."
                                // Small delay to show the message, then navigate
                                scope.launch {
                                    kotlinx.coroutines.delay(300)
                                    showProcessingDialog = false
                                    // Navigate to first item (invoice type is already set in ViewModel)
                                    Timber.d("Navigating to first invoice: $firstUriName (from original: '$firstUriOriginalName')")
                                    navController.navigate("review/${Uri.encode(firstUri.toString())}")
                                }
                            } else {
                                Timber.e("ERROR: getNextUri() returned null after adding ${allProcessedUris.size} items to queue!")
                                showProcessingDialog = false
                            }
                        } else {
                            processingMessage = if (failedCount > 0) {
                                "No invoices found in selected folder"
                            } else {
                                "No invoices found in folder"
                            }
                        }
                    } else {
                        isProcessingFile = false
                        processingMessage = "No invoice files found in selected folder"
                    }
                }.onFailure { error ->
                    isProcessingFile = false
                    processingMessage = "Failed to scan folder: ${error.message}"
                }
            }
        }
    }
    
    // File picker launcher for Import button - supports multiple file selection
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
                // Log files BEFORE sorting
                Timber.d("=== MULTIPLE FILE SELECTION: FILES BEFORE SORTING ===")
                uris.take(10).forEachIndexed { idx, uri ->
                    val name = fileImportService.getDisplayName(uri) ?: uri.lastPathSegment ?: "unknown"
                    val lowerName = name.lowercase()
                    Timber.d("  [$idx] '$name' (lowercase: '$lowerName')")
                }
                
                // Sort files first by display name (matches file explorer order)
                val sortedUris = uris.sortedBy { originalUri ->
                    fileImportService.getDisplayName(originalUri)?.lowercase() ?: originalUri.lastPathSegment?.lowercase() ?: ""
                }
                
                // Log files AFTER sorting
                Timber.d("=== MULTIPLE FILE SELECTION: FILES AFTER SORTING ===")
                sortedUris.take(10).forEachIndexed { idx, uri ->
                    val name = fileImportService.getDisplayName(uri) ?: uri.lastPathSegment ?: "unknown"
                    val lowerName = name.lowercase()
                    Timber.d("  [$idx] '$name' (lowercase: '$lowerName')")
                }
                
                // Process each file sequentially to maintain sorted order
                // Store original filename with each processed URI for verification
                val uriToProcessedUris = mutableListOf<Pair<Uri, List<Uri>>>()
                val processedUriToOriginalInfo = mutableMapOf<Uri, Pair<String, Int>>() // processedUri -> (originalFileName, originalIndex)
                var processedCount = 0
                var failedCount = 0
                
                for ((index, uri) in sortedUris.withIndex()) {
                    val originalFileName = fileImportService.getDisplayName(uri) ?: uri.lastPathSegment ?: "unknown"
                    processingMessage = "Processing file ${index + 1} of ${sortedUris.size}: $originalFileName"
                    Timber.d("Starting to process file [$index/${sortedUris.size}]: '$originalFileName'")
                    
                    // Process file directly (it's a suspend function)
                    val result = fileImportService.processFile(uri)
                    result.onSuccess { processedUris ->
                        if (processedUris.isNotEmpty()) {
                            uriToProcessedUris.add(uri to processedUris)
                            // Store mapping of processed URIs to original file info
                            processedUris.forEach { processedUri ->
                                processedUriToOriginalInfo[processedUri] = originalFileName to index
                            }
                            processedCount++
                            Timber.d("✓ Successfully processed file [$index]: '$originalFileName' -> ${processedUris.size} image(s)")
                        } else {
                            failedCount++
                            Timber.w("✗ File [$index] produced no images: '$originalFileName'")
                        }
                    }.onFailure { error ->
                        failedCount++
                        Timber.e(error, "✗ Failed to process file [$index]: '$originalFileName'")
                        // Show error in snackbar
                        scope.launch {
                            snackbarHostState.showSnackbar("Failed to process file: ${error.message ?: "Unknown error"}")
                        }
                    }
                }
                
                isProcessingFile = false
                if (uriToProcessedUris.isNotEmpty()) {
                    // Log ALL original files with their display names BEFORE building the final list
                    Timber.d("=== ORIGINAL FILES SORTING VERIFICATION (Multiple File Selection) ===")
                    uriToProcessedUris.forEachIndexed { idx, (originalUri, processedUris) ->
                        val originalName = fileImportService.getDisplayName(originalUri) ?: originalUri.lastPathSegment ?: "unknown"
                        val lowerName = originalName.lowercase()
                        Timber.d("  [$idx] Original file: '$originalName' (lowercase: '$lowerName') -> ${processedUris.size} page(s)")
                    }
                    
                    // Build final list maintaining the sorted order
                    val allProcessedUris = uriToProcessedUris.flatMap { (originalUri, processedUris) ->
                        processedUris
                    }
                    
                    Timber.d("Final queue order with original file names (first 10 processed URIs):")
                    allProcessedUris.take(10).forEachIndexed { idx, uri ->
                        val originalInfo = processedUriToOriginalInfo[uri]
                        val originalName = originalInfo?.first ?: "UNKNOWN_ORIGINAL"
                        val originalIdx = originalInfo?.second ?: -1
                        val processedName = uri.lastPathSegment ?: "unknown"
                        Timber.d("  [$idx] Processed URI: $processedName -> FROM original file [$originalIdx]: '$originalName'")
                    }
                    
                    // Verify first file
                    if (uriToProcessedUris.isNotEmpty()) {
                        val (firstOriginalUri, firstProcessedUris) = uriToProcessedUris[0]
                        val firstOriginalName = fileImportService.getDisplayName(firstOriginalUri) ?: firstOriginalUri.lastPathSegment ?: "unknown"
                        val firstProcessedUri = allProcessedUris[0]
                        val firstProcessedName = firstProcessedUri.lastPathSegment ?: "unknown"
                        val firstProcessedOriginalInfo = processedUriToOriginalInfo[firstProcessedUri]
                        Timber.d("=== CRITICAL VERIFICATION (Multiple File Selection) ===")
                        Timber.d("First original file [0]: '$firstOriginalName'")
                        Timber.d("First processed URI [0]: $firstProcessedName")
                        Timber.d("First processed URI's original file: '${firstProcessedOriginalInfo?.first ?: "UNKNOWN"}' [index ${firstProcessedOriginalInfo?.second ?: -1}]")
                        if (firstProcessedUris.firstOrNull() != firstProcessedUri) {
                            Timber.e("ERROR: First processed URI does not match first original file's first page!")
                        } else if (firstProcessedOriginalInfo?.first != firstOriginalName || firstProcessedOriginalInfo?.second != 0) {
                            Timber.e("ERROR: First processed URI mapping is incorrect! Expected: '$firstOriginalName' [0], Got: '${firstProcessedOriginalInfo?.first}' [${firstProcessedOriginalInfo?.second}]")
                        } else {
                            Timber.d("✓ VERIFICATION PASSED: First processed URI matches first original file's first page")
                        }
                    }
                    
                    fileImportViewModel.addToQueue(allProcessedUris)
                    
                    // Use getNextUri() to ensure consistency with the queue system
                    val firstUri = fileImportViewModel.getNextUri()
                    if (firstUri != null) {
                        val firstUriName = firstUri.lastPathSegment ?: "unknown"
                        val firstUriOriginalInfo = processedUriToOriginalInfo[firstUri]
                        val firstUriOriginalName = firstUriOriginalInfo?.first ?: "UNKNOWN_ORIGINAL"
                        Timber.d("=== NAVIGATION VERIFICATION (Multiple File Selection) ===")
                        Timber.d("Queue set. getNextUri() returns: $firstUriName")
                        Timber.d("This URI comes from original file: '$firstUriOriginalName' [index ${firstUriOriginalInfo?.second ?: -1}]")
                        processingMessage = "Found ${allProcessedUris.size} invoice(s) from ${processedCount} file(s). Opening first invoice..."
                        // Small delay to show the message, then navigate
                        scope.launch {
                            kotlinx.coroutines.delay(300)
                            showProcessingDialog = false
                            // Navigate to first item (invoice type is already set in ViewModel)
                            Timber.d("Navigating to first invoice: $firstUriName (from original: '$firstUriOriginalName')")
                            navController.navigate("review/${Uri.encode(firstUri.toString())}")
                        }
                    } else {
                        Timber.e("ERROR: getNextUri() returned null after adding ${allProcessedUris.size} items to queue!")
                        showProcessingDialog = false
                    }
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Subscription usage indicator at the top
            UsageIndicator(
                subscriptionStatus = subscriptionStatus,
                modifier = Modifier.padding(horizontal = 24.dp),
                onUpgradeClick = {
                    navController.navigate(Routes.Subscription)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Your Company section below subscription
            OwnCompanySelector(
                activeCompanyId = activeCompanyId,
                activeCompanyName = activeCompanyName,
                onCompanySelected = { newCompanyId ->
                    scope.launch {
                        if (newCompanyId != null) {
                            // Try to find company in loaded list first (faster)
                            val company = ownCompanies.find { it.id == newCompanyId }
                            if (company != null) {
                                activeCompanyName = company.company_name
                            } else {
                                // Not in loaded list, fetch from database
                                val fetchedCompany = repo.getCompanyById(newCompanyId)
                                activeCompanyName = fetchedCompany?.company_name
                            }
                        } else {
                            activeCompanyName = null
                        }
                    }
                },
                onShowSnackbar = { message ->
                    scope.launch {
                        snackbarHostState.showSnackbar(message)
                    }
                },
                navController = navController,
                viewModel = ownCompanyViewModel
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Rest of the buttons (centered)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // All buttons in one column with consistent spacing
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Import File button
                    Button(
                        onClick = {
                            // Check subscription limit before showing invoice type dialog
                            // For file import, check if user can scan at least 1 page
                            if (!subscriptionViewModel.canScanPages(1)) {
                                showUpgradeDialog = true
                            } else {
                                // Launch with "*/*" to allow all file types (PDFs and images)
                                // The custom contract ensures multiple selection is enabled
                                pendingAction = { filePickerLauncher.launch("*/*") }
                                showInvoiceTypeDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Import from files",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Import File")
                    }
                    
                    // Import Folder button
                    Button(
                        onClick = {
                            // Check subscription limit before showing invoice type dialog
                            // For folder import, check if user can scan at least 1 page
                            if (!subscriptionViewModel.canScanPages(1)) {
                                showUpgradeDialog = true
                            } else {
                                pendingAction = { 
                                    folderPickerLauncher.launch(null)
                                }
                                showInvoiceTypeDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Import from folder",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Import Folder")
                    }
                    
                    // Scan with Camera button
                    Button(
                        onClick = {
                            // Check subscription limit before showing invoice type dialog
                            // Camera scan is always 1 page
                            if (!subscriptionViewModel.canScanPages(1)) {
                                showUpgradeDialog = true
                            } else {
                                pendingAction = { navController.navigate(Routes.Scan) }
                                showInvoiceTypeDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Camera,
                            contentDescription = "Capture photo",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Scan with Camera")
                    }
                    
                    // Companies button
                    Button(
                        onClick = { navController.navigate(Routes.Companies) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Business,
                            contentDescription = "Companies",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = "Companies")
                    }
                    
                    // Exports button
                    Button(
                        onClick = { navController.navigate(Routes.Exports) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Exports",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = "Exports")
                    }
                    
                    // Settings button
                    Button(
                        onClick = { navController.navigate(Routes.Settings) },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = "Settings")
                    }
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        
        // Version number at the bottom
        Text(
            text = "v${com.vitol.inv3.BuildConfig.VERSION_NAME}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp), // Add more padding to avoid navigation bar
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        // Invoice Type Selection Dialog - Large and Visible
        if (showInvoiceTypeDialog) {
            AlertDialog(
                onDismissRequest = {
                    showInvoiceTypeDialog = false
                    pendingAction = null
                },
                title = {
                    Text(
                        text = "Select Invoice Type",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Please select whether this is a Purchase (Received) or Sales (Issued) invoice.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Large Purchase button
                        Button(
                            onClick = {
                                fileImportViewModel.setInvoiceType("P")
                                showInvoiceTypeDialog = false
                                pendingAction?.invoke()
                                pendingAction = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp), // Make button taller
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = "Purchase Invoice",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        
                        // Large Sales button
                        Button(
                            onClick = {
                                fileImportViewModel.setInvoiceType("S")
                                showInvoiceTypeDialog = false
                                pendingAction?.invoke()
                                pendingAction = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp), // Make button taller
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(
                                text = "Sales Invoice",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showInvoiceTypeDialog = false
                            pendingAction = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        // Processing dialog
        if (showProcessingDialog) {
            AlertDialog(
                onDismissRequest = { /* Don't allow dismissing during processing */ },
                title = { Text("Processing File") },
                text = {
                    if (isProcessingFile) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(processingMessage ?: "Processing file...")
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
        
        // Upgrade dialog - shown when user tries to import/scan but has reached limit
        if (showUpgradeDialog) {
            UpgradeDialog(
                subscriptionStatus = subscriptionStatus,
                onDismiss = {
                    showUpgradeDialog = false
                },
                onUpgradeClick = { plan ->
                    showUpgradeDialog = false
                    navController.navigate(Routes.Subscription)
                }
            )
        }
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(text = "Coming soon", modifier = Modifier.padding(top = 8.dp))
    }
}


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
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
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
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.data.local.getActiveOwnCompanyIdFlow
import com.vitol.inv3.data.local.setActiveOwnCompanyId
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ui.auth.AuthViewModel
import com.vitol.inv3.ui.auth.LoginScreen
import com.vitol.inv3.ui.home.OwnCompanySelector
import com.vitol.inv3.ui.home.OwnCompanyViewModel
import com.vitol.inv3.ui.scan.FileImportViewModel
import com.vitol.inv3.ui.scan.processSelectedFile
import com.vitol.inv3.utils.FileImportService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Handle deep links for email confirmation
        handleDeepLink(intent)
        
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val isAuthenticated by authManager.isAuthenticated.collectAsState(initial = false)
                
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (isAuthenticated) {
                        AppNavHost(navController)
                    } else {
                        LoginScreen(
                            onLoginSuccess = {
                                // Navigation will happen automatically via state change
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: android.content.Intent) {
        val data = intent.data
        if (data != null) {
            // Handle Supabase email confirmation links
            // Format: https://azbyzwdthelztfuybxmg.supabase.co/auth/v1/verify?token=...
            if (data.host?.contains("supabase.co") == true) {
                timber.log.Timber.d("Received Supabase deep link: $data")
                // The Supabase client should handle the verification automatically
                // If there's a token in the URL, we can extract it and verify
                val token = data.getQueryParameter("token")
                val type = data.getQueryParameter("type")
                if (token != null && type == "signup") {
                    // Email confirmation - Supabase will handle this via the auth flow
                    timber.log.Timber.d("Email confirmation token received")
                }
            } else if (data.scheme == "com.vitol.inv3" && data.host == "auth") {
                // Custom scheme deep link
                timber.log.Timber.d("Received custom auth deep link: $data")
            }
        }
    }
}

object Routes {
    const val Home = "home"
    const val Scan = "scan"
    const val Review = "review"
    const val Companies = "companies"
    const val AddOwnCompany = "addOwnCompany"
    const val Exports = "exports"
    const val EditInvoice = "editInvoice"
    const val EditCompany = "editCompany"
}

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.Home) {
        composable(Routes.Home) { HomeScreen(navController) }
        composable(Routes.Scan) { com.vitol.inv3.ui.scan.ScanScreen(navController = navController) }
        composable("review/{uri}") { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uri")
            val uri = if (uriString.isNullOrBlank()) null else {
                try {
                    android.net.Uri.parse(java.net.URLDecoder.decode(uriString, "UTF-8"))
                } catch (e: Exception) {
                    null
                }
            }
            if (uri != null) {
                com.vitol.inv3.ui.review.ReviewScreen(imageUri = uri, navController = navController)
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
    repo: SupabaseRepository = hiltViewModel<MainActivityViewModel>().repo,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
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
    
    LaunchedEffect(activeCompanyId) {
        if (activeCompanyId != null) {
            val company = repo.getCompanyById(activeCompanyId!!)
            activeCompanyName = company?.company_name
        } else {
            activeCompanyName = null
        }
    }

    // File processing state
    var isProcessingFile by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf<String?>(null) }
    var showProcessingDialog by remember { mutableStateOf(false) }
    var showInvoiceTypeDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    // File picker launcher for Import button
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessingFile = true
            showProcessingDialog = true
            processingMessage = "Processing file..."
            
            scope.launch {
                processSelectedFile(uri, fileImportService, snackbarHostState) { uris ->
                    isProcessingFile = false
                    if (uris.isNotEmpty()) {
                        fileImportViewModel.addToQueue(uris)
                        processingMessage = "Found ${uris.size} invoice(s). Opening first invoice..."
                        // Small delay to show the message, then navigate
                        scope.launch {
                            kotlinx.coroutines.delay(300)
                            showProcessingDialog = false
                            // Navigate to first item (invoice type is already set in ViewModel)
                            val firstUri = uris[0]
                            navController.navigate("review/${Uri.encode(firstUri.toString())}")
                        }
                    } else {
                        processingMessage = "No invoices found in file"
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
            // Logout button at the top right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = { authViewModel.signOut() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = "Sign out"
                    )
                }
            }
            
            // Your Company section at the top
            OwnCompanySelector(
                activeCompanyId = activeCompanyId,
                activeCompanyName = activeCompanyName,
                onCompanySelected = { newCompanyId ->
                    scope.launch {
                        if (newCompanyId != null) {
                            val company = repo.getCompanyById(newCompanyId)
                            activeCompanyName = company?.company_name
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
                // Import File and Scan with Camera buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Import File button
                    Button(
                        onClick = {
                            pendingAction = { filePickerLauncher.launch("*/*") }
                            showInvoiceTypeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Import from files",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Import File")
                    }
                    
                    // Scan with Camera button
                    Button(
                        onClick = {
                            pendingAction = { navController.navigate(Routes.Scan) }
                            showInvoiceTypeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Camera,
                            contentDescription = "Capture photo",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Scan with Camera")
                    }
                }
            
                Spacer(modifier = Modifier.padding(16.dp))
                
                // Other buttons
                Button(
                    onClick = { navController.navigate(Routes.Companies) },
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Text(text = "Companies")
                }
                Button(
                    onClick = { navController.navigate(Routes.Exports) },
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Text(text = "Exports")
                }
            }
        }
        
        // Delete Account button at the bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextButton(
                onClick = { showDeleteAccountDialog = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete account",
                    modifier = Modifier
                        .width(18.dp)
                        .height(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Delete My Account",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        // Delete Account Confirmation Dialog
        if (showDeleteAccountDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAccountDialog = false },
                title = { Text("Delete Account") },
                text = {
                    Text("Are you sure you want to delete your account? This action cannot be undone and all your data will be permanently deleted.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteAccountDialog = false
                            scope.launch {
                                authViewModel.deleteAccount()
                                snackbarHostState.showSnackbar("Account deleted successfully")
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAccountDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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


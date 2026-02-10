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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Upload
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
import com.vitol.inv3.ui.subscription.UsageIndicator
import com.vitol.inv3.ui.subscription.SubscriptionViewModel
import com.vitol.inv3.ui.subscription.SubscriptionScreen
import com.vitol.inv3.ui.subscription.UpgradeDialog
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.auth.AuthState
import com.vitol.inv3.ui.auth.LoginScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.net.URLDecoder
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
    const val Companies = "companies"
    const val AddOwnCompany = "addOwnCompany"
    const val Exports = "exports"
    const val EditInvoice = "editInvoice"
    const val EditCompany = "editCompany"
    const val Settings = "settings"
    const val Subscription = "subscription"
    const val SelectInvoiceType = "selectInvoiceType"
    const val SelectImportType = "selectImportType"
    const val ScanCamera = "scanCamera/{invoiceType}"
    const val ReviewScan = "reviewScan/{imageUri}/{invoiceType}"
    const val ReviewScanEdit = "reviewScanEdit/{invoiceId}/{invoiceType}"
    const val ReviewScanImport = "reviewScanImport/{invoiceType}"
    const val ImportFiles = "importFiles/{invoiceType}"
    const val ImportPrepare = "importPrepare"
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
                // Load invoice to get invoice type, then navigate to ReviewScanScreen
                val viewModel: com.vitol.inv3.MainActivityViewModel = hiltViewModel()
                androidx.compose.runtime.LaunchedEffect(invoiceId) {
                    val invoice = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            viewModel.repo.getAllInvoices().find { it.id == invoiceId }
                        } catch (e: Exception) {
                            timber.log.Timber.e(e, "Failed to load invoice")
                            null
                        }
                    }
                    
                    // Navigation must be on main thread
                    if (invoice != null) {
                        val invoiceType = invoice.invoice_type ?: "P"
                        // Navigate to ReviewScanScreen with invoiceId
                        navController.navigate("${Routes.ReviewScanEdit}/$invoiceId/$invoiceType") {
                            popUpTo("${Routes.EditInvoice}/$invoiceId") { inclusive = true }
                        }
                    } else {
                        // Invoice not found, show error
                        navController.navigate(Routes.Exports) {
                            popUpTo(0) { inclusive = false }
                        }
                    }
                }
                // Show loading while fetching invoice
                androidx.compose.material3.CircularProgressIndicator()
            } else {
                PlaceholderScreen("Invalid invoice ID")
            }
        }
        composable("${Routes.ReviewScanEdit}/{invoiceId}/{invoiceType}") { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getString("invoiceId") ?: ""
            val invoiceType = backStackEntry.arguments?.getString("invoiceType") ?: "P"
            if (invoiceId.isNotBlank()) {
                com.vitol.inv3.ui.scan.ReviewScanScreen(
                    imageUri = null,
                    navController = navController,
                    invoiceType = invoiceType,
                    invoiceId = invoiceId
                )
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
        composable(Routes.SelectInvoiceType) {
            com.vitol.inv3.ui.scan.SelectInvoiceTypeScreen(navController = navController)
        }
        composable(Routes.SelectImportType) {
            com.vitol.inv3.ui.scan.SelectImportTypeScreen(navController = navController)
        }
        composable("${Routes.ScanCamera}/{invoiceType}") { backStackEntry ->
            val invoiceType = backStackEntry.arguments?.getString("invoiceType") ?: "P"
            com.vitol.inv3.ui.scan.CameraScreen(
                navController = navController,
                invoiceType = invoiceType
            )
        }
        composable("${Routes.ReviewScan}/{imageUri}/{invoiceType}") { backStackEntry ->
            val imageUriString = backStackEntry.arguments?.getString("imageUri") ?: ""
            val invoiceType = backStackEntry.arguments?.getString("invoiceType") ?: "P"
            if (imageUriString.isNotBlank()) {
                // URL-decode the URI string from navigation
                val decodedUriString = java.net.URLDecoder.decode(imageUriString, "UTF-8")
                val imageUri = Uri.parse(decodedUriString)
                com.vitol.inv3.ui.scan.ReviewScanScreen(
                    imageUri = imageUri,
                    navController = navController,
                    invoiceType = invoiceType,
                    fromImport = false
                )
            } else {
                PlaceholderScreen("Invalid image URI")
            }
        }
        composable("${Routes.ReviewScanImport}/{invoiceType}") { backStackEntry ->
            val invoiceTypeEnc = backStackEntry.arguments?.getString("invoiceType") ?: ""
            val invoiceType = if (invoiceTypeEnc.isNotBlank()) java.net.URLDecoder.decode(invoiceTypeEnc, "UTF-8") else "P"
            com.vitol.inv3.ui.scan.ReviewScanScreen(
                imageUri = null,
                navController = navController,
                invoiceType = invoiceType,
                fromImport = true
            )
        }
        composable(Routes.ImportPrepare) {
            com.vitol.inv3.ui.scan.ImportPrepareScreen(navController = navController)
        }
    }
}

@Composable
fun HomeScreen(
    navController: NavHostController,
    ownCompanyViewModel: OwnCompanyViewModel = hiltViewModel(),
    subscriptionViewModel: SubscriptionViewModel = hiltViewModel(),
    repo: SupabaseRepository = hiltViewModel<MainActivityViewModel>().repo
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
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

    var showUpgradeDialog by remember { mutableStateOf(false) }
    
    // Get subscription status for limit checks and upgrade dialog
    val subscriptionStatus by subscriptionViewModel.subscriptionStatus.collectAsState()

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
                    // Scan with Camera button
                    Button(
                        onClick = { 
                            if (subscriptionStatus?.canScan == true) {
                                navController.navigate(Routes.SelectInvoiceType)
                            } else {
                                showUpgradeDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Scan with Camera",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = "Scan with Camera")
                    }
                    
                    // Import files button
                    Button(
                        onClick = { 
                            if (subscriptionStatus?.canScan == true) {
                                navController.navigate(Routes.SelectImportType)
                            } else {
                                showUpgradeDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = "Import files",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = "Import files")
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
        
        
        // Upgrade dialog - shown when user has reached limit
        if (showUpgradeDialog) {
            UpgradeDialog(
                subscriptionStatus = subscriptionStatus,
                onDismiss = {
                    showUpgradeDialog = false
                },
                onUpgradeClick = { _ ->
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


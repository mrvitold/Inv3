package com.vitol.inv3

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vitol.inv3.analytics.AppAnalytics
import com.vitol.inv3.data.local.applyStoredAppLocales
import com.vitol.inv3.data.local.consumePendingFeedbackAfterImport
import com.vitol.inv3.data.local.getActiveOwnCompanyIdFlow
import com.vitol.inv3.data.local.recordFeedbackPromptOffered
import com.vitol.inv3.data.local.shouldOfferFeedbackPrompt
import com.vitol.inv3.data.local.getCompanySetupHomePromptAckUserId
import com.vitol.inv3.data.local.setActiveOwnCompanyId
import com.vitol.inv3.data.local.setCompanySetupHomePromptAckUserId
import com.vitol.inv3.ui.home.OwnCompanySelector
import com.vitol.inv3.ui.home.OwnCompanyViewModel
import com.vitol.inv3.ui.subscription.UsageIndicator
import com.vitol.inv3.ui.subscription.SubscriptionViewModel
import com.vitol.inv3.ui.subscription.SubscriptionScreen
import com.vitol.inv3.ui.subscription.UpgradeDialog
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.auth.AuthState
import com.vitol.inv3.ui.auth.LoginScreen
import com.vitol.inv3.utils.openFeedbackEmail
import com.vitol.inv3.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.net.URLDecoder
import javax.inject.Inject

private const val NAV_STATE_KEY = "nav_state"

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainActivityViewModel
    private var navControllerRef: NavHostController? = null
    @Inject lateinit var appAnalytics: AppAnalytics
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Per-app locales from AppCompatDelegate apply to AppCompatActivity's resources; call before super.
        applyStoredAppLocales(applicationContext)
        super.onCreate(savedInstanceState)
        
        // Initialize ViewModel for lifecycle-aware access in onResume
        viewModel = androidx.lifecycle.ViewModelProvider(this)[MainActivityViewModel::class.java]
        
        val restoredNavState = savedInstanceState?.getBundle(NAV_STATE_KEY)
        
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                navControllerRef = navController
                // Restore navigation state once before NavHost sets graph - required when activity
                // is recreated (e.g. after file picker opens and system destroys activity)
                if (restoredNavState != null) {
                    remember(restoredNavState) {
                        navController.restoreState(restoredNavState)
                        Unit
                    }
                }
                val viewModel: MainActivityViewModel = hiltViewModel()
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val activity = context as? ComponentActivity

                // Handle new intents (e.g. when user taps app icon to return) - avoids setContent in
                // onNewIntent which would replace UI with empty content and cause black screen
                activity?.let { act ->
                    DisposableEffect(act) {
                        val listener: (android.content.Intent) -> Unit = { intent ->
                            scope.launch {
                                handleIntent(intent, viewModel.authManager)
                            }
                        }
                        act.addOnNewIntentListener(listener)
                        onDispose { act.removeOnNewIntentListener(listener) }
                    }
                }

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

                // Note: key(resumeKey) was removed - it recreated NavHost on every resume (e.g. when
                // returning from file picker), which reset the back stack and sent users to Home.
                // Nav state save/restore handles activity recreation. If black screen returns on
                // some devices, we need a fix that doesn't recreate the NavHost.
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        navController = navController,
                        authManager = viewModel.authManager,
                        appAnalytics = appAnalytics
                    )
                }
            }
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navControllerRef?.saveState()?.let { navState ->
            outState.putBundle(NAV_STATE_KEY, navState)
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
        // Do NOT call setContent here - it would replace the entire UI with empty content
        // (the old code only had LaunchedEffect, which renders nothing) causing a black screen.
        // Intent handling is done via addOnNewIntentListener in the composable below.
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
    const val Guide = "guide"
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
    authManager: AuthManager,
    appAnalytics: AppAnalytics
) {
    val authState by authManager.authState.collectAsState(initial = AuthState.Loading)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(currentRoute) {
        appAnalytics.trackScreenViewed(currentRoute)
    }
    
    // Navigate based on auth state (single source of truth - prevents duplicate navigation)
    LaunchedEffect(authState) {
        val destinationRoute = navController.currentDestination?.route
        when (authState) {
            is AuthState.Unauthenticated -> {
                if (destinationRoute != Routes.Login) {
                    navController.navigate(Routes.Login) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            is AuthState.Authenticated -> {
                if (destinationRoute == Routes.Login) {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                }
            }
            else -> { /* Loading - no navigation */ }
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
        composable(Routes.Home) { HomeScreen(navController, appAnalytics = appAnalytics) }
        composable(Routes.Guide) { com.vitol.inv3.ui.guide.GuideScreen(navController = navController) }
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
                },
                onCompanyLimitReached = {
                    navController.navigate(Routes.Subscription) {
                        popUpTo(Routes.AddOwnCompany) { inclusive = true }
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
                PlaceholderScreen(stringResource(R.string.placeholder_invalid_invoice))
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
                PlaceholderScreen(stringResource(R.string.placeholder_invalid_invoice))
            }
        }
        composable("${Routes.EditCompany}/{companyId}") { backStackEntry ->
            val companyId = backStackEntry.arguments?.getString("companyId") ?: ""
            if (companyId.isNotBlank()) {
                com.vitol.inv3.ui.companies.EditCompanyScreen(companyId = companyId, navController = navController)
            } else {
                PlaceholderScreen(stringResource(R.string.placeholder_invalid_company))
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
                PlaceholderScreen(stringResource(R.string.placeholder_invalid_uri))
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
    appAnalytics: AppAnalytics,
    ownCompanyViewModel: OwnCompanyViewModel = hiltViewModel(),
    subscriptionViewModel: SubscriptionViewModel = hiltViewModel(),
    mainActivityViewModel: MainActivityViewModel = hiltViewModel(),
) {
    val repo = mainActivityViewModel.repo
    val authManager = mainActivityViewModel.authManager
    val context = LocalContext.current
    val fillCompanyFirst = stringResource(R.string.toast_fill_company_first)
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
    val currentUserId by authManager.currentUserId.collectAsState(initial = null)
    var showCompanySetupDialog by remember { mutableStateOf(false) }
    /** Sync guard so LaunchedEffect does not reopen the dialog before DataStore ack finishes writing. */
    var companySetupPromptDismissed by remember(currentUserId) { mutableStateOf(false) }
    var addOwnCompanyDialogTrigger by remember { mutableStateOf(0) }
    
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

    LaunchedEffect(Unit) {
        if (!consumePendingFeedbackAfterImport(context)) return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = context.getString(R.string.all_invoices_saved),
            duration = SnackbarDuration.Short
        )
        if (!shouldOfferFeedbackPrompt(context)) return@LaunchedEffect
        recordFeedbackPromptOffered(context)
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.feedback_snackbar_message),
            actionLabel = context.getString(R.string.feedback_snackbar_action),
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) openFeedbackEmail(context)
    }
    
    // Get subscription status for limit checks and upgrade dialog
    val subscriptionStatus by subscriptionViewModel.subscriptionStatus.collectAsState()

    /** True if user has an active own company with company_number and company_name filled. */
    fun isOwnCompanyFilled(): Boolean {
        val id = activeCompanyId ?: return false
        val company = ownCompanies.find { it.id == id } ?: return false
        return !company.company_number.isNullOrBlank() && !company.company_name.isNullOrBlank()
    }

    LaunchedEffect(currentUserId, isLoadingCompanies, ownCompanies, activeCompanyId) {
        val uid = currentUserId ?: return@LaunchedEffect
        if (companySetupPromptDismissed) return@LaunchedEffect
        if (isLoadingCompanies) return@LaunchedEffect
        if (ownCompanies.size == 1 && activeCompanyId == null) return@LaunchedEffect
        if (context.getCompanySetupHomePromptAckUserId() == uid) {
            showCompanySetupDialog = false
            return@LaunchedEffect
        }
        if (isOwnCompanyFilled()) {
            context.setCompanySetupHomePromptAckUserId(uid)
            showCompanySetupDialog = false
            return@LaunchedEffect
        }
        showCompanySetupDialog = true
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
                    appAnalytics.trackPaywallViewed(source = "usage_indicator")
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
                addDialogTrigger = addOwnCompanyDialogTrigger,
                navController = navController,
                viewModel = ownCompanyViewModel
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Rest of the buttons (slightly above vertical center)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .offset(y = (-40).dp),
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
                            if (!isOwnCompanyFilled()) {
                                appAnalytics.trackHomeAction(
                                    action = "scan_camera",
                                    allowed = false,
                                    failureReason = "own_company_missing"
                                )
                                Toast.makeText(context, fillCompanyFirst, Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            if (subscriptionStatus?.canScan == true) {
                                appAnalytics.trackHomeAction(action = "scan_camera", allowed = true)
                                navController.navigate(Routes.SelectInvoiceType)
                            } else {
                                appAnalytics.trackHomeAction(
                                    action = "scan_camera",
                                    allowed = false,
                                    failureReason = "scan_limit_reached"
                                )
                                appAnalytics.trackPaywallViewed(source = "scan_camera")
                                showUpgradeDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.cd_scan_camera),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.nav_scan_invoice),
                            maxLines = 2
                        )
                    }
                    
                    // Import files button
                    Button(
                        onClick = { 
                            if (!isOwnCompanyFilled()) {
                                appAnalytics.trackHomeAction(
                                    action = "import_files",
                                    allowed = false,
                                    failureReason = "own_company_missing"
                                )
                                Toast.makeText(context, fillCompanyFirst, Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            if (subscriptionStatus?.canScan == true) {
                                appAnalytics.trackHomeAction(action = "import_files", allowed = true)
                                navController.navigate(Routes.SelectImportType)
                            } else {
                                appAnalytics.trackHomeAction(
                                    action = "import_files",
                                    allowed = false,
                                    failureReason = "scan_limit_reached"
                                )
                                appAnalytics.trackPaywallViewed(source = "import_files")
                                showUpgradeDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = stringResource(R.string.cd_import_files),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.nav_import_files),
                            maxLines = 2
                        )
                    }
                    
                    // Exports button
                    Button(
                        onClick = {
                            appAnalytics.trackHomeAction(action = "open_exports", allowed = true)
                            navController.navigate(Routes.Exports)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.cd_exports),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.nav_exports),
                            maxLines = 2
                        )
                    }
                    
                    // Guide button
                    Button(
                        onClick = {
                            appAnalytics.trackHomeAction(action = "open_guide", allowed = true)
                            navController.navigate(Routes.Guide)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = stringResource(R.string.cd_guide),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.nav_guide),
                            maxLines = 2
                        )
                    }
                    
                    // Settings button
                    Button(
                        onClick = {
                            appAnalytics.trackHomeAction(action = "open_settings", allowed = true)
                            navController.navigate(Routes.Settings)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.nav_settings),
                            maxLines = 2
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            appAnalytics.trackFeedbackAction(source = "home")
                            openFeedbackEmail(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = stringResource(R.string.cd_send_feedback),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_send_feedback),
                            maxLines = 2,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            snackbar = { snackbarData ->
                var dragAccumulation = 0f
                Snackbar(
                    modifier = Modifier.pointerInput(snackbarData) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount ->
                                dragAccumulation += dragAmount
                                if (kotlin.math.abs(dragAccumulation) > 80f) {
                                    snackbarData.dismiss()
                                }
                            }
                        )
                    },
                    snackbarData = snackbarData
                )
            }
        )
        
        // Version number at the bottom
        Text(
            text = stringResource(R.string.common_version, com.vitol.inv3.BuildConfig.VERSION_NAME),
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
                    appAnalytics.trackPaywallViewed(source = "upgrade_dialog")
                    navController.navigate(Routes.Subscription)
                }
            )
        }

        if (showCompanySetupDialog && currentUserId != null) {
            val uid = currentUserId!!
            fun acknowledgePrompt() {
                companySetupPromptDismissed = true
                showCompanySetupDialog = false
                scope.launch {
                    context.setCompanySetupHomePromptAckUserId(uid)
                }
            }
            AlertDialog(
                onDismissRequest = { acknowledgePrompt() },
                title = { Text(stringResource(R.string.company_setup_title)) },
                text = {
                    Text(stringResource(R.string.company_setup_message))
                },
                confirmButton = {
                    Button(
                        onClick = {
                            acknowledgePrompt()
                            appAnalytics.trackHomeAction(
                                action = "open_add_own_company",
                                allowed = true
                            )
                            addOwnCompanyDialogTrigger += 1
                        },
                    ) {
                        Text(stringResource(R.string.company_setup_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { acknowledgePrompt() },
                    ) {
                        Text(stringResource(R.string.common_later))
                    }
                },
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
        Text(
            text = stringResource(R.string.common_coming_soon),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}


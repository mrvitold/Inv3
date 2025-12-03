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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.vitol.inv3.ui.scan.processSelectedFile
import com.vitol.inv3.utils.FileImportService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(navController)
                }
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
                            // Navigate to first item
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
                // Scan Invoice and Import buttons in a Row, centered
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { navController.navigate(Routes.Scan) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Scan Invoice")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Import")
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


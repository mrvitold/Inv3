package com.vitol.inv3.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vitol.inv3.MainActivityViewModel
import com.vitol.inv3.Routes
import com.vitol.inv3.data.local.getActiveOwnCompanyId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPrepareScreen(
    navController: NavController,
    importSessionViewModel: ImportSessionViewModel = hiltViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity),
    mainViewModel: MainActivityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val totalCount = importSessionViewModel.totalCount
    val invoiceType by importSessionViewModel.invoiceType.collectAsState(initial = "P")
    val extractionState by importSessionViewModel.extractionState.collectAsState(initial = ImportExtractionState.Idle)

    LaunchedEffect(Unit) {
        if (totalCount == 0) {
            navController.popBackStack()
            return@LaunchedEffect
        }
        val activeId = withContext(Dispatchers.IO) { context.getActiveOwnCompanyId() }
        val company = withContext(Dispatchers.IO) { mainViewModel.repo.getCompanyById(activeId ?: "") }
        importSessionViewModel.setOwnCompanyForExtraction(
            company?.company_number,
            company?.vat_number,
            company?.company_name
        )
        importSessionViewModel.runExtraction(context)
    }

    LaunchedEffect(extractionState) {
        when (extractionState) {
            is ImportExtractionState.Done -> {
                val encodedType = java.net.URLEncoder.encode(invoiceType, "UTF-8")
                navController.navigate("${Routes.ReviewScanImport}/$encodedType") {
                    popUpTo(Routes.ImportPrepare) { inclusive = true }
                }
            }
            is ImportExtractionState.Error -> {
                // Stay on screen; UI shows error below
            }
            else -> { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val state = extractionState) {
            is ImportExtractionState.Extracting -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Extracting invoice ${state.current} of ${state.total}…",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            is ImportExtractionState.Error -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Preparing…",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

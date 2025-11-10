package com.vitol.inv3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint

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
    const val Exports = "exports"
    const val Settings = "settings"
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
        composable(Routes.Companies) { com.vitol.inv3.ui.companies.CompaniesScreen() }
        composable(Routes.Exports) { com.vitol.inv3.ui.exports.ExportsScreen() }
        composable(Routes.Settings) { com.vitol.inv3.ui.settings.SettingsScreen() }
    }
}

@Composable
fun HomeScreen(navController: NavHostController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { navController.navigate(Routes.Scan) }) {
            Text(text = "Scan Invoice")
        }
        // Review Queue - navigate to a list screen (to be implemented)
        Button(onClick = { /* TODO: Navigate to review queue list */ }) {
            Text(text = "Review Queue")
        }
        Button(onClick = { navController.navigate(Routes.Companies) }) {
            Text(text = "Companies")
        }
        Button(onClick = { navController.navigate(Routes.Exports) }) {
            Text(text = "Exports")
        }
        Button(onClick = { navController.navigate(Routes.Settings) }) {
            Text(text = "Settings")
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


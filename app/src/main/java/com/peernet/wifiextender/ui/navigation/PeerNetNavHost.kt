package com.peernet.wifiextender.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.peernet.wifiextender.ui.home.HomeScreen

private const val ROUTE_MAIN = "main"

/**
 * Two-button layout: SHARE and CONNECT. Nothing else on screen.
 * User joins the Wi-Fi Direct network via phone settings; PeerNet
 * detects and links automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerNetRoot() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("PeerNet") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                        )
                    )
                }
            ) { innerPadding ->
                HomeScreen(modifier = Modifier.padding(innerPadding))
            }
        }
    }
}

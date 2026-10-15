package com.abeswastaken.trades.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abeswastaken.trades.ui.screens.HomeScreen
import com.abeswastaken.trades.ui.screens.LedgerScreen
import com.abeswastaken.trades.ui.screens.LogFillScreen
import com.abeswastaken.trades.ui.screens.PositionsScreen
import com.abeswastaken.trades.ui.screens.RecommendationScreen
import com.abeswastaken.trades.ui.screens.SettingsScreen
import com.abeswastaken.trades.ui.screens.WatchlistScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("positions", "Positions", Icons.Filled.PieChart),
    Tab("ledger", "Ledger", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("watch", "Watchlist", Icons.Filled.Visibility),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun TradesApp(vm: AppViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.toast.value = null }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(vm, openRec = { nav.navigate("rec/$it") }) }
            composable("positions") { PositionsScreen(vm, openLog = { nav.navigate("log") }) }
            composable("ledger") { LedgerScreen(vm, openLog = { nav.navigate("log") }) }
            composable("watch") { WatchlistScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
            composable("rec/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                RecommendationScreen(
                    vm, e.arguments?.getLong("id") ?: 0L,
                    back = { nav.popBackStack() },
                    openLog = { nav.navigate("log") },
                )
            }
            composable("log") { LogFillScreen(vm, done = { nav.popBackStack() }) }
        }
    }
}

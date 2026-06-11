// app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt
package com.darius.listmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.darius.listmanager.ui.screens.AccountScreen
import com.darius.listmanager.ui.screens.HomeScreen
import com.darius.listmanager.ui.screens.LoginScreen
import com.darius.listmanager.ui.screens.SessionScreen
import com.darius.listmanager.ui.screens.UnknownProductsScreen
import com.darius.listmanager.ui.screens.PDFsScreen
import com.darius.listmanager.ui.screens.CatalogScreen
import com.darius.listmanager.ui.screens.EditProductScreen
import com.darius.listmanager.ui.screens.SettingsScreen
import com.darius.listmanager.ui.screens.AboutScreen
import com.darius.listmanager.ui.screens.SyncDebugScreen
import com.darius.listmanager.ui.screens.TeamsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    startDestination: String = "home",
    isLoggedIn: Boolean = false,
    username: String? = null,
    onLoginStateChanged: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    onLoginStateChanged()  // Notify state change (observable AuthState already updated)
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else null
            )
        }
        composable("home") {
            HomeScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSession = { navController.navigate("session") },
                onNavigateToUnknown = { navController.navigate("unknown") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToAccount = { 
                    // Navigate to account if logged in, login if not
                    if (isLoggedIn) {
                        navController.navigate("account")
                    } else {
                        navController.navigate("login")
                    }
                },
                isLoggedIn = isLoggedIn,
                username = username
            )
        }
        composable("account") {
            AccountScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    onLoginStateChanged()  // Notify state change (observable AuthState already cleared)
                    // After logout, route to login and clear the entire back stack.
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                username = username
            )
        }
        composable("session") {
            SessionScreen(onBack = { navController.popBackStack() })
        }
        composable("unknown") {
            UnknownProductsScreen(onBack = { navController.popBackStack() })
        }
        composable("pdfs") {
            PDFsScreen(onBack = { navController.popBackStack() })
        }
        composable("catalog") {
            CatalogScreen(
                onBack = { navController.popBackStack() },
                onNavigateToEdit = { productId ->
                    navController.navigate("edit_product/$productId")
                }
            )
        }
        composable(
            route = "edit_product/{productId}",
            arguments = listOf(
                navArgument("productId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val productId = backStackEntry.arguments?.getLong("productId") ?: 0L
            EditProductScreen(
                productId = productId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("teams") {
            TeamsScreen(
                onBack = { navController.popBackStack() },
                onOpenTeam = { _, _ -> }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToSyncDebug = { navController.navigate("sync_debug") }
            )
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable("sync_debug") {
            SyncDebugScreen(onBack = { navController.popBackStack() })
        }
    }
}
package com.darius.listmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.darius.listmanager.ui.screens.*
import com.darius.listmanager.ui.viewmodel.EditProductViewModel

@Composable
fun NavGraph(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            HomeScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToSession = { navController.navigate("session") },
                onNavigateToUnknown = { navController.navigate("unknown") },
                onNavigateToSettings = { navController.navigate("settings") }
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
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
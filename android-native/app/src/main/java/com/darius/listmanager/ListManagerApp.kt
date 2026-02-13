package com.darius.listmanager

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.SyncWorker
import com.darius.listmanager.sync.SyncWorkManager
import com.darius.listmanager.ui.components.DrawerContent
import com.darius.listmanager.ui.components.SyncStatusBar
import com.darius.listmanager.ui.navigation.NavGraph
import kotlinx.coroutines.launch

class ListManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        Log.d("ListManagerApp", "Application starting...")
        SyncWorkManager.initialize(this)
        
        Log.d("ListManagerApp", "WorkManager initialized")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ===== SYNC SERVICE & STATUS =====
    val syncService = remember { SyncService(context) }
    val syncStatus by syncService.syncStatus.collectAsState()
    
    // ===== WEBSOCKET STATE =====
    val webSocketService = remember { WebSocketService.getInstance() }
    val webSocketState by webSocketService.connectionState.collectAsState()
    
    // Get pending operations count from database
    val database = remember { com.darius.listmanager.data.local.AppDatabase.getInstance(context) }
    val pendingCount by database.pendingOperationDao().getPendingCountFlow().collectAsState(initial = 0)
    
    // Track login state with refresh trigger
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val isLoggedIn = remember(refreshTrigger) { 
        syncService.isLoggedIn().also {
            Log.d("AppContent", "isLoggedIn computed: $it (trigger: $refreshTrigger)")
        }
    }
    val username = remember(refreshTrigger) { 
        if (syncService.isLoggedIn()) {
            context.getSharedPreferences("auth", 0).getString("saved_username", null)
        } else null
    }
    val startDestination = "home"
    
    // Refresh login state when navigating
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            Log.d("AppContent", "Nav entry: ${entry.destination.route}")
            kotlinx.coroutines.delay(100)
            refreshTrigger++
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isLoggedIn,
        drawerContent = {
            DrawerContent(
                onNavigate = { route ->
                    scope.launch {
                        drawerState.close()
                        navController.navigate(route) {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
    ) {
        Scaffold { padding ->
            Column(modifier = Modifier.padding(padding)) {
                val currentRoute = navController.currentBackStackEntryFlow
                    .collectAsState(initial = null).value?.destination?.route
                
                if (currentRoute != "login") {
                    SyncStatusBar(
                        pendingCount = pendingCount,
                        isSyncing = syncStatus.isSyncing,
                        isLoggedIn = isLoggedIn,
                        username = username,
                        webSocketState = webSocketState,
                        onSyncClick = {
                            Log.d("AppContent", " Sync button pressed! pendingCount=$pendingCount")
                            try {
                                SyncWorker.scheduleImmediateSync(context)
                                Log.d("AppContent", " Sync scheduled successfully")
                            } catch (e: Exception) {
                                Log.e("AppContent", " Error scheduling sync", e)
                            }
                        }
                    )
                }
                
                NavGraph(
                    navController = navController,
                    startDestination = startDestination,
                    isLoggedIn = isLoggedIn,
                    username = username,
                    onOpenDrawer = {
                        scope.launch {
                            drawerState.open()
                        }
                    },
                    onLoginStateChanged = { 
                        Log.d("AppContent", "Login state changed callback triggered")
                        refreshTrigger++ 
                    }
                )
            }
        }
    }
}
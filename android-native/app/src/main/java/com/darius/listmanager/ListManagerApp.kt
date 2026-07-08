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
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.SyncWorker
import com.darius.listmanager.sync.SyncWorkManager
import com.darius.listmanager.ui.components.DrawerContent
import com.darius.listmanager.ui.navigation.NavGraph
import kotlinx.coroutines.launch

class ListManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        Log.d("ListManagerApp", "Application starting...")
        SyncWorkManager.initialize(this)

        // Restore the saved auth token into the in-memory HTTP client so the user
        // stays logged in across app restarts. Without this, the token lives only
        // in encrypted prefs; RetrofitClient starts null, authenticated calls go
        // out without a Bearer header -> 401 -> forced re-login.
        SyncService(this).getAuthToken()?.let { token ->
            RetrofitClient.setAuthToken(token)
            Log.d("ListManagerApp", "Restored saved auth token")
        }

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

    // ===== WORKSPACE STATE =====
    val workspaceManager = remember {
        com.darius.listmanager.data.workspace.WorkspaceManager.getInstance(context)
    }
    val currentWorkspace by workspaceManager.currentWorkspace.collectAsState()
    val teamRepository = remember { com.darius.listmanager.data.repository.TeamRepository() }
    var drawerTeams by remember {
        mutableStateOf<List<com.darius.listmanager.network.TeamDTO>>(emptyList())
    }

    // Get pending operations count from database
    val database = remember { com.darius.listmanager.data.local.AppDatabase.getInstance(context) }
    val pendingCount by database.pendingOperationDao().getPendingCountFlow().collectAsState(initial = 0)
    
    // ===== OBSERVABLE LOGIN STATE (single source of truth, no disk polling) =====
    val isLoggedIn by AuthState.isLoggedIn.collectAsState()
    val username by AuthState.username.collectAsState()
    val sessionExpiredEvent by AuthState.sessionExpiredEvent.collectAsState()

    // Refresh the team list whenever the drawer is opened while logged in.
    // On logout, drop the cached teams so the next user cannot see (or switch
    // into) the previous user's workspaces.
    LaunchedEffect(drawerState.isOpen, isLoggedIn) {
        if (!isLoggedIn) {
            drawerTeams = emptyList()
        } else if (drawerState.isOpen) {
            val result = teamRepository.getMyTeams()
            if (result is com.darius.listmanager.data.repository.TeamResult.Success) {
                drawerTeams = result.data
            }
        }
    }

    // Decide where to start once, based on the persisted token at first composition.
    val startDestination = remember { if (AuthState.isLoggedIn.value) "home" else "login" }

    val snackbarHostState = remember { SnackbarHostState() }

    // On token expiry (HTTP 401), route to login clearing the back stack and inform the user.
    LaunchedEffect(sessionExpiredEvent) {
        if (sessionExpiredEvent > 0) {
            Log.d("AppContent", "Session expired event -> routing to login")
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
            snackbarHostState.showSnackbar("Sesiune expirată")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isLoggedIn,
        drawerContent = {
            DrawerContent(
                workspaceName = currentWorkspace.displayName,
                teams = drawerTeams,
                username = username,
                webSocketState = webSocketState,
                pendingCount = pendingCount,
                isSyncing = syncStatus.isSyncing,
                onSyncClick = {
                    Log.d("AppContent", "Sync from drawer, pendingCount=$pendingCount")
                    try {
                        SyncWorker.scheduleImmediateSync(context)
                    } catch (e: Exception) {
                        Log.e("AppContent", "Error scheduling sync", e)
                    }
                },
                onSwitchWorkspace = { workspace ->
                    workspaceManager.switchTo(workspace)
                    scope.launch {
                        drawerState.close()
                        navController.navigate("session") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
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
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                NavGraph(
                    navController = navController,
                    startDestination = startDestination,
                    isLoggedIn = isLoggedIn,
                    username = username,
                    onOpenDrawer = {
                        scope.launch {
                            drawerState.open()
                        }
                    }
                )
            }
        }
    }
}
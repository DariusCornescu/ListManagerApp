package com.darius.listmanager

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.network.AuthTokenStore
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.data.websocket.WebSocketService
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.sync.CatalogEtags
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.sync.SyncWorker
import com.darius.listmanager.sync.SyncWorkManager
import com.darius.listmanager.ui.components.CloseDrawerOnBack
import com.darius.listmanager.ui.components.DrawerContent
import com.darius.listmanager.ui.navigation.NavGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

class ListManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Crash-loop self-heal check + telemetry FIRST so anything below is covered.
        val selfHealed = com.darius.listmanager.util.CrashLoopGuard.onAppStart(this)
        com.darius.listmanager.util.CrashReporter.install(this)
        if (selfHealed) {
            android.widget.Toast.makeText(
                this,
                "Aplicația s-a recuperat după erori repetate — datele locale au fost resetate",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        Log.d("ListManagerApp", "Application starting...")
        CatalogEtags.initialize(this)
        SyncWorkManager.initialize(this)

        // Restore the saved auth token into the in-memory HTTP client so the user
        // stays logged in across app restarts. Without this, the token lives only
        // in encrypted prefs; RetrofitClient starts null, authenticated calls go
        // out without a Bearer header -> 401 -> forced re-login.
        val tokenService = SyncService(this)
        RetrofitClient.tokenStore = object : AuthTokenStore {
            override fun accessToken() = tokenService.getAuthToken()
            override fun refreshToken() = tokenService.getRefreshToken()
            override fun saveTokens(accessToken: String, refreshToken: String) =
                tokenService.saveRotatedTokens(accessToken, refreshToken)
        }
        tokenService.getAuthToken()?.let { token ->
            RetrofitClient.setAuthToken(token)
            tokenService.getAuthenticatedUserId()?.let(AuthState::setUserId)
            Log.d("ListManagerApp", "Restored saved auth token")
        }

        // Upload any crash reports saved by a previous run (background, offline-safe).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.darius.listmanager.util.CrashReporter.flushPending(this@ListManagerApp)
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
    val onlineUsers by webSocketService.onlineUsers.collectAsState()

    // ===== WORKSPACE STATE =====
    val workspaceManager = remember {
        com.darius.listmanager.data.workspace.WorkspaceManager.getInstance(context)
    }
    val currentWorkspace by workspaceManager.currentWorkspace.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var serverReachable by remember { mutableStateOf(false) }

    // Get pending operations count from database
    val database = remember { com.darius.listmanager.data.local.AppDatabase.getInstance(context) }
    val accountUserId by AuthState.userId.collectAsState()
    val pendingCountFlow = remember(accountUserId) {
        accountUserId?.let { database.pendingOperationDao().getPendingCountFlowForUser(it) }
            ?: flowOf(0)
    }
    val pendingCount by pendingCountFlow.collectAsState(initial = 0)
    
    // ===== OBSERVABLE LOGIN STATE (single source of truth, no disk polling) =====
    val isLoggedIn by AuthState.isLoggedIn.collectAsState()
    val username by AuthState.username.collectAsState()
    val sessionExpiredEvent by AuthState.sessionExpiredEvent.collectAsState()

    // Refresh connection and presence whenever the drawer is opened while logged in.
    LaunchedEffect(drawerState.isOpen, isLoggedIn) {
        if (isLoggedIn && drawerState.isOpen) {
            // Server reachability (the old banner's middle state): REST can be
            // perfectly fine while the live WebSocket is down — show that
            // honestly instead of a scary "no connection".
            serverReachable = try {
                com.darius.listmanager.util.NetworkHelper.isServerReachable()
            } catch (e: Exception) { false }
            // If the live socket dropped but we're logged in and the server is
            // up, quietly reconnect it.
            if (serverReachable && webSocketState !is WebSocketState.Connected) {
                SyncService(context).getAuthToken()?.let { token ->
                    Log.d("AppContent", "Drawer open: reconnecting WebSocket")
                    webSocketService.connect(token)
                }
            }
            // Presence fallback: WebSocket pushes keep it live, but refresh on
            // open too so the list is right even after a silent reconnect.
            try {
                val presence = RetrofitClient.api.getPresence()
                if (presence.isSuccessful) {
                    webSocketService.setOnlineUsers(
                        presence.body().orEmpty().map {
                            com.darius.listmanager.data.websocket.OnlineUser(it.user_id, it.username)
                        }
                    )
                }
            } catch (e: Exception) {
                Log.d("AppContent", "Presence refresh failed: ${e.message}")
            }
        }
    }

    // Queued work and a live connection are both reasons to replay now. The network
    // constraint holds the request until a connection exists; KEEP removes duplicates.
    LaunchedEffect(pendingCount, webSocketState is WebSocketState.Connected, isLoggedIn) {
        val connected = webSocketState is WebSocketState.Connected
        if (isLoggedIn && (pendingCount > 0 || connected)) SyncWorker.scheduleImmediateSync(context)
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
                currentRoute = currentRoute,
                username = username,
                webSocketState = webSocketState,
                serverReachable = serverReachable,
                onlineUsers = onlineUsers,
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
            // Each screen has its own Scaffold. Consuming here stops it reserving the system bars again.
            Column(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
            ) {
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
                // After NavGraph, so it answers Back before navigation does.
                CloseDrawerOnBack(drawerState)
            }
        }
    }
}

package com.darius.listmanager.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.model.SyncResult
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.util.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDebugScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }
    val syncService = remember { SyncService(context) }
    val accountUserId by AuthState.userId.collectAsState()
    val scope = rememberCoroutineScope()
    
    // State
    var isNetworkAvailable by remember { mutableStateOf(false) }
    var isServerReachable by remember { mutableStateOf(false) }
    var isCheckingServer by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastSyncResult by remember { mutableStateOf<String?>(null) }
    
    // Database flows
    val pendingOperationsFlow = remember(accountUserId) {
        accountUserId?.let { database.pendingOperationDao().getAllPendingFlowForUser(it) }
            ?: flowOf(emptyList())
    }
    val failedCountFlow = remember(accountUserId) {
        accountUserId?.let { database.pendingOperationDao().getFailedCountFlowForUser(it) }
            ?: flowOf(0)
    }
    val pendingOperations by pendingOperationsFlow.collectAsState(initial = emptyList())
    val failedCount by failedCountFlow.collectAsState(initial = 0)
    val productCount by database.productDao().getCountFlow().collectAsState(initial = 0)
    val distributorCount by database.distributorDao().getCountFlow().collectAsState(initial = 0)
    
    // All operations (including completed/failed)
    var allOperations by remember { mutableStateOf<List<PendingOperationEntity>>(emptyList()) }
    
    // Check network status periodically
    LaunchedEffect(accountUserId) {
        while (true) {
            isNetworkAvailable = NetworkHelper.isNetworkAvailable(context)
            if (isNetworkAvailable && !isCheckingServer) {
                isCheckingServer = true
                isServerReachable = withContext(Dispatchers.IO) {
                    NetworkHelper.isServerReachable()
                }
                isCheckingServer = false
            } else if (!isNetworkAvailable) {
                isServerReachable = false
            }
            
            // Refresh all operations
            allOperations = withContext(Dispatchers.IO) {
                accountUserId?.let {
                    database.pendingOperationDao().getAllPendingForUser(it) +
                        database.pendingOperationDao().getAllFailedForUser(it)
                } ?: emptyList()
            }
            
            delay(2000)
        }
    }
    
    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        syncLogs = listOf("[$timestamp] $message") + syncLogs.take(49)
        Log.d("SyncDebug", message)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔧 Sync Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { syncLogs = emptyList() }) {
                        Icon(Icons.Rounded.Delete, "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                NetworkStatusCard(
                    isNetworkAvailable = isNetworkAvailable,
                    isServerReachable = isServerReachable,
                    isCheckingServer = isCheckingServer,
                    onCheckServer = {
                        scope.launch {
                            isCheckingServer = true
                            addLog("Checking server reachability...")
                            isServerReachable = withContext(Dispatchers.IO) {
                                NetworkHelper.isServerReachable()
                            }
                            addLog("Server reachable: $isServerReachable")
                            isCheckingServer = false
                        }
                    },
                )
            }

            item {
                LocalDatabaseCard(
                    productCount = productCount,
                    distributorCount = distributorCount,
                    pendingCount = pendingOperations.size,
                    failedCount = failedCount,
                )
            }

            item {
                SyncControlsCard(
                    isSyncing = isSyncing,
                    lastSyncResult = lastSyncResult,
                    onSync = {
                        scope.launch {
                            isSyncing = true
                            addLog("========== SYNC STARTED ==========")
                            addLog("Pending operations: ${pendingOperations.size}")

                            try {
                                val result = withContext(Dispatchers.IO) {
                                    syncService.syncPendingOperations()
                                }

                                lastSyncResult = when (result) {
                                    is SyncResult.Success -> "✅ SUCCESS"
                                    is SyncResult.Error -> "❌ ERROR: ${result.message}"
                                    is SyncResult.Conflict -> "⚠️ CONFLICT: ${result.message}"
                                }
                                addLog("Sync result: $lastSyncResult")
                            } catch (e: Exception) {
                                lastSyncResult = "❌ EXCEPTION: ${e.message}"
                                addLog("Sync exception: ${e.message}")
                            } finally {
                                addLog("========== SYNC FINISHED ==========")
                                isSyncing = false
                            }
                        }
                    },
                    onClearAll = {
                        scope.launch {
                            addLog("Clearing all pending operations...")
                            withContext(Dispatchers.IO) {
                                database.pendingOperationDao().deleteAll()
                            }
                            addLog("All pending operations cleared")
                        }
                    },
                )
            }

            item {
                Text(
                    "📋 Pending Operations (${allOperations.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (allOperations.isEmpty()) {
                item { NoPendingOperationsCard() }
            } else {
                items(allOperations, key = { it.id }) { operation ->
                    OperationDetailCard(operation)
                }
            }

            item {
                Text(
                    "📝 Logs (${syncLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item { SyncLogCard(syncLogs) }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

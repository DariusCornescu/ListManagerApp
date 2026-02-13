package com.darius.listmanager.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.data.model.SyncResult
import com.darius.listmanager.network.ApiConfig
import com.darius.listmanager.sync.SyncService
import com.darius.listmanager.util.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    
    // State
    var isNetworkAvailable by remember { mutableStateOf(false) }
    var isServerReachable by remember { mutableStateOf(false) }
    var isCheckingServer by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastSyncResult by remember { mutableStateOf<String?>(null) }
    
    // Database flows
    val pendingOperations by database.pendingOperationDao().getAllPendingFlow().collectAsState(initial = emptyList())
    val failedCount by database.pendingOperationDao().getFailedCountFlow().collectAsState(initial = 0)
    val productCount by database.productDao().getCountFlow().collectAsState(initial = 0)
    val distributorCount by database.distributorDao().getCountFlow().collectAsState(initial = 0)
    
    // All operations (including completed/failed)
    var allOperations by remember { mutableStateOf<List<PendingOperationEntity>>(emptyList()) }
    
    // Check network status periodically
    LaunchedEffect(Unit) {
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
                database.pendingOperationDao().getAllPending() + 
                database.pendingOperationDao().getAllFailed()
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
            // ===== NETWORK STATUS =====
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "📡 Network Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        HorizontalDivider()
                        
                        StatusRow(
                            label = "Network Available",
                            value = isNetworkAvailable,
                            trueText = "✅ Yes",
                            falseText = "❌ No"
                        )
                        
                        StatusRow(
                            label = "Server Reachable",
                            value = isServerReachable,
                            trueText = "✅ Yes",
                            falseText = "❌ No",
                            isLoading = isCheckingServer
                        )
                        
                        Text(
                            "Base URL: ${ApiConfig.BASE_URL}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Text(
                            "Environment: ${ApiConfig.getCurrentEnvironment()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        // Refresh button
                        OutlinedButton(
                            onClick = {
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
                            enabled = !isCheckingServer
                        ) {
                            if (isCheckingServer) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Check Server")
                        }
                    }
                }
            }
            
            // ===== LOCAL DATABASE =====
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "💾 Local Database",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        HorizontalDivider()
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatBox("Products", productCount.toString(), Color(0xFF4CAF50))
                            StatBox("Distributors", distributorCount.toString(), Color(0xFF2196F3))
                            StatBox("Pending Ops", pendingOperations.size.toString(), Color(0xFFFF9800))
                            StatBox("Failed Ops", failedCount.toString(), Color(0xFFF44336))
                        }
                    }
                }
            }
            
            // ===== SYNC CONTROLS =====
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "🔄 Sync Controls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        HorizontalDivider()
                        
                        // Main sync button
                        Button(
                            onClick = {
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
                            enabled = !isSyncing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Rounded.Sync, null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (isSyncing) "Syncing..." else "Sync Pending Operations")
                        }
                        
                        // Last sync result
                        lastSyncResult?.let { result ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        result.contains("SUCCESS") -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                                        result.contains("ERROR") || result.contains("EXCEPTION") -> Color(0xFFF44336).copy(alpha = 0.2f)
                                        else -> Color(0xFFFF9800).copy(alpha = 0.2f)
                                    }
                                )
                            ) {
                                Text(
                                    text = result,
                                    modifier = Modifier.padding(12.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        // Clear all pending operations
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    addLog("Clearing all pending operations...")
                                    withContext(Dispatchers.IO) {
                                        database.pendingOperationDao().deleteAll()
                                    }
                                    addLog("All pending operations cleared")
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.DeleteForever, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear All Pending Operations")
                        }
                    }
                }
            }
            
            // ===== PENDING OPERATIONS DETAILS =====
            item {
                Text(
                    "📋 Pending Operations (${allOperations.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (allOperations.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF4CAF50))
                            Spacer(Modifier.width(8.dp))
                            Text("No pending operations")
                        }
                    }
                }
            } else {
                items(allOperations, key = { it.id }) { operation ->
                    OperationDetailCard(operation)
                }
            }
            
            // ===== LOGS =====
            item {
                Text(
                    "📝 Logs (${syncLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1E1E)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (syncLogs.isEmpty()) {
                            Text(
                                "No logs yet. Press 'Sync Pending Operations' to see logs.",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        } else {
                            syncLogs.forEach { log ->
                                Text(
                                    text = log,
                                    color = when {
                                        log.contains("ERROR") || log.contains("EXCEPTION") || log.contains("❌") -> Color(0xFFF44336)
                                        log.contains("SUCCESS") || log.contains("✅") -> Color(0xFF4CAF50)
                                        log.contains("STARTED") || log.contains("FINISHED") -> Color(0xFF2196F3)
                                        else -> Color(0xFFCCCCCC)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            // Spacer at bottom
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: Boolean,
    trueText: String,
    falseText: String,
    isLoading: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(
                if (value) trueText else falseText,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatBox(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OperationDetailCard(operation: PendingOperationEntity) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    val now = System.currentTimeMillis()
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (operation.status) {
                "PENDING" -> MaterialTheme.colorScheme.tertiaryContainer
                "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
                "FAILED" -> MaterialTheme.colorScheme.errorContainer
                "COMPLETED" -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ID: ${operation.id}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Badge(
                    containerColor = when (operation.status) {
                        "PENDING" -> Color(0xFFFF9800)
                        "IN_PROGRESS" -> Color(0xFF2196F3)
                        "FAILED" -> Color(0xFFF44336)
                        "COMPLETED" -> Color(0xFF4CAF50)
                        else -> Color.Gray
                    }
                ) {
                    Text(operation.status, color = Color.White)
                }
            }
            
            HorizontalDivider()
            
            // Details
            DetailRow("Type", operation.operationType)
            DetailRow("Resource", operation.resourceType)
            operation.resourceId?.let { DetailRow("Resource ID", it.toString()) }
            DetailRow("Created", dateFormat.format(Date(operation.createdAt)))
            DetailRow("Updated", dateFormat.format(Date(operation.updatedAt)))
            
            // ScheduledFor - important for debugging!
            val scheduledFor = operation.scheduledFor
            val isReadyForSync = scheduledFor <= now
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Scheduled For", style = MaterialTheme.typography.bodySmall)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        dateFormat.format(Date(scheduledFor)),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (isReadyForSync) "✅ Ready for sync" else "⏳ Waiting ${(scheduledFor - now) / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReadyForSync) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }
            }
            
            DetailRow("Retry Count", operation.retryCount.toString())
            DetailRow("Conflict Strategy", operation.conflictStrategy)
            
            // Error message
            operation.lastError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                    )
                ) {
                    Text(
                        "Error: $error",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // Operation data (truncated)
            Spacer(Modifier.height(4.dp))
            Text(
                "Data: ${operation.operationData.take(100)}${if (operation.operationData.length > 100) "..." else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}


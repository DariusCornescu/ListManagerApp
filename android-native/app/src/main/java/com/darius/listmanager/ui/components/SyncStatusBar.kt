package com.darius.listmanager.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.util.NetworkHelper

enum class ConnectionStatus {
    CONNECTED_LIVE,      // WebSocket connected - real-time sync active
    SERVER_AVAILABLE,    // Server reachable but no WebSocket (not logged in)
    OFFLINE              // Server not reachable
}

@Composable
fun SyncStatusBar(
    pendingCount: Int,
    isSyncing: Boolean = false,
    isLoggedIn: Boolean = false,
    username: String? = null,
    webSocketState: WebSocketState = WebSocketState.Disconnected,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Start as false to prevent accidental clicks before server check completes
    var isServerReachable by remember { mutableStateOf(false) }
    var consecutiveSuccesses by remember { mutableIntStateOf(0) }
    var showPendingDialog by remember { mutableStateOf(false) }
    
    // Show pending operations dialog
    if (showPendingDialog) {
        PendingOperationsDialog(
            onDismiss = { showPendingDialog = false },
            onSyncClick = {
                android.util.Log.d("SyncStatusBar", "Sync triggered from dialog")
                onSyncClick()
            }
        )
    }
    
    // Smart server check: frequent when unstable, rare when stable
    LaunchedEffect(Unit) {
        // Initial check immediately
        val hasNetwork = NetworkHelper.isNetworkAvailable(context)
        isServerReachable = if (hasNetwork) NetworkHelper.isServerReachable() else false
        
        while (true) {
            // Adaptive delay based on stability
            val delay = when {
                !isServerReachable -> 3000L        // Offline: check every 3s to detect recovery
                consecutiveSuccesses < 3 -> 5000L  // Recently online: check every 5s
                else -> 15000L                     // Stable: check every 15s
            }
            
            kotlinx.coroutines.delay(delay)
            
            val wasReachable = isServerReachable
            val networkAvailable = NetworkHelper.isNetworkAvailable(context)
            isServerReachable = if (networkAvailable) NetworkHelper.isServerReachable() else false
            
            // Track stability
            if (isServerReachable) {
                consecutiveSuccesses++
            } else { consecutiveSuccesses = 0 }
            
            if (wasReachable != isServerReachable) {
                android.util.Log.d("SyncStatusBar", "Server status changed: ${if (isServerReachable) "Online" else "Offline"}")
            }
        }
    }
    
    // Determine connection status
    val connectionStatus = when {
        webSocketState is WebSocketState.Connected -> ConnectionStatus.CONNECTED_LIVE
        isServerReachable -> ConnectionStatus.SERVER_AVAILABLE
        else -> ConnectionStatus.OFFLINE
    }
    
    // Animated rotation for sync icon
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    // Colors based on status
    val containerColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED_LIVE -> MaterialTheme.colorScheme.primaryContainer
        ConnectionStatus.SERVER_AVAILABLE -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.errorContainer
    }
    
    val contentColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED_LIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionStatus.SERVER_AVAILABLE -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.onErrorContainer
    }
    
    val statusIcon = when (connectionStatus) {
        ConnectionStatus.CONNECTED_LIVE -> Icons.Rounded.CloudDone
        ConnectionStatus.SERVER_AVAILABLE -> Icons.Rounded.Cloud
        ConnectionStatus.OFFLINE -> Icons.Rounded.CloudOff
    }
    
    val statusColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED_LIVE -> Color(0xFF4CAF50)  // Green
        ConnectionStatus.SERVER_AVAILABLE -> Color(0xFFFFA726)  // Orange
        ConnectionStatus.OFFLINE -> Color(0xFFF44336)  // Red
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        color = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: Status + info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Status icon with colored dot
                    Box {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = contentColor
                        )
                        // Status dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .align(Alignment.BottomEnd)
                                .background(statusColor, shape = androidx.compose.foundation.shape.CircleShape)
                        )
                    }
                    
                    // Status text
                    Column {
                        Text(
                            text = when (connectionStatus) {
                                ConnectionStatus.CONNECTED_LIVE -> "Conectat live"
                                ConnectionStatus.SERVER_AVAILABLE -> "Server disponibil"
                                ConnectionStatus.OFFLINE -> "Offline"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        Text(
                            text = when (connectionStatus) {
                                ConnectionStatus.CONNECTED_LIVE -> 
                                    if (username != null) "$username • Sincronizare activă" 
                                    else "Sincronizare în timp real"
                                ConnectionStatus.SERVER_AVAILABLE -> 
                                    "Autentifică-te pentru sync live"
                                ConnectionStatus.OFFLINE -> 
                                    "Datele se salvează local"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.8f)
                        )
                    }
                }
                
                // Right side: Pending count + Sync button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pending operations indicator - CLICKABLE to show details
                    if (pendingCount > 0) {
                        Surface(
                            onClick = { 
                                android.util.Log.d("SyncStatusBar", "Pending badge clicked - showing dialog")
                                showPendingDialog = true 
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = when (connectionStatus) {
                                ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Badge(
                                    containerColor = when (connectionStatus) {
                                        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.tertiary
                                    }
                                ) {
                                    Text(
                                        pendingCount.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "în așteptare",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (connectionStatus) {
                                        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.onErrorContainer
                                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                                    }
                                )
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = "Vezi detalii",
                                    modifier = Modifier.size(16.dp),
                                    tint = when (connectionStatus) {
                                        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.onErrorContainer
                                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                                    }
                                )
                            }
                        }
                    }
                    
                    // Sync button - always visible, shows status
                    FilledTonalIconButton(
                        onClick = {
                            android.util.Log.d("SyncStatusBar", "Sync button clicked! Pending: $pendingCount, ServerReachable: $isServerReachable, isSyncing: $isSyncing")
                            if (pendingCount > 0) {
                                showPendingDialog = true  // Show dialog first to see operations
                            } else {
                                android.util.Log.d("SyncStatusBar", "No pending operations to sync")
                            }
                        },
                        enabled = !isSyncing,  // Only disabled while syncing
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (pendingCount > 0) Icons.Rounded.Sync else Icons.Rounded.CloudDone,
                            contentDescription = "Sincronizare",
                            modifier = Modifier
                                .size(20.dp)
                                .then(if (isSyncing) Modifier.rotate(rotation) else Modifier)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SyncStatusCompact(
    isOnline: Boolean,
    pendingCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336),
                    shape = MaterialTheme.shapes.small
                )
        )
        
        Text(
            text = if (isOnline) "Online" else "Offline",
            style = MaterialTheme.typography.bodySmall
        )
        
        if (pendingCount > 0) {
            Text(
                text = "• $pendingCount pending",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/**
 * Full sync status card for settings screen
 */
@Composable
fun SyncStatusCard(
    isOnline: Boolean,
    pendingCount: Int,
    lastSyncTime: String?,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Status Sincronizare",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            HorizontalDivider()
            
            // Connection status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Conexiune", style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isOnline) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                    Text(
                        text = if (isOnline) "Conectat" else "Deconectat",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            // Pending operations
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Operații în așteptare", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = pendingCount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (pendingCount > 0) 
                        MaterialTheme.colorScheme.tertiary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Last sync time
            if (lastSyncTime != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ultima sincronizare", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = lastSyncTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Sync button
            Button(
                onClick = onSyncClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = isOnline && !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sincronizare...")
                } else {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sincronizează Acum")
                }
            }
        }
    }
}


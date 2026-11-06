package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.network.ApiConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Success = Color(0xFF4CAF50)
private val Info = Color(0xFF2196F3)
private val Warning = Color(0xFFFF9800)
private val Failure = Color(0xFFF44336)

@Composable
private fun SectionCard(
    title: String,
    containerColor: Color,
    spacing: Int = 8,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
internal fun NetworkStatusCard(
    isNetworkAvailable: Boolean,
    isServerReachable: Boolean,
    isCheckingServer: Boolean,
    onCheckServer: () -> Unit,
) {
    SectionCard("📡 Network Status", MaterialTheme.colorScheme.surfaceVariant) {
        StatusRow("Network Available", isNetworkAvailable)
        StatusRow("Server Reachable", isServerReachable, isLoading = isCheckingServer)
        Text(
            "Base URL: ${ApiConfig.BASE_URL}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Environment: ${ApiConfig.getCurrentEnvironment()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCheckServer, enabled = !isCheckingServer) {
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

@Composable
internal fun LocalDatabaseCard(
    productCount: Int,
    distributorCount: Int,
    pendingCount: Int,
    failedCount: Int,
) {
    SectionCard("💾 Local Database", MaterialTheme.colorScheme.secondaryContainer) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBox("Products", productCount.toString(), Success)
            StatBox("Distributors", distributorCount.toString(), Info)
            StatBox("Pending Ops", pendingCount.toString(), Warning)
            StatBox("Failed Ops", failedCount.toString(), Failure)
        }
    }
}

@Composable
internal fun SyncControlsCard(
    isSyncing: Boolean,
    lastSyncResult: String?,
    onSync: () -> Unit,
    onClearAll: () -> Unit,
) {
    SectionCard("🔄 Sync Controls", MaterialTheme.colorScheme.primaryContainer, spacing = 12) {
        Button(onClick = onSync, enabled = !isSyncing, modifier = Modifier.fillMaxWidth()) {
            if (isSyncing) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Rounded.Sync, null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (isSyncing) "Syncing..." else "Sync Pending Operations")
        }

        lastSyncResult?.let { result ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        result.contains("SUCCESS") -> Success.copy(alpha = 0.2f)
                        result.contains("ERROR") || result.contains("EXCEPTION") -> Failure.copy(alpha = 0.2f)
                        else -> Warning.copy(alpha = 0.2f)
                    }
                )
            ) {
                Text(text = result, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onClearAll,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.DeleteForever, null)
            Spacer(Modifier.width(8.dp))
            Text("Clear All Pending Operations")
        }
    }
}

@Composable
internal fun NoPendingOperationsCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Success)
            Spacer(Modifier.width(8.dp))
            Text("No pending operations")
        }
    }
}

@Composable
internal fun SyncLogCard(logs: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (logs.isEmpty()) {
                Text(
                    "No logs yet. Press 'Sync Pending Operations' to see logs.",
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                logs.forEach { log ->
                    Text(
                        text = log,
                        color = when {
                            log.contains("ERROR") || log.contains("EXCEPTION") || log.contains("❌") -> Failure
                            log.contains("SUCCESS") || log.contains("✅") -> Success
                            log.contains("STARTED") || log.contains("FINISHED") -> Info
                            else -> Color(0xFFCCCCCC)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: Boolean, isLoading: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(if (value) "✅ Yes" else "❌ No", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun OperationDetailCard(operation: PendingOperationEntity) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    val now = System.currentTimeMillis()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (operation.status) {
                "PENDING" -> MaterialTheme.colorScheme.tertiaryContainer
                "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
                "FAILED" -> MaterialTheme.colorScheme.errorContainer
                "COMPLETED" -> Success.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ID: ${operation.id}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
                Badge(
                    containerColor = when (operation.status) {
                        "PENDING" -> Warning
                        "IN_PROGRESS" -> Info
                        "FAILED" -> Failure
                        "COMPLETED" -> Success
                        else -> Color.Gray
                    }
                ) {
                    Text(operation.status, color = Color.White)
                }
            }

            HorizontalDivider()

            DetailRow("Type", operation.operationType)
            DetailRow("Resource", operation.resourceType)
            operation.resourceId?.let { DetailRow("Resource ID", it.toString()) }
            DetailRow("Created", dateFormat.format(Date(operation.createdAt)))
            DetailRow("Updated", dateFormat.format(Date(operation.updatedAt)))

            val scheduledFor = operation.scheduledFor
            val isReadyForSync = scheduledFor <= now
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Scheduled For", style = MaterialTheme.typography.bodySmall)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        dateFormat.format(Date(scheduledFor)),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (isReadyForSync) "✅ Ready for sync" else "⏳ Waiting ${(scheduledFor - now) / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReadyForSync) Success else Warning,
                    )
                }
            }

            DetailRow("Retry Count", operation.retryCount.toString())
            DetailRow("Conflict Strategy", operation.conflictStrategy)

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
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Data: ${operation.operationData.take(100)}${if (operation.operationData.length > 100) "..." else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

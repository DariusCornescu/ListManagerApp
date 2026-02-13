package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingOperationsDialog( onDismiss: () -> Unit, onSyncClick: () -> Unit ) {
    val context = LocalContext.current
    val database = remember { com.darius.listmanager.data.local.AppDatabase.getInstance(context) }
    
    var operations by remember { mutableStateOf<List<PendingOperationEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Load pending operations
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            operations = database.pendingOperationDao().getAllPending()
            isLoading = false
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PendingActions, "Operații în așteptare") },
        title = { 
            Text(
                "Operații în așteptare",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (operations.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Toate operațiile sunt sincronizate!",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(operations) { operation ->
                        PendingOperationItem(operation)
                    }
                }
            }
        },
        confirmButton = {
            if (operations.isNotEmpty()) {
                Button(
                    onClick = {
                        android.util.Log.d("PendingOpsDialog", "Sync button clicked in dialog - ${operations.size} operations")
                        onSyncClick()
                        onDismiss()
                    }
                ) {
                    Icon(Icons.Rounded.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sincronizează acum")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Închide")
            }
        }
    )
}

@Composable
private fun PendingOperationItem(operation: PendingOperationEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (operation.status) {
                "PENDING" -> MaterialTheme.colorScheme.tertiaryContainer
                "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
                "FAILED" -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Operation type icon + text
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            operation.operationType.contains("CREATE") -> Icons.Rounded.Add
                            operation.operationType.contains("UPDATE") -> Icons.Rounded.Edit
                            operation.operationType.contains("DELETE") -> Icons.Rounded.Delete
                            else -> Icons.Rounded.Info
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (operation.status) {
                            "PENDING" -> MaterialTheme.colorScheme.onTertiaryContainer
                            "IN_PROGRESS" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "FAILED" -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = formatOperationType(operation.operationType),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Status badge
                Badge(
                    containerColor = when (operation.status) {
                        "PENDING" -> MaterialTheme.colorScheme.tertiary
                        "IN_PROGRESS" -> MaterialTheme.colorScheme.primary
                        "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    }
                ) {
                    Text(
                        text = when (operation.status) {
                            "PENDING" -> "În așteptare"
                            "IN_PROGRESS" -> "În curs"
                            "FAILED" -> "Eșuat"
                            else -> operation.status
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            
            // Resource type
            Text(
                text = formatResourceType(operation.resourceType),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Created time
            Text(
                text = "Creat: ${formatTime(operation.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Error message if failed
            if (operation.status == "FAILED" && operation.lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ ${operation.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatOperationType(type: String): String {
    return when (type) {
        "CREATE_PRODUCT" -> "Creare produs"
        "UPDATE_PRODUCT" -> "Actualizare produs"
        "DELETE_PRODUCT" -> "Ștergere produs"
        "CREATE_DISTRIBUTOR" -> "Creare distribuitor"
        "UPDATE_DISTRIBUTOR" -> "Actualizare distribuitor"
        "DELETE_DISTRIBUTOR" -> "Ștergere distribuitor"
        "ADD_SESSION_ITEM" -> "Adăugare în sesiune"
        "UPDATE_SESSION_ITEM" -> "Actualizare sesiune"
        "DELETE_SESSION_ITEM" -> "Ștergere din sesiune"
        else -> type
    }
}

private fun formatResourceType(type: String): String {
    return when (type) {
        "PRODUCT" -> "📦 Produs"
        "DISTRIBUTOR" -> "🏢 Distribuitor"
        "SESSION_ITEM" -> "🛒 Element sesiune"
        else -> type
    }
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "acum ${diff / 1000}s"
        diff < 3600_000 -> "acum ${diff / 60_000}m"
        diff < 86400_000 -> "acum ${diff / 3600_000}h"
        else -> SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}


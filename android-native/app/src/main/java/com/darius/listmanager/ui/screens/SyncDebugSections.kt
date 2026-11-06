package com.darius.listmanager.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darius.listmanager.data.local.entity.PendingOperationEntity
import com.darius.listmanager.network.ApiConfig
import com.darius.listmanager.ui.components.AppDangerSoftButton
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun operationStatusLabel(status: String): String = when (status) {
    "PENDING" -> "În așteptare"
    "IN_PROGRESS" -> "În curs"
    "FAILED" -> "Eșuat"
    "COMPLETED" -> "Finalizat"
    else -> status
}

@Composable
internal fun DiagnosticHeading(title: String, count: Int? = null) {
    Text(
        if (count == null) title else "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
    )
}

@Composable
private fun DiagnosticCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun NetworkStatusCard(
    isNetworkAvailable: Boolean,
    isServerReachable: Boolean,
    isCheckingServer: Boolean,
    onCheckServer: () -> Unit,
) {
    DiagnosticCard {
        StatusRow("Internet", isNetworkAvailable)
        StatusRow("Server", isServerReachable, isLoading = isCheckingServer)
        Text(
            "${ApiConfig.BASE_URL} · ${ApiConfig.getCurrentEnvironment()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppSecondaryButton("Verifică serverul", onCheckServer, enabled = !isCheckingServer, icon = Icons.Rounded.Refresh)
    }
}

@Composable
internal fun LocalDatabaseCard(
    productCount: Int,
    distributorCount: Int,
    pendingCount: Int,
    failedCount: Int,
) {
    DiagnosticCard {
        Row(Modifier.fillMaxWidth()) {
            StatBox("Produse", productCount, Modifier.weight(1f))
            StatBox("Distribuitori", distributorCount, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatBox("În așteptare", pendingCount, Modifier.weight(1f))
            StatBox("Eșuate", failedCount, Modifier.weight(1f))
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
    DiagnosticCard {
        AppPrimaryButton(
            text = "Sincronizează operațiunile",
            onClick = onSync,
            loading = isSyncing,
            loadingText = "Se sincronizează…",
        )
        lastSyncResult?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        AppDangerSoftButton("Golește operațiunile", onClearAll)
    }
}

@Composable
internal fun NoPendingOperationsCard() {
    DiagnosticCard {
        Text(
            "Nu există operațiuni în așteptare.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SyncLogCard(logs: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .padding(14.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            if (logs.isEmpty()) {
                LogLine("Jurnalul este gol.", scheme.onSurfaceVariant)
            } else {
                logs.forEach { log ->
                    LogLine(log, if (log.contains("Eroare") || log.contains("Conflict")) scheme.error else scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LogLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun StatusRow(label: String, value: Boolean, isLoading: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        when {
            isLoading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Surface(
                shape = CircleShape,
                color = if (value) scheme.secondaryContainer else scheme.errorContainer,
                contentColor = if (value) scheme.onSecondaryContainer else scheme.onErrorContainer,
            ) {
                Text(
                    if (value) "Da" else "Nu",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun OperationDetailCard(operation: PendingOperationEntity) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    val now = System.currentTimeMillis()
    val failed = operation.status == "FAILED"

    DiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ID ${operation.id}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Surface(
                shape = CircleShape,
                color = if (failed) scheme.errorContainer else scheme.primaryContainer,
                contentColor = if (failed) scheme.onErrorContainer else scheme.onPrimaryContainer,
            ) {
                Text(
                    operationStatusLabel(operation.status),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        DetailRow("Tip", operation.operationType)
        DetailRow("Resursă", operation.resourceType)
        operation.resourceId?.let { DetailRow("ID resursă", it.toString()) }
        DetailRow("Creată", dateFormat.format(Date(operation.createdAt)))
        DetailRow("Modificată", dateFormat.format(Date(operation.updatedAt)))
        DetailRow(
            "Programată",
            if (operation.scheduledFor <= now) "Gata de trimis"
            else "Peste ${(operation.scheduledFor - now) / 1000} s",
        )
        DetailRow("Reîncercări", operation.retryCount.toString())
        DetailRow("Conflicte", operation.conflictStrategy)
        operation.lastError?.let {
            Text("Eroare: $it", style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
        Text(
            "Date: ${operation.operationData.take(100)}${if (operation.operationData.length > 100) "…" else ""}",
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

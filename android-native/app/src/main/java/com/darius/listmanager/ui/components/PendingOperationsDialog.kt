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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.ReviewKind
import com.darius.listmanager.ui.viewmodel.ReviewLine
import com.darius.listmanager.ui.viewmodel.SaveStateViewModel

@Composable
fun PendingOperationsDialog(
    onDismiss: () -> Unit,
    onSyncClick: () -> Unit,
    viewModel: SaveStateViewModel = viewModel(),
) {
    val lines by viewModel.lines.collectAsState()
    val message by viewModel.message.collectAsState()
    val isResolving by viewModel.isResolving.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.clearMessage() }
    }

    PendingOperationsDialogContent(
        lines = lines,
        message = message,
        isResolving = isResolving,
        onDismiss = onDismiss,
        onSync = {
            onSyncClick()
            onDismiss()
        },
        onKeepMine = viewModel::keepMine,
        onUseServer = viewModel::useServer,
        onRetry = viewModel::retry,
    )
}

@Composable
internal fun PendingOperationsDialogContent(
    lines: List<ReviewLine>,
    message: String?,
    isResolving: Boolean,
    onDismiss: () -> Unit,
    onSync: () -> Unit,
    onKeepMine: (Long) -> Unit,
    onUseServer: (Long) -> Unit,
    onRetry: (Long) -> Unit,
) {
    val needsDecision = lines.any { it.kind != ReviewKind.QUEUED }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (lines.isEmpty()) Icons.Rounded.CloudDone else Icons.Rounded.PendingActions,
                contentDescription = null,
            )
        },
        title = {
            Text(
                when {
                    lines.isEmpty() -> "Totul este salvat"
                    needsDecision -> "Ai de decis"
                    else -> "Modificări în așteptare"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    lines.isEmpty() -> Text("Toate modificările tale au ajuns pe server.")
                    needsDecision -> Text("Restul modificărilor așteaptă până rezolvi aceste produse. Nimic nu s-a pierdut.")
                    else -> Text("Sunt salvate pe telefon și se trimit automat.")
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isResolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (lines.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(lines, key = { it.id }) { line ->
                            ReviewLineCard(line, enabled = !isResolving, onKeepMine, onUseServer, onRetry)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (lines.isNotEmpty() && !needsDecision) {
                Button(onClick = onSync) {
                    Icon(Icons.Rounded.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Trimite acum")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Închide") }
        },
    )
}

@Composable
private fun ReviewLineCard(
    line: ReviewLine,
    enabled: Boolean,
    onKeepMine: (Long) -> Unit,
    onUseServer: (Long) -> Unit,
    onRetry: (Long) -> Unit,
) {
    val isHeld = line.kind != ReviewKind.QUEUED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isHeld) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isHeld) Icons.Rounded.WarningAmber else Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(line.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            when (line.kind) {
                ReviewKind.QUEUED -> Unit
                ReviewKind.CONFLICT -> {
                    Text(
                        "Altcineva a modificat acest produs între timp. Ce păstrăm?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onKeepMine(line.id) }, enabled = enabled) { Text("Varianta mea") }
                        OutlinedButton(onClick = { onUseServer(line.id) }, enabled = enabled) { Text("Varianta lor") }
                    }
                }
                ReviewKind.HELD -> {
                    Text(
                        "Serverul nu a acceptat modificarea.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRetry(line.id) }, enabled = enabled) { Text("Reîncearcă") }
                        OutlinedButton(onClick = { onUseServer(line.id) }, enabled = enabled) { Text("Renunță") }
                    }
                }
            }
        }
    }
}

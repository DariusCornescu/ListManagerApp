package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
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

@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
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
    val scheme = MaterialTheme.colorScheme

    SheetBody {
        Text(
            when {
                lines.isEmpty() -> "Totul este salvat"
                needsDecision -> "Ai de decis"
                else -> "Modificări în așteptare"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            when {
                lines.isEmpty() -> "Toate modificările tale au ajuns pe server."
                needsDecision -> "Restul modificărilor așteaptă până rezolvi aceste produse. Nimic nu s-a pierdut."
                else -> "Sunt salvate pe telefon și se trimit automat."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        message?.let { StatusBanner(it, modifier = Modifier.padding(bottom = 10.dp)) }
        if (isResolving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
        if (lines.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lines, key = { it.id }) { line ->
                    ReviewLineCard(line, enabled = !isResolving, onKeepMine, onUseServer, onRetry)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (lines.isNotEmpty() && !needsDecision) {
            AppPrimaryButton("Trimite acum", onSync)
            Spacer(Modifier.height(6.dp))
        }
        AppSecondaryButton("Închide", onDismiss)
    }
}

@Composable
private fun ReviewLineCard(
    line: ReviewLine,
    enabled: Boolean,
    onKeepMine: (Long) -> Unit,
    onUseServer: (Long) -> Unit,
    onRetry: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isHeld = line.kind != ReviewKind.QUEUED
    Surface(
        color = if (isHeld) scheme.errorContainer else scheme.surfaceVariant,
        contentColor = if (isHeld) scheme.onErrorContainer else scheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (isHeld) Icons.Rounded.WarningAmber else Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(line.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            when (line.kind) {
                ReviewKind.QUEUED -> Unit
                ReviewKind.CONFLICT -> {
                    Text(
                        "Altcineva a modificat acest produs între timp. Ce păstrăm?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSecondaryButton("Varianta mea", { onKeepMine(line.id) }, Modifier.weight(1f), enabled = enabled)
                        AppSecondaryButton("Varianta lor", { onUseServer(line.id) }, Modifier.weight(1f), enabled = enabled)
                    }
                }
                ReviewKind.OUTCOME_UNKNOWN -> {
                    Text(
                        "Nu știm dacă a ajuns pe server, iar repetarea ar putea șterge " +
                            "ce au adăugat alții între timp. Verifică lista de pe server.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    AppSecondaryButton("Arată lista de pe server", { onUseServer(line.id) }, enabled = enabled)
                }
                ReviewKind.HELD -> {
                    Text("Serverul nu a acceptat modificarea.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSecondaryButton("Reîncearcă", { onRetry(line.id) }, Modifier.weight(1f), enabled = enabled)
                        AppSecondaryButton("Renunță", { onUseServer(line.id) }, Modifier.weight(1f), enabled = enabled)
                    }
                }
            }
        }
    }
}

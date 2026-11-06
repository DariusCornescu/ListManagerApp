package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.sync.SaveState
import com.darius.listmanager.ui.viewmodel.SaveStateViewModel

/** Short label for the chip, and the full sentence read by TalkBack. */
internal fun SaveState.label(): String = when (this) {
    SaveState.Saved -> "Salvat"
    SaveState.SavedOffline -> "Salvat, offline"
    is SaveState.Saving -> "Se salvează…"
    is SaveState.StoredOnPhone -> "Pe telefon ($count)"
    is SaveState.NeedsAttention -> "De verificat ($count)"
}

internal fun SaveState.description(): String = when (this) {
    SaveState.Saved -> "Toate modificările sunt salvate pe server."
    SaveState.SavedOffline -> "Toate modificările sunt salvate. Nu ai conexiune acum."
    is SaveState.Saving -> "Se trimit $count modificări la server."
    is SaveState.StoredOnPhone -> "$count modificări sunt salvate pe telefon și se trimit automat când revine conexiunea."
    is SaveState.NeedsAttention -> "$count modificări au nevoie de decizia ta. Apasă pentru a le vedea."
}

@Composable
fun SaveStateChip(modifier: Modifier = Modifier, viewModel: SaveStateViewModel = viewModel()) {
    val state by viewModel.saveState.collectAsState()
    var showDetails by remember { mutableStateOf(false) }

    SaveStateChipContent(state, onClick = { showDetails = true }, modifier)

    if (showDetails) {
        PendingOperationsDialog(
            onDismiss = { showDetails = false },
            onSyncClick = viewModel::syncNow,
            viewModel = viewModel,
        )
    }
}

@Composable
internal fun SaveStateChipContent(state: SaveState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (icon: ImageVector, container: Color, content: Color) = when (state) {
        SaveState.Saved, SaveState.SavedOffline ->
            Triple(Icons.Rounded.CloudDone, scheme.secondaryContainer, scheme.onSecondaryContainer)
        is SaveState.Saving ->
            Triple(Icons.Rounded.CloudUpload, scheme.primaryContainer, scheme.onPrimaryContainer)
        is SaveState.StoredOnPhone ->
            Triple(Icons.Rounded.PhoneAndroid, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SaveState.NeedsAttention ->
            Triple(Icons.Rounded.WarningAmber, scheme.errorContainer, scheme.onErrorContainer)
    }
    val sentence = state.description()

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        modifier = modifier
            .heightIn(min = 40.dp)
            .semantics { contentDescription = sentence },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(state.label(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

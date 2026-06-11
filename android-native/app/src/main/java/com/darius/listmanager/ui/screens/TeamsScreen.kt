package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.network.TeamDTO
import com.darius.listmanager.ui.viewmodel.TeamsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(
    onBack: () -> Unit,
    onOpenTeam: (Long, String) -> Unit,
    viewModel: TeamsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // After create/join: offer to switch the app into that team's workspace.
    uiState.justJoinedTeam?.let { team ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeJustJoined() },
            title = { Text("Switch to ${team.name}?") },
            text = { Text("Work in this team's shared session now?") },
            confirmButton = {
                TextButton(onClick = { viewModel.switchToTeam(team) }) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.consumeJustJoined() }) { Text("Not now") }
            }
        )
    }

    if (showCreateDialog) {
        TeamNameDialog(
            title = "Create team",
            confirmLabel = "Create",
            onConfirm = { viewModel.createTeam(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (showJoinDialog) {
        TeamNameDialog(
            title = "Join with invite code",
            confirmLabel = "Join",
            placeholder = "Paste invite code",
            onConfirm = { viewModel.joinTeam(it); showJoinDialog = false },
            onDismiss = { showJoinDialog = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Teams") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Create team") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedButton(
                onClick = { showJoinDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Join with invite code")
            }

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.teams.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No teams yet. Create one or join with a code.")
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(uiState.teams, key = { it.id }) { team ->
                        TeamRow(team = team, onClick = { onOpenTeam(team.id, team.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamRow(team: TeamDTO, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Groups, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(team.name, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TeamNameDialog(
    title: String,
    confirmLabel: String,
    placeholder: String = "Team name",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

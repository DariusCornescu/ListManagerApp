package com.darius.listmanager.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.TeamDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDetailScreen(
    teamId: Long,
    teamName: String,
    onBack: () -> Unit,
    viewModel: TeamDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(teamId) { viewModel.load(teamId) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.leftTeam) {
        if (uiState.leftTeam) onBack()
    }

    // Open the Android share sheet when an invite code arrives.
    LaunchedEffect(uiState.inviteCode) {
        uiState.inviteCode?.let { code ->
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Join my ListManager team \"$teamName\" with this invite code:\n\n$code"
                )
                type = "text/plain"
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share invite code"))
            viewModel.consumeInviteCode()
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave $teamName?") },
            text = { Text("You will lose access to this team's shared session.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    uiState.myUserId?.let { viewModel.removeMember(it) }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(teamName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (uiState.amAdmin) {
                Button(onClick = { viewModel.generateInvite() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Invite — generate & share code")
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { confirmLeave = true },
                // Without our own user id "leave" can't target anyone — disable instead of no-op.
                enabled = uiState.myUserId != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Leave team")
            }

            Spacer(Modifier.height(16.dp))
            Text("Members", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.loadFailed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load members")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.load(teamId) }) { Text("Retry") }
                    }
                }
            } else {
                LazyColumn {
                    items(uiState.members, key = { it.id }) { member ->
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            headlineContent = {
                                Text(
                                    if (member.user_id == uiState.myUserId) "User ${member.user_id} (you)"
                                    else "User ${member.user_id}"
                                )
                            },
                            supportingContent = { Text(member.role) },
                            trailingContent = {
                                if (uiState.amAdmin && member.user_id != uiState.myUserId) {
                                    IconButton(onClick = { viewModel.removeMember(member.user_id) }) {
                                        Icon(
                                            Icons.Rounded.PersonRemove,
                                            contentDescription = "Remove member",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

package com.darius.listmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.websocket.OnlineUser
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.network.TeamDTO

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    workspaceName: String = "Personal",
    teams: List<TeamDTO> = emptyList(),
    username: String? = null,
    webSocketState: WebSocketState = WebSocketState.Disconnected,
    serverReachable: Boolean = false,
    onlineUsers: List<OnlineUser> = emptyList(),
    pendingCount: Int = 0,
    isSyncing: Boolean = false,
    onSyncClick: () -> Unit = {},
    onSwitchWorkspace: (Workspace) -> Unit = {},
) {
    var switcherExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    ModalDrawerSheet {
        // Settings/About stay pinned to the bottom while everything above scrolls,
        // so a long presence list or a small screen can't push nav items off-screen.
        Column(Modifier.fillMaxHeight()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                Spacer(Modifier.height(24.dp))

                Text(
                    "List Manager",
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // ===== Workspace switcher =====
                Box(Modifier.padding(horizontal = 12.dp)) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                        label = {
                            Column {
                                Text("Workspace", style = MaterialTheme.typography.labelSmall)
                                Text(workspaceName, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        selected = false,
                        onClick = { switcherExpanded = true }
                    )
                    DropdownMenu(
                        expanded = switcherExpanded,
                        onDismissRequest = { switcherExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Personal") },
                            onClick = {
                                switcherExpanded = false
                                onSwitchWorkspace(Workspace.Personal)
                            }
                        )
                        teams.forEach { team ->
                            DropdownMenuItem(
                                text = { Text(team.name) },
                                onClick = {
                                    switcherExpanded = false
                                    onSwitchWorkspace(Workspace.Team(team.id, team.name))
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Manage teams…") },
                            onClick = {
                                switcherExpanded = false
                                onNavigate("teams")
                            }
                        )
                    }
                }

                // Connection & sync status (the old banner's three states, moved here):
                // live (WebSocket) / server reachable (REST fine, no live socket) / offline.
                val live = webSocketState is WebSocketState.Connected
                Box(Modifier.padding(horizontal = 12.dp)) {
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                when {
                                    live -> Icons.Rounded.CloudDone
                                    serverReachable -> Icons.Rounded.Cloud
                                    else -> Icons.Rounded.CloudOff
                                },
                                contentDescription = null,
                                tint = when {
                                    live -> MaterialTheme.colorScheme.secondary
                                    serverReachable -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                        },
                        label = {
                            Column {
                                Text(
                                    when {
                                        live -> "Conectat live"
                                        serverReachable -> "Server disponibil"
                                        else -> "Offline"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    when {
                                        isSyncing -> "Sincronizare…"
                                        pendingCount > 0 -> "$pendingCount în așteptare — apasă pentru sync"
                                        live -> username?.let { "Sincronizat • $it" } ?: "Sincronizare activă"
                                        serverReachable -> "Se reconectează live…"
                                        else -> "Datele se salvează local"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        selected = false,
                        onClick = onSyncClick
                    )
                }

                // ===== Online now (presence) =====
                if (onlineUsers.isNotEmpty()) {
                    Text(
                        "Online acum",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 8.dp)
                    )
                    onlineUsers.forEach { user ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 28.dp, top = 6.dp, end = 28.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.secondary, shape = CircleShape)
                            )
                            Text(
                                if (user.username == username) "${user.username} (tu)" else user.username,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DrawerItem(Icons.Rounded.Home, "Home") { onNavigate("home") }
                DrawerItem(Icons.Rounded.ShoppingCart, "Current Session") { onNavigate("session") }
                DrawerItem(Icons.Rounded.Warning, "Unknown Products") { onNavigate("unknown") }
                DrawerItem(Icons.Rounded.Inventory, "Catalog") { onNavigate("catalog") }
                DrawerItem(Icons.Rounded.FactCheck, "Liste inventar") { onNavigate("inventory") }
                DrawerItem(Icons.Rounded.Groups, "Teams") { onNavigate("teams") }
                DrawerItem(Icons.Rounded.Description, "Generated PDFs") { onNavigate("pdfs") }
            }

            // Pinned footer (outside the scroll) — always reachable.
            HorizontalDivider()
            DrawerItem(Icons.Rounded.Settings, "Settings") { onNavigate("settings") }
            DrawerItem(Icons.Rounded.Info, "About") { onNavigate("about") }
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

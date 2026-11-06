package com.darius.listmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
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

internal data class DrawerStatus(val title: String, val detail: String)

/** Live socket, server reachable without a live socket, or offline. Syncing and waiting work come first. */
internal fun drawerStatus(
    live: Boolean,
    serverReachable: Boolean,
    isSyncing: Boolean,
    pendingCount: Int,
    username: String?,
): DrawerStatus = DrawerStatus(
    title = when {
        live -> "Conectat live"
        serverReachable -> "Server disponibil"
        else -> "Offline"
    },
    detail = when {
        isSyncing -> "Se sincronizează…"
        pendingCount > 0 -> "$pendingCount în așteptare. Apasă ca să trimiți."
        live -> username?.let { "Sincronizat · $it" } ?: "Sincronizat"
        serverReachable -> "Se reconectează…"
        else -> "Datele se salvează pe telefon"
    },
)

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    workspaceName: String = "Personal",
    currentRoute: String? = null,
    username: String? = null,
    webSocketState: WebSocketState = WebSocketState.Disconnected,
    serverReachable: Boolean = false,
    onlineUsers: List<OnlineUser> = emptyList(),
    pendingCount: Int = 0,
    isSyncing: Boolean = false,
    onSyncClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme

    ModalDrawerSheet(drawerContainerColor = scheme.surface) {
        // Settings and About stay pinned to the bottom while everything above scrolls,
        // so a long presence list or a small screen can't push them off-screen.
        Column(Modifier.fillMaxHeight()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 14.dp)) {
                    Text("ListManager", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(workspaceName, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }

                ConnectionRow(
                    live = webSocketState is WebSocketState.Connected,
                    serverReachable = serverReachable,
                    status = drawerStatus(
                        live = webSocketState is WebSocketState.Connected,
                        serverReachable = serverReachable,
                        isSyncing = isSyncing,
                        pendingCount = pendingCount,
                        username = username,
                    ),
                    onClick = onSyncClick,
                )

                if (onlineUsers.isNotEmpty()) {
                    Text(
                        "Online acum",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                    )
                    onlineUsers.forEach { user ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(scheme.secondary, CircleShape)
                            )
                            Text(
                                if (user.username == username) "${user.username} (tu)" else user.username,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                HorizontalDivider(color = scheme.outline, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))

                DrawerItem(Icons.Rounded.Home, "Acasă", "home", currentRoute, onNavigate)
                DrawerItem(Icons.Rounded.ShoppingCart, "Lista curentă", "session", currentRoute, onNavigate)
                DrawerItem(Icons.Rounded.Checklist, "De rezolvat", "resolve", currentRoute, onNavigate)
                DrawerItem(Icons.Rounded.FactCheck, "Inventar", "inventory", currentRoute, onNavigate)
                DrawerItem(Icons.Rounded.Inventory2, "Catalog", "catalog", currentRoute, onNavigate)
                DrawerItem(Icons.AutoMirrored.Rounded.Article, "PDF-uri", "pdfs", currentRoute, onNavigate)
                DrawerItem(Icons.Rounded.Groups, "Echipe", "teams", currentRoute, onNavigate)
            }

            HorizontalDivider(color = scheme.outline)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                DrawerItem(Icons.Rounded.Settings, "Setări", "settings", currentRoute, onNavigate)
                DrawerItem(Icons.Rounded.Info, "Despre", "about", currentRoute, onNavigate)
            }
        }
    }
}

@Composable
private fun ConnectionRow(live: Boolean, serverReachable: Boolean, status: DrawerStatus, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when {
        live -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Rounded.CloudDone)
        serverReachable -> Triple(scheme.surfaceVariant, scheme.onSurface, Icons.Rounded.Cloud)
        else -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Rounded.CloudOff)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column {
                Text(status.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(status.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    route: String,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, fontWeight = FontWeight.SemiBold) },
        selected = route == currentRoute,
        onClick = { onNavigate(route) },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = scheme.primaryContainer,
            selectedIconColor = scheme.onPrimaryContainer,
            selectedTextColor = scheme.onPrimaryContainer,
            unselectedContainerColor = scheme.surface,
        ),
    )
}

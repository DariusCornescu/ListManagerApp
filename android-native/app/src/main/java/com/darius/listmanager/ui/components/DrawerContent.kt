package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.network.TeamDTO

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    workspaceName: String = "Personal",
    teams: List<TeamDTO> = emptyList(),
    onSwitchWorkspace: (Workspace) -> Unit = {},
) {
    var switcherExpanded by remember { mutableStateOf(false) }

    ModalDrawerSheet {
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        DrawerItem(Icons.Rounded.Home, "Home") { onNavigate("home") }
        DrawerItem(Icons.Rounded.ShoppingCart, "Current Session") { onNavigate("session") }
        DrawerItem(Icons.Rounded.Warning, "Unknown Products") { onNavigate("unknown") }
        DrawerItem(Icons.Rounded.Inventory, "Catalog") { onNavigate("catalog") }
        DrawerItem(Icons.Rounded.Groups, "Teams") { onNavigate("teams") }
        DrawerItem(Icons.Rounded.Description, "Generated PDFs") { onNavigate("pdfs") }

        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        DrawerItem(Icons.Rounded.Settings, "Settings") { onNavigate("settings") }
        DrawerItem(Icons.Rounded.Info, "About") { onNavigate("about") }
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

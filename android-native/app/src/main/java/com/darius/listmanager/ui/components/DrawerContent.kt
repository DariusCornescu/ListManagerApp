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

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(24.dp))

        Text(
            "List Manager",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        
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
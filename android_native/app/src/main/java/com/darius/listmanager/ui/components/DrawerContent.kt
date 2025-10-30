package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DrawerContent(onNavigate: (String) -> Unit) {
    ModalDrawerSheet {
        Spacer(Modifier.height(24.dp))
        Text(
            "Manager Liste",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        DrawerItem(Icons.Rounded.Home, "Acasă") { onNavigate("home") }
        DrawerItem(Icons.Rounded.ShoppingCart, "Sesiune Curentă") { onNavigate("session") }
        DrawerItem(Icons.Rounded.Warning, "Produse Necunoscute") { onNavigate("unknown") }
        DrawerItem(Icons.Rounded.Inventory, "Catalog") { onNavigate("catalog") }
        DrawerItem(Icons.Rounded.Description, "PDF-uri Generate") { onNavigate("pdfs") }
        DrawerItem(Icons.Rounded.Settings, "Setări") { onNavigate("settings") }
        DrawerItem(Icons.Rounded.Info, "Despre") { onNavigate("about") }
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
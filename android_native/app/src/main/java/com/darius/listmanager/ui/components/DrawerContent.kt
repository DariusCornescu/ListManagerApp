package com.darius.listmanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.AuthViewModel

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    authViewModel: AuthViewModel = viewModel()
) {
    val authState by authViewModel.uiState.collectAsState()

    ModalDrawerSheet {
        Spacer(Modifier.height(24.dp))

        // App title
        Text(
            "List Manager",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Login/Account section at the top
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (authState.isAuthenticated)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            if (authState.isAuthenticated) {
                // Logged in - show user info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                authState.currentUser?.username ?: "User",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Synced",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onNavigate("profile") },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            Icon(Icons.Rounded.Person, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Profile", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = { authViewModel.logout() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            Icon(Icons.Rounded.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Logout", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                // Not logged in - show login button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                "Offline Mode",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Login to sync your data",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = { onNavigate("auth") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Login, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Login / Register")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Navigation items
        DrawerItem(Icons.Rounded.Home, "Home") { onNavigate("home") }
        DrawerItem(Icons.Rounded.ShoppingCart, "Current Session") { onNavigate("session") }
        DrawerItem(Icons.Rounded.Warning, "Unknown Products") { onNavigate("unknown") }
        DrawerItem(Icons.Rounded.Inventory, "Catalog") { onNavigate("catalog") }
        DrawerItem(Icons.Rounded.Description, "Generated PDFs") { onNavigate("pdfs") }
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
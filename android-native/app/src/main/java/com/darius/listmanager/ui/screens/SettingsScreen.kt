package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.darius.listmanager.ui.components.SettingItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSyncDebug: () -> Unit = {}
) {
    var useOfflineStt by remember { mutableStateOf(false) }
    var hapticFeedback by remember { mutableStateOf(true) }
    var darkTheme by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            SettingItem(
                title = "Dark theme",
                subtitle = "Note: Currently visual only, needs app restart to fully apply",
                checked = darkTheme,
                onCheckedChange = { darkTheme = it }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Voice & Feedback",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            SettingItem(
                title = "Use offline STT",
                subtitle = "Speech-to-text without internet connection",
                checked = useOfflineStt,
                onCheckedChange = { useOfflineStt = it }
            )
            SettingItem(
                title = "Haptic feedback",
                subtitle = "Vibration on button presses",
                checked = hapticFeedback,
                onCheckedChange = { hapticFeedback = it }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text(
                "Developer",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            
            // Sync Debug button
            Card(
                onClick = onNavigateToSyncDebug,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Column {
                            Text(
                                "Sync Debug",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Test și debug pentru sincronizare offline/online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
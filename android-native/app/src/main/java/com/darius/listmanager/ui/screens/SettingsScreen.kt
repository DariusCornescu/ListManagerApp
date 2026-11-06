package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.BuildConfig
import com.darius.listmanager.data.settings.AppSettings
import com.darius.listmanager.data.settings.ThemeMode
import com.darius.listmanager.ui.components.AppTopBar

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSyncDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.getInstance(context) }
    val themeMode by settings.themeMode.collectAsState()
    val haptics by settings.hapticsEnabled.collectAsState()

    Scaffold(
        topBar = { AppTopBar(title = "Setări", onBack = onBack) },
    ) { padding ->
        SettingsContent(
            themeMode = themeMode,
            onThemeMode = settings::setThemeMode,
            hapticsEnabled = haptics,
            onHapticsChange = settings::setHapticsEnabled,
            showDeveloper = BuildConfig.DEBUG,
            onOpenDiagnostics = onNavigateToSyncDebug,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    hapticsEnabled: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    showDeveloper: Boolean,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(
        ThemeMode.SYSTEM to "Sistem",
        ThemeMode.DARK to "Întunecat",
        ThemeMode.LIGHT to "Deschis",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        SettingsHeading("Aspect")
        Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Temă", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = mode == themeMode,
                            onClick = { onThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = scheme.primary,
                                activeContentColor = scheme.onPrimary,
                            ),
                            icon = {},
                            label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                        )
                    }
                }
            }
        }

        SettingsHeading("Vibrații")
        Surface(color = scheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Vibrații", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "La butoane, la înregistrare și la ștergere",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(checked = hapticsEnabled, onCheckedChange = onHapticsChange)
            }
        }

        if (showDeveloper) {
            SettingsHeading("Dezvoltator")
            Surface(
                onClick = onOpenDiagnostics,
                color = scheme.surface,
                contentColor = scheme.onSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Diagnostic sincronizare", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Apare doar în build-urile de test",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 8.dp),
    )
}

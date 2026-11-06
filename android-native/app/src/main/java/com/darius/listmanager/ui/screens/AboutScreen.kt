package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darius.listmanager.BuildConfig
import com.darius.listmanager.ui.components.AppTopBar

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = { AppTopBar(title = "Despre", onBack = onBack) },
    ) { padding ->
        AboutContent(BuildConfig.VERSION_NAME, Modifier.padding(padding))
    }
}

@Composable
internal fun AboutContent(version: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, bottom = 96.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = scheme.primary,
            contentColor = scheme.onPrimary,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(38.dp))
            }
        }
        Text("ListManager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Versiunea $version", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Text(
            "Liste de cumpărături dictate cu vocea, comune pentru toată echipa.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.util.RankedProduct

internal enum class RecordAction { START, STOP, BUSY }

/** Stop must work while the recognizer is mid-phrase, not only between phrases. */
internal fun recordActionFor(speechState: SpeechState, isProcessing: Boolean): RecordAction = when {
    speechState is SpeechState.Listening || speechState is SpeechState.Partial -> RecordAction.STOP
    speechState is SpeechState.Final || isProcessing -> RecordAction.BUSY
    else -> RecordAction.START
}

/** The one thing a first-time user needs to find on this screen. */
@Composable
internal fun RecordButton(
    action: RecordAction,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (action) {
        RecordAction.START -> scheme.primary
        RecordAction.STOP -> scheme.error
        RecordAction.BUSY -> scheme.surfaceVariant
    }
    val content = when (action) {
        RecordAction.START -> scheme.onPrimary
        RecordAction.STOP -> scheme.onError
        RecordAction.BUSY -> scheme.onSurfaceVariant
    }
    val description = when (action) {
        RecordAction.START -> "Începe înregistrarea"
        RecordAction.STOP -> "Oprește înregistrarea"
        RecordAction.BUSY -> "Se procesează"
    }

    Surface(
        onClick = {
            when (action) {
                RecordAction.START -> onStart()
                RecordAction.STOP -> onStop()
                RecordAction.BUSY -> Unit
            }
        },
        enabled = action != RecordAction.BUSY,
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(RecordButtonSize)
            .semantics { contentDescription = description },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (action == RecordAction.BUSY) {
                CircularProgressIndicator(
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 4.dp,
                    color = content,
                )
            } else {
                Icon(
                    if (action == RecordAction.STOP) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (action) {
                    RecordAction.START -> "Înregistrează"
                    RecordAction.STOP -> "Oprește"
                    RecordAction.BUSY -> "Procesez…"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val RecordButtonSize = 220.dp

@Composable
internal fun HomeSpeechStatus(speechState: SpeechState, addedCount: Int) {
    Text(
        when (speechState) {
            is SpeechState.Idle -> "Apasă și spune produsele"
            is SpeechState.Listening -> "Te ascult..."
            is SpeechState.Partial -> "Am auzit: ${speechState.text}"
            is SpeechState.Final -> "Procesez: ${speechState.text}"
            is SpeechState.Error -> "Eroare: ${speechState.message}"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    if (speechState is SpeechState.Listening || addedCount > 0) {
        Text(
            "Adăugate în sesiune: $addedCount",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun HomeSuggestionsCard(
    suggestions: List<RankedProduct>,
    onClear: () -> Unit,
    onPick: (RankedProduct) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Ai vrut sa zici?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        "Clear suggestions",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider()

            suggestions.forEach { rankedProduct ->
                SuggestionItem(
                    productName = rankedProduct.product.name,
                    score = rankedProduct.score,
                    onClick = { onPick(rankedProduct) }
                )
            }
        }
    }
}

/** Compact status row: what needs attention, at a glance. */
@Composable
internal fun HomeAttentionRow(
    reviewCount: Int,
    unknownCount: Int,
    onOpenReview: () -> Unit,
    onOpenUnknown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HomeStatCard(
            title = "De verificat",
            count = reviewCount,
            icon = Icons.Rounded.Checklist,
            onClick = onOpenReview,
            modifier = Modifier.weight(1f)
        )
        HomeStatCard(
            title = "Necunoscute",
            count = unknownCount,
            icon = Icons.Rounded.Help,
            onClick = onOpenUnknown,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HomeStatCard(
    title: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val highlighted = count > 0
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon, contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuggestionItem(
    productName: String,
    score: Double,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = productName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    text = "${(score * 100).toInt().coerceAtMost(100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

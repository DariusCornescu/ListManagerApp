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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.util.RankedProduct

/** The mic control and its processing overlay, the hero of the screen. */
@Composable
internal fun HomeMicButton(speechState: SpeechState, isProcessing: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = when (speechState) {
                is SpeechState.Listening -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxSize(),
            onClick = {

                when (speechState) {
                    is SpeechState.Idle -> {
                        onStart()
                    }
                    is SpeechState.Listening -> {
                        onStop()
                    }
                    is SpeechState.Partial, is SpeechState.Final -> {
                    }
                    is SpeechState.Error -> {
                        onStart()
                    }
                }
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(72.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        if (speechState is SpeechState.Listening)
                            Icons.Rounded.Stop
                        else
                            Icons.Rounded.Mic,
                        contentDescription = "Microphone",
                        modifier = Modifier.size(72.dp),
                        tint = when (speechState) {
                            is SpeechState.Listening -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // Pulsing ring when processing
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(140.dp)
                    .padding(8.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
internal fun HomeSpeechStatus(speechState: SpeechState, addedCount: Int) {
    Text(
        when (val state = speechState) {
            is SpeechState.Idle -> "Apasă pentru a începe înregistrarea"
            is SpeechState.Listening -> "Ascultare..."
            is SpeechState.Partial -> "Am auzit: ${state.text}"
            is SpeechState.Final -> "Procesez: ${state.text}"
            is SpeechState.Error -> "Eroare: ${state.message}"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (speechState is SpeechState.Listening || addedCount > 0) {
        Text(
            "Adăugate în sesiune: ${addedCount}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun HomeProcessingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Procesez cererea…",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
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
                    text = "$score%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

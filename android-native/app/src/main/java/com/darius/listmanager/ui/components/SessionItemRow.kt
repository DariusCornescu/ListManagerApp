package com.darius.listmanager.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.local.dao.SessionItemWithProduct
import com.darius.listmanager.ui.theme.Motion

/** Swipe left to delete. The minus button also deletes at quantity 1, for anyone who can't swipe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionItemRow(
    item: SessionItemWithProduct,
    onQuantityChange: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> value == SwipeToDismissBoxValue.EndToStart },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    // Not in confirmValueChange: Material calls that many times during one swipe.
    val latestOnDelete by rememberUpdatedState(onDelete)
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) latestOnDelete()
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            Surface(color = scheme.error, contentColor = scheme.onError, shape = shape, modifier = Modifier.fillMaxSize()) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Șterge", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
        },
    ) {
        Surface(color = scheme.surface, contentColor = scheme.onSurface, shape = shape) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    item.productName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (item.quantity > 1) {
                    AppIconButton(
                        Icons.Rounded.Remove,
                        "Scade cantitatea pentru ${item.productName}",
                        { onQuantityChange(item.quantity - 1) },
                        tint = scheme.onSurfaceVariant,
                    )
                } else {
                    AppIconButton(
                        Icons.Rounded.Delete,
                        "Șterge ${item.productName}",
                        onDelete,
                        tint = scheme.onSurfaceVariant,
                    )
                }
                AnimatedContent(
                    targetState = item.quantity,
                    transitionSpec = {
                        val direction = if (targetState > initialState) -1 else 1
                        (slideInVertically(tween(Motion.ShortMillis)) { direction * it / 2 } + fadeIn(tween(Motion.ShortMillis))) togetherWith
                            fadeOut(tween(Motion.ShortMillis / 2))
                    },
                    label = "itemQuantity",
                ) { quantity ->
                    Text(
                        "$quantity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 24.dp),
                    )
                }
                AppIconButton(
                    Icons.Rounded.Add,
                    "Crește cantitatea pentru ${item.productName}",
                    { onQuantityChange(item.quantity + 1) },
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

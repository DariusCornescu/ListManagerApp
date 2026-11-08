package com.darius.listmanager.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTextButton
import com.darius.listmanager.ui.components.SheetBody
import com.darius.listmanager.ui.components.pressScale
import com.darius.listmanager.ui.components.rememberHaptics
import com.darius.listmanager.ui.theme.AppTextStyles
import com.darius.listmanager.ui.theme.Motion
import com.darius.listmanager.ui.theme.rememberReducedMotion
import com.darius.listmanager.ui.viewmodel.LastAdded
import com.darius.listmanager.ui.viewmodel.SuggestionPrompt
import com.darius.listmanager.util.RankedProduct

internal enum class RecordAction { START, STOP, BUSY }

/** Stop must work while the recognizer is mid-phrase, not only between phrases. */
internal fun recordActionFor(speechState: SpeechState, isProcessing: Boolean): RecordAction = when {
    speechState is SpeechState.Listening || speechState is SpeechState.Partial -> RecordAction.STOP
    speechState is SpeechState.Final || isProcessing -> RecordAction.BUSY
    else -> RecordAction.START
}

/** Gold circle at rest. While listening it squares off, turns red and pulses. */
@Composable
internal fun RecordButton(
    action: RecordAction,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val listening = action == RecordAction.STOP
    val corner by animateDpAsState(if (listening) 28.dp else 42.dp, tween(Motion.ShortMillis), label = "recordCorner")
    val container by animateColorAsState(
        when (action) {
            RecordAction.START -> scheme.primary
            RecordAction.STOP -> scheme.error
            RecordAction.BUSY -> scheme.surfaceVariant
        },
        tween(Motion.ShortMillis),
        label = "recordColor",
    )
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
    val source = remember { MutableInteractionSource() }
    val haptic = rememberHaptics()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (listening && !reduced) PulseRings(scheme.error, corner.value)
            Surface(
                onClick = {
                    haptic()
                    when (action) {
                        RecordAction.START -> onStart()
                        RecordAction.STOP -> onStop()
                        RecordAction.BUSY -> Unit
                    }
                },
                enabled = action != RecordAction.BUSY,
                shape = RoundedCornerShape(corner),
                color = container,
                contentColor = content,
                shadowElevation = 6.dp,
                interactionSource = source,
                modifier = Modifier
                    .pressScale(source, pressedScale = 0.94f)
                    .size(RecordButtonSize)
                    .semantics { contentDescription = description },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        action == RecordAction.BUSY && !reduced -> CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 3.dp,
                            color = content,
                        )
                        else -> Icon(
                            if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
            }
        }
        Text(
            when (action) {
                RecordAction.START -> "Înregistrează"
                RecordAction.STOP -> "Oprește"
                RecordAction.BUSY -> "Procesez…"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val RecordButtonSize = 84.dp

@Composable
private fun PulseRings(color: Color, cornerDp: Float) {
    val transition = rememberInfiniteTransition(label = "recordRings")
    val spec = { delay: Int ->
        infiniteRepeatable<Float>(
            tween(1700, easing = Motion.Standard),
            RepeatMode.Restart,
            StartOffset(delay),
        )
    }
    val first by transition.animateFloat(0f, 1f, spec(0), label = "ringOne")
    val second by transition.animateFloat(0f, 1f, spec(850), label = "ringTwo")

    Canvas(Modifier.size(RecordButtonSize)) {
        val radius = cornerDp.dp.toPx()
        for (progress in listOf(first, second)) {
            val scale = 1f + 0.55f * progress
            val ring = Size(size.width * scale, size.height * scale)
            drawRoundRect(
                color = color.copy(alpha = 0.75f * (1f - progress)),
                topLeft = Offset((size.width - ring.width) / 2, (size.height - ring.height) / 2),
                size = ring,
                cornerRadius = CornerRadius(radius * scale),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

internal enum class StageKind { IDLE, LISTENING, HEARD, ERROR }

internal data class HomeStageState(
    val kind: StageKind,
    val text: String? = null,
    val seeking: Boolean = false,
)

internal fun homeStageFor(speechState: SpeechState, isProcessing: Boolean): HomeStageState = when (speechState) {
    is SpeechState.Error -> HomeStageState(StageKind.ERROR, speechState.message)
    is SpeechState.Final -> HomeStageState(StageKind.HEARD, speechState.text, seeking = true)
    is SpeechState.Partial -> HomeStageState(StageKind.HEARD, speechState.text, seeking = isProcessing)
    SpeechState.Listening -> HomeStageState(StageKind.LISTENING, seeking = isProcessing)
    SpeechState.Idle ->
        if (isProcessing) HomeStageState(StageKind.LISTENING, seeking = true) else HomeStageState(StageKind.IDLE)
}

/** What's happening right now, in large type at the top of the screen. */
@Composable
internal fun HomeStage(
    speechState: SpeechState,
    isProcessing: Boolean,
    addedCount: Int,
    modifier: Modifier = Modifier,
) {
    val stage = homeStageFor(speechState, isProcessing)
    AnimatedContent(
        targetState = stage,
        // Only a change of kind animates. Otherwise each new word would replay the transition.
        contentKey = { it.kind },
        transitionSpec = {
            (fadeIn(tween(Motion.ShortMillis)) + slideInVertically(tween(Motion.ShortMillis)) { it / 8 }) togetherWith
                fadeOut(tween(Motion.ShortMillis / 2))
        },
        label = "homeStage",
        modifier = modifier,
    ) { current ->
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            when (current.kind) {
                StageKind.IDLE -> {
                    Text("Apasă și spune produsele", style = AppTextStyles.BigMessage)
                    StageNote(
                        when (addedCount) {
                            0 -> "Poți dicta mai multe produse odată."
                            1 -> "Ai adăugat un produs."
                            else -> "Ai adăugat $addedCount produse."
                        }
                    )
                }
                StageKind.LISTENING -> {
                    SoundWave()
                    Text("Te ascult…", style = AppTextStyles.BigMessage)
                    if (current.seeking) SeekingChip()
                }
                StageKind.HEARD -> {
                    SoundWave()
                    Text(
                        current.text.orEmpty(),
                        style = AppTextStyles.BigMessage,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (current.seeking) SeekingChip()
                }
                StageKind.ERROR -> {
                    Text("Nu am putut asculta", style = AppTextStyles.BigMessage)
                    StageNote(current.text.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun StageNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SeekingChip() {
    val scheme = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    Surface(shape = CircleShape, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            if (!reduced) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = scheme.onPrimaryContainer,
                )
            }
            Text("Caut în catalog…", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Decoration only. It shows the phone is listening, not the actual volume. */
@Composable
private fun SoundWave() {
    val color = MaterialTheme.colorScheme.error
    val reduced = rememberReducedMotion()
    val delays = listOf(0, 120, 280, 80, 220)
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(34.dp),
    ) {
        if (reduced) {
            delays.forEach { _ -> WaveBar(color, 0.5f) }
        } else {
            val transition = rememberInfiniteTransition(label = "soundWave")
            delays.forEach { delay ->
                val scale by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(500, easing = LinearEasing),
                        RepeatMode.Reverse,
                        StartOffset(delay),
                    ),
                    label = "waveBar",
                )
                WaveBar(color, scale)
            }
        }
    }
}

@Composable
private fun WaveBar(color: Color, scale: Float) {
    Box(
        Modifier
            .width(5.dp)
            .fillMaxHeight()
            .graphicsLayer { scaleY = scale }
            .background(color, CircleShape)
    )
}

/** Confirms the last add and offers a way back, for as long as the view model allows it. */
@Composable
internal fun LastAddedArea(
    lastAdded: LastAdded?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = lastAdded,
        contentKey = { it?.token },
        transitionSpec = {
            (fadeIn(tween(Motion.ShortMillis)) + slideInVertically(tween(Motion.ScreenMillis, easing = Motion.Standard)) { it / 2 }) togetherWith
                fadeOut(tween(Motion.ShortMillis / 2)) using SizeTransform(clip = false)
        },
        label = "lastAdded",
        modifier = modifier,
    ) { item ->
        if (item != null) LastAddedCard(item, canUndo, onUndo)
    }
}

@Composable
private fun LastAddedCard(item: LastAdded, canUndo: Boolean, onUndo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surface,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .heightIn(min = 72.dp)
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${item.name} × ${item.quantity}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Adăugat în listă", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (canUndo) AppTextButton("Anulează", onUndo)
            }
            if (canUndo) UndoCountdown(item.token, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** Runs out when the undo window does, so the button doesn't vanish without warning. */
@Composable
private fun UndoCountdown(key: Long, modifier: Modifier) {
    val left = remember(key) { Animatable(1f) }
    LaunchedEffect(key) { left.animateTo(0f, tween(LastAdded.UNDO_WINDOW_MILLIS.toInt(), easing = LinearEasing)) }
    Box(
        modifier
            .fillMaxWidth(left.value)
            .height(3.dp)
            .background(MaterialTheme.colorScheme.primary)
    )
}

/** The bar within thumb reach: what needs a decision, recording, and the list. */
/** One phrase per tap, or listening until Stop. Sits above the dock, within thumb reach. */
@Composable
internal fun DictationModeRow(
    continuous: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Dictare continuă", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (continuous) "Ascultă până apeși Oprește" else "Se oprește după o frază",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = continuous, onCheckedChange = onChange)
    }
}

@Composable
internal fun HomeDock(
    action: RecordAction,
    resolveCount: Int,
    itemCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenResolve: () -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outline)
        )
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 16.dp),
        ) {
            DockButton(
                icon = Icons.Rounded.Checklist,
                label = "De rezolvat",
                description = "De rezolvat: $resolveCount",
                count = resolveCount,
                accent = false,
                onClick = onOpenResolve,
                modifier = Modifier.weight(1f),
            )
            RecordButton(action, onStart, onStop, Modifier.weight(1f))
            DockButton(
                icon = Icons.Rounded.ShoppingCart,
                label = "Lista",
                description = "Lista curentă: $itemCount produse",
                count = itemCount,
                accent = true,
                onClick = onOpenList,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    description: String,
    count: Int,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            contentColor = scheme.onSurfaceVariant,
            interactionSource = source,
            modifier = Modifier
                .pressScale(source)
                .width(92.dp)
                .heightIn(min = 76.dp)
                .semantics { contentDescription = description },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            ) {
                Box {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    if (count > 0) {
                        CountBadge(count, accent, Modifier.offset(x = 20.dp, y = (-10).dp))
                    }
                }
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int, accent: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val scale = remember { Animatable(1f) }
    var shown by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count != shown) {
            shown = count
            scale.snapTo(1.4f)
            scale.animateTo(1f, spring(dampingRatio = 0.45f))
        }
    }
    Surface(
        shape = CircleShape,
        color = if (accent) scheme.primary else scheme.surfaceVariant,
        contentColor = if (accent) scheme.onPrimary else scheme.onSurface,
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** The only potentially long choice on Home. Closing it leaves the phrase in "De rezolvat". */
@Composable
internal fun SuggestionSheetContent(
    prompt: SuggestionPrompt,
    onPick: (RankedProduct) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SheetBody {
        Text("Ai vrut să zici?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text(
            "Am auzit „${prompt.spokenText}”.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )
        prompt.candidates.forEach { candidate ->
            val source = remember { MutableInteractionSource() }
            Surface(
                onClick = { onPick(candidate) },
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceVariant,
                contentColor = scheme.onSurface,
                interactionSource = source,
                modifier = Modifier
                    .pressScale(source)
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 60.dp)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        candidate.product.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }
        }
        AppSecondaryButton("Niciunul", onDismiss)
    }
}

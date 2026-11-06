package com.darius.listmanager.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darius.listmanager.ui.theme.AppTextStyles
import com.darius.listmanager.ui.theme.Motion

/** The press response every button in the app shares. */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = Motion.PressedScale,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Motion.PressMillis, easing = Motion.Standard),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    loadingText: String?,
    icon: ImageVector?,
    height: Dp,
    colors: ButtonColors,
    textStyle: TextStyle,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .pressScale(source)
            .fillMaxWidth()
            .height(height),
        enabled = enabled && !loading,
        shape = CircleShape,
        colors = colors,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp),
        interactionSource = source,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                if (loadingText != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(loadingText, style = textStyle)
                }
            }
            else -> {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = textStyle)
            }
        }
    }
}

/** The screen's main action. A screen should have at most one. */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    AppButton(
        text, onClick, modifier, enabled, loading, loadingText, icon,
        height = 56.dp,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.surfaceVariant,
            disabledContentColor = scheme.onSurfaceVariant,
        ),
        textStyle = AppTextStyles.ButtonLabel,
    )
}

@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    AppButton(
        text, onClick, modifier, enabled, loading, null, icon,
        height = 48.dp,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurface,
            disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = scheme.onSurfaceVariant,
        ),
        textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
    )
}

/** Starts something destructive, such as "Deconectare". The confirmation comes after it. */
@Composable
fun AppDangerSoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    AppButton(
        text, onClick, modifier, enabled, false, null, null,
        height = 48.dp,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        ),
        textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
    )
}

/** Confirms something destructive. Only used inside a confirmation. */
@Composable
fun AppDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    AppButton(
        text, onClick, modifier, true, loading, null, null,
        height = 52.dp,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.error,
            contentColor = scheme.onError,
        ),
        textStyle = AppTextStyles.ButtonLabel,
    )
}

@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
) {
    val source = remember { MutableInteractionSource() }
    val resolved = if (color == Color.Unspecified) MaterialTheme.colorScheme.onPrimaryContainer else color
    TextButton(
        onClick = onClick,
        modifier = modifier.pressScale(source),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = resolved),
        interactionSource = source,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    }
}

/** An icon needs a description, because it's the only label the button has. */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.pressScale(source),
        enabled = enabled,
        interactionSource = source,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

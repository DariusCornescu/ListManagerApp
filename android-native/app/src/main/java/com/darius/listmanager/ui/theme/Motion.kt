package com.darius.listmanager.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One set of timings, so every screen moves the same way. */
object Motion {
    const val PressMillis = 140
    const val ShortMillis = 200
    const val ScreenMillis = 300
    const val PressedScale = 0.96f
    val Standard = CubicBezierEasing(0.2f, 0.8f, 0.25f, 1f)
    val ScreenOffset = 44.dp
}

internal fun isMotionReduced(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/**
 * Compose already skips finite animations when the phone turns animations off.
 * Endless ones, such as a shimmer or a pulsing ring, need this to show a still state instead.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        isMotionReduced(
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        )
    }
}

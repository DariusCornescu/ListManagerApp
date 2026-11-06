package com.darius.listmanager.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import com.darius.listmanager.ui.theme.Motion

private val slide = tween<IntOffset>(Motion.ScreenMillis, easing = Motion.Standard)
private val fade = tween<Float>(Motion.ShortMillis)

// A short slide with a fade: enough to show direction, without a full-width sweep.
internal fun screenEnter(offset: Int): EnterTransition =
    slideInHorizontally(slide) { offset } + fadeIn(fade)

internal fun screenExit(offset: Int): ExitTransition =
    slideOutHorizontally(slide) { -offset / 2 } + fadeOut(fade)

internal fun screenPopEnter(offset: Int): EnterTransition =
    slideInHorizontally(slide) { -offset } + fadeIn(fade)

internal fun screenPopExit(offset: Int): ExitTransition =
    slideOutHorizontally(slide) { offset / 2 } + fadeOut(fade)

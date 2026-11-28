package com.odyssey.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * Quiet motion for the Industry design: things you press settle a little, screens and cards rise in once, and a few
 * small marks (live, waiting, brand) breathe slowly. Durations mirror the web consoles' styles.css.
 */
object Motion {
    val easeOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    fun <T> quick(): FiniteAnimationSpec<T> = tween(180, easing = easeOut)
    fun <T> springy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
}

/** A clickable that scales down slightly while pressed and springs back: the touch feels physical. */
fun Modifier.pressable(enabled: Boolean = true, role: Role? = null, onClickLabel: String? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, Motion.springy(), label = "press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/** Fades and lifts content in the first time it appears. [order] staggers siblings by 45 ms each (capped). */
fun Modifier.enterOnce(order: Int = 0): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(380, delayMillis = (order.coerceIn(0, 6)) * 45, easing = Motion.easeOut)) }
    this.graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * 10f * density }
}

/** A slow, endless 0→1→0 value for small looped accents (breathing dots, floating icons). */
@Composable
fun breathing(periodMillis: Int = 2600, label: String = "breathe"): Float {
    val t = rememberInfiniteTransition(label = label)
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMillis / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = label)
    return v
}

/** Floats content up and down by a couple of dp, forever, very slowly. */
fun Modifier.floating(amplitudeDp: Float = 3f, periodMillis: Int = 4000): Modifier = composed {
    val v = breathing(periodMillis, "float")
    this.graphicsLayer { translationY = -v * amplitudeDp * density }
}

/** Soft pulsing opacity for "needs attention" marks. */
fun Modifier.pulsing(min: Float = 0.45f, periodMillis: Int = 2400): Modifier = composed {
    val v = breathing(periodMillis, "pulse")
    this.graphicsLayer { alpha = 1f - (1f - min) * v }
}

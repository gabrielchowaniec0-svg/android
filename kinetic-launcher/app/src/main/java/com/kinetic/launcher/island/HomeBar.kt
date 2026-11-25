package com.kinetic.launcher.island

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Kinetic's home bar: replaces One UI's gesture bar at the bottom (hide that one with
 * Good Lock → NavStar). It never takes touches, so the system gestures work as always.
 *
 * - normally white (or black over light apps)
 * - glows in the album's colours while music plays
 * - pulses green during a call
 * - takes on the colour of the Focus that's on
 * - a green sweep when you plug in the charger
 */
@Composable
fun KineticHomeBar(model: IslandModel) {
    val settings by com.kinetic.launcher.KineticSettings.values.collectAsState()
    val st by StatusBarData.state.collectAsState()
    if (!settings.homeBar || !st.systemBarShown) return
    val media by model.media.collectAsState()
    val call by model.call.collectAsState()
    val focus by com.kinetic.launcher.FocusModes.active.collectAsState()

    val base = if (st.lightBottom) Color(0xFF111114) else Color.White
    val accent: Color? = when {
        call != null -> Color(0xFF30D158)
        media?.playing == true -> media?.accent
        focus != null -> Color(focus!!.color)
        else -> null
    }
    val barColor by animateColorAsState(accent ?: base, tween(500), label = "barColor")
    val glowOn by animateFloatAsState(if (accent != null) 1f else 0f, tween(500), label = "barGlow")
    val pulse = rememberInfiniteTransition(label = "barPulse")
    val breath by pulse.animateFloat(
        0.45f, 1f,
        infiniteRepeatable(tween(if (call != null) 700 else 1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )
    // Plugging in: a green fill sweeps along the bar.
    val sweep = remember { Animatable(0f) }
    var lastCharging by remember { androidx.compose.runtime.mutableStateOf(st.charging) }
    LaunchedEffect(st.charging) {
        if (st.charging && !lastCharging) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(900))
            kotlinx.coroutines.delay(500)
            sweep.animateTo(0f, tween(400))
        }
        lastCharging = st.charging
    }

    Canvas(Modifier.fillMaxSize()) {
        val w = 134.dp.toPx()
        val h = 5.dp.toPx()
        val x = (size.width - w) / 2
        val y = size.height - 8.dp.toPx() - h
        val r = CornerRadius(h / 2)
        // Soft glow around the bar.
        if (glowOn > 0.01f) {
            val g = glowOn * (if (media?.playing == true || call != null) breath else 0.7f)
            for (i in 1..4) {
                val grow = i * 2.6.dp.toPx()
                drawRoundRect(
                    barColor.copy(alpha = 0.16f * g / i),
                    Offset(x - grow, y - grow), Size(w + grow * 2, h + grow * 2), CornerRadius(h / 2 + grow),
                )
            }
        }
        drawRoundRect(barColor, Offset(x, y), Size(w, h), r)
        if (sweep.value > 0f) {
            drawRoundRect(Color(0xFF30D158), Offset(x, y), Size(w * sweep.value, h), r)
        }
    }
}

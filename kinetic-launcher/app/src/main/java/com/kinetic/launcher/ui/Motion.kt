package com.kinetic.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kinetic.core.apps.Squircle

/**
 * Where the last tap or long-press on an icon/folder happened, in window pixels.
 * App launches zoom out of it, folders grow out of it, and the long-press menu
 * points at it. Set by the tiles right before they call their click handler.
 */
object TapOrigin {
    var rect: Rect? = null
}

/** The live wallpaper's colours, used so rings and glows match it. */
object WallpaperPalette {
    val Deep = Color(0xFF2E4066)
    val Mid = Color(0xFF5A6E99)
    val Light = Color(0xFF8FA6D8)
    val Glint = Color(0xFFE3ECFF)
}

/**
 * A ring that spins around an icon when its app has notifications. It follows the
 * icon's squircle shape and uses the wallpaper's colours, with a bright glint
 * travelling around it.
 */
@Composable
fun Modifier.notificationRing(
    active: Boolean,
    /** How far outside the icon the ring sits. Keep it small where icons are packed tightly. */
    grow: Dp = 4.dp,
    strokeWidth: Dp = 2.5.dp,
): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "angle",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "breathe",
    )
    return this.drawBehind {
        val grow = grow.toPx()
        val w = size.width + grow * 2
        val h = size.height + grow * 2
        val path = Squircle.androidPath(w, h).asComposePath()
        // Spin the colours, not the shape (a squircle isn't round, so rotating it would wobble).
        val sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(
                WallpaperPalette.Deep.toArgb(), WallpaperPalette.Mid.toArgb(), WallpaperPalette.Light.toArgb(),
                WallpaperPalette.Glint.toArgb(), WallpaperPalette.Deep.toArgb(),
            ),
            floatArrayOf(0f, 0.55f, 0.8f, 0.9f, 1f),
        )
        sweep.setLocalMatrix(Matrix().apply { setRotate(angle, w / 2f, h / 2f) })
        translate(-grow, -grow) {
            drawPath(path, ShaderBrush(sweep), alpha = breathe, style = Stroke(width = strokeWidth.toPx()))
        }
    }
}

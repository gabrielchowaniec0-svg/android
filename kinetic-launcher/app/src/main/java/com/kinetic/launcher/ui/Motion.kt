package com.kinetic.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import android.os.SystemClock
import com.kinetic.wallpaper.WallpaperSignals
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
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

/**
 * "Apps react too": when a ripple crosses the screen (unlock, app launch, charging,
 * notification), each icon hops a little as the wave front reaches it.
 */
class IconWave {
    var originX by mutableFloatStateOf(0f)
        private set
    var originY by mutableFloatStateOf(0f)
        private set
    /** uptimeMillis when the wave started (0 = none yet). */
    var startedAt by mutableLongStateOf(0L)
        private set
    /** Advanced every frame while a wave is running. */
    var now by mutableLongStateOf(0L)
    var speedPxPerMs = 1f
        private set
    var strength = 1f
        private set

    fun start(ripple: WallpaperSignals.Ripple, screenW: Float, screenH: Float) {
        originX = if (ripple.x < 0f) screenW / 2f else ripple.x
        originY = when {
            ripple.y >= 0f -> ripple.y
            ripple.y == -1f -> 0f
            ripple.y == -2f -> screenH
            else -> screenH / 2f
        }
        // Same speed as the wallpaper's ring (0.75 screen heights per second).
        speedPxPerMs = screenH * 0.75f / 1000f
        strength = ripple.strength.coerceIn(0.3f, 1.3f)
        startedAt = SystemClock.uptimeMillis()
        now = startedAt
    }
}

val LocalIconWave = staticCompositionLocalOf<IconWave?> { null }

/** Makes an icon hop as a [IconWave] passes over it. [bounds] is the icon's window rect. */
@Composable
fun Modifier.iconWave(bounds: () -> Rect?): Modifier {
    val wave = LocalIconWave.current ?: return this
    val lift = with(LocalDensity.current) { 7.dp.toPx() }
    return this.graphicsLayer {
        val start = wave.startedAt
        val b = bounds()
        if (start == 0L || b == null) return@graphicsLayer
        val dist = hypot(b.center.x - wave.originX, b.center.y - wave.originY)
        val dt = (wave.now - start) - dist / wave.speedPxPerMs
        if (dt in 0f..420f) {
            val k = sin(PI.toFloat() * dt / 420f) * wave.strength
            scaleX = 1f + 0.10f * k
            scaleY = 1f + 0.10f * k
            translationY = -lift * k
        }
    }
}

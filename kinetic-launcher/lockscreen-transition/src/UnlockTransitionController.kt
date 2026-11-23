package com.kinetic.lockscreen

import android.graphics.PathMeasure
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Directional flow for the icon glide. The icons travel along cubic-bezier
 * "flow lines" that echo the wallpaper's ribbons.
 */
enum class FlowDirection { TOP_BOTTOM, LEFT_RIGHT }

/** An icon to assemble. [home] is its resting position in pixels within the host Box. */
data class UnlockIcon(
    val id: String,
    val home: Offset,
    val content: @Composable () -> Unit,
)

/** A widget that fades + springs into place during Phase C. */
data class UnlockWidget(
    val id: String,
    val content: @Composable () -> Unit,
)

/**
 * Drives the whole unlock as a single 0..1 master timeline. Call [runUnlock]
 * from a biometric success callback; call [reset] when the screen re-locks.
 */
class UnlockTransitionController {
    val master = Animatable(0f)

    suspend fun runUnlock(durationMillis: Int = 1100) {
        master.snapTo(0f)
        master.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis, easing = LinearEasing),
        )
    }

    suspend fun reset() = master.snapTo(0f)
}

@Composable
fun rememberUnlockController(): UnlockTransitionController =
    remember { UnlockTransitionController() }

/* ---- phase windows on the master timeline -------------------------------- */
private const val A_START = 0.00f; private const val A_END = 0.42f   // unblur + scale
private const val B_START = 0.18f; private const val B_END = 0.86f   // icon glide
private const val C_START = 0.66f; private const val C_END = 1.00f   // widget assembly

/** Remap a sub-window of the master timeline to its own 0..1, then ease it. */
private fun segment(p: Float, start: Float, end: Float): Float {
    if (p <= start) return 0f
    if (p >= end) return 1f
    val x = (p - start) / (end - start)
    return FastOutSlowInEasing.transform(x)
}

/** Light overshoot so widgets settle with a spring-like pop. */
private fun overshoot(x: Float, tension: Float = 2.2f): Float {
    val t = x - 1f
    return t * t * ((tension + 1f) * t + tension) + 1f
}

/**
 * Builds a flow-line path from an off-screen start to the icon's home.
 * Control points bow perpendicular to the travel axis so the glide curves
 * like the wallpaper ribbons rather than moving in a straight line.
 */
private fun buildFlowPath(
    start: Offset,
    end: Offset,
    direction: FlowDirection,
    curve: Float,
): Path {
    val path = Path()
    path.moveTo(start.x, start.y)
    val c1: Offset
    val c2: Offset
    if (direction == FlowDirection.TOP_BOTTOM) {
        val dx = end.x - start.x
        c1 = Offset(start.x + dx * 0.10f + curve, start.y + (end.y - start.y) * 0.33f)
        c2 = Offset(end.x - dx * 0.10f - curve,   start.y + (end.y - start.y) * 0.66f)
    } else {
        val dy = end.y - start.y
        c1 = Offset(start.x + (end.x - start.x) * 0.33f, start.y + dy * 0.10f + curve)
        c2 = Offset(start.x + (end.x - start.x) * 0.66f, end.y - dy * 0.10f - curve)
    }
    path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    return path
}

private fun sampleAlong(measure: PathMeasure, t: Float): Offset {
    val pos = FloatArray(2)
    measure.getPosTan(measure.length * t.coerceIn(0f, 1f), pos, null)
    return Offset(pos[0], pos[1])
}

/**
 * Host for the cinematic unlock.
 *
 * @param controller timeline from [rememberUnlockController].
 * @param direction  TOP_BOTTOM converges icons from the top & bottom edges;
 *                   LEFT_RIGHT sweeps them in from the left & right edges.
 * @param canvasSize the host size in pixels (needed to compute off-screen starts).
 * @param maxBlur    Phase A blur radius at full lock, eased to 0 as auth completes.
 */
@Composable
fun CinematicUnlock(
    controller: UnlockTransitionController,
    direction: FlowDirection,
    canvasSize: Offset,
    icons: List<UnlockIcon>,
    widgets: List<UnlockWidget> = emptyList(),
    maxBlur: Dp = 28.dp,
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit = {},
) {
    // Animatable.value is a Compose State read — reading it here subscribes
    // this composable to every frame of the running animation.
    val p = controller.master.value

    val pA = segment(p, A_START, A_END)
    val pB = segment(p, B_START, B_END)
    val pC = segment(p, C_START, C_END)

    Box(modifier.fillMaxSize()) {

        // Background (e.g. the live wallpaper / a snapshot) — Phase A unblur + scale.
        Box(
            Modifier
                .fillMaxSize()
                .blur((maxBlur.value * (1f - pA)).dp)
                .graphicsLayer {
                    val s = 1.08f - 0.08f * pA
                    scaleX = s; scaleY = s
                }
        ) { background() }

        // Phase B — icons glide in along their flow lines, staggered.
        icons.forEachIndexed { index, icon ->
            val start = offscreenStart(icon.home, index, direction, canvasSize)
            val path = remember(icon.id, direction, canvasSize) {
                buildFlowPath(start, icon.home, direction, curve = 120f).asAndroidPath()
            }
            val measure = remember(path) { PathMeasure(path, false) }

            // stagger: each icon consumes a slightly later slice of Phase B
            val stagger = index * 0.06f
            val local = ((pB - stagger) / (1f - stagger)).coerceIn(0f, 1f)
            val pos = sampleAlong(measure, local)

            Box(
                Modifier
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .graphicsLayer {
                        alpha = local
                        val s = 0.6f + 0.4f * local
                        scaleX = s; scaleY = s
                        rotationZ = (1f - local) * 8f
                    }
            ) { icon.content() }
        }

        // Phase C — widgets spring + fade into place.
        widgets.forEachIndexed { index, widget ->
            val stagger = index * 0.12f
            val local = ((pC - stagger) / (1f - stagger)).coerceIn(0f, 1f)
            Box(
                Modifier.graphicsLayer {
                    alpha = local
                    val s = overshoot(local)
                    scaleX = s; scaleY = s
                }
            ) { widget.content() }
        }
    }
}

/**
 * Where an icon begins before it glides home. For TOP_BOTTOM, even-indexed
 * icons drop from above and odd ones rise from below; for LEFT_RIGHT they
 * sweep in from alternating sides.
 */
private fun offscreenStart(
    home: Offset,
    index: Int,
    direction: FlowDirection,
    canvas: Offset,
): Offset = when (direction) {
    FlowDirection.TOP_BOTTOM ->
        if (index % 2 == 0) Offset(home.x, -canvas.y * 0.4f)
        else Offset(home.x, canvas.y * 1.4f)
    FlowDirection.LEFT_RIGHT ->
        if (index % 2 == 0) Offset(-canvas.x * 0.4f, home.y)
        else Offset(canvas.x * 1.4f, home.y)
}

package com.kinetic.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Grid configuration. Columns/rows are configurable (4x5 up to 8x10). Half-grid
 * snapping lets an item rest on a half-cell boundary, so a 1x1 icon can sit
 * between two full cells for finer composition.
 */
data class GridConfig(
    val columns: Int = 5,
    val rows: Int = 6,
    val gutterDp: Int = 10,
    val halfSnap: Boolean = true,
) {
    init {
        require(columns in 4..8) { "columns must be 4..8" }
        require(rows in 5..10) { "rows must be 5..10" }
    }
}

/**
 * A placed item. [cellX]/[cellY] are in cell units; with halfSnap on they may
 * be multiples of 0.5 (e.g. 1.5 = halfway between columns 1 and 2).
 */
data class GridItem(
    val id: String,
    val cellX: Float,
    val cellY: Float,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val content: @Composable () -> Unit,
)

/**
 * Pixel geometry of a grid laid out in a [widthPx] x [heightPx] area. Public so
 * other modules (e.g. the unlock transition) can target the exact pixel spot
 * where the grid will place an item.
 */
data class GridGeometry(
    val config: GridConfig,
    val gutterPx: Float,
    val cellW: Float,
    val cellH: Float,
) {
    private val step get() = if (config.halfSnap) 0.5f else 1f

    /** Top-left pixel position of a cell coordinate. */
    fun cellOrigin(cx: Float, cy: Float): Offset = Offset(
        x = gutterPx + cx * (cellW + gutterPx),
        y = gutterPx + cy * (cellH + gutterPx),
    )

    /** Nearest legal (half-)cell to a pixel position, clamped to the grid. */
    fun nearestCell(px: Offset): Pair<Float, Float> {
        fun snap(v: Float) = (v / step).roundToInt() * step
        val cx = (px.x - gutterPx) / (cellW + gutterPx)
        val cy = (px.y - gutterPx) / (cellH + gutterPx)
        return snap(cx).coerceIn(0f, (config.columns - 1).toFloat()) to
            snap(cy).coerceIn(0f, (config.rows - 1).toFloat())
    }

    fun spanWidthPx(span: Int): Int = (cellW * span + gutterPx * (span - 1)).roundToInt()
    fun spanHeightPx(span: Int): Int = (cellH * span + gutterPx * (span - 1)).roundToInt()

    companion object {
        fun of(config: GridConfig, widthPx: Float, heightPx: Float, gutterPx: Float) = GridGeometry(
            config = config,
            gutterPx = gutterPx,
            cellW = (widthPx - gutterPx * (config.columns + 1)) / config.columns,
            cellH = (heightPx - gutterPx * (config.rows + 1)) / config.rows,
        )
    }
}

/**
 * Spring-physics home grid. Items are draggable; on release each one snaps to
 * the nearest (half-)cell with a spring settle. [onMove] reports the new
 * logical position so the host can persist layout.
 */
@Composable
fun LauncherGrid(
    config: GridConfig,
    items: List<GridItem>,
    modifier: Modifier = Modifier,
    onMove: (id: String, cellX: Float, cellY: Float) -> Unit = { _, _, _ -> },
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val gutterPx = with(LocalDensity.current) { config.gutterDp.dp.toPx() }
        val geometry = GridGeometry.of(
            config,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
            gutterPx,
        )
        // Gesture handlers outlive recompositions; read the latest values through these.
        val currentGeometry by rememberUpdatedState(geometry)
        val currentOnMove by rememberUpdatedState(onMove)

        items.forEach { item ->
            key(item.id) {
                val scope = rememberCoroutineScope()
                val home = geometry.cellOrigin(item.cellX, item.cellY)
                val animX = remember { Animatable(home.x) }
                val animY = remember { Animatable(home.y) }

                // Follow external position changes and grid resizes (e.g. rotation).
                LaunchedEffect(home) {
                    launch { animX.animateTo(home.x, gridSpring()) }
                    launch { animY.animateTo(home.y, gridSpring()) }
                }

                val wPx = geometry.spanWidthPx(item.spanX)
                val hPx = geometry.spanHeightPx(item.spanY)

                Box(
                    Modifier
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(
                                    animX.value.roundToInt(),
                                    animY.value.roundToInt(),
                                )
                            }
                        }
                        .pointerInput(item.id) {
                            detectDragGestures(
                                onDrag = { change, delta ->
                                    change.consume()
                                    scope.launch { animX.snapTo(animX.value + delta.x) }
                                    scope.launch { animY.snapTo(animY.value + delta.y) }
                                },
                                onDragEnd = {
                                    val g = currentGeometry
                                    val (cx, cy) = g.nearestCell(Offset(animX.value, animY.value))
                                    val target = g.cellOrigin(cx, cy)
                                    scope.launch { animX.animateTo(target.x, gridSpring()) }
                                    scope.launch { animY.animateTo(target.y, gridSpring()) }
                                    currentOnMove(item.id, cx, cy)
                                },
                            )
                        }
                ) { item.content() }
            }
        }
    }
}

private fun gridSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

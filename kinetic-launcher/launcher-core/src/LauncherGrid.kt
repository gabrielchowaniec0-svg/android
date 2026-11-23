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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Grid geometry. Columns/rows are configurable (4x5 up to 8x10). Half-grid
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

/** A placed item. [cellX]/[cellY] are in half-cell units when halfSnap is on. */
data class GridItem(
    val id: String,
    val cellX: Float,
    val cellY: Float,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val content: @Composable () -> Unit,
)

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
        val density = LocalDensity.current
        val gutterPx = with(density) { config.gutterDp.dp.toPx() }

        val cellW = (constraints.maxWidth - gutterPx * (config.columns + 1)) / config.columns
        val cellH = (constraints.maxHeight - gutterPx * (config.rows + 1)) / config.rows
        val step = if (config.halfSnap) 0.5f else 1f

        // pixel position of a cell coordinate (in cell units)
        fun cellToPx(cx: Float, cy: Float): Offset =
            Offset(
                x = gutterPx + cx * (cellW + gutterPx),
                y = gutterPx + cy * (cellH + gutterPx),
            )

        fun pxToCell(px: Offset): Pair<Float, Float> {
            val cx = ((px.x - gutterPx) / (cellW + gutterPx))
            val cy = ((px.y - gutterPx) / (cellH + gutterPx))
            fun snap(v: Float) = (v / step).roundToInt() * step
            val maxX = (config.columns - 1).toFloat()
            val maxY = (config.rows - 1).toFloat()
            return snap(cx).coerceIn(0f, maxX) to snap(cy).coerceIn(0f, maxY)
        }

        items.forEach { item ->
            key(item.id) {
                val scope = rememberCoroutineScope()
                val home = cellToPx(item.cellX, item.cellY)
                val animX = remember(item.id) { Animatable(home.x) }
                val animY = remember(item.id) { Animatable(home.y) }

                // keep in sync if the logical position changes externally
                LaunchedEffect(item.cellX, item.cellY) {
                    scope.launch { animX.animateTo(home.x, grdSpring()) }
                    scope.launch { animY.animateTo(home.y, grdSpring()) }
                }

                val wPx = (cellW * item.spanX + gutterPx * (item.spanX - 1)).roundToInt()
                val hPx = (cellH * item.spanY + gutterPx * (item.spanY - 1)).roundToInt()

                Box(
                    Modifier
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(
                                androidx.compose.ui.unit.Constraints.fixed(wPx, hPx)
                            )
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
                                    val (cx, cy) = pxToCell(Offset(animX.value, animY.value))
                                    val target = cellToPx(cx, cy)
                                    scope.launch { animX.animateTo(target.x, grdSpring()) }
                                    scope.launch { animY.animateTo(target.y, grdSpring()) }
                                    onMove(item.id, cx, cy)
                                },
                            )
                        }
                ) { item.content() }
            }
        }
    }
}

private fun grdSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

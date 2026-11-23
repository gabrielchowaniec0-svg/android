package com.kinetic.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    /** Space between cells (and above/below the grid). */
    val gutterDp: Int = 10,
    /** Left/right margin of the grid. One UI uses ~14dp with no gutter. */
    val marginDp: Int = 10,
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
 * Pixel geometry of a grid laid out in a given area. Public so other modules
 * (e.g. the unlock transition) can target the exact pixel spot where the grid
 * will place an item.
 */
data class GridGeometry(
    val config: GridConfig,
    val gutterPx: Float,
    val marginPx: Float,
    val cellW: Float,
    val cellH: Float,
) {
    private val step get() = if (config.halfSnap) 0.5f else 1f

    /** Top-left pixel position of a cell coordinate. */
    fun cellOrigin(cx: Float, cy: Float): Offset = Offset(
        x = marginPx + cx * (cellW + gutterPx),
        y = gutterPx + cy * (cellH + gutterPx),
    )

    /** Nearest legal (half-)cell for an item's top-left pixel, keeping the whole span on the grid. */
    fun nearestCell(px: Offset, spanX: Int = 1, spanY: Int = 1): Pair<Float, Float> {
        fun snap(v: Float) = (v / step).roundToInt() * step
        val cx = (px.x - marginPx) / (cellW + gutterPx)
        val cy = (px.y - gutterPx) / (cellH + gutterPx)
        return snap(cx).coerceIn(0f, (config.columns - spanX).toFloat().coerceAtLeast(0f)) to
            snap(cy).coerceIn(0f, (config.rows - spanY).toFloat().coerceAtLeast(0f))
    }

    fun spanWidthPx(span: Int): Int = (cellW * span + gutterPx * (span - 1)).roundToInt()
    fun spanHeightPx(span: Int): Int = (cellH * span + gutterPx * (span - 1)).roundToInt()

    companion object {
        fun of(config: GridConfig, widthPx: Float, heightPx: Float, gutterPx: Float, marginPx: Float) = GridGeometry(
            config = config,
            gutterPx = gutterPx,
            marginPx = marginPx,
            cellW = (widthPx - marginPx * 2 - gutterPx * (config.columns - 1)) / config.columns,
            cellH = (heightPx - gutterPx * (config.rows + 1)) / config.rows,
        )

        /** Geometry for [config] in an area of the given pixel size, using the current density. */
        fun of(config: GridConfig, widthPx: Float, heightPx: Float, density: androidx.compose.ui.unit.Density) =
            with(density) {
                of(config, widthPx, heightPx, config.gutterDp.dp.toPx(), config.marginDp.dp.toPx())
            }
    }
}

/**
 * Spring-physics home grid.
 *
 * Items are frozen unless [editable] is true; then they can be dragged, and on
 * release they snap to the nearest (half-)cell. [canPlace] can veto a drop, in
 * which case the item springs back.
 *
 * [onEmptyLongPress] fires when the user presses and holds on empty space (not on
 * an item) for [emptyHoldMillis] without moving — used to enter edit mode.
 */
@Composable
fun LauncherGrid(
    config: GridConfig,
    items: List<GridItem>,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    canPlace: (id: String, cellX: Float, cellY: Float) -> Boolean = { _, _, _ -> true },
    onMove: (id: String, cellX: Float, cellY: Float) -> Unit = { _, _, _ -> },
    onEmptyLongPress: (() -> Unit)? = null,
    emptyHoldMillis: Long = 700L,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val geometry = GridGeometry.of(
            config,
            constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(),
            LocalDensity.current,
        )
        // Gesture handlers outlive recompositions; read the latest values through these.
        val currentGeometry by rememberUpdatedState(geometry)
        val currentOnMove by rememberUpdatedState(onMove)
        val currentCanPlace by rememberUpdatedState(canPlace)
        val currentLongPress by rememberUpdatedState(onEmptyLongPress)

        // Empty-space layer. It sits *below* the items, so it only receives touches
        // that don't land on an icon or widget.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (editable) Modifier.drawBehind { drawCellOutlines(currentGeometry) } else Modifier)
                .pointerInput(emptyHoldMillis) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentLongPress == null) return@awaitEachGesture
                        val slop = viewConfiguration.touchSlop
                        // Returns non-null if the finger lifted or moved before the timeout.
                        val endedEarly = withTimeoutOrNull(emptyHoldMillis) {
                            var cancelled = false
                            while (!cancelled) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                cancelled = change == null || !change.pressed ||
                                    (change.position - down.position).getDistance() > slop
                            }
                            true
                        }
                        if (endedEarly == null) currentLongPress?.invoke()
                    }
                }
        )

        items.forEach { item ->
            key(item.id) {
                val scope = rememberCoroutineScope()
                val home = geometry.cellOrigin(item.cellX, item.cellY)
                val currentHome by rememberUpdatedState(home)
                val animX = remember { Animatable(home.x) }
                val animY = remember { Animatable(home.y) }
                var dragging by remember { mutableStateOf(false) }

                // Follow external position changes and grid resizes (e.g. rotation).
                LaunchedEffect(home) {
                    if (!dragging) {
                        launch { animX.animateTo(home.x, gridSpring()) }
                        launch { animY.animateTo(home.y, gridSpring()) }
                    }
                }

                val wPx = geometry.spanWidthPx(item.spanX)
                val hPx = geometry.spanHeightPx(item.spanY)

                val dragModifier = if (!editable) Modifier else Modifier.pointerInput(item.id, item.spanX, item.spanY) {
                    fun settleAt(target: Offset) {
                        scope.launch { animX.animateTo(target.x, gridSpring()) }
                        scope.launch { animY.animateTo(target.y, gridSpring()) }
                    }
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            val g = currentGeometry
                            val (cx, cy) = g.nearestCell(Offset(animX.value, animY.value), item.spanX, item.spanY)
                            if (currentCanPlace(item.id, cx, cy)) {
                                currentOnMove(item.id, cx, cy)
                                settleAt(g.cellOrigin(cx, cy))
                            } else {
                                settleAt(currentHome)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            settleAt(currentHome)
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            scope.launch { animX.snapTo(animX.value + delta.x) }
                            scope.launch { animY.snapTo(animY.value + delta.y) }
                        },
                    )
                }

                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(animX.value.roundToInt(), animY.value.roundToInt())
                            }
                        }
                        .graphicsLayer {
                            val s = if (dragging) 1.08f else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .then(dragModifier)
                ) { item.content() }
            }
        }
    }
}

private fun DrawScope.drawCellOutlines(g: GridGeometry) {
    val stroke = Stroke(width = 1.dp.toPx())
    val radius = CornerRadius(12.dp.toPx())
    for (row in 0 until g.config.rows) {
        for (col in 0 until g.config.columns) {
            val inset = 3.dp.toPx()
            val o = g.cellOrigin(col.toFloat(), row.toFloat())
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(o.x + inset, o.y + inset),
                size = Size(g.cellW - inset * 2, g.cellH - inset * 2),
                cornerRadius = radius,
                style = stroke,
            )
        }
    }
}

private fun gridSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

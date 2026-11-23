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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay
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
 *
 * Edit-mode juice:
 *  - Drag an item over a same-size item's spot and that item slides out of the way
 *    into the spot you came from ([onSwap] on release).
 *  - Hold an item over the middle of another for a moment ([canMerge] permitting)
 *    and the target swells up; let go to merge them ([onMerge] — e.g. make a folder).
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
    canMerge: (draggedId: String, targetId: String) -> Boolean = { _, _ -> false },
    onMerge: (draggedId: String, targetId: String) -> Unit = { _, _ -> },
    onSwap: ((draggedId: String, targetId: String) -> Unit)? = null,
    mergeHoldMillis: Long = 450L,
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
        val currentItems by rememberUpdatedState(items)
        val currentCanMerge by rememberUpdatedState(canMerge)
        val currentOnMerge by rememberUpdatedState(onMerge)
        val currentOnSwap by rememberUpdatedState(onSwap)

        // ---- shared drag state (for swap previews and merging) --------------------
        var dragId by remember { mutableStateOf<String?>(null) }
        var dragTopLeft by remember { mutableStateOf(Offset.Zero) }
        var mergeArmed by remember { mutableStateOf(false) }

        val hover by remember {
            derivedStateOf {
                val id = dragId ?: return@derivedStateOf DragHover.None
                val all = currentItems
                val dragged = all.firstOrNull { it.id == id } ?: return@derivedStateOf DragHover.None
                val g = currentGeometry
                val center = dragTopLeft + Offset(g.spanWidthPx(dragged.spanX) / 2f, g.spanHeightPx(dragged.spanY) / 2f)
                // Merge: hovering near the middle of another item.
                val under = all.firstOrNull { other ->
                    if (other.id == id) return@firstOrNull false
                    val o = g.cellOrigin(other.cellX, other.cellY)
                    center.x in o.x..(o.x + g.spanWidthPx(other.spanX)) && center.y in o.y..(o.y + g.spanHeightPx(other.spanY))
                }
                if (under != null && currentCanMerge(id, under.id)) {
                    val o = g.cellOrigin(under.cellX, under.cellY)
                    val w = g.spanWidthPx(under.spanX).toFloat()
                    val h = g.spanHeightPx(under.spanY).toFloat()
                    val dist = (center - Offset(o.x + w / 2f, o.y + h / 2f)).getDistance()
                    if (dist < minOf(w, h) * 0.3f) return@derivedStateOf DragHover.Merge(under.id)
                }
                // Swap: the spot we'd land on holds an item of the same size.
                if (currentOnSwap != null) {
                    val (cx, cy) = g.nearestCell(dragTopLeft, dragged.spanX, dragged.spanY)
                    val occupant = all.firstOrNull {
                        it.id != id && it.cellX == cx && it.cellY == cy && it.spanX == dragged.spanX && it.spanY == dragged.spanY
                    }
                    if (occupant != null) return@derivedStateOf DragHover.Swap(occupant.id, dragged.cellX, dragged.cellY)
                }
                DragHover.None
            }
        }
        val mergeTargetId = (hover as? DragHover.Merge)?.targetId
        LaunchedEffect(mergeTargetId) {
            mergeArmed = false
            if (mergeTargetId != null) {
                delay(mergeHoldMillis)
                mergeArmed = true
            }
        }

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
                val swapping = (hover as? DragHover.Swap)?.takeIf { it.targetId == item.id }
                // While another icon hovers over our spot, slide into the spot it came from.
                val home = if (swapping != null) geometry.cellOrigin(swapping.toX, swapping.toY)
                else geometry.cellOrigin(item.cellX, item.cellY)
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

                // Merge target: swells a little while you hover, then more when it's ready.
                val isHoverTarget = mergeTargetId == item.id
                val isMergeTarget = isHoverTarget && mergeArmed
                val targetScale = remember { Animatable(1f) }
                val glow = remember { Animatable(0f) }
                LaunchedEffect(isHoverTarget, isMergeTarget) {
                    val to = when {
                        isMergeTarget -> 1.16f
                        isHoverTarget -> 1.05f
                        else -> 1f
                    }
                    launch { targetScale.animateTo(to, spring(dampingRatio = 0.5f, stiffness = 500f)) }
                    launch { glow.animateTo(if (isMergeTarget) 1f else 0f, spring(stiffness = 400f)) }
                }

                val wPx = geometry.spanWidthPx(item.spanX)
                val hPx = geometry.spanHeightPx(item.spanY)

                val dragModifier = if (!editable) Modifier else Modifier.pointerInput(item.id, item.spanX, item.spanY) {
                    fun settleAt(target: Offset) {
                        scope.launch { animX.animateTo(target.x, gridSpring()) }
                        scope.launch { animY.animateTo(target.y, gridSpring()) }
                    }
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            dragId = item.id
                            dragTopLeft = Offset(animX.value, animY.value)
                        },
                        onDragEnd = {
                            val g = currentGeometry
                            val h = hover
                            val armed = mergeArmed
                            dragging = false
                            when {
                                h is DragHover.Merge && armed -> {
                                    val t = currentItems.firstOrNull { it.id == h.targetId }
                                    if (t != null) settleAt(g.cellOrigin(t.cellX, t.cellY))
                                    currentOnMerge(item.id, h.targetId)
                                }
                                h is DragHover.Swap -> {
                                    val t = currentItems.firstOrNull { it.id == h.targetId }
                                    if (t != null) settleAt(g.cellOrigin(t.cellX, t.cellY))
                                    currentOnSwap?.invoke(item.id, h.targetId)
                                }
                                else -> {
                                    val (cx, cy) = g.nearestCell(Offset(animX.value, animY.value), item.spanX, item.spanY)
                                    if (currentCanPlace(item.id, cx, cy)) {
                                        currentOnMove(item.id, cx, cy)
                                        settleAt(g.cellOrigin(cx, cy))
                                    } else {
                                        settleAt(currentHome)
                                    }
                                }
                            }
                            dragId = null
                            mergeArmed = false
                        },
                        onDragCancel = {
                            dragging = false
                            dragId = null
                            mergeArmed = false
                            settleAt(currentHome)
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            val nx = animX.value + delta.x
                            val ny = animY.value + delta.y
                            scope.launch { animX.snapTo(nx) }
                            scope.launch { animY.snapTo(ny) }
                            dragTopLeft = Offset(nx, ny)
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
                            val s = when {
                                dragging && mergeArmed -> 0.86f
                                dragging -> 1.08f
                                else -> targetScale.value
                            }
                            scaleX = s
                            scaleY = s
                        }
                        .then(
                            if (isMergeTarget) Modifier.drawBehind {
                                // Soft glow showing "let go to make a folder".
                                val r = minOf(size.width, size.height) * 0.42f
                                drawCircle(
                                    Brush.radialGradient(
                                        listOf(Color.White.copy(alpha = 0.35f * glow.value), Color.Transparent),
                                        center = center,
                                        radius = r * 1.5f,
                                    ),
                                    radius = r * 1.5f,
                                )
                            } else Modifier
                        )
                        .then(dragModifier)
                ) { item.content() }
            }
        }
    }
}

private sealed interface DragHover {
    data object None : DragHover
    data class Merge(val targetId: String) : DragHover
    /** [targetId] slides to cell ([toX], [toY]) — where the dragged item started. */
    data class Swap(val targetId: String, val toX: Float, val toY: Float) : DragHover
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

package com.kinetic.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kinetic.core.home.HomeItem
import com.kinetic.core.widgets.WidgetHostManager
import kotlinx.coroutines.delay
import java.util.Calendar

/** Pages in the (looping) stack pager; big enough that you never hit the end. */
private const val LOOP = 10_000

/**
 * A smart stack: several widgets in one spot. Swipe up or down to flip between them
 * (it loops). With smart rotate on, it switches by itself to whatever fits right
 * now — the music app's widget while something plays, calendar/weather in the
 * morning — unless you've flipped it yourself in the last few minutes.
 */
@Composable
fun WidgetStack(
    item: HomeItem.Widget,
    widgets: WidgetHostManager,
    editMode: Boolean,
    isResumed: Boolean,
    playingPackage: String?,
    onEditTap: (shownAppWidgetId: Int) -> Unit,
    onResize: (dx: Int, dy: Int) -> Boolean,
    cell: @Composable (appWidgetId: Int, editMode: Boolean, onEditTap: () -> Unit, onResize: ((Int, Int) -> Boolean)?) -> Unit,
) {
    val ids = item.allIds
    val count = ids.size
    val pager = rememberPagerState(initialPage = LOOP / 2 - (LOOP / 2) % count) { LOOP }
    val shownIndex = pager.currentPage % count
    val shownId = ids[shownIndex]

    // Remember when the user last flipped it themselves.
    var userFlippedAt by remember { mutableLongStateOf(0L) }
    val autoScrolling = remember { booleanArrayOf(false) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling[0]) userFlippedAt = System.currentTimeMillis()
        }
    }

    // Smart rotate: re-check when you come home, when music starts/stops, and every few minutes.
    LaunchedEffect(item.smart, isResumed, playingPackage, ids) {
        if (!item.smart || !isResumed) return@LaunchedEffect
        while (true) {
            val recentlyFlipped = System.currentTimeMillis() - userFlippedAt < 3 * 60_000L
            if (!recentlyFlipped && !pager.isScrollInProgress) {
                val best = smartPick(ids, widgets, playingPackage)
                val now = pager.currentPage % count
                if (best != null && best != now) {
                    val delta = ((best - now) % count + count) % count
                    // Go the short way round.
                    val steps = if (delta > count / 2) delta - count else delta
                    autoScrolling[0] = true
                    try {
                        pager.animateScrollToPage(pager.currentPage + steps)
                    } finally {
                        autoScrolling[0] = false
                    }
                }
            }
            delay(5 * 60_000L)
        }
    }

    if (editMode) {
        // In edit mode it behaves like one widget (move, stretch-resize, menu).
        cell(shownId, true, { onEditTap(shownId) }, onResize)
        return
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            flingBehavior = PagerDefaults.flingBehavior(state = pager, snapPositionalThreshold = 0.2f),
            key = { it },
        ) { page ->
            val id = ids[page % count]
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Cards tilt back and shrink a little as they slide away, like iOS stacks.
                        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        val a = kotlin.math.abs(offset).coerceIn(0f, 1f)
                        val s = 1f - 0.08f * a
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.5f * a
                    }
            ) {
                cell(id, false, { onEditTap(id) }, null)
            }
        }

        // Dots on the right edge, shown briefly while flipping.
        val dotsAlpha by animateFloatAsState(if (pager.isScrollInProgress) 1f else 0f, tween(if (pager.isScrollInProgress) 120 else 900), label = "dots")
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .graphicsLayer { alpha = dotsAlpha },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == shownIndex) 0.95f else 0.35f))
                )
            }
        }
    }
}

/** Which widget suits the moment best, or null to leave it where it is. */
private fun smartPick(ids: List<Int>, widgets: WidgetHostManager, playingPackage: String?): Int? {
    val packages = ids.map { widgets.infoFor(it)?.provider?.packageName.orEmpty().lowercase() }
    fun firstMatching(vararg words: String): Int? =
        packages.indexOfFirst { pkg -> words.any { it in pkg } }.takeIf { it >= 0 }

    // Something's playing: show that app's widget, or any music widget.
    if (playingPackage != null) {
        packages.indexOf(playingPackage.lowercase()).takeIf { it >= 0 }?.let { return it }
        firstMatching("music", "spotify", "youtube", "podcast", "media")?.let { return it }
    }
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> firstMatching("calendar", "weather")
        in 11..16 -> firstMatching("calendar", "reminder", "notes", "keep")
        in 17..21 -> firstMatching("weather", "health", "fitness", "battery", "devicecare", "lool")
        else -> firstMatching("clock", "alarm", "battery", "devicecare", "lool")
    }
}

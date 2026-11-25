package com.kinetic.launcher.island

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A button on a live activity ("Lap", "Stop", "End"…). */
data class LiveAction(val label: String, val run: () -> Unit)

/**
 * Kinetic's own live activities (stopwatch, Focus, up next, planes, tracked flights).
 * The island shows the most important one; pull it down for details and buttons.
 */
data class Live(
    val id: String,
    /** A short symbol or emoji. */
    val icon: String,
    val title: String,
    val detail: String = "",
    /** Fixed text for the right side (when it isn't counting). */
    val value: String? = null,
    /** Counts up from this SystemClock.elapsedRealtime() (stopwatch). */
    val countUpFrom: Long? = null,
    /** Counts down to this wall-clock time (ms). */
    val countDownTo: Long? = null,
    /** With [countDownTo]: the whole length, so a ring can show how much is left. */
    val totalMs: Long? = null,
    /** 0..1 for a ring/bar, when it isn't worked out from the countdown. */
    val progress: Float? = null,
    val accent: Int = 0xFF0A84FF.toInt(),
    val actions: List<LiveAction> = emptyList(),
    /** Higher shows first. */
    val priority: Int = 0,
    val onTap: (() -> Unit)? = null,
)

object LiveHub {
    private val _lives = MutableStateFlow<List<Live>>(emptyList())
    val lives: StateFlow<List<Live>> = _lives.asStateFlow()

    fun put(live: Live) = synchronized(this) {
        val plain = live.copy(icon = Glyphs.plain(live.icon), title = Glyphs.text(live.title), detail = Glyphs.text(live.detail), value = live.value?.let(Glyphs::text))
        _lives.value = (_lives.value.filter { it.id != plain.id } + plain).sortedByDescending { it.priority }
    }

    fun remove(id: String) = synchronized(this) {
        _lives.value = _lives.value.filter { it.id != id }
    }

    fun get(id: String): Live? = _lives.value.firstOrNull { it.id == id }

    /** "12:04.3" for a stopwatch, "4:59" / "1:02:10" for a countdown. */
    fun clock(ms: Long, tenths: Boolean): String {
        val t = ms.coerceAtLeast(0)
        val s = t / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        return if (tenths && h == 0L) base + "." + (t % 1000) / 100 else base
    }
}

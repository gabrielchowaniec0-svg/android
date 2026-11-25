package com.kinetic.launcher.island

/**
 * The rules for picking things back up after Kinetic (or the phone) restarts,
 * kept apart so they can be tested. Times are in milliseconds.
 */
internal object RestoreRules {
    private const val DAY = 86_400_000L

    /**
     * Where a stopwatch that started at [wallStart] (wall clock) should count from,
     * on the elapsed-time clock. Null when there's nothing to restore: never started,
     * over a week old, or the clock was moved back past the start.
     */
    fun stopwatchStart(wallStart: Long, nowWall: Long, nowElapsed: Long): Long? {
        if (wallStart <= 0L) return null
        val ran = nowWall - wallStart
        if (ran < -60_000L || ran > 7 * DAY) return null
        val start = nowElapsed - ran.coerceAtLeast(0L)
        // 0 means "not running", so never land exactly on it.
        return if (start == 0L) -1L else start
    }

    /** Saved laps ("1200,5300") back into numbers; anything garbled is skipped. */
    fun laps(saved: String?): List<Long> =
        saved.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }

    /** A plane watch lasts an hour; anything further away than that is a clock change. */
    fun planeWatchResumes(until: Long, now: Long): Boolean = until > now && until - now <= 61 * 60_000L

    /** A tracked flight is picked up again for 20 hours. */
    fun flightResumes(trackedAt: Long, now: Long): Boolean {
        val age = now - trackedAt
        return trackedAt > 0L && age in 0 until 20 * 3_600_000L
    }
}

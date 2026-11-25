package com.kinetic.launcher.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking things back up after Kinetic or the phone restarts. */
class RestoreRulesTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    @Test
    fun stopwatchKeepsCountingAcrossARestart() {
        // Started 5 minutes ago; the phone just rebooted (elapsed clock only 20 s in).
        val start = RestoreRules.stopwatchStart(now - 5 * min, now, 20_000L)!!
        assertEquals(5 * min, 20_000L - start)
    }

    @Test
    fun stopwatchNothingToRestore() {
        assertNull(RestoreRules.stopwatchStart(0L, now, 1000L))
        assertNull(RestoreRules.stopwatchStart(now - 8 * 24 * hour, now, 1000L))
        // Clock moved back an hour: drop it rather than count backwards.
        assertNull(RestoreRules.stopwatchStart(now + hour, now, 1000L))
    }

    @Test
    fun stopwatchSmallClockWobbleStartsAtZero() {
        val start = RestoreRules.stopwatchStart(now + 5_000L, now, 50_000L)!!
        assertEquals(50_000L, start)
    }

    @Test
    fun stopwatchNeverLandsOnNotRunning() {
        // 0 means "not running"; a restore must never produce it.
        assertNotEquals(0L, RestoreRules.stopwatchStart(now - 10_000L, now, 10_000L))
    }

    @Test
    fun lapsSurviveGarbage() {
        assertEquals(listOf(1200L, 5300L), RestoreRules.laps("1200,5300"))
        assertEquals(emptyList<Long>(), RestoreRules.laps(""))
        assertEquals(emptyList<Long>(), RestoreRules.laps(null))
        assertEquals(listOf(7L), RestoreRules.laps("x,7,,-3, "))
    }

    @Test
    fun planeWatch() {
        assertTrue(RestoreRules.planeWatchResumes(now + 30 * min, now))
        assertFalse(RestoreRules.planeWatchResumes(now - 1, now))
        // An "until" days away means the clock changed; don't watch for days.
        assertFalse(RestoreRules.planeWatchResumes(now + 3 * 24 * hour, now))
    }

    @Test
    fun trackedFlight() {
        assertTrue(RestoreRules.flightResumes(now - 2 * hour, now))
        assertFalse(RestoreRules.flightResumes(now - 21 * hour, now))
        assertFalse(RestoreRules.flightResumes(0L, now))
        assertFalse(RestoreRules.flightResumes(now + hour, now))
    }
}

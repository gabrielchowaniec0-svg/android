package com.kinetic.launcher.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The island's choices: what's in front, what shape it takes, and how swipes rotate the stack. */
class IslandDecideTest {
    private val N = IslandDecide.Now()

    private fun front(n: IslandDecide.Now, shift: Int = 0) = IslandDecide.stack(IslandDecide.activities(n), shift).firstOrNull()
    private fun mode(n: IslandDecide.Now, shift: Int = 0) = IslandDecide.mode(n, front(n, shift))

    @Test
    fun nothingRunningIsTheRestingPill() {
        assertEquals(IslandMode.Idle, mode(N))
    }

    @Test
    fun importanceOrder() {
        val all = N.copy(call = true, torch = true, nav = true, lives = 2, trip = true, timer = true, score = true, media = true)
        assertEquals(
            listOf(Activity.Call, Activity.Torch, Activity.Nav, Activity.Live, Activity.Live2, Activity.Trip, Activity.Timer, Activity.Score, Activity.Media),
            IslandDecide.activities(all),
        )
        assertEquals(Activity.Timer, front(N.copy(timer = true, media = true)))
        assertEquals(Activity.Torch, front(N.copy(torch = true, nav = true)))
    }

    @Test
    fun ringingThingsWinOverEverything() {
        val busy = N.copy(media = true, timer = true, expanded = true, alert = IslandAlert.Say("i", "hello"), peek = true)
        assertEquals(IslandMode.CallExpanded, mode(busy.copy(call = true, incomingCall = true)))
        assertEquals(IslandMode.AlarmExpanded, mode(busy.copy(alarm = true)))
    }

    @Test
    fun notificationThenMessageThenActivity() {
        val playing = N.copy(media = true)
        assertEquals(IslandMode.PeekCompact, mode(playing.copy(peek = true, alert = IslandAlert.Say("i", "x"))))
        assertEquals(IslandMode.Peek, mode(playing.copy(peek = true, peekOpen = true)))
        assertEquals(IslandMode.Alert(IslandAlert.Say("i", "x")), mode(playing.copy(alert = IslandAlert.Say("i", "x"))))
        assertEquals(IslandMode.MediaCompact, mode(playing))
    }

    @Test
    fun aRingingAlarmIsNotAlsoATimer() {
        assertEquals(emptyList<Activity>(), IslandDecide.activities(N.copy(alarm = true)))
    }

    @Test
    fun openingShowsTheBigVersionOfWhatsInFront() {
        val pairs = mapOf(
            N.copy(call = true) to IslandMode.CallExpanded,
            N.copy(nav = true) to IslandMode.NavExpanded,
            N.copy(lives = 1) to IslandMode.LiveExpanded,
            N.copy(torch = true) to IslandMode.TorchExpanded,
            N.copy(timer = true) to IslandMode.TimerExpanded,
            N.copy(trip = true) to IslandMode.TripExpanded,
            N.copy(score = true) to IslandMode.ScoreExpanded,
            N.copy(media = true) to IslandMode.MediaExpanded,
        )
        pairs.forEach { (n, want) -> assertEquals("$n", want, mode(n.copy(expanded = true))) }
        // Open with nothing running: stays a resting pill.
        assertEquals(IslandMode.Idle, mode(N.copy(expanded = true)))
    }

    @Test
    fun pausedMusicGoesMinimal() {
        assertEquals(IslandMode.MediaMinimal, mode(N.copy(media = true, mediaMinimal = true)))
        // …but only music goes minimal; a timer in front stays a timer.
        assertEquals(IslandMode.TimerCompact, mode(N.copy(media = true, mediaMinimal = true, timer = true)))
    }

    @Test
    fun swipingRotatesAndNeverGoesOutOfRange() {
        val n = N.copy(lives = 2, timer = true, media = true)
        val list = IslandDecide.activities(n)
        for (shift in -50..50) {
            val s = IslandDecide.stack(list, shift)
            assertEquals(list.size, s.size)
            assertEquals(list.toSet(), s.toSet())
        }
        assertEquals(Activity.Live2, front(n, 1))
        assertEquals(Activity.Media, front(n, -1))
        assertEquals(front(n, 0), front(n, list.size))
        // The second live thing shows as a live island too.
        assertEquals(IslandMode.LiveCompact, mode(n, 1))
    }

    @Test
    fun singleThingIgnoresSwipes() {
        val n = N.copy(media = true)
        for (shift in -3..3) assertEquals(Activity.Media, front(n, shift))
        assertTrue(IslandDecide.stack(emptyList(), 7).isEmpty())
    }
}

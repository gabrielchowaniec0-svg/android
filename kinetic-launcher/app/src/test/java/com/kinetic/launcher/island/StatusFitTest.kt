package com.kinetic.launcher.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulates the status bar on narrow and wide islands: nothing may overflow (that's what greyed it out). */
class StatusFitTest {

    /** Width the left side actually takes for a result. */
    private fun leftWidth(timeW: Float, n: Int, dot: Boolean): Float =
        timeW + (if (n > 0) 8f + 14f + (n - 1) * 20f else 0f) + (if (dot) 10f else 0f)

    @Test
    fun leftNeverOverflows() {
        for (room in 30..200) {
            for (available in 0..6) {
                for (more in listOf(false, true)) {
                    val (n, dot) = StatusFit.left(room.toFloat(), 40f, available, more)
                    if (room >= 40) {
                        assertTrue("room $room avail $available: ${leftWidth(40f, n, dot)}", leftWidth(40f, n, dot) <= room)
                    }
                    assertTrue(n <= 3 && n <= available)
                }
            }
        }
    }

    @Test
    fun screenshotCaseNowFits() {
        // Flashlight island: time + 3 icons + more-dot used to spill over and grey out.
        val (n, dot) = StatusFit.left(100f, 40f, 4, true)
        assertTrue(leftWidth(40f, n, dot) <= 100f)
    }

    @Test
    fun dotOnlyWhenSomethingIsHidden() {
        assertFalse(StatusFit.left(200f, 40f, 2, false).second)
        assertTrue(StatusFit.left(200f, 40f, 5, false).second)
    }

    @Test
    fun rightKeepsMostImportantFirst() {
        val keep = StatusFit.right(80f, 30f, listOf(23f, 22f, 20f, 19f))
        assertEquals(listOf(true, true, false, false), keep)
        assertEquals(listOf(false, false), StatusFit.right(20f, 30f, listOf(23f, 22f)))
        assertEquals(listOf(false, true), StatusFit.right(60f, 30f, listOf(null, 22f)))
    }
}

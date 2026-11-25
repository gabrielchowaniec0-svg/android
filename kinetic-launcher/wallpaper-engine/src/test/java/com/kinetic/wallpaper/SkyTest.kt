package com.kinetic.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the sky maths against things that are always true. */
class SkyTest {
    private val out = DoubleArray(2)

    @Test
    fun polarisSitsAtYourLatitudeAllNight() {
        // From Palos Park (about 41.7° N), Polaris is always about 41.7° up, due north.
        val lat = 41.67
        val lon = -87.83
        var t = 1_795_000_000_000L
        repeat(24) {
            SkyOverlay.altAz(2.530, 89.264, lat, SkyOverlay.lst(t, lon), out)
            assertEquals("altitude", lat, out[0], 1.2)
            assertTrue("azimuth ${out[1]}", out[1] < 3.0 || out[1] > 357.0)
            t += 3_600_000L
        }
    }

    @Test
    fun starsTurnAboutOnceADay() {
        // A sidereal day later (23 h 56 m 4 s), a star is back where it was.
        val t = 1_795_000_000_000L
        SkyOverlay.altAz(6.752, -16.716, 41.67, SkyOverlay.lst(t, -87.83), out)
        val a = out[0]
        val z = out[1]
        SkyOverlay.altAz(6.752, -16.716, 41.67, SkyOverlay.lst(t + 86_164_091L, -87.83), out)
        assertEquals(a, out[0], 0.05)
        assertEquals(z, out[1], 0.05)
    }

    @Test
    fun planetsStayNearTheEcliptic() {
        val p = SkyOverlay.planetPositions(1_795_000_000_000L)
        assertEquals(4, p.size)
        p.forEach { (name, ra, dec) ->
            assertTrue("$name RA $ra", ra in 0.0..24.0)
            assertTrue("$name Dec $dec", dec in -30.0..30.0)
        }
    }
}

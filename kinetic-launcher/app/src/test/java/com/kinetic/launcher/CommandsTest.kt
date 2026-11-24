package com.kinetic.launcher

import com.kinetic.launcher.island.LiveHub
import com.kinetic.launcher.ui.Commands3
import com.kinetic.launcher.ui.HelpActivity
import com.kinetic.launcher.ui.KineticCommands
import com.kinetic.launcher.ui.KineticCommands.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Simulated" tests: runs written commands through Kinetic's command understanding on a
 * computer (no phone), so mistakes show up in the build check before an update ships.
 */
class CommandsTest {

    private fun three(text: String): Commands3.Cmd? = (KineticCommands.parse(text) as? Parsed.Three)?.cmd

    @Test
    fun everyHelpExampleIsUnderstood() {
        // Sums ("12 x 7") are worked out separately from commands.
        val skip = setOf("12 x 7")
        val failures = HelpActivity.builtIn().flatMap { it.items }.map { it.write }
            .filter { it !in skip && it != "remember the wifi password is …" }
            .filter { runCatching { KineticCommands.parse(it) }.getOrNull() == null }
        assertTrue("Not understood: $failures", failures.isEmpty())
    }

    @Test
    fun helpExamplesAreUnique() {
        val all = HelpActivity.builtIn().flatMap { g -> g.items.map { g.title + "|" + it.write } }
        assertEquals("Duplicate help entries would crash the list", all.size, all.toSet().size)
    }

    @Test
    fun questionMarkOpensHelp() {
        assertEquals(Commands3.Cmd.Tool("help"), three("?"))
        assertEquals(Commands3.Cmd.Tool("help"), three("help"))
    }

    @Test
    fun quickTools() {
        assertEquals(Commands3.Cmd.Split(84.0, 3), three("split 84 between 3"))
        assertEquals(Commands3.Cmd.PercentOff(15.0, 60.0), three("15% off 60"))
        assertEquals(Commands3.Cmd.FlightLevel(350), three("FL350"))
        assertEquals(Commands3.Cmd.Metar("KORD"), three("metar ORD"))
        assertEquals(Commands3.Cmd.Metar("KORD"), three("metar kord"))
        assertEquals(Commands3.Cmd.Zones(listOf("chicago", "london", "tokyo")), three("time zones: Chicago, London, Tokyo"))
        val pick = three("pick for me: pizza, tacos, sushi") as Commands3.Cmd.Pick
        assertEquals(3, pick.options.size)
    }

    @Test
    fun flights() {
        assertEquals(Commands3.Cmd.TrackFlight("UA123", "UAL123"), three("track UA123"))
        assertEquals(Commands3.Cmd.TrackFlight(null, null), three("stop tracking"))
        assertEquals(Commands3.Cmd.PlaneWatch(true), three("plane watch"))
        // "watch" alone is for YouTube, never a flight.
        assertFalse(three("watch tv 2") is Commands3.Cmd.TrackFlight)
    }

    @Test
    fun schoolAndMemory() {
        val hw = three("homework math page 42 due friday") as Commands3.Cmd.AddHomework
        assertEquals("math page 42", hw.text)
        assertNotNull(hw.due)
        assertEquals(Commands3.Cmd.Remember("i parked on level 3"), three("remember I parked on level 3"))
        assertEquals(Commands3.Cmd.SaveWord("spanish", "gato", "cat"), three("save spanish word gato = cat"))
        assertEquals(Commands3.Cmd.SchoolDays("summer"), three("school days until summer"))
    }

    @Test
    fun stopOnlyStopsARunningStopwatch() {
        // With no stopwatch running, "stop" must not be grabbed by the stopwatch.
        assertFalse(three("stop") is Commands3.Cmd.Stopwatch)
        assertEquals(Commands3.Cmd.Stopwatch("start"), three("stopwatch"))
        assertEquals(Commands3.Cmd.Stopwatch("lap"), three("lap"))
    }

    @Test
    fun peopleCommands() {
        assertTrue(KineticCommands.parse("call mom") is Parsed.Call)
        val t = KineticCommands.parse("text mom on my way")
        assertTrue("got $t", t is Parsed.Text)
        assertEquals(Commands3.Cmd.Reply("sounds good"), three("reply sounds good"))
    }

    @Test
    fun durations() {
        assertEquals(600, KineticCommands.parseDuration("10 min"))
        assertEquals(3600, KineticCommands.parseDuration("1 hour"))
        assertEquals(1800, KineticCommands.parseDuration("half an hour"))
        assertNull(KineticCommands.parseDuration("banana"))
    }

    @Test
    fun nothingCrashesOnOddInput() {
        listOf("", " ", "?", "??", "a", "!!!", "call", "text", "track", "metar", "split", "15%", "time zones:", "pick:", "🔦", "remember", "fl", "lap lap")
            .forEach { runCatching { KineticCommands.parse(it) }.onFailure { e -> throw AssertionError("\"$it\" crashed: $e") } }
    }

    @Test
    fun clockText() {
        assertEquals("0:09.3", LiveHub.clock(9_300, tenths = true))
        assertEquals("1:02:10", LiveHub.clock(3_730_000, tenths = false))
        assertEquals("4:59", LiveHub.clock(299_999, tenths = false))
    }
}

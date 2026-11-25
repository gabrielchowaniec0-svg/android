package com.kinetic.launcher

import com.kinetic.launcher.ui.Commands3
import com.kinetic.launcher.ui.KineticCommands
import com.kinetic.launcher.ui.KineticCommands.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Strange things people might write: nothing may crash, and silly numbers stay sensible. */
class OddCommandsTest {

    private fun safe(text: String): Parsed? =
        runCatching { KineticCommands.parse(text) }.getOrElse { throw AssertionError("\"$text\" crashed: $it") }

    @Test
    fun hugeNumbers() {
        val big = "99999999999999999999"
        listOf(
            "split 84 between $big", "split $big between 3", "random number between 1 and $big",
            "random number $big to $big", "timer $big min", "timer $big hours $big min", "$big% off $big",
            "volume $big", "brightness 999", "alarm 99", "alarm 7:99", "fl99999", "days until march 99",
            "tip $big% on $big", "set alarm for 25 pm",
        ).forEach(::safe)
        val split = (KineticCommands.parse("split 84 between $big") as Parsed.Three).cmd as Commands3.Cmd.Split
        assertEquals(100, split.people)
    }

    @Test
    fun sillyDurations() {
        assertNull(KineticCommands.parseDuration("99999999999 hours"))
        assertNull(KineticCommands.parseDuration("0 min"))
        assertNull(KineticCommands.parseDuration("25 hours"))
        assertEquals(86_400, KineticCommands.parseDuration("24 hours"))
        assertEquals(90, KineticCommands.parseDuration("1 min 30 sec"))
    }

    @Test
    fun impossibleAlarmTimesAreIgnored() {
        assertTrue(safe("alarm 99") !is Parsed.Alarm)
        assertTrue(safe("alarm 7:75") !is Parsed.Alarm)
        val ok = safe("alarm 7:30 pm") as Parsed.Alarm
        assertEquals(19, ok.hour)
    }

    @Test
    fun oddCharactersAndShapes() {
        listOf(
            "\u0000", "\n\n", "\t call mom", "CALL MOM", "call    mom", "call mom!!!", "“call mom”",
            "timer 5 min 🔦", "remind me", "remind me to", "remember", "track", "track 🛫", "metar 12345",
            "split between", "% off", "random number between and", "days until", "days until 🎉",
            "time zones: ,,,", "pick for me: ,", "save spanish word = ", "homework due", "text mom",
            "a".repeat(5000), "call " + "x".repeat(2000), "1/0", "12 ÷ 0", "-5 min timer", "timer -5 min",
        ).forEach(::safe)
    }

    @Test
    fun randomGibberishNeverCrashes() {
        val rnd = Random(42)
        val parts = listOf(
            "call", "text", "timer", "alarm", "track", "split", "between", "%", "off", "remind", "me", "to",
            "at", "pm", "am", ":", "99", "0", "-1", "3.5", "min", "hours", "🔦", "“", "\"", "?", "mom", "metar",
            "fl", "homework", "due", "friday", "random", "number", "and", "days", "until", "=", ",", "pick:",
        )
        repeat(3000) {
            val text = (1..rnd.nextInt(1, 8)).joinToString(" ") { parts[rnd.nextInt(parts.size)] }
            safe(text)
        }
    }
}

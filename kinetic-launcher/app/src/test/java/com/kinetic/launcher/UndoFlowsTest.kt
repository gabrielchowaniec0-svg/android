package com.kinetic.launcher

import com.kinetic.launcher.ui.Commands3.Cmd
import com.kinetic.launcher.ui.KineticCommands
import com.kinetic.launcher.ui.KineticCommands.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every command that adds or turns something on has a way to take it away again. */
class UndoFlowsTest {
    private fun three(text: String): Cmd? = (KineticCommands.parse(text) as? Parsed.Three)?.cmd

    @Test
    fun stickyNotes() {
        assertTrue(KineticCommands.parse("note buy milk") is Parsed.Note)
        assertEquals(Cmd.ListNotes, three("my notes"))
        assertEquals(Cmd.ListNotes, three("sticky notes"))
        assertEquals(Cmd.RemoveNote("milk"), three("remove note milk"))
        assertEquals(Cmd.RemoveNote("buy milk"), three("delete the note about buy milk"))
        assertEquals(Cmd.RemoveNote("milk"), three("throw away note milk"))
        assertEquals(Cmd.RemoveNote(null), three("remove note"))
        assertEquals(Cmd.RemoveNote("*"), three("clear notes"))
        assertEquals(Cmd.RemoveNote("*"), three("delete all notes"))
        // "notes" on its own still opens your notes app, not a Kinetic command.
        assertTrue(three("notes") == null)
    }

    @Test
    fun rememberAndForget() {
        assertEquals(Cmd.Forget("park"), three("forget parking"))
        assertEquals(Cmd.Forget("park"), three("forget where I parked"))
        assertEquals(Cmd.Forget(null), three("forget that"))
        assertEquals(Cmd.Forget("wifi password"), three("forget the wifi password"))
        assertEquals(Cmd.ListMemories, three("what do you remember"))
    }

    @Test
    fun theRest() {
        assertEquals(Cmd.RemoveWord("gato"), three("remove word gato"))
        assertEquals(Cmd.RemoveShortcut("m"), three("remove shortcut m"))
        assertEquals(Cmd.EndFocus, three("end focus"))
        assertEquals(Cmd.EndFocus, three("focus off"))
        assertEquals(Cmd.Unsnooze, three("unsnooze"))
        assertEquals(Cmd.Unsnooze, three("notifications back on"))
        assertEquals(Cmd.PlaneWatch(false), three("plane watch off"))
        assertEquals(Cmd.TrackFlight(null, null), three("stop tracking"))
        assertEquals(Cmd.Stopwatch("stop"), three("stop stopwatch"))
    }
}

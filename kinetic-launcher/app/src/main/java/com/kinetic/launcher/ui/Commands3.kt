package com.kinetic.launcher.ui

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.kinetic.launcher.island.IslandBus
import com.kinetic.launcher.ui.KineticCommands.Ready
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The third set of Kinetic Commands: replies, memories, photos, schedule, homework,
 * flashcards, stopwatch, aviation (METAR, phonetic, flight levels), quick tools,
 * "again"/"undo", your own shortcuts, and finishing what you started writing.
 */
object Commands3 {

    sealed interface Cmd {
        data class Reply(val text: String) : Cmd
        data object CallBack : Cmd
        data object ReadIt : Cmd
        data class Snooze(val minutes: Int) : Cmd
        data class Eta(val who: String?) : Cmd
        data class Remember(val text: String) : Cmd
        data class Recall(val query: String, val hit: Memory.Item) : Cmd
        /** "forget parking", "forget the wifi password"; null = the newest thing remembered. */
        data class Forget(val what: String?) : Cmd
        data object ListMemories : Cmd
        data object ListNotes : Cmd
        /** null = the newest note; "*" = all of them. */
        data class RemoveNote(val query: String?) : Cmd
        data class RemoveWord(val front: String) : Cmd
        data class RemoveShortcut(val short: String) : Cmd
        data object EndFocus : Cmd
        data object Unsnooze : Cmd
        data class LastPhoto(val who: String?) : Cmd
        data class PhotosFrom(val day: String) : Cmd
        data class Schedule(val day: LocalDate, val label: String) : Cmd
        data class AddHomework(val text: String, val due: LocalDate?) : Cmd
        data object ListHomework : Cmd
        data class DoneHomework(val text: String) : Cmd
        data class SaveWord(val deck: String, val front: String, val back: String) : Cmd
        data class Flashcards(val deck: String?) : Cmd
        data class Stopwatch(val action: String) : Cmd
        data class PlaneWatch(val on: Boolean) : Cmd
        data class TrackFlight(val shown: String?, val callsign: String?) : Cmd
        data class SchoolDays(val until: String) : Cmd
        data class SetSummer(val day: LocalDate) : Cmd
        data class Metar(val station: String) : Cmd
        data class Phonetic(val text: String) : Cmd
        data class FlightLevel(val fl: Int) : Cmd
        data class Split(val amount: Double, val people: Int) : Cmd
        data class PercentOff(val pct: Double, val amount: Double) : Cmd
        data class Zones(val places: List<String>) : Cmd
        data class Tool(val which: String) : Cmd
        data class Pick(val options: List<String>) : Cmd
        data object Again : Cmd
        data object Undo : Cmd
        data class SetShortcut(val short: String, val command: String) : Cmd
    }

    // ---- shortcuts & history --------------------------------------------------------------

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    /** Your own writing shortcuts ("m" → "call mom"). */
    fun shortcut(c: Context, text: String): String? =
        runCatching { JSONObject(prefs(c).getString("shortcuts", null) ?: "{}").optString(text.trim().lowercase()).takeIf { it.isNotBlank() } }.getOrNull()

    fun setShortcut(c: Context, short: String, command: String) {
        val o = runCatching { JSONObject(prefs(c).getString("shortcuts", null) ?: "{}") }.getOrDefault(JSONObject())
        if (command.isBlank()) o.remove(short.lowercase()) else o.put(short.lowercase(), command)
        prefs(c).edit().putString("shortcuts", o.toString()).apply()
    }

    fun shortcuts(c: Context): Map<String, String> = runCatching {
        val o = JSONObject(prefs(c).getString("shortcuts", null) ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** Remembers a command you ran (for "again" and finishing what you write). */
    fun recordHistory(c: Context, text: String) {
        val t = text.trim()
        if (t.isBlank() || t.lowercase() in setOf("again", "undo", "repeat", "do that again")) return
        val arr = runCatching { JSONArray(prefs(c).getString("history", "[]")) }.getOrDefault(JSONArray())
        val list = (0 until arr.length()).map { arr.getString(it) }.filter { !it.equals(t, true) }
        prefs(c).edit().putString("history", JSONArray(listOf(t) + list.take(49)).toString()).apply()
    }

    fun history(c: Context): List<String> = runCatching {
        val arr = JSONArray(prefs(c).getString("history", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** A past command that starts with what you wrote ("ca" → "call mom"), if any. */
    fun complete(c: Context, partial: String): String? {
        val p = partial.trim().lowercase()
        if (p.length < 2) return null
        return history(c).firstOrNull { it.lowercase().startsWith(p) && it.length > p.length }
            ?: shortcuts(c).values.firstOrNull { it.lowercase().startsWith(p) && it.length > p.length }
    }

    // ---- undo -------------------------------------------------------------------------------

    @Volatile
    private var undo: Pair<String, (Context) -> Unit>? = null

    /** Called just before a setting changes, with how to put it back. */
    fun rememberUndo(label: String, restore: (Context) -> Unit) {
        undo = label to restore
    }

    // ---- "be there in 10" → a reminder -------------------------------------------------------

    /** After a text that mentions a time ("in 10", "in 20 min"), the island offers a reminder. */
    fun followUp(context: Context, body: String, who: String) {
        val m = Regex("""\bin\s+(\d{1,3})\s*(min|mins|minutes|m|hours?|hrs?|h)?\b""", RegexOption.IGNORE_CASE).find(body) ?: return
        val n = m.groupValues[1].toIntOrNull() ?: return
        val unit = m.groupValues[2].lowercase()
        val secs = if (unit.startsWith("h")) n * 3600 else n * 60
        if (secs < 60 || secs > 12 * 3600) return
        val label = KineticCommands.prettyDuration(secs)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            IslandBus.ask("⏲", "Remind you in $label?", "Remind", secondary = "No") {
                val timer = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "You told $who: $body")
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                if (start(context, timer)) IslandBus.say("⏲", "Reminder in $label")
            }
        }, 3_500)
    }

    // ---- understanding ------------------------------------------------------------------

    fun parse(raw: String): Cmd? {
        val q = raw.trim().lowercase(Locale.US).trimEnd('.', '!', '?').replace('’', '\'')
        if (q.isEmpty()) return null
        fun r(p: String) = Regex(p).matchEntire(q)
        val ctx = com.kinetic.launcher.KineticApp.context

        when (q) {
            "again", "repeat", "do that again", "repeat that" -> return Cmd.Again
            "undo", "undo that", "put it back" -> return Cmd.Undo
            "call back", "call them back", "return call", "call back last" -> return Cmd.CallBack
            "read it", "read that", "read my notification", "read my last notification", "read it out" -> return Cmd.ReadIt
            "what's my eta", "whats my eta", "my eta", "eta", "when will i get there" -> return Cmd.Eta(null)
            "where did i park", "where's my car", "wheres my car", "where is my car", "find my car" ->
                return ctx?.let { c -> Memory.find(c, "park")?.let { Cmd.Recall("park", it) } } ?: Cmd.Recall("park", Memory.Item(0, "No parking spot saved. Write \"remember I parked …\"", 0.0, 0.0))
            "forget parking", "forget where i parked", "forget my parking spot", "forget where i parked my car" -> return Cmd.Forget("park")
            "forget that", "forget it", "forget the last thing" -> return Cmd.Forget(null)
            "what do you remember", "what have i saved", "my memories", "things i saved", "what did i ask you to remember" -> return Cmd.ListMemories
            "my notes", "sticky notes", "show notes", "show my notes", "list notes", "home notes", "my sticky notes" -> return Cmd.ListNotes
            "remove note", "delete note", "remove the note", "delete the note", "remove last note", "delete last note",
            "throw away note", "remove sticky", "delete sticky", "remove sticky note", "delete sticky note" -> return Cmd.RemoveNote(null)
            "clear notes", "clear all notes", "remove all notes", "delete all notes", "clear sticky notes", "remove all sticky notes",
            "clear my notes", "delete my notes" -> return Cmd.RemoveNote("*")
            "end focus", "stop focus", "focus off", "cancel focus", "end focus session", "stop the focus", "turn off focus", "end session" -> return Cmd.EndFocus
            "unsnooze", "unsnooze notifications", "stop snooze", "end snooze", "notifications on", "notifications back on",
            "turn notifications back on", "unmute notifications", "unpause notifications" -> return Cmd.Unsnooze
            "last photo", "my last photo", "show last photo", "latest photo" -> return Cmd.LastPhoto(null)
            "my schedule", "schedule today", "my schedule today", "my day", "today's schedule", "what's my day" -> return Cmd.Schedule(LocalDate.now(), "Today")
            "my schedule tomorrow", "schedule tomorrow", "tomorrow's schedule", "what do i have tomorrow" -> return Cmd.Schedule(LocalDate.now().plusDays(1), "Tomorrow")
            "homework", "my homework", "what homework do i have", "tasks", "my tasks" -> return Cmd.ListHomework
            "flashcards", "quiz me", "study" -> return Cmd.Flashcards(null)
            "stopwatch", "start stopwatch", "start the stopwatch" -> return Cmd.Stopwatch("start")
            "lap" -> return Cmd.Stopwatch("lap")
            "stop stopwatch", "stop the stopwatch" -> return Cmd.Stopwatch("stop")
            "stop" -> if (com.kinetic.launcher.island.Stopwatch.running) return Cmd.Stopwatch("stop")
            "plane watch", "plane watch on", "watch for planes", "tell me when planes fly over", "planes overhead" -> return Cmd.PlaneWatch(true)
            "plane watch off", "stop plane watch", "stop watching planes" -> return Cmd.PlaneWatch(false)
            "stop tracking", "stop tracking flight", "stop tracking the flight", "untrack" -> return Cmd.TrackFlight(null, null)
            "what's that plane", "whats that plane", "what plane is that", "which plane is that" -> return Cmd.Tool("planes")
            "report problem", "report a problem", "report bug", "report a bug", "send logs", "send report", "something's wrong", "somethings wrong" -> return Cmd.Tool("report")
            "help", "commands", "all commands", "what can i write", "what can i say", "what can you do", "command list", "cheat sheet" -> return Cmd.Tool("help")
            "level", "bubble level", "spirit level" -> return Cmd.Tool("level")
            "ruler", "measure" -> return Cmd.Tool("ruler")
            "decibels", "how loud is it", "sound meter", "noise level" -> return Cmd.Tool("decibels")
        }
        r("""(?:reply|respond|answer|text back)\s+(.+)""")?.let { return Cmd.Reply(it.groupValues[1].trim()) }
        r("""(?:snooze|mute|silence|pause) (?:all )?notifications?(?: for)?\s+(.+)""")?.let { m ->
            KineticCommands.parseDuration(m.groupValues[1])?.let { return Cmd.Snooze((it / 60).coerceAtLeast(1)) }
        }
        r("""(?:send|share|text)\s+my\s+eta\s+(?:to|with)\s+(.+)""")?.let { return Cmd.Eta(it.groupValues[1].trim()) }
        r("""remember\s+(?:that\s+)?(.+)""")?.let { return Cmd.Remember(it.groupValues[1].trim()) }
        // Taking things away again: every "add" command has a "remove".
        r("""(?:remove|delete|clear|throw away|bin|trash|get rid of)\s+(?:the\s+|my\s+)?(?:sticky\s+)?note\s+(?:about\s+|that says\s+|saying\s+)?(.+)""")?.let {
            return Cmd.RemoveNote(it.groupValues[1].trim().removeSurrounding("\""))
        }
        r("""(?:remove|delete)\s+(?:the\s+)?(?:flashcard|card|word)\s+(.+)""")?.let { return Cmd.RemoveWord(it.groupValues[1].trim()) }
        r("""(?:remove|delete|clear)\s+(?:the\s+)?(?:shortcut|alias)\s+(\S+)""")?.let { return Cmd.RemoveShortcut(it.groupValues[1]) }
        r("""(?:remove|delete|cancel)\s+(?:the\s+|my\s+)?(?:homework|task|assignment)\s+(.+)""")?.let { m ->
            if (ctx != null && Homework.all(ctx).any { it.text.lowercase().contains(m.groupValues[1]) }) return Cmd.DoneHomework(m.groupValues[1])
        }
        r("""forget\s+(?:about\s+)?(?:the\s+|my\s+)?(.+)""")?.let { return Cmd.Forget(it.groupValues[1].trim()) }
        r("""(?:send|share|text)\s+(?:my\s+)?(?:last|latest)\s+(?:photo|picture|pic)\s+(?:to|with)\s+(.+)""")?.let { return Cmd.LastPhoto(it.groupValues[1].trim()) }
        r("""(?:find|show)?\s*photos?\s+(?:from\s+)?(today|yesterday|last week|this week)""")?.let { return Cmd.PhotosFrom(it.groupValues[1]) }
        r("""(?:my\s+)?schedule\s+(?:on\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)""")?.let { m ->
            val d = DayOfWeek.valueOf(m.groupValues[1].uppercase())
            var x = LocalDate.now().plusDays(1)
            while (x.dayOfWeek != d) x = x.plusDays(1)
            return Cmd.Schedule(x, m.groupValues[1].replaceFirstChar { it.uppercase() })
        }
        r("""(?:homework|task|todo|to do|assignment)\s+(.+?)(?:\s+due\s+(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday))?""")?.let { m ->
            return Cmd.AddHomework(m.groupValues[1].trim(), dueDate(m.groupValues[2]))
        }
        r("""(?:done|finished|did|completed?)\s+(?:with\s+)?(?:my\s+)?(.+?)(?:\s+homework)?""")?.let { m ->
            if (ctx != null && Homework.all(ctx).any { it.text.lowercase().contains(m.groupValues[1]) }) return Cmd.DoneHomework(m.groupValues[1])
        }
        r("""save(?:\s+(\w+))?\s+word\s+(.+?)\s*(?:=|means|is)\s*(.+)""")?.let { m ->
            return Cmd.SaveWord(m.groupValues[1].ifBlank { "words" }, m.groupValues[2].trim(), m.groupValues[3].trim())
        }
        r("""(?:flashcards|quiz me(?: on)?|study)\s+(\w+)""")?.let { return Cmd.Flashcards(it.groupValues[1]) }
        r("""(?:summer (?:break )?starts|last day of school(?: is)?|school ends)\s+(?:on\s+)?([a-z]+)\s+(\d{1,2})""")?.let { m ->
            val today = LocalDate.now()
            java.time.Month.values().firstOrNull { it.name.lowercase().startsWith(m.groupValues[1].take(3)) }?.let { mo ->
                runCatching { LocalDate.of(today.year, mo, m.groupValues[2].toInt()) }.getOrNull()?.let { d ->
                    return Cmd.SetSummer(if (d.isBefore(today)) d.plusYears(1) else d)
                }
            }
        }
        r("""(?:how many )?school days (?:until|till|left until|left before)\s+(.+)""")?.let { return Cmd.SchoolDays(it.groupValues[1].trim()) }
        r("""(?:metar|weather at|airport weather)\s+([a-z]{3,4})""")?.let { m ->
            val id = m.groupValues[1].uppercase()
            return Cmd.Metar(if (id.length == 3) "K$id" else id)
        }
        r("""(?:track|follow)\s+(?:flight\s+)?([a-z][a-z0-9]|[a-z]{3})\s?(\d{1,4})""")?.let { m ->
            val air = m.groupValues[1]
            val icao = if (air.length == 3) air.uppercase() else (Assistant.AIRLINE[air.uppercase()] ?: air.uppercase())
            return Cmd.TrackFlight(air.uppercase() + m.groupValues[2], icao + m.groupValues[2])
        }
        r("""(?:phonetic|spell|nato)\s+(.+)""")?.let { return Cmd.Phonetic(it.groupValues[1]) }
        r("""(?:flight level|fl)\s*(\d{2,3})(?:\s+in\s+feet)?""")?.let { return Cmd.FlightLevel(it.groupValues[1].toInt()) }
        r("""split\s+\$?(\d+(?:\.\d+)?)\s+(?:between|by|among|with|for|into)\s+(\d+)(?:\s+people)?""")?.let { return Cmd.Split(it.groupValues[1].toDouble(), (it.groupValues[2].toIntOrNull() ?: 100).coerceIn(1, 100)) }
        r("""(?:what's\s+|whats\s+)?(\d+(?:\.\d+)?)%\s+off\s+(?:of\s+)?\$?(\d+(?:\.\d+)?)""")?.let { return Cmd.PercentOff(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        r("""(?:time zones?|times)(?:\s+for(?:\s+my\s+trip)?)?[:\s]+(.+)""")?.let { m ->
            val places = m.groupValues[1].split(',', '&').map { it.trim().removePrefix("and ").trim() }.filter { it.isNotBlank() }
            if (places.size >= 2) return Cmd.Zones(places)
        }
        r("""(?:pick|choose|decide)(?: for me)?[:\s]+(.+)""")?.let { m ->
            val opts = m.groupValues[1].split(',', '/').flatMap { it.split(" or ") }.map { it.trim() }.filter { it.isNotBlank() }
            if (opts.size >= 2) return Cmd.Pick(opts)
        }
        r("""(?:shortcut|alias)\s+(\S+)\s*(?:=|means|for)\s*(.+)""")?.let { return Cmd.SetShortcut(it.groupValues[1], it.groupValues[2].trim()) }
        // "what's the wifi password", "where's my locker" → things you asked Kinetic to remember.
        r("""(?:what(?:'s| is| was)|where(?:'s| is| did i put)|when(?:'s| is)|remind me)\s+(?:the|my)\s+(.+)""")?.let { m ->
            ctx?.let { c -> Memory.find(c, m.groupValues[1])?.let { hit -> return Cmd.Recall(m.groupValues[1], hit) } }
        }
        return null
    }

    private fun dueDate(word: String): LocalDate? {
        val today = LocalDate.now()
        return when (word) {
            "" -> null
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> {
                val d = DayOfWeek.valueOf(word.uppercase())
                var x = today.plusDays(1)
                while (x.dayOfWeek != d) x = x.plusDays(1)
                x
            }
        }
    }

    // ---- doing ----------------------------------------------------------------------------

    fun resolve(context: Context, cmd: Cmd, original: String): Ready? = when (cmd) {
        is Cmd.Reply -> reply(context, cmd.text, original)
        Cmd.CallBack -> callBack(context)
        Cmd.ReadIt -> readIt(context)
        is Cmd.Snooze -> Ready("☾", "Snooze notifications", "For ${KineticCommands.prettyDuration(cmd.minutes * 60)} (calls still ring if your Do Not Disturb allows them)", "Snooze", quiet = true) { ctx ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                MoreCommands.askSpecial(ctx, "☾", "Snoozing needs Do Not Disturb access", PermissionActivity.DND, original)
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                SnoozeEndReceiver.schedule(ctx, cmd.minutes)
                tell(ctx, "☾", "Notifications snoozed for ${KineticCommands.prettyDuration(cmd.minutes * 60)}")
                true
            }
        }
        is Cmd.Eta -> {
            val nav = com.kinetic.launcher.NotificationHub.nav.value
            when {
                nav == null -> answer("➤", "No directions running", "Start navigation in Maps first", "No directions running")
                cmd.who == null -> answer("➤", nav.eta?.let { "Arriving $it" } ?: "On the way", "${nav.distance} · ${nav.instruction}", (nav.eta?.let { "ETA $it" } ?: "On the way") + " · ${nav.distance}")
                else -> KineticCommands.resolve(context, KineticCommands.Parsed.Text(cmd.who, "On my way! ETA " + (nav.eta ?: "soon")))
            }
        }
        is Cmd.Remember -> Ready("📌", "Remember", cmd.text, "Save", done = "Got it: ${cmd.text}") { ctx ->
            Memory.add(ctx, cmd.text)
            true
        }
        is Cmd.Recall -> if (cmd.hit.at == 0L) {
            answer("📌", cmd.hit.text, "", cmd.hit.text)
        } else {
            val ago = ago(cmd.hit.at)
            val line = cmd.hit.text.replaceFirstChar { it.uppercase() } + " · saved $ago"
            if (cmd.hit.lat == 0.0 && cmd.hit.lon == 0.0) {
                answer("📌", cmd.hit.text.replaceFirstChar { it.uppercase() }, "Saved $ago", line)
            } else {
                // Saved with a place: the island offers the map.
                Ready("📌", cmd.hit.text.replaceFirstChar { it.uppercase() }, "Saved $ago", "Map", quiet = true) { ctx ->
                    val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${cmd.hit.lat},${cmd.hit.lon}?q=${cmd.hit.lat},${cmd.hit.lon}(${Uri.encode(cmd.hit.text)})"))
                    IslandBus.ask("📌", line, "Map", secondary = "OK") { start(ctx, map) } || start(ctx, map)
                }
            }
        }
        is Cmd.Forget -> {
            val hit = when (cmd.what) {
                null -> Memory.all(context).maxByOrNull { it.at }
                "park" -> Memory.all(context).firstOrNull { it.text.contains("park") }
                else -> Memory.find(context, cmd.what)
            }
            if (hit == null) {
                answer("📌", if (cmd.what == "park") "No parking spot saved" else "Nothing saved about that", "Write \u201Cwhat do you remember\u201D to see everything", "Nothing to forget")
            } else {
                Ready("📌", "Forget: ${hit.text}", "Removes it from what Kinetic remembers", "Forget", done = "Forgotten") { ctx ->
                    Memory.removeItem(ctx, hit.at)
                    true
                }
            }
        }
        Cmd.ListMemories -> {
            val all = Memory.all(context)
            if (all.isEmpty()) {
                answer("📌", "Nothing saved yet", "Write \u201Cremember …\u201D to save something", "Nothing saved yet")
            } else {
                val lines = all.take(5).joinToString("\n") { "· " + it.text }
                answer("📌", "${all.size} saved", lines + "\nForget one with \u201Cforget …\u201D", "${all.size} saved: " + all.take(3).joinToString(" · ") { it.text })
            }
        }
        Cmd.ListNotes -> {
            com.kinetic.launcher.pen.StickyNotes.load(context)
            val all = com.kinetic.launcher.pen.StickyNotes.notes.value
            if (all.isEmpty()) {
                answer("✎", "No sticky notes", "Write \u201Cnote buy milk\u201D to add one", "No sticky notes")
            } else {
                answer(
                    "✎", if (all.size == 1) "1 sticky note" else "${all.size} sticky notes",
                    all.joinToString("\n") { "· " + it.text } + "\nRemove one with \u201Cremove note …\u201D, or swipe it sideways",
                    "Notes: " + all.joinToString(" · ") { it.text },
                )
            }
        }
        is Cmd.RemoveNote -> {
            com.kinetic.launcher.pen.StickyNotes.load(context)
            val all = com.kinetic.launcher.pen.StickyNotes.notes.value
            val q = cmd.query
            when {
                all.isEmpty() -> answer("✎", "No sticky notes to remove", "", "No sticky notes")
                q == "*" -> Ready("✎", "Remove all ${all.size} sticky notes", "From your home screen", "Remove", confirm = true, done = "Sticky notes removed") { ctx ->
                    all.forEach { com.kinetic.launcher.pen.StickyNotes.remove(ctx, it.id) }
                    true
                }
                else -> {
                    val hit = if (q == null) all.first() else com.kinetic.launcher.pen.StickyNotes.find(context, q)
                    if (hit == null) {
                        answer("✎", "No note says \u201C$q\u201D", "Your notes: " + all.joinToString(" · ") { it.text }, "No note says \u201C$q\u201D")
                    } else {
                        Ready("✎", "Remove note: ${hit.text}", "From your home screen", "Remove", done = "Note removed") { ctx ->
                            com.kinetic.launcher.pen.StickyNotes.remove(ctx, hit.id)
                            true
                        }
                    }
                }
            }
        }
        is Cmd.RemoveWord -> {
            val card = Flashcards.all(context).firstOrNull { it.front.equals(cmd.front, true) || it.back.equals(cmd.front, true) }
            if (card == null) {
                answer("🃏", "No flashcard \u201C${cmd.front}\u201D", "", "No flashcard \u201C${cmd.front}\u201D")
            } else {
                Ready("🃏", "Remove ${card.front} = ${card.back}", "From ${card.deck}", "Remove", done = "Flashcard removed") { ctx ->
                    Flashcards.remove(ctx, card.front)
                    true
                }
            }
        }
        is Cmd.RemoveShortcut -> {
            val short = cmd.short.lowercase()
            val now = shortcuts(context)[short]
            if (now == null) {
                answer("⌘", "No shortcut \u201C$short\u201D", "", "No shortcut \u201C$short\u201D")
            } else {
                Ready("⌘", "Remove shortcut \u201C$short\u201D", "It runs \u201C$now\u201D", "Remove", done = "Shortcut removed") { ctx ->
                    setShortcut(ctx, short, "")
                    true
                }
            }
        }
        Cmd.EndFocus -> Ready("☾", "End Focus", "Turns the current Focus off", "End", quiet = true) { ctx ->
            com.kinetic.launcher.FocusModes.init(ctx)
            val wasOn = com.kinetic.launcher.FocusModes.active.value != null
            val timer = com.kinetic.launcher.island.FocusLive.running(ctx)
            if (timer) {
                com.kinetic.launcher.island.FocusLive.end(ctx)
            } else if (wasOn) {
                com.kinetic.launcher.FocusModes.turnOff(ctx)
                tell(ctx, "☾", "Focus off")
            } else {
                tell(ctx, "☾", "No Focus is on")
            }
            true
        }
        Cmd.Unsnooze -> Ready("🔔", "Notifications back on", "Ends the snooze", "Turn on", quiet = true) { ctx ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching {
                val pi = PendingIntent.getBroadcast(ctx, 11, Intent(ctx, SnoozeEndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
            }
            com.kinetic.launcher.KineticApp.pen(ctx).edit().remove(SnoozeEndReceiver.KEY).apply()
            if (nm.isNotificationPolicyAccessGranted) runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
            tell(ctx, "🔔", "Notifications are back on")
            true
        }
        is Cmd.LastPhoto -> lastPhoto(context, cmd.who)
        is Cmd.PhotosFrom -> Ready("🖼", "Photos from ${cmd.day}", "Google Photos / Gallery", "Show", done = "Photos from ${cmd.day}") { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/search/" + Uri.encode(cmd.day))).setPackage("com.google.android.apps.photos")) ||
                (ctx.packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")?.let { start(ctx, it) } ?: false)
        }
        is Cmd.Schedule -> schedule(context, cmd.day, cmd.label)
        is Cmd.AddHomework -> Ready(
            "📚", cmd.text.replaceFirstChar { it.uppercase() },
            cmd.due?.let { "Due " + it.format(DateTimeFormatter.ofPattern("EEEE, MMM d")) + " · reminder the evening before" } ?: "No due date",
            "Add", done = "Added: ${cmd.text}" + (cmd.due?.let { " · due " + it.format(DateTimeFormatter.ofPattern("EEE")) } ?: ""),
        ) { ctx ->
            Homework.add(ctx, cmd.text, cmd.due)
            if (cmd.due != null && android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                IslandBus.ask("📚", "Let Kinetic remind you about homework?", "Allow", secondary = "Not now") {
                    PermissionActivity.start(ctx, Manifest.permission.POST_NOTIFICATIONS, "")
                }
            }
            true
        }
        Cmd.ListHomework -> {
            val all = Homework.all(context)
            if (all.isEmpty()) {
                answer("📚", "No homework saved 🎉", "Write \"homework math page 42 due friday\"", "No homework saved 🎉")
            } else {
                val list = all.joinToString(" · ") { h -> h.text + (h.due?.let { " (" + it.format(DateTimeFormatter.ofPattern("EEE")) + ")" } ?: "") }
                answer("📚", "${all.size} to do", list, "${all.size} to do: $list")
            }
        }
        is Cmd.DoneHomework -> Ready("✓", "Done: ${cmd.text}", "Removes it from your list", "Done", quiet = true) { ctx ->
            Homework.done(ctx, cmd.text)
            val left = Homework.all(ctx).size
            val msg = "${cmd.text.replaceFirstChar { it.uppercase() }} is done!" + if (left == 0) " All homework finished" else ""
            if (!IslandBus.celebrate("🎉", msg)) tell(ctx, "🎉", msg)
            true
        }
        is Cmd.SaveWord -> Ready("🃏", "${cmd.front} = ${cmd.back}", "Saved to ${cmd.deck}", "Save", done = "Saved: ${cmd.front} = ${cmd.back}") { ctx ->
            Flashcards.add(ctx, cmd.deck, cmd.front, cmd.back)
            true
        }
        is Cmd.Flashcards -> Ready("🃏", "Flashcards" + (cmd.deck?.let { ": $it" } ?: ""), "${Flashcards.all(context, cmd.deck).size} cards", "Start", done = "") { ctx ->
            Flashcards.quiz(ctx, cmd.deck)
            true
        }.copy(quiet = true)
        is Cmd.Stopwatch -> stopwatch(context, cmd.action)
        is Cmd.PlaneWatch -> if (cmd.on) {
            Ready("✈", "Plane watch", "Planes flying over you pop up in the island for the next hour", "Start", done = "Plane watch on for 1 hour ✈") { ctx ->
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) false
                else { com.kinetic.launcher.island.PlaneWatch.start(ctx); true }
            }.copy(permission = Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            Ready("✈", "Plane watch off", "", "Stop", done = "Plane watch off") { _ -> com.kinetic.launcher.island.PlaneWatch.stop(); true }
        }
        is Cmd.TrackFlight -> if (cmd.shown == null || cmd.callsign == null) {
            val now = com.kinetic.launcher.island.FlightWatch.tracking
            if (now == null) answer("✈", "Not tracking a flight", "Write \"track UA123\"", "Not tracking a flight")
            else Ready("✈", "Stop tracking $now", "", "Stop", done = "Stopped tracking $now") { _ -> com.kinetic.launcher.island.FlightWatch.stop(); true }
        } else {
            Ready("✈", "Track ${cmd.shown}", "Live in the island until it lands", "Track", done = "Tracking ${cmd.shown} ✈") { ctx ->
                com.kinetic.launcher.island.FlightWatch.track(ctx, cmd.shown, cmd.callsign)
                true
            }
        }
        is Cmd.SchoolDays -> schoolDays(context, cmd.until)
        is Cmd.SetSummer -> Ready("🏫", "Summer starts " + cmd.day.format(DateTimeFormatter.ofPattern("MMMM d")), "Used by \u201Cschool days until summer\u201D", "Save", done = "Got it: summer starts " + cmd.day.format(DateTimeFormatter.ofPattern("MMMM d"))) { ctx ->
            prefs(ctx).edit().putString("summer", cmd.day.toString()).apply()
            true
        }
        is Cmd.Metar -> metar(cmd.station)
        is Cmd.Phonetic -> {
            val s = cmd.text.uppercase().filter { it.isLetterOrDigit() || it == ' ' }.map { ch -> if (ch == ' ') "/" else NATO[ch] ?: ch.toString() }.joinToString(" ")
            answer("📡", s, cmd.text.uppercase(), s)
        }
        is Cmd.FlightLevel -> answer("✈", "FL${cmd.fl} = %,d ft".format(cmd.fl * 100), "Standard pressure altitude (29.92 inHg)", "FL${cmd.fl} = %,d ft".format(cmd.fl * 100))
        is Cmd.Split -> {
            val each = cmd.amount / cmd.people
            answer("$", "${money(each)} each", "${money(cmd.amount)} split ${cmd.people} ways", "${money(each)} each · ${money(cmd.amount)} ÷ ${cmd.people}")
        }
        is Cmd.PercentOff -> {
            val saved = cmd.amount * cmd.pct / 100
            answer("%", "${money(cmd.amount - saved)}", "You save ${money(saved)} (${fmt(cmd.pct)}% off ${money(cmd.amount)})", "${money(cmd.amount - saved)} · save ${money(saved)}")
        }
        is Cmd.Zones -> {
            val f = DateTimeFormatter.ofPattern("h:mm a")
            val parts = cmd.places.mapNotNull { p -> zoneFor(p)?.let { z -> p.replaceFirstChar { it.uppercase() } + " " + ZonedDateTime.now(z).format(f) } }
            if (parts.isEmpty()) answer("◷", "Didn't recognise those places", "Try big cities", "Didn't recognise those places")
            else answer("◷", parts.first(), parts.drop(1).joinToString(" · "), parts.joinToString(" · "))
        }
        is Cmd.Tool -> when (cmd.which) {
            "report" -> Ready("!", "Report a problem", "Makes one file with Kinetic's log to send", "Make", done = "Making a problem report") { ctx ->
                com.kinetic.launcher.ProblemReport.send(ctx)
                true
            }
            "help" -> Ready("?", "What can I write?", "Every command, with Try buttons", "Open", done = "Here's everything you can write") { ctx ->
                start(ctx, Intent(ctx, HelpActivity::class.java))
            }
            "planes" -> Ready("✈", "What's that plane?", "Point at it", "Open", done = "Point at the plane") { ctx ->
                start(ctx, Intent(ctx, com.kinetic.launcher.planes.PlaneSpotterActivity::class.java))
            }
            else -> Ready("📐", cmd.which.replaceFirstChar { it.uppercase() }, "", "Open", done = cmd.which.replaceFirstChar { it.uppercase() }) { ctx ->
                start(ctx, Intent(ctx, ToolsActivity::class.java).putExtra(ToolsActivity.EXTRA_TOOL, cmd.which))
            }
        }
        is Cmd.Pick -> cmd.options.random().let { answer("🎲", it.replaceFirstChar { c -> c.uppercase() }, "Picked from ${cmd.options.size}", "I pick: $it") }
        Cmd.Again -> {
            val last = history(context).firstOrNull()
            if (last == null) answer("↻", "Nothing to repeat yet", "", "Nothing to repeat yet")
            else KineticCommands.parse(last)?.let { KineticCommands.resolve(context, it) }
        }
        Cmd.Undo -> {
            val u = undo
            if (u == null) answer("↶", "Nothing to undo", "Undo works for volume, brightness, ringer and Do Not Disturb", "Nothing to undo")
            else Ready("↶", "Undo ${u.first}", "", "Undo", done = "Undid ${u.first}") { ctx ->
                undo = null
                runCatching { u.second(ctx) }.isSuccess
            }
        }
        is Cmd.SetShortcut -> Ready("⌘", "“${cmd.short}” → ${cmd.command}", "Your shortcut", "Save", done = "Shortcut saved: ${cmd.short} → ${cmd.command}") { ctx ->
            setShortcut(ctx, cmd.short, cmd.command)
            true
        }
    }

    private fun answer(badge: String, title: String, sub: String, done: String) = Ready(badge, title, sub, "OK", answer = true, done = done) { false }

    private fun money(v: Double) = java.text.NumberFormat.getCurrencyInstance(Locale.US).format(v)
    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    private fun ago(at: Long): String {
        val m = (System.currentTimeMillis() - at) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    // ---- replies, call back, read it ----------------------------------------------------------

    /** The newest notification with a reply box. */
    private fun replyable(): Pair<android.service.notification.StatusBarNotification, Notification.Action>? {
        val l = com.kinetic.launcher.KineticNotificationListener.instance ?: return null
        return runCatching {
            l.activeNotifications.sortedByDescending { it.postTime }.firstNotNullOfOrNull { sbn ->
                sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let { sbn to it }
            }
        }.getOrNull()
    }

    private fun reply(context: Context, text: String, original: String): Ready {
        if (com.kinetic.launcher.KineticNotificationListener.instance == null) {
            return Ready("↩", "Reply", "Needs notification access", "Allow") { ctx -> MoreCommands.askSpecial(ctx, "↩", "Replies need notification access", PermissionActivity.NOTIFICATIONS, original) }
        }
        val (sbn, action) = replyable() ?: return answer("↩", "Nothing to reply to", "No message notification with a reply box", "Nothing to reply to")
        val who = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "them"
        return Ready("↩", "Reply to $who", "“$text”", "Send", confirm = true, done = "Replied to $who: “$text”") { ctx ->
            runCatching {
                val intent = Intent()
                val results = Bundle()
                action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
                action.actionIntent.send(ctx, 0, intent)
                true
            }.getOrDefault(false)
        }
    }

    private fun callBack(context: Context): Ready {
        val l = com.kinetic.launcher.KineticNotificationListener.instance
        val missed = runCatching {
            l?.activeNotifications?.sortedByDescending { it.postTime }?.firstOrNull { it.notification.category == Notification.CATEGORY_MISSED_CALL }
        }.getOrNull()
        val action = missed?.notification?.actions?.firstOrNull { (it.title?.toString()?.lowercase() ?: "").contains("call") }
        val who = missed?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: missed?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return Ready("☏", if (who != null) "Call back $who" else "Recent calls", if (action != null) "From your missed call" else "No missed call notification: opens your call history", "Call", confirm = action != null, done = if (who != null) "Calling $who" else "Recent calls") { ctx ->
            if (action != null) runCatching { action.actionIntent.send(); true }.getOrDefault(false)
            else start(ctx, Intent(Intent.ACTION_VIEW).setType(android.provider.CallLog.Calls.CONTENT_TYPE))
        }
    }

    private fun readIt(context: Context): Ready {
        val l = com.kinetic.launcher.KineticNotificationListener.instance
            ?: return answer("🔈", "Needs notification access", "", "Reading needs notification access")
        val sbn = runCatching {
            l.activeNotifications.filter { it.isClearable && it.packageName != context.packageName }.maxByOrNull { it.postTime }
        }.getOrNull() ?: return answer("🔈", "No notifications to read", "", "No notifications to read")
        val e = sbn.notification.extras
        val app = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault("")
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return Ready("🔈", title.ifBlank { app }, body, "Read", done = "Reading: $title") { ctx ->
            Speaker.say(ctx, "$app. $title. $body")
            true
        }
    }

    // ---- photos & schedule -----------------------------------------------------------------------

    private fun lastPhoto(context: Context, who: String?): Ready {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            return Ready("🖼", "Last photo", "Needs your photos", "Allow", Manifest.permission.READ_MEDIA_IMAGES) { false }
        }
        val id = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull() ?: return answer("🖼", "No photos found", "", "No photos found")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        if (who == null) {
            return Ready("🖼", "Your last photo", "", "Show", done = "Your last photo") { ctx ->
                start(ctx, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        // To a contact: Messages opens with the photo attached and their number filled in.
        val number = runCatching {
            context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null,
            )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: who) to (c.getString(1) ?: "") else null }
        }.getOrNull()
        return Ready("🖼", "Send last photo to ${number?.first ?: who}", "Opens in Messages with the photo attached", "Send", confirm = true, done = "Photo ready for ${number?.first ?: who}") { ctx ->
            val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            number?.let { send.putExtra("address", it.second).putExtra("sms_body", "") }
            val sms = Telephony().defaultSmsApp(ctx)
            if (sms != null) send.setPackage(sms)
            start(ctx, send) || start(ctx, Intent.createChooser(send.setPackage(null), "Send photo"))
        }
    }

    private class Telephony {
        fun defaultSmsApp(c: Context): String? = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(c) }.getOrNull()
    }

    private fun schedule(context: Context, day: LocalDate, label: String): Ready {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return Ready("📅", "$label's schedule", "Needs your calendar", "Allow", Manifest.permission.READ_CALENDAR) { false }
        }
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start)
        ContentUris.appendId(b, end)
        val f = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        val items = ArrayList<String>()
        runCatching {
            context.contentResolver.query(
                b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0) ?: "Event"
                    items += if (c.getInt(2) == 1) "$t (all day)" else "${f.format(java.util.Date(c.getLong(1)))} $t"
                }
            }
        }
        val h = Homework.all(context).filter { it.due == day }.map { "📚 ${it.text}" }
        val all = items + h
        return if (all.isEmpty()) answer("📅", "$label: nothing planned", "", "$label: nothing planned")
        else answer("📅", "$label: ${all.size} thing" + if (all.size == 1) "" else "s", all.joinToString(" · "), "$label: " + all.joinToString(" · "))
    }

    // ---- stopwatch, school days, METAR ----------------------------------------------------------

    private fun stopwatch(context: Context, action: String): Ready {
        val sw = com.kinetic.launcher.island.Stopwatch
        return when (action) {
            "start" -> Ready("⏱", "Stopwatch", "Counts in the island · write \"lap\" or \"stop\"", "Start", quiet = true) { ctx ->
                sw.start(ctx)
                if (!com.kinetic.launcher.island.DynamicIslandService.isRunning) tell(ctx, "⏱", "Stopwatch started · write \u201Clap\u201D or \u201Cstop\u201D")
                true
            }
            "lap" -> Ready("⏱", "Lap", "", "Lap", quiet = true) { ctx -> sw.lap(ctx); true }
            else -> Ready("⏱", "Stop stopwatch", "", "Stop", quiet = true) { ctx -> sw.stop(ctx); true }
        }
    }

    private fun tell(ctx: Context, icon: String, text: String) = IslandBus.tell(ctx, icon, text)

    private fun schoolDays(context: Context, until: String): Ready {
        val today = LocalDate.now()
        val target: LocalDate? = when (until) {
            "summer", "summer break", "summer vacation", "the end of school", "the last day of school" -> {
                val saved = prefs(context).getString("summer", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                saved?.takeIf { !it.isBefore(today) } ?: LocalDate.of(if (today.monthValue > 6) today.year + 1 else today.year, 6, 1)
            }
            "christmas break", "winter break" -> LocalDate.of(today.year, 12, 20).let { if (it.isBefore(today)) it.plusYears(1) else it }
            else -> Regex("""([a-z]+)\s+(\d{1,2})""").matchEntire(until)?.let { m ->
                java.time.Month.values().firstOrNull { it.name.lowercase().startsWith(m.groupValues[1].take(3)) }?.let { mo ->
                    runCatching { LocalDate.of(today.year, mo, m.groupValues[2].toInt()) }.getOrNull()?.let { if (it.isBefore(today)) it.plusYears(1) else it }
                }
            }
        }
        if (target == null) return answer("🏫", "Which day?", "Try \"school days until june 4\"", "Try \"school days until june 4\"")
        var d = today
        var n = 0
        while (d.isBefore(target)) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) n++
            d = d.plusDays(1)
        }
        val total = ChronoUnit.DAYS.between(today, target)
        return answer("🏫", "$n school days", "until ${target.format(DateTimeFormatter.ofPattern("MMMM d"))} ($total days, weekdays only, holidays not counted)", "$n school days until ${until}")
    }

    private fun metar(station: String): Ready {
        val json = runCatching {
            val c = (java.net.URL("https://aviationweather.gov/api/data/metar?ids=$station&format=json").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Kinetic-Launcher")
            }
            try {
                if (c.responseCode !in 200..299) null else JSONArray(c.inputStream.bufferedReader().readText())
            } finally {
                c.disconnect()
            }
        }.getOrNull()?.optJSONObject(0) ?: return answer("📡", "No METAR for $station", com.kinetic.launcher.Net.why("Check the airport code"), "No METAR for $station")
        val raw = json.optString("rawOb")
        val cat = json.optString("fltCat").ifBlank { null }
        val wdir = json.opt("wdir")
        val wspd = json.optInt("wspd", -1)
        val gust = json.optInt("wgst", -1)
        val vis = json.opt("visib")?.toString()
        val temp = if (json.has("temp")) json.optDouble("temp") else Double.NaN
        val dew = if (json.has("dewp")) json.optDouble("dewp") else Double.NaN
        val altim = if (json.has("altim")) json.optDouble("altim") else Double.NaN
        val clouds = json.optJSONArray("clouds")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.joinToString(", ") { c ->
                val cover = when (c.optString("cover")) {
                    "FEW" -> "few"
                    "SCT" -> "scattered"
                    "BKN" -> "broken"
                    "OVC" -> "overcast"
                    "CLR", "SKC" -> "clear"
                    else -> c.optString("cover").lowercase()
                }
                if (c.has("base")) "$cover at %,d ft".format(c.optInt("base")) else cover
            }
        }.orEmpty()
        val plain = buildList {
            cat?.let { add(it) }
            if (wspd == 0) add("calm wind") else if (wspd > 0) add("wind ${wdir ?: "VRB"}° at $wspd kt" + if (gust > 0) " gusting $gust" else "")
            vis?.let { add("$it mi visibility") }
            if (clouds.isNotBlank()) add(clouds)
            if (!temp.isNaN()) add("${temp.roundToInt()}°C" + if (!dew.isNaN()) "/${dew.roundToInt()}°C" else "")
            // The API gives the altimeter in hPa; pilots in the US use inches of mercury.
            if (!altim.isNaN()) add("altimeter %.2f".format(if (altim > 100) altim * 0.02953 else altim))
        }.joinToString(" · ")
        return answer("📡", "$station${cat?.let { " · $it" } ?: ""}", "$raw\n$plain", "$station: $plain")
    }

    private val NATO = mapOf(
        'A' to "Alpha", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Echo", 'F' to "Foxtrot", 'G' to "Golf",
        'H' to "Hotel", 'I' to "India", 'J' to "Juliett", 'K' to "Kilo", 'L' to "Lima", 'M' to "Mike", 'N' to "November",
        'O' to "Oscar", 'P' to "Papa", 'Q' to "Quebec", 'R' to "Romeo", 'S' to "Sierra", 'T' to "Tango", 'U' to "Uniform",
        'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray", 'Y' to "Yankee", 'Z' to "Zulu",
        '0' to "Zero", '1' to "One", '2' to "Two", '3' to "Tree", '4' to "Four", '5' to "Fife", '6' to "Six", '7' to "Seven", '8' to "Eight", '9' to "Niner",
    )

    private fun zoneFor(placeRaw: String): ZoneId? {
        val p = placeRaw.lowercase().removePrefix("the ").trim()
        val id = Assistant.CITY[p] ?: ZoneId.getAvailableZoneIds().firstOrNull { it.substringAfterLast('/').replace('_', ' ').equals(p, true) }
        return id?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    }
}

// ======================================================================================
// Memory: "remember I parked on level 3" (with where you were), "where did I park?"
// ======================================================================================
object Memory {
    data class Item(val at: Long, val text: String, val lat: Double, val lon: Double)

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    fun all(c: Context): List<Item> = runCatching {
        val arr = JSONArray(prefs(c).getString("memory", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Item(it.getLong("at"), it.getString("t"), it.optDouble("lat", 0.0), it.optDouble("lon", 0.0)) } }
    }.getOrDefault(emptyList())

    fun add(c: Context, text: String) {
        val loc = if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = c.getSystemService(LocationManager::class.java)
            runCatching {
                listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
            }.getOrNull()
        } else {
            null
        }
        // A new parking spot replaces the old one.
        val keep = all(c).filter { !(text.contains("park") && it.text.contains("park")) }
        save(c, listOf(Item(System.currentTimeMillis(), text, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)) + keep.take(99))
    }

    fun remove(c: Context, word: String) = save(c, all(c).filter { !it.text.contains(word) })

    /** Removes just one saved thing (the one saved at [at]). */
    fun removeItem(c: Context, at: Long) = save(c, all(c).filter { it.at != at })

    /** The newest memory sharing the most words with [query]. */
    fun find(c: Context, query: String): Item? {
        val stop = setOf("the", "my", "a", "an", "is", "was", "what", "where", "did", "i", "put", "at", "on", "in", "of", "to")
        val words = query.lowercase().split(' ', '\'').filter { it.length > 1 && it !in stop }
        if (words.isEmpty()) return null
        return all(c).map { it to words.count { w -> it.text.lowercase().contains(w.removeSuffix("s")) } }
            .filter { it.second > 0 && it.second * 2 >= words.size }.maxWithOrNull(compareBy({ it.second }, { it.first.at }))?.first
    }

    private fun save(c: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("at", it.at).put("t", it.text).put("lat", it.lat).put("lon", it.lon)) }
        prefs(c).edit().putString("memory", arr.toString()).apply()
    }
}

// ======================================================================================
// Homework / tasks, with a reminder the evening before (7 pm).
// ======================================================================================
object Homework {
    data class Task(val id: Long, val text: String, val due: LocalDate?)

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    fun all(c: Context): List<Task> = runCatching {
        val arr = JSONArray(prefs(c).getString("homework", "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Task(it.getLong("id"), it.getString("t"), it.optString("due").takeIf { s -> s.isNotBlank() }?.let(LocalDate::parse)) }
        }.sortedWith(compareBy(nullsLast()) { it.due })
    }.getOrDefault(emptyList())

    fun add(c: Context, text: String, due: LocalDate?) {
        val t = Task(System.currentTimeMillis(), text, due)
        save(c, all(c) + t)
        remind(c, t)
    }

    /** The evening before it's due, at 7 pm. */
    private fun remind(c: Context, t: Task) {
        val due = t.due ?: return
        val at = due.minusDays(1).atTime(19, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) return
        val pi = PendingIntent.getBroadcast(
            c, (t.id % Int.MAX_VALUE).toInt(),
            Intent(c, HomeworkReminder::class.java).putExtra("text", t.text).putExtra("id", t.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        c.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** After a restart every alarm is gone, so set them all again. */
    fun rescheduleAll(c: Context) = all(c).forEach { remind(c, it) }

    fun exists(c: Context, id: Long) = all(c).any { it.id == id }

    fun done(c: Context, text: String) {
        val list = all(c)
        val hit = list.firstOrNull { it.text.lowercase().contains(text.lowercase()) } ?: return
        save(c, list.filter { it.id != hit.id })
    }

    private fun save(c: Context, list: List<Task>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.text).put("due", it.due?.toString() ?: "")) }
        prefs(c).edit().putString("homework", arr.toString()).apply()
    }
}

/** "Due tomorrow: math page 42", in the island and as a notification. */
class HomeworkReminder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        // Already ticked off? Then stay quiet.
        val id = intent.getLongExtra("id", -1L)
        if (id != -1L && !Homework.exists(context, id)) return
        IslandBus.say("📚", "Due tomorrow: $text")
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("homework", "Homework", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(
                text.hashCode(),
                Notification.Builder(context, "homework").setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("Due tomorrow").setContentText(text).setAutoCancel(true).build(),
            )
        }
    }
}

/** Ends "snooze notifications for …". */
class SnoozeEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        com.kinetic.launcher.KineticApp.pen(context).edit().remove(KEY).apply()
        runCatching { context.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        IslandBus.say("🔔", "Notifications are back on")
    }

    companion object {
        const val KEY = "snooze_until"

        fun schedule(context: Context, minutes: Int) {
            val at = System.currentTimeMillis() + minutes * 60_000L
            // Remembered so a restart can still end the snooze.
            com.kinetic.launcher.KineticApp.pen(context).edit().putLong(KEY, at).apply()
            scheduleAt(context, at)
        }

        fun scheduleAt(context: Context, at: Long) {
            val pi = PendingIntent.getBroadcast(context, 11, Intent(context, SnoozeEndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

// ======================================================================================
// Flashcards in the island: the word drops down, "Show" reveals it, "Next" moves on.
// ======================================================================================
object Flashcards {
    data class Card(val deck: String, val front: String, val back: String)

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    fun all(c: Context, deck: String? = null): List<Card> = runCatching {
        val arr = JSONArray(prefs(c).getString("cards", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Card(o.getString("d"), o.getString("f"), o.getString("b")) } }
    }.getOrDefault(emptyList()).filter { deck == null || it.deck.equals(deck, true) || it.deck == "words" && deck == "words" }

    fun add(c: Context, deck: String, front: String, back: String) {
        val list = all(c).filter { !it.front.equals(front, true) } + Card(deck, front, back)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("d", it.deck).put("f", it.front).put("b", it.back)) }
        prefs(c).edit().putString("cards", arr.toString()).apply()
    }

    fun remove(c: Context, front: String) {
        val list = all(c).filter { !it.front.equals(front, true) }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("d", it.deck).put("f", it.front).put("b", it.back)) }
        prefs(c).edit().putString("cards", arr.toString()).apply()
    }

    fun quiz(c: Context, deck: String?) {
        val cards = all(c, deck).shuffled()
        if (cards.isEmpty()) {
            IslandBus.say("🃏", "No cards yet. Write \"save spanish word gato = cat\"")
            return
        }
        show(c, cards, 0)
    }

    private fun show(c: Context, cards: List<Card>, i: Int) {
        if (i >= cards.size) {
            if (!IslandBus.celebrate("🃏", "Done! ${cards.size} cards")) IslandBus.say("🃏", "Done! ${cards.size} cards 🎉")
            return
        }
        val card = cards[i]
        IslandBus.ask("🃏", "${i + 1}/${cards.size}  ${card.front}", "Show", secondary = "Stop") {
            IslandBus.ask("🃏", "${card.front} = ${card.back}", if (i + 1 < cards.size) "Next" else "Finish", secondary = "Stop") { show(c, cards, i + 1) }
        }
    }
}

/** Reads things out loud (keeps one voice ready). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(context: Context, text: String) {
        val t = tts
        if (t != null && ready) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kinetic")
            return
        }
        pending = text
        if (t == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "kinetic") }
                pending = null
            }
        }
    }
}

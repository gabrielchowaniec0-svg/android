package com.kinetic.launcher.ui

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kinetic.launcher.island.IslandBus
import com.kinetic.launcher.ui.KineticCommands.Ready
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * More Kinetic Commands (S Pen or search), many of them working straight inside apps:
 * music controls, volume/brightness/silent/Do Not Disturb, Spotify, Maps, Uber/Lyft,
 * WhatsApp, Translate, calendar events, Keep/Samsung Notes, websites, voice memos,
 * "where am I", tips, countdowns, random numbers.
 *
 * When something needs a permission, the Dynamic Island drops down with an Allow button.
 */
object MoreCommands {

    sealed interface Cmd {
        data class Media(val what: String) : Cmd // next, previous, pause, resume, playing
        data class Volume(val percent: Int) : Cmd // -1 = mute, -2 = unmute, -3 = up, -4 = down
        data class Ringer(val mode: String) : Cmd // silent, vibrate, ring
        data class Dnd(val on: Boolean) : Cmd
        data class Brightness(val percent: Int) : Cmd // -1 brighter, -2 dimmer
        data class Spotify(val query: String) : Cmd
        data class Maps(val query: String) : Cmd
        data class Ride(val app: String, val place: String) : Cmd
        data class WhatsApp(val who: String, val body: String) : Cmd
        data class Translate(val text: String, val lang: String, val code: String) : Cmd
        data class Event(val title: String, val start: LocalDateTime, val label: String) : Cmd
        data class NoteApp(val app: String, val text: String) : Cmd
        data class Website(val url: String) : Cmd
        data class Setting(val action: String, val label: String) : Cmd
        data object VoiceMemo : Cmd
        data object WhereAmI : Cmd
        data class Tip(val percent: Double, val amount: Double, val people: Int) : Cmd
        data class Countdown(val label: String, val date: LocalDate) : Cmd
        data class Random(val from: Int, val to: Int) : Cmd
        data object ScreenTime : Cmd
        data object Storage : Cmd
        data object Missed : Cmd
        data object ClearNotifications : Cmd
        data class ShareLocation(val who: String) : Cmd
        data class Copy(val text: String) : Cmd
        data object Joke : Cmd
        data class EightBall(val question: String) : Cmd
        data class Distance(val place: String) : Cmd
        data object Sun : Cmd
        data object Moon : Cmd
        data object PlanesNearby : Cmd
    }

    // ---- understanding ------------------------------------------------------------------

    private val LANGS = mapOf(
        "spanish" to "es", "french" to "fr", "german" to "de", "italian" to "it", "portuguese" to "pt", "polish" to "pl",
        "finnish" to "fi", "russian" to "ru", "ukrainian" to "uk", "japanese" to "ja", "korean" to "ko", "chinese" to "zh-CN",
        "arabic" to "ar", "hindi" to "hi", "dutch" to "nl", "swedish" to "sv", "norwegian" to "no", "danish" to "da",
        "greek" to "el", "turkish" to "tr", "hebrew" to "iw", "english" to "en", "czech" to "cs", "hungarian" to "hu",
        "vietnamese" to "vi", "thai" to "th", "tagalog" to "tl", "irish" to "ga", "latin" to "la",
    )

    fun parse(raw: String): Cmd? {
        val q = raw.trim().lowercase(Locale.US).trimEnd('.', '!', '?').replace('’', '\'')
        if (q.length < 3) return null
        fun r(p: String) = Regex(p).matchEntire(q)

        // Music
        when (q) {
            "next", "next song", "skip", "skip song", "next track", "skip this" -> return Cmd.Media("next")
            "previous", "previous song", "last song", "go back a song", "back a song" -> return Cmd.Media("previous")
            "pause", "pause music", "stop music", "pause the music" -> return Cmd.Media("pause")
            "resume", "resume music", "play music", "unpause", "continue music" -> return Cmd.Media("resume")
            "what's playing", "whats playing", "what song is this", "what's this song", "song" -> return Cmd.Media("playing")
            "mute" -> return Cmd.Volume(-1)
            "unmute" -> return Cmd.Volume(-2)
            "louder", "volume up", "turn it up" -> return Cmd.Volume(-3)
            "quieter", "softer", "volume down", "turn it down" -> return Cmd.Volume(-4)
            "silent", "silent mode", "silence phone" -> return Cmd.Ringer("silent")
            "vibrate", "vibrate mode", "vibrate only" -> return Cmd.Ringer("vibrate")
            "ring", "ringer on", "sound on", "normal mode" -> return Cmd.Ringer("ring")
            "dnd on", "do not disturb on", "turn on do not disturb", "turn on dnd" -> return Cmd.Dnd(true)
            "dnd off", "do not disturb off", "turn off do not disturb", "turn off dnd" -> return Cmd.Dnd(false)
            "brighter", "brightness up" -> return Cmd.Brightness(-1)
            "dimmer", "darker", "brightness down" -> return Cmd.Brightness(-2)
            "voice memo", "record audio", "record voice", "voice recorder", "record a voice memo" -> return Cmd.VoiceMemo
            "where am i", "my location", "what's my address", "whats my address", "where am i right now" -> return Cmd.WhereAmI
            "hotspot", "mobile hotspot" -> return Cmd.Setting("android.settings.TETHER_SETTINGS", "Mobile hotspot")
            "battery saver", "power saving", "power saving mode" -> return Cmd.Setting(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery saver")
            "airplane mode", "airplane" -> return Cmd.Setting(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode")
            "display", "dark mode", "display settings" -> return Cmd.Setting(Settings.ACTION_DISPLAY_SETTINGS, "Display")
            "storage" -> return Cmd.Setting(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage")
            "location settings", "gps" -> return Cmd.Setting(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location")
            "random number" -> return Cmd.Random(1, 100)
            "screen time", "how long have i been on my phone", "phone usage", "my screen time" -> return Cmd.ScreenTime
            "storage left", "how much storage", "free space", "space left" -> return Cmd.Storage
            "what did i miss", "notifications summary", "my notifications", "unread", "how many notifications" -> return Cmd.Missed
            "clear notifications", "clear all notifications", "dismiss notifications" -> return Cmd.ClearNotifications
            "tell me a joke", "joke", "make me laugh" -> return Cmd.Joke
            "sunset", "sunrise", "when is sunset", "when's sunset", "when is sunrise", "when's sunrise" -> return Cmd.Sun
            "moon", "moon phase", "what's the moon", "whats the moon" -> return Cmd.Moon
            "planes nearby", "planes overhead", "any planes", "how many planes", "what's flying", "whats flying" -> return Cmd.PlanesNearby
        }
        r("""(?:share|send)\s+(?:my\s+)?location\s+(?:with|to)\s+(.+)""")?.let { return Cmd.ShareLocation(it.groupValues[1].trim()) }
        r("""copy\s+(.+)""")?.let { return Cmd.Copy(it.groupValues[1].trim()) }
        r("""(?:magic 8 ball|8 ball|eight ball)\s*(.*)""")?.let { return Cmd.EightBall(it.groupValues[1]) }
        r("""(?:should i|will i|am i going to|is it going to be)\s+(.+)""")?.let { m ->
            if (listOf("rain", "snow", "umbrella", "jacket", "coat").none { m.groupValues[1].contains(it) }) return Cmd.EightBall(q)
        }
        r("""how far(?: away)? is\s+(.+?)(?:\s+from here)?""")?.let { return Cmd.Distance(it.groupValues[1].trim()) }
        r("""(?:volume|set volume(?: to)?)\s+(\d{1,3})%?""")?.let { return Cmd.Volume(it.groupValues[1].toInt().coerceIn(0, 100)) }
        r("""(?:brightness|set brightness(?: to)?)\s+(\d{1,3})%?""")?.let { return Cmd.Brightness(it.groupValues[1].toInt().coerceIn(0, 100)) }

        // Apps
        r("""(?:spotify|play on spotify|spotify play)\s+(.+)|play\s+(.+?)\s+on spotify""")?.let { m ->
            return Cmd.Spotify(m.groupValues[1].ifBlank { m.groupValues[2] }.trim())
        }
        r("""(?:maps|map|find|search maps for)\s+(.+?)(?:\s+(?:nearby|near me|around here))?""")?.let { m ->
            if (q.startsWith("find") && !q.contains("near") && !q.contains("nearby")) return@let
            return Cmd.Maps(m.groupValues[1].trim())
        }
        r("""(.+?)\s+(?:near me|nearby|around here)""")?.let { return Cmd.Maps(it.groupValues[1].trim()) }
        r("""(uber|lyft)(?:\s+(?:to|home))?\s*(.*)""")?.let { m -> return Cmd.Ride(m.groupValues[1], m.groupValues[2].trim().ifBlank { if (q.contains("home")) "home" else "" }) }
        r("""(?:whatsapp|wa)\s+(\S+)\s*(.*)""")?.let { return Cmd.WhatsApp(it.groupValues[1], it.groupValues[2].trim()) }
        r("""translate\s+(.+?)\s+(?:to|into|in)\s+([a-z]+)""")?.let { m ->
            val lang = m.groupValues[2]
            LANGS[lang]?.let { code -> return Cmd.Translate(m.groupValues[1].trim('"', '\''), lang, code) }
        }
        r("""how do (?:you|i) say\s+(.+?)\s+in\s+([a-z]+)""")?.let { m ->
            LANGS[m.groupValues[2]]?.let { code -> return Cmd.Translate(m.groupValues[1].trim('"', '\''), m.groupValues[2], code) }
        }
        r("""(?:add event|new event|schedule|add to calendar|calendar)\s+(.+?)\s+(today|tonight|tomorrow|on \w+day|\w+day)?\s*(?:at|@)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")?.let { m ->
            event(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5])?.let { return it }
        }
        r("""(?:keep|keep note|google keep)\s+(.+)""")?.let { return Cmd.NoteApp("keep", it.groupValues[1]) }
        r("""(?:samsung notes?|s note)\s+(.+)""")?.let { return Cmd.NoteApp("samsung", it.groupValues[1]) }
        r("""(?:go to|open|visit|website)?\s*((?:https?://)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|edu|gov|io|ai|co|app|dev|tv|me|us|uk|de|fi|pl)(?:/\S*)?)""")?.let { m ->
            val u = m.groupValues[1]
            return Cmd.Website(if (u.startsWith("http")) u else "https://$u")
        }

        // Little answers
        r("""(?:tip|(\d+(?:\.\d+)?)%\s*tip(?: on)?)\s*(?:(\d+(?:\.\d+)?)%\s*(?:on|of)?)?\s*\$?(\d+(?:\.\d+)?)(?:\s+(?:for|split|between)\s+(\d+)(?:\s+people)?)?""")?.let { m ->
            val pct = (m.groupValues[1].ifBlank { m.groupValues[2] }).toDoubleOrNull() ?: 20.0
            val amount = m.groupValues[3].toDoubleOrNull() ?: return@let
            return Cmd.Tip(pct, amount, m.groupValues[4].toIntOrNull()?.coerceIn(1, 50) ?: 1)
        }
        r("""(?:random number|pick a number|number)\s+(?:between|from)?\s*(\d+)\s*(?:to|and|-)\s*(\d+)""")?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return Cmd.Random(minOf(a, b), maxOf(a, b))
        }
        r("""(?:how many )?days (?:until|till|to|left until)\s+(.+)|(?:countdown to|how long until)\s+(.+)""")?.let { m ->
            countdown(m.groupValues[1].ifBlank { m.groupValues[2] }.trim())?.let { return it }
        }
        return null
    }

    private fun event(titleRaw: String, dayRaw: String, hS: String, mS: String, ap: String): Cmd? {
        var h = hS.toIntOrNull() ?: return null
        val mi = mS.toIntOrNull() ?: 0
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (ap.isEmpty() && h in 1..7) h += 12 // "at 3" usually means the afternoon
        var date = LocalDate.now()
        val day = dayRaw.removePrefix("on ").trim()
        when {
            day == "tomorrow" -> date = date.plusDays(1)
            day.endsWith("day") && day != "today" -> {
                val target = java.time.DayOfWeek.values().firstOrNull { it.name.lowercase() == day } ?: return null
                while (date.dayOfWeek != target || date == LocalDate.now()) date = date.plusDays(1)
            }
        }
        val start = date.atTime(h.coerceIn(0, 23), mi.coerceIn(0, 59))
        val title = titleRaw.replaceFirstChar { it.uppercase() }
        val label = start.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"))
        return Cmd.Event(title, start, label)
    }

    private fun countdown(what: String): Cmd? {
        val today = LocalDate.now()
        fun next(m: Int, d: Int): LocalDate {
            var x = LocalDate.of(today.year, m, d)
            if (x.isBefore(today)) x = x.plusYears(1)
            return x
        }
        val fixed = mapOf(
            "christmas" to (12 to 25), "christmas eve" to (12 to 24), "new year" to (1 to 1), "new years" to (1 to 1),
            "new year's" to (1 to 1), "halloween" to (10 to 31), "valentine's day" to (2 to 14), "valentines day" to (2 to 14),
            "4th of july" to (7 to 4), "fourth of july" to (7 to 4), "independence day" to (7 to 4), "st patrick's day" to (3 to 17),
        )
        fixed[what]?.let { (m, d) -> return Cmd.Countdown(what.replaceFirstChar { it.uppercase() }, next(m, d)) }
        if (what == "thanksgiving") {
            fun tg(y: Int): LocalDate {
                var x = LocalDate.of(y, 11, 1)
                while (x.dayOfWeek != java.time.DayOfWeek.THURSDAY) x = x.plusDays(1)
                return x.plusWeeks(3)
            }
            val t = tg(today.year).let { if (it.isBefore(today)) tg(today.year + 1) else it }
            return Cmd.Countdown("Thanksgiving", t)
        }
        // "march 3", "dec 25"
        val m = Regex("""([a-z]+)\s+(\d{1,2})""").matchEntire(what) ?: return null
        val month = java.time.Month.values().firstOrNull { it.name.lowercase().startsWith(m.groupValues[1].take(3)) } ?: return null
        val d = m.groupValues[2].toInt()
        if (d !in 1..31) return null
        return runCatching { Cmd.Countdown(what.replaceFirstChar { it.uppercase() }, next(month.value, d)) }.getOrNull()
    }

    // ---- doing ----------------------------------------------------------------------------

    fun resolve(context: Context, cmd: Cmd, original: String): Ready? = more(context, cmd, original) ?: when (cmd) {
        is Cmd.Media -> media(context, cmd.what, original)
        is Cmd.Volume -> {
            val label = when (cmd.percent) {
                -1 -> "Mute"
                -2 -> "Unmute"
                -3 -> "Louder"
                -4 -> "Quieter"
                else -> "Volume ${cmd.percent}%"
            }
            Ready("🔊", label, "Media volume", "Set", done = label) { ctx -> volume(ctx, cmd.percent) }
        }
        is Cmd.Ringer -> Ready(
            if (cmd.mode == "ring") "🔔" else "🔕", cmd.mode.replaceFirstChar { it.uppercase() } + " mode", "Ringer", "Set",
            done = cmd.mode.replaceFirstChar { it.uppercase() } + " mode on",
        ) { ctx -> ringer(ctx, cmd.mode, original) }
        is Cmd.Dnd -> Ready("☾", "Do Not Disturb " + if (cmd.on) "on" else "off", "", "Set", done = "Do Not Disturb " + if (cmd.on) "on" else "off") { ctx ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                askSpecial(ctx, "☾", "Do Not Disturb needs permission", PermissionActivity.DND, original)
            } else {
                nm.setInterruptionFilter(if (cmd.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                true
            }
        }
        is Cmd.Brightness -> {
            val label = when (cmd.percent) {
                -1 -> "Brighter"
                -2 -> "Dimmer"
                else -> "Brightness ${cmd.percent}%"
            }
            Ready("☀", label, "Screen brightness", "Set", done = label) { ctx -> brightness(ctx, cmd.percent, original) }
        }
        is Cmd.Spotify -> Ready("♫", "Play ${cmd.query}", "Spotify", "Play", done = "Playing ${cmd.query} on Spotify") { ctx ->
            start(
                ctx,
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage("com.spotify.music")
                    .putExtra(SearchManager.QUERY, cmd.query).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
            ) || start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(cmd.query))))
        }
        is Cmd.Maps -> Ready("⌖", cmd.query.replaceFirstChar { it.uppercase() }, "Near you · Maps", "Show", done = "Finding ${cmd.query} near you") { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(cmd.query))))
        }
        is Cmd.Ride -> {
            val app = cmd.app.replaceFirstChar { it.uppercase() }
            Ready("🚗", if (cmd.place.isBlank()) "Open $app" else "$app to ${cmd.place}", app, "Go", done = "$app" + if (cmd.place.isBlank()) "" else " to ${cmd.place}") { ctx ->
                val q = Uri.encode(cmd.place)
                val deep = if (cmd.app == "uber") {
                    "uber://?action=setPickup&pickup=my_location" + if (cmd.place.isNotBlank()) "&dropoff[formatted_address]=$q" else ""
                } else {
                    "lyft://ridetype?id=lyft" + if (cmd.place.isNotBlank()) "&destination[address]=$q" else ""
                }
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(deep))) ||
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (cmd.app == "uber") "https://m.uber.com/ul/" else "https://lyft.com/ride")))
            }
        }
        is Cmd.WhatsApp -> whatsapp(context, cmd)
        is Cmd.Translate -> Ready("文", "“${cmd.text}” in ${cmd.lang.replaceFirstChar { it.uppercase() }}", "Google Translate", "Translate", done = "Translating to ${cmd.lang.replaceFirstChar { it.uppercase() }}") { ctx ->
            start(
                ctx,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=${cmd.code}&text=" + Uri.encode(cmd.text) + "&op=translate")),
            )
        }
        is Cmd.Event -> Ready("📅", cmd.title, cmd.label, "Add", done = "Added: ${cmd.title} · ${cmd.label}") { ctx ->
            val begin = cmd.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            start(
                ctx,
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, cmd.title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60_000L),
            )
        }
        is Cmd.NoteApp -> {
            val (pkg, name) = if (cmd.app == "keep") "com.google.android.keep" to "Google Keep" else "com.samsung.android.app.notes" to "Samsung Notes"
            Ready("📝", cmd.text, name, "Save", done = "Sent to $name") { ctx ->
                start(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg).putExtra(Intent.EXTRA_TEXT, cmd.text)) ||
                    start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cmd.text), "Save note"))
            }
        }
        is Cmd.Website -> Ready("🌐", cmd.url.removePrefix("https://").removePrefix("http://"), "Website", "Open", done = "Opening ${cmd.url.removePrefix("https://")}") { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(cmd.url)))
        }
        is Cmd.Setting -> Ready("⚙", cmd.label, "Settings", "Open", done = cmd.label) { ctx ->
            start(ctx, Intent(cmd.action)) || start(ctx, Intent(Settings.ACTION_SETTINGS))
        }
        Cmd.VoiceMemo -> Ready("🎙", "Voice memo", "Voice Recorder", "Record", done = "Voice Recorder") { ctx ->
            start(ctx, Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)) ||
                (ctx.packageManager.getLaunchIntentForPackage("com.sec.android.app.voicenote")?.let { start(ctx, it) } ?: false)
        }
        Cmd.WhereAmI -> whereAmI(context)
        is Cmd.Tip -> {
            val tip = cmd.amount * cmd.percent / 100.0
            val total = cmd.amount + tip
            val each = if (cmd.people > 1) " · ${money(total / cmd.people)} each" else ""
            val title = "Tip ${money(tip)} · total ${money(total)}"
            answer("$", title, "${fmtPct(cmd.percent)}% on ${money(cmd.amount)}$each", title + each)
        }
        is Cmd.Countdown -> {
            val days = ChronoUnit.DAYS.between(LocalDate.now(), cmd.date)
            val title = when (days) {
                0L -> "${cmd.label} is today! 🎉"
                1L -> "1 day until ${cmd.label}"
                else -> "$days days until ${cmd.label}"
            }
            answer("◷", title, cmd.date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")), title)
        }
        is Cmd.Random -> {
            val n = (cmd.from..cmd.to).random()
            answer("🎲", "$n", "Random number from ${cmd.from} to ${cmd.to}", "$n · random ${cmd.from}–${cmd.to}")
        }
        else -> null
    }

    // ---- more island answers ------------------------------------------------------------------

    private fun more(context: Context, cmd: Cmd, original: String): Ready? = when (cmd) {
        Cmd.ScreenTime -> {
            if (!KineticCommands.hasUsageAccess(context)) {
                Ready("⏱", "Screen time", "Needs usage access", "Allow") { ctx -> askSpecial(ctx, "⏱", "Screen time needs usage access", PermissionActivity.USAGE, original) }
            } else {
                val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val stats = usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
                    .filterKeys { it != context.packageName }
                    .mapValues { it.value.totalTimeInForeground }.filterValues { it > 60_000 }
                val total = stats.values.sum()
                val top = stats.entries.sortedByDescending { it.value }.take(3).joinToString(" · ") { (pkg, ms) ->
                    val l = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    "$l ${dur(ms)}"
                }
                answer("⏱", "${dur(total)} today", top, "Screen time ${dur(total)} · $top")
            }
        }
        Cmd.Storage -> {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val free = st.availableBytes / 1_000_000_000.0
            val total = st.totalBytes / 1_000_000_000.0
            val t = "%.0f GB free".format(free)
            answer("▤", t, "of %.0f GB".format(total), "$t of %.0f GB".format(total))
        }
        Cmd.Missed -> {
            val counts = com.kinetic.launcher.NotificationHub.counts.value
            if (counts.isEmpty()) {
                answer("☰", "You're all caught up", "", "You're all caught up ✓")
            } else {
                val pm = context.packageManager
                val parts = counts.entries.sortedByDescending { it.value }.take(4).joinToString(" · ") { (pkg, n) ->
                    val l = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    "$n $l"
                }
                val total = counts.values.sum()
                answer("☰", "$total unread", parts, "$total unread · $parts")
            }
        }
        Cmd.ClearNotifications -> Ready("☰", "Clear all notifications", "Everything that can be cleared", "Clear", confirm = true, done = "Notifications cleared") { _ ->
            val l = com.kinetic.launcher.KineticNotificationListener.instance ?: return@Ready false
            runCatching { l.cancelAllNotifications() }.isSuccess
        }
        is Cmd.ShareLocation -> {
            val loc = lastLocation(context)
            if (loc == null) {
                Ready("⌖", "Share location", "Needs your location", "Allow", Manifest.permission.ACCESS_FINE_LOCATION) { false }
            } else {
                val link = "https://maps.google.com/?q=%.5f,%.5f".format(Locale.US, loc.latitude, loc.longitude)
                KineticCommands.resolve(context, KineticCommands.Parsed.Text(cmd.who, "I'm here: $link"))
            }
        }
        is Cmd.Copy -> Ready("⧉", "Copy “${cmd.text}”", "To the clipboard", "Copy", done = "Copied “${cmd.text}”") { ctx ->
            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Kinetic", cmd.text))
            true
        }
        Cmd.Joke -> JOKES.random().let { answer("😄", it.first, it.second, "${it.first} … ${it.second}") }
        is Cmd.EightBall -> EIGHT.random().let { answer("🎱", it, cmd.question.ifBlank { "Magic 8 ball" }, "🎱 $it") }
        is Cmd.Distance -> {
            val me = lastLocation(context)
            if (me == null) {
                Ready("⌖", "How far is ${cmd.place}", "Needs your location", "Allow", Manifest.permission.ACCESS_FINE_LOCATION) { false }
            } else {
                @Suppress("DEPRECATION")
                val there = runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(cmd.place, 1)?.firstOrNull() }.getOrNull()
                if (there == null) {
                    answer("⌖", "Couldn't find “${cmd.place}”", "Try a fuller name or city", "Couldn't find ${cmd.place}")
                } else {
                    val out = FloatArray(1)
                    android.location.Location.distanceBetween(me.latitude, me.longitude, there.latitude, there.longitude, out)
                    val mi = out[0] / 1609.34
                    val t = if (mi < 10) "%.1f miles".format(mi) else "%,.0f miles".format(mi)
                    Ready("⌖", "${cmd.place.replaceFirstChar { it.uppercase() }}: $t", "As the crow flies", "Directions", answer = true, done = "${cmd.place.replaceFirstChar { it.uppercase() }} is $t away") { ctx ->
                        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(cmd.place))))
                    }
                }
            }
        }
        Cmd.Sun -> {
            val p = context.getSharedPreferences("kinetic_settings", Context.MODE_PRIVATE)
            val rise = p.getLong("wp_sunrise", 0L)
            val set = p.getLong("wp_sunset", 0L)
            if (rise == 0L || set == 0L) {
                answer("☀", "Not known yet", "Turn on Sunset & moon in Settings ▸ Living wallpaper", "Sun times aren't known yet")
            } else {
                val f = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
                // Saved times may be from an earlier day; the clock time is what matters.
                val t = "Sunrise ${f.format(java.util.Date(rise))} · Sunset ${f.format(java.util.Date(set))}"
                answer("☀", t, "Where you are", t)
            }
        }
        Cmd.Moon -> {
            val days = (System.currentTimeMillis() - 947_182_440_000L) / 86_400_000.0
            val phase = ((days / 29.530588853) % 1.0 + 1.0) % 1.0
            val (name, icon) = when {
                phase < 0.03 || phase > 0.97 -> "New moon" to "🌑"
                phase < 0.22 -> "Waxing crescent" to "🌒"
                phase < 0.28 -> "First quarter" to "🌓"
                phase < 0.47 -> "Waxing gibbous" to "🌔"
                phase < 0.53 -> "Full moon" to "🌕"
                phase < 0.72 -> "Waning gibbous" to "🌖"
                phase < 0.78 -> "Last quarter" to "🌗"
                else -> "Waning crescent" to "🌘"
            }
            val lit = ((1 - kotlin.math.cos(2 * Math.PI * phase)) / 2 * 100).roundToInt()
            val toFull = (((0.5 - phase + 1.0) % 1.0) * 29.53).roundToInt()
            answer(icon, name, "$lit% lit · full moon in $toFull days", "$name · $lit% lit")
        }
        Cmd.PlanesNearby -> {
            val me = lastLocation(context)
            if (me == null) {
                Ready("✈", "Planes nearby", "Needs your location", "Allow", Manifest.permission.ACCESS_FINE_LOCATION) { false }
            } else {
                val planes = com.kinetic.launcher.planes.PlaneData.nearby(me.latitude, me.longitude, 20)
                val closest = planes.minByOrNull { com.kinetic.launcher.planes.PlaneData.distanceM(me.latitude, me.longitude, it.lat, it.lon) }
                if (closest == null) {
                    answer("✈", "No planes nearby right now", "Within 23 miles", "No planes nearby right now")
                } else {
                    val mi = com.kinetic.launcher.planes.PlaneData.distanceM(me.latitude, me.longitude, closest.lat, closest.lon) / 1609.34
                    val t = "${planes.size} planes nearby"
                    val s = "Closest: ${closest.label} · %,d ft · %.1f mi".format(closest.altitudeFt, mi)
                    Ready("✈", t, s, "Spot", answer = true, done = "$t · $s") { ctx ->
                        start(ctx, Intent(ctx, com.kinetic.launcher.planes.PlaneSpotterActivity::class.java))
                    }
                }
            }
        }
        else -> null
    }

    private fun dur(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun lastLocation(context: Context): android.location.Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return runCatching {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    private val JOKES = listOf(
        "Why did the airplane get sent to its room?" to "Bad altitude.",
        "What do you call a pilot who's always late?" to "A delayed departure.",
        "Why don't skeletons fight each other?" to "They don't have the guts.",
        "What do you call a fake noodle?" to "An impasta.",
        "Why did the scarecrow win an award?" to "He was outstanding in his field.",
        "How does a phone propose?" to "With a ring.",
        "Why was the math book sad?" to "It had too many problems.",
        "What did the ocean say to the beach?" to "Nothing, it just waved.",
        "Why can't you trust atoms?" to "They make up everything.",
        "What do clouds wear?" to "Thunderwear.",
    )

    private val EIGHT = listOf(
        "It is certain", "Without a doubt", "Yes, definitely", "Most likely", "Outlook good", "Signs point to yes",
        "Ask again later", "Better not tell you now", "Cannot predict now", "Don't count on it", "My reply is no", "Very doubtful",
    )

    private fun answer(badge: String, title: String, sub: String, done: String) = Ready(badge, title, sub, "OK", answer = true, done = done) { false }

    private fun money(v: Double) = java.text.NumberFormat.getCurrencyInstance(Locale.US).format(v)
    private fun fmtPct(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    // ---- music ------------------------------------------------------------------------------

    private fun controller(context: Context): MediaController? = runCatching {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, com.kinetic.launcher.KineticNotificationListener::class.java))
            .firstOrNull()
    }.getOrNull()

    private fun media(context: Context, what: String, original: String): Ready {
        val c = controller(context)
        val title = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        if (c == null && !com.kinetic.launcher.NotificationHub.connected.value) {
            return Ready("♫", "Music controls", "Needs notification access", "Allow") { ctx ->
                askSpecial(ctx, "♫", "Music controls need notification access", PermissionActivity.NOTIFICATIONS, original)
            }
        }
        if (what == "playing") {
            return if (title == null) answer("♫", "Nothing is playing", "", "Nothing is playing")
            else answer("♫", title, artist ?: "", title + (artist?.let { " · $it" } ?: ""))
        }
        val label = when (what) {
            "next" -> "Next song"
            "previous" -> "Previous song"
            "pause" -> "Pause"
            else -> "Play"
        }
        return Ready("♫", label, title?.let { "Now: $it" } ?: "Music", label, done = label + (title?.let { " · $it" } ?: "")) { _ ->
            val t = c?.transportControls ?: return@Ready false
            when (what) {
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "pause" -> t.pause()
                else -> if (c.playbackState?.state != PlaybackState.STATE_PLAYING) t.play()
            }
            true
        }
    }

    // ---- sound & screen --------------------------------------------------------------------------

    private fun volume(context: Context, p: Int): Boolean {
        val a = context.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        when (p) {
            -1 -> a.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            -2 -> a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            -3 -> repeat(2) { a.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
            -4 -> repeat(2) { a.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
            else -> a.setStreamVolume(s, (a.getStreamMaxVolume(s) * p / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    private fun ringer(context: Context, mode: String, original: String): Boolean {
        val a = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        // Silent (and leaving it) needs Do Not Disturb access on modern Android.
        if (!nm.isNotificationPolicyAccessGranted && (mode == "silent" || a.ringerMode == AudioManager.RINGER_MODE_SILENT)) {
            return askSpecial(context, "🔕", "Silent mode needs permission", PermissionActivity.DND, original)
        }
        return runCatching {
            a.ringerMode = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            true
        }.getOrDefault(false)
    }

    private fun brightness(context: Context, p: Int, original: String): Boolean {
        if (!Settings.System.canWrite(context)) {
            return askSpecial(context, "☀", "Brightness needs permission", PermissionActivity.WRITE_SETTINGS, original)
        }
        val cr = context.contentResolver
        val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (p) {
            -1 -> now + 50
            -2 -> now - 50
            else -> (p * 255 / 100)
        }.coerceIn(1, 255)
        return runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target); true }.getOrDefault(false)
    }

    // ---- contacts & location ---------------------------------------------------------------------

    private fun whatsapp(context: Context, cmd: Cmd.WhatsApp): Ready {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Ready("💬", "WhatsApp ${cmd.who}", "Needs your contacts", "Allow", Manifest.permission.READ_CONTACTS) { false }
        }
        val number = lookupNumber(context, cmd.who)
            ?: return Ready("💬", "WhatsApp ${cmd.who}", "No number for “${cmd.who}” in your contacts", "OK", answer = true) { false }
        val digits = number.second.filter { it.isDigit() }.let { if (it.length == 10) "1$it" else it }
        return Ready("💬", "WhatsApp ${number.first}", cmd.body.ifBlank { "New message" }, "Open", done = "WhatsApp · ${number.first}") { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits" + if (cmd.body.isNotBlank()) "?text=" + Uri.encode(cmd.body) else "")).setPackage("com.whatsapp")) ||
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits" + if (cmd.body.isNotBlank()) "?text=" + Uri.encode(cmd.body) else "")))
        }
    }

    private fun lookupNumber(context: Context, who: String): Pair<String, String>? = runCatching {
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null,
        )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: who) to (c.getString(1) ?: "") else null }
    }.getOrNull()

    private fun whereAmI(context: Context): Ready {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return Ready("⌖", "Where am I", "Needs your location", "Allow", Manifest.permission.ACCESS_FINE_LOCATION) { false }
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = runCatching {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull() ?: return answer("⌖", "Couldn't find you yet", "Try again in a moment", "Couldn't find you yet")
        @Suppress("DEPRECATION")
        val addr = runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
        val line = addr?.let { a -> listOfNotNull(a.thoroughfare?.let { (a.subThoroughfare?.plus(" ") ?: "") + it }, a.locality, a.adminArea).joinToString(", ") }
            ?.takeIf { it.isNotBlank() } ?: "%.4f, %.4f".format(Locale.US, loc.latitude, loc.longitude)
        return Ready("⌖", line, "You are here", "Map", answer = true, done = "You're at $line") { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}")))
        }
    }

    // ---- permission from the island -------------------------------------------------------------------

    /** Drops an "Allow" button down from the island (or opens the page straight away if the island is off). */
    fun askSpecial(context: Context, icon: String, text: String, kind: String, retry: String): Boolean {
        val go = { PermissionActivity.start(context, kind, retry) }
        if (!IslandBus.ask(icon, text, "Allow", onTap = go)) go()
        return true
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

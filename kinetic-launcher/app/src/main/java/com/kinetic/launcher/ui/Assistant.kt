package com.kinetic.launcher.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.kinetic.launcher.island.DynamicIslandService
import com.kinetic.launcher.ui.KineticCommands.Parsed
import com.kinetic.launcher.ui.KineticCommands.Ready
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The "mini assistant" part of Kinetic Commands: actions (screenshot, record video,
 * open an app, lock…) and questions it answers itself (weather, calendar, battery,
 * time in a city, unit conversions, flights). Works typed in search or written with
 * the S Pen. Everything runs on the phone except the weather and flight lookups.
 */
object Assistant {

    enum class Action {
        Screenshot, SecureScreenshot, Video, Photo, Selfie, Lock, Vault, Notifications,
        QuickSettings, Bluetooth, PhoneSettings, KineticSettings, Focus, Wallpaper, Recents,
    }

    sealed interface Question {
        data class Weather(val rainOnly: Boolean) : Question
        data class CalendarFind(val what: String) : Question
        data object CalendarNext : Question
        data object Battery : Question
        data class TimeIn(val place: String) : Question
        data object Today : Question
        data class Convert(val amount: Double, val from: String, val to: String) : Question
        data class Flight(val airline: String, val number: String) : Question
        data object Coin : Question
        data class Dice(val sides: Int) : Question
    }

    // ---- understanding ----------------------------------------------------------------

    private val OPEN = Regex("""^(?:open|launch|start|run|show)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app)?$""")
    private val SEARCH = Regex("""^(?:search(?: for)?|google|look up|search the web for)\s+(.+)$""")
    private val REMIND_AT = Regex("""^remind me\s+(?:to\s+)?(.+?)\s+(?:at|@)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?$""")
    private val REMIND_AT_FIRST = Regex("""^remind me\s+(?:at|@)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\s+(?:to\s+)?(.+)$""")
    private val REMIND_IN = Regex("""^remind me\s+(?:to\s+)?(.+?)\s+in\s+(.+)$""")
    private val REMIND_IN_FIRST = Regex("""^remind me\s+in\s+(.+?)\s+(?:to\s+)?(.+)$""")
    private val WHEN = Regex("""^(?:when(?:'s| is| are)|when do i have|when's|whens)\s+(?:my|the|our)?\s*(.+)$""")
    private val TIME_IN = Regex("""^(?:what(?:'s| is)?\s+)?(?:the\s+)?time(?:\s+is\s+it)?\s+in\s+(.+)$""")
    private val CONVERT = Regex("""^([\d.,]+)\s*([a-z°"'/ .]+?)\s+(?:in|to|into|as)\s+([a-z°"'/ .]+)$""")
    private val FLIGHT = Regex("""^(?:where(?:'s| is)|track|find|flight|status of|where is flight)\s+(?:flight\s+)?([a-z][a-z0-9]|[a-z]{3})\s?(\d{1,4})$""")
    private val DICE = Regex("""^roll\s+(?:a|one|the)?\s*(?:d(\d+)|dice|die)$""")

    fun parse(raw: String): Parsed? {
        val q = raw.trim().lowercase(Locale.US)
            .removePrefix("hey kinetic").removePrefix("kinetic,").removePrefix("please").trim()
            .trimEnd('?', '.', '!').replace('’', '\'').trim()
        if (q.length < 3) return null

        action(q)?.let { return Parsed.Act(it) }

        // Questions
        if (Regex("""\bweather\b|^temperature$|^temp$|how (hot|cold|warm) is it|what'?s it like outside""").containsMatchIn(q)) return Parsed.Ask(Question.Weather(false))
        if (Regex("""^(will|is) it (going to )?(rain|snow|storm)|^(do|should) i (need|bring|take) an? (umbrella|jacket|coat)|^any rain""").containsMatchIn(q)) return Parsed.Ask(Question.Weather(true))
        if (Regex("""^(what'?s|what is) (next|on my calendar|my next (event|meeting|appointment))|^next (event|meeting|appointment)$|^(my )?(schedule|agenda|calendar)( today)?$|^what do i have (today|next|coming up)""").containsMatchIn(q)) return Parsed.Ask(Question.CalendarNext)
        WHEN.matchEntire(q)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.isNotBlank() && !what.startsWith("sunset") && !what.startsWith("sunrise")) return Parsed.Ask(Question.CalendarFind(what))
        }
        if (Regex("""^(battery|battery level|battery life|how much battery( do i have)?( left)?|battery percentage|how charged)$""").matches(q)) return Parsed.Ask(Question.Battery)
        TIME_IN.matchEntire(q)?.let { return Parsed.Ask(Question.TimeIn(it.groupValues[1].trim())) }
        if (Regex("""^(what'?s|what is) (the date|today'?s date|the day)( today)?$|^what day is (it|today)$|^today'?s date$|^date$""").matches(q)) return Parsed.Ask(Question.Today)
        CONVERT.matchEntire(q)?.let { m ->
            val amount = m.groupValues[1].replace(",", "").toDoubleOrNull()
            val from = unit(m.groupValues[2])
            val to = unit(m.groupValues[3])
            if (amount != null && from != null && to != null && from.kind == to.kind) return Parsed.Ask(Question.Convert(amount, from.key, to.key))
        }
        FLIGHT.matchEntire(q)?.let { m -> return Parsed.Ask(Question.Flight(m.groupValues[1].uppercase(), m.groupValues[2])) }
        if (Regex("""^(flip|toss) a coin$|^heads or tails$""").matches(q)) return Parsed.Ask(Question.Coin)
        DICE.matchEntire(q)?.let { return Parsed.Ask(Question.Dice(it.groupValues[1].toIntOrNull()?.coerceIn(2, 100) ?: 6)) }

        // Reminders become a labelled alarm or timer.
        REMIND_AT.matchEntire(q)?.let { m -> alarmAt(m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[1])?.let { return it } }
        REMIND_AT_FIRST.matchEntire(q)?.let { m -> alarmAt(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])?.let { return it } }
        REMIND_IN.matchEntire(q)?.let { m -> KineticCommands.parseDuration(m.groupValues[2])?.let { return Parsed.Timer(it, m.groupValues[1].trim()) } }
        REMIND_IN_FIRST.matchEntire(q)?.let { m -> KineticCommands.parseDuration(m.groupValues[1])?.let { return Parsed.Timer(it, m.groupValues[2].trim()) } }

        SEARCH.matchEntire(q)?.let { return Parsed.Search(it.groupValues[1].trim()) }
        OPEN.matchEntire(q)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty() && name !in setOf("camera roll")) return Parsed.Open(name)
        }
        return null
    }

    private fun action(q: String): Action? = when {
        Regex("""^(take (a |the )?)?(secure|private|vault|hidden|secret) (screen ?shot|screen capture|capture)$""").matches(q) -> Action.SecureScreenshot
        Regex("""^(take (a |the )?)?(screen ?shot|screen capture|snapshot)( of (the|my) screen)?$|^capture (the |my )?screen$""").matches(q) -> Action.Screenshot
        Regex("""^(start |begin |take (a )?)?(record(ing)? (a )?video|video( recording)?|recording video|record a clip)$|^(start )?recording$|^record$""").matches(q) -> Action.Video
        Regex("""^(take (a |my )?)?selfie$|^front camera$""").matches(q) -> Action.Selfie
        Regex("""^(take (a )?)(photo|picture|pic)$|^camera$|^(photo|picture)$""").matches(q) -> Action.Photo
        Regex("""^lock( the| my)?( phone| screen| device)?$|^lock it$""").matches(q) -> Action.Lock
        Regex("""^(open )?(the |my )?vault$""").matches(q) -> Action.Vault
        Regex("""^(show |open )?(my )?notifications?$""").matches(q) -> Action.Notifications
        Regex("""^(show |open )?quick (settings|panel|toggles)$""").matches(q) -> Action.QuickSettings
        Regex("""^(open )?bluetooth( settings)?$""").matches(q) -> Action.Bluetooth
        Regex("""^(open )?(phone |android |system )?settings$""").matches(q) -> Action.PhoneSettings
        Regex("""^(open )?kinetic( settings)?$""").matches(q) -> Action.KineticSettings
        Regex("""^(open )?(focus|do not disturb|dnd)( mode)?$""").matches(q) -> Action.Focus
        Regex("""^(change |open )?(the |my )?wallpaper$""").matches(q) -> Action.Wallpaper
        Regex("""^(recent apps|recents|app switcher|multitask)$""").matches(q) -> Action.Recents
        else -> null
    }

    private fun alarmAt(hourS: String, minS: String, ampmRaw: String, label: String): Parsed? {
        var h = hourS.toIntOrNull() ?: return null
        val m = minS.toIntOrNull() ?: 0
        val ampm = ampmRaw.replace(".", "")
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        if (ampm.isEmpty() && h in 1..11) {
            // "at 6" means the next 6 o'clock coming up.
            val now = java.util.Calendar.getInstance()
            val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            if (nowMin >= h * 60 + m && nowMin < (h + 12) * 60 + m) h += 12
        }
        if (h !in 0..23 || m !in 0..59) return null
        return Parsed.Alarm(h, m, label.trim().replaceFirstChar { it.uppercase() })
    }

    // ---- doing ------------------------------------------------------------------------

    fun resolveAction(context: Context, a: Action): Ready = when (a) {
        Action.Screenshot -> {
            val on = DynamicIslandService.isEnabled(context)
            Ready("⬚", "Take a screenshot", if (on) "Saved to your gallery" else "Needs the Dynamic Island switch (Accessibility)", if (on) "Take" else "Set up", quiet = true) { ctx ->
                DynamicIslandService.screenshotLater(650) || run { DynamicIslandService.openSettings(ctx); true }
            }
        }
        Action.SecureScreenshot -> {
            val ready = DynamicIslandService.isEnabled(context) && com.kinetic.launcher.vault.VaultCrypto.canDrop(context)
            Ready(
                "🔒", "Secure screenshot",
                if (ready) "Goes straight into the Vault, never the gallery" else "Needs the Dynamic Island switch and the Vault set up",
                if (ready) "Take" else "Set up", quiet = true,
            ) { ctx ->
                DynamicIslandService.secureScreenshotLater(650) || run {
                    if (!DynamicIslandService.isEnabled(ctx)) DynamicIslandService.openSettings(ctx) else com.kinetic.launcher.vault.VaultActivity.open(ctx)
                    true
                }
            }
        }
        Action.Video -> Ready("●", "Record a video", "Opens the camera in video mode, tap record", "Open") { ctx ->
            start(ctx, Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
        }
        Action.Photo -> Ready("◉", "Take a photo", "Opens the camera", "Open") { ctx ->
            start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }
        Action.Selfie -> Ready("☺", "Take a selfie", "Opens the front camera", "Open") { ctx ->
            start(
                ctx,
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .putExtra("android.intent.extras.CAMERA_FACING", 1)
                    .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    .putExtra("camerafacing", "front"),
            )
        }
        Action.Lock -> Ready("⏻", "Lock the phone", "Turns the screen off", "Lock", quiet = true) { ctx ->
            DynamicIslandService.lockScreen() || run { DynamicIslandService.openSettings(ctx); true }
        }
        Action.Vault -> Ready("🔒", "Vault", "Encrypted photos & videos", "Open") { ctx -> com.kinetic.launcher.vault.VaultActivity.open(ctx); true }
        Action.Notifications -> Ready("☰", "Notifications", "Pull down the shade", "Show", quiet = true) { ctx ->
            DynamicIslandService.globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) || com.kinetic.core.system.SystemGestures.expandNotifications(ctx)
        }
        Action.QuickSettings -> Ready("☰", "Quick settings", "Toggles", "Show", quiet = true) { ctx ->
            DynamicIslandService.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) || com.kinetic.core.system.SystemGestures.expandNotifications(ctx)
        }
        Action.Bluetooth -> Ready("ᛒ", "Bluetooth", "Bluetooth settings", "Open") { ctx -> start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        Action.PhoneSettings -> Ready("⚙", "Settings", "Phone settings", "Open") { ctx -> start(ctx, Intent(Settings.ACTION_SETTINGS)) }
        Action.KineticSettings -> Ready("⚙", "Kinetic settings", "Island, wallpaper, Vault, S Pen…", "Open") { ctx ->
            start(ctx, Intent(ctx, com.kinetic.launcher.SettingsActivity::class.java))
        }
        Action.Focus -> Ready("☾", "Focus", "Sleep, School, Gaming…", "Open") { ctx -> com.kinetic.launcher.FocusActivity.open(ctx); true }
        Action.Wallpaper -> Ready("◐", "Wallpaper", "Styles, colours and the living wallpaper", "Open") { ctx ->
            start(ctx, Intent(ctx, com.kinetic.launcher.WallpaperStudioActivity::class.java))
        }
        Action.Recents -> Ready("❐", "Recent apps", "App switcher", "Show", quiet = true) { _ ->
            DynamicIslandService.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }

    fun resolveOpen(context: Context, name: String): Ready? {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName && it.first !in KineticCommands.hiddenPackages }
            .distinctBy { it.first }
        val squash = name.replace(" ", "")
        val best = apps.firstOrNull { it.second.replace(" ", "").equals(squash, true) }
            ?: apps.map { it to Fuzzy.score(name, it.second) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            ?: return null
        val launch = pm.getLaunchIntentForPackage(best.first) ?: return null
        return Ready("↗", "Open ${best.second}", "App", "Open") { ctx -> start(ctx, launch) }
    }

    fun resolveSearch(query: String): Ready = Ready("⌕", "Search the web", query, "Search") { ctx ->
        start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query)) ||
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }

    // ---- answering ----------------------------------------------------------------------

    /** Network lookups happen here, so call it off the main thread. */
    fun resolveQuestion(context: Context, q: Question): Ready? = when (q) {
        is Question.Weather -> weather(context, q.rainOnly)
        is Question.CalendarFind -> calendar(context, q.what)
        Question.CalendarNext -> calendar(context, null)
        Question.Battery -> battery(context)
        is Question.TimeIn -> timeIn(q.place)
        Question.Today -> {
            val d = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            answer("📅", d, "Today", d)
        }
        is Question.Convert -> convert(q)
        is Question.Flight -> flight(context, q)
        Question.Coin -> (if (Math.random() < 0.5) "Heads" else "Tails").let { answer("◎", it, "Coin flip", it) }
        is Question.Dice -> (1 + (Math.random() * q.sides).toInt()).let { answer("⚄", "You rolled $it", "d${q.sides}", it.toString()) }
    }

    /** An answer card. Tapping it copies the answer. */
    private fun answer(badge: String, title: String, subtitle: String, copy: String, open: ((Context) -> Boolean)? = null) =
        Ready(badge, title, subtitle, if (open != null) "Open" else "Copy", answer = true) { ctx ->
            if (open != null) {
                open(ctx)
            } else {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Kinetic", copy))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                false
            }
        }

    private fun allow(badge: String, title: String, why: String, permission: String) =
        Ready(badge, title, why, "Allow", permission) { false }

    private fun has(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // Weather (Open-Meteo, no account; rough location only)
    private fun weather(context: Context, rainOnly: Boolean): Ready? {
        if (!has(context, Manifest.permission.ACCESS_COARSE_LOCATION)) return allow("☁", "Weather", "Allow location to get your local weather", Manifest.permission.ACCESS_COARSE_LOCATION)
        val loc = lastLocation(context) ?: return answer("☁", "Weather", "Couldn't find where you are yet", "")
        val metric = Locale.getDefault().country !in setOf("US", "LR", "MM")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, loc.latitude, loc.longitude) +
            "&current=temperature_2m,weather_code,apparent_temperature&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1" + if (metric) "" else "&temperature_unit=fahrenheit"
        val json = get(url) ?: return answer("☁", "Weather", "Couldn't reach the weather service", "")
        val cur = json.optJSONObject("current") ?: return null
        val daily = json.optJSONObject("daily")
        val t = cur.optDouble("temperature_2m").roundToInt()
        val feels = cur.optDouble("apparent_temperature").roundToInt()
        val code = cur.optInt("weather_code")
        val hi = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.roundToInt()
        val lo = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.roundToInt()
        val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0
        val open: (Context) -> Boolean = { ctx ->
            start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "weather")) ||
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather")))
        }
        if (rainOnly) {
            val title = when {
                rain >= 60 -> "Yes, rain is likely today ($rain%)"
                rain >= 30 -> "Maybe: $rain% chance of rain"
                else -> "No rain expected ($rain%)"
            }
            return answer(weatherIcon(code), title, "Now $t° · ${weatherText(code)}" + if (rain >= 30) " · bring an umbrella" else "", title, open)
        }
        val title = "$t° · ${weatherText(code)}"
        val sub = buildString {
            if (hi != null && lo != null) append("High $hi° · Low $lo°")
            if (feels != t) append(" · Feels like $feels°")
            if (rain >= 20) append(" · $rain% rain")
        }
        return answer(weatherIcon(code), title, sub, "$title. $sub", open)
    }

    private fun weatherText(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 66, 80, 81 -> "Rain"
        65, 67, 82 -> "Heavy rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorms"
        else -> "—"
    }

    private fun weatherIcon(code: Int) = when (code) {
        0, 1 -> "☀"
        2 -> "⛅"
        3, 45, 48 -> "☁"
        71, 73, 75, 77, 85, 86 -> "❄"
        95, 96, 99 -> "⚡"
        in 51..82 -> "☂"
        else -> "☁"
    }

    // Calendar: next matching event in the coming 3 months.
    private val STOP = setOf("my", "the", "a", "an", "next", "appointment", "appt", "meeting", "event", "with", "at", "for", "is", "in", "on", "of", "to")

    private fun calendar(context: Context, what: String?): Ready {
        if (!has(context, Manifest.permission.READ_CALENDAR)) return allow("📅", if (what == null) "What's next" else "When's ${what}", "Allow calendar so Kinetic can look", Manifest.permission.READ_CALENDAR)
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + 92L * 24 * 3600 * 1000)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        data class Ev(val id: Long, val title: String, val begin: Long, val allDay: Boolean, val place: String?)
        val events = ArrayList<Ev>()
        runCatching {
            context.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && events.size < 400) {
                    events += Ev(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getInt(3) == 1, c.getString(4)?.takeIf { it.isNotBlank() })
                }
            }
        }
        val upcoming = events.filter { it.allDay || it.begin >= now - 5 * 60_000 }
        val ev = if (what == null) {
            upcoming.firstOrNull { !it.allDay } ?: upcoming.firstOrNull()
        } else {
            val phrase = what.lowercase()
            val words = phrase.split(' ', '\'').filter { it.length > 1 && it !in STOP }
            upcoming.firstOrNull { it.title.lowercase().contains(phrase) }
                ?: upcoming.firstOrNull { e -> words.isNotEmpty() && words.all { w -> e.title.lowercase().contains(w) } }
                ?: upcoming.firstOrNull { e -> words.any { w -> w.length >= 3 && e.title.lowercase().contains(w) } }
        }
        if (ev == null) {
            return answer("📅", if (what == null) "Nothing coming up" else "No “$what” found", "Checked the next 3 months of your calendar", "")
        }
        val whenText = describeWhen(ev.begin, ev.allDay)
        return answer("📅", ev.title.ifBlank { "Event" }, whenText + (ev.place?.let { " · $it" } ?: ""), "${ev.title}: $whenText") { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.id)))
        }
    }

    private fun describeWhen(begin: Long, allDay: Boolean): String {
        val zone = if (allDay) java.util.TimeZone.getTimeZone("UTC") else java.util.TimeZone.getDefault()
        val day = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).apply { timeZone = zone }.format(Date(begin))
        val time = if (allDay) "All day" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(begin))
        val mins = (begin - System.currentTimeMillis()) / 60_000
        val rel = when {
            allDay -> ""
            mins < 1 -> " · now"
            mins < 60 -> " · in $mins min"
            mins < 24 * 60 -> " · in ${mins / 60} h ${mins % 60} min"
            else -> " · in ${(mins + 12 * 60) / (24 * 60)} days"
        }
        return "$day · $time$rel"
    }

    private fun battery(context: Context): Ready {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sub = if (bm.isCharging) {
            val ms = bm.computeChargeTimeRemaining()
            if (ms > 0) "Charging · full in ${ms / 3_600_000} h ${(ms / 60_000) % 60} min".replace("0 h ", "") else "Charging"
        } else {
            "Not charging"
        }
        return answer("▮", "$pct% battery", sub, "$pct%") { ctx -> start(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
    }

    private val CITY = mapOf(
        "nyc" to "America/New_York", "new york" to "America/New_York", "ny" to "America/New_York", "boston" to "America/New_York",
        "miami" to "America/New_York", "atlanta" to "America/New_York", "dc" to "America/New_York", "washington" to "America/New_York",
        "la" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles", "sf" to "America/Los_Angeles", "seattle" to "America/Los_Angeles",
        "las vegas" to "America/Los_Angeles", "vegas" to "America/Los_Angeles", "chicago" to "America/Chicago", "dallas" to "America/Chicago",
        "houston" to "America/Chicago", "denver" to "America/Denver", "phoenix" to "America/Phoenix", "hawaii" to "Pacific/Honolulu",
        "honolulu" to "Pacific/Honolulu", "alaska" to "America/Anchorage", "london" to "Europe/London", "uk" to "Europe/London",
        "england" to "Europe/London", "paris" to "Europe/Paris", "france" to "Europe/Paris", "berlin" to "Europe/Berlin",
        "germany" to "Europe/Berlin", "poland" to "Europe/Warsaw", "warsaw" to "Europe/Warsaw", "krakow" to "Europe/Warsaw",
        "rome" to "Europe/Rome", "italy" to "Europe/Rome", "madrid" to "Europe/Madrid", "spain" to "Europe/Madrid",
        "finland" to "Europe/Helsinki", "helsinki" to "Europe/Helsinki", "moscow" to "Europe/Moscow", "dubai" to "Asia/Dubai",
        "india" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata", "beijing" to "Asia/Shanghai",
        "china" to "Asia/Shanghai", "hong kong" to "Asia/Hong_Kong", "tokyo" to "Asia/Tokyo", "japan" to "Asia/Tokyo",
        "seoul" to "Asia/Seoul", "korea" to "Asia/Seoul", "singapore" to "Asia/Singapore", "sydney" to "Australia/Sydney",
        "australia" to "Australia/Sydney", "auckland" to "Pacific/Auckland", "new zealand" to "Pacific/Auckland",
        "toronto" to "America/Toronto", "mexico city" to "America/Mexico_City", "mexico" to "America/Mexico_City",
        "sao paulo" to "America/Sao_Paulo", "brazil" to "America/Sao_Paulo",
    )

    private fun timeIn(placeRaw: String): Ready? {
        val place = placeRaw.lowercase().removePrefix("the ").trim()
        val id = CITY[place] ?: ZoneId.getAvailableZoneIds().firstOrNull {
            it.substringAfterLast('/').replace('_', ' ').equals(place, true)
        } ?: return answer("◷", "Don't know “$placeRaw”", "Try a big city nearby", "")
        val there = ZonedDateTime.now(ZoneId.of(id))
        val here = ZonedDateTime.now()
        val diffH = (there.offset.totalSeconds - here.offset.totalSeconds) / 3600.0
        val diff = when {
            diffH == 0.0 -> "Same time as you"
            else -> {
                val n = if (diffH % 1.0 == 0.0) "%d".format(kotlin.math.abs(diffH).toInt()) else "%.1f".format(kotlin.math.abs(diffH))
                "$n hours " + if (diffH > 0) "ahead" else "behind"
            }
        }
        val time = there.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val day = there.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        val name = placeRaw.split(' ').joinToString(" ") { w -> if (w.length <= 3) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
        return answer("◷", "$time in $name", "$day · $diff", time)
    }

    // ---- unit conversion ----------------------------------------------------------------

    private class U(val key: String, val kind: String, val factor: Double)

    private val UNITS: Map<String, U> = buildMap {
        fun add(kind: String, factor: Double, vararg names: String) = names.forEach { put(it, U(names.first(), kind, factor)) }
        add("len", 0.001, "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        add("len", 0.01, "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add("len", 1.0, "m", "meter", "meters", "metre", "metres")
        add("len", 1000.0, "km", "kilometer", "kilometers", "kilometre", "kilometres", "kms")
        add("len", 0.0254, "in", "inch", "inches", "\"")
        add("len", 0.3048, "ft", "foot", "feet", "'")
        add("len", 0.9144, "yd", "yard", "yards")
        add("len", 1609.344, "mi", "mile", "miles")
        add("len", 1852.0, "nmi", "nautical mile", "nautical miles", "nm")
        add("mass", 0.001, "g", "gram", "grams")
        add("mass", 1.0, "kg", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 0.45359237, "lb", "lbs", "pound", "pounds")
        add("mass", 0.028349523125, "oz", "ounce", "ounces")
        add("mass", 6.35029318, "st", "stone", "stones")
        add("vol", 0.001, "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        add("vol", 1.0, "l", "liter", "liters", "litre", "litres")
        add("vol", 3.785411784, "gal", "gallon", "gallons")
        add("vol", 0.946352946, "qt", "quart", "quarts")
        add("vol", 0.2365882365, "cup", "cups")
        add("vol", 0.0295735295625, "fl oz", "floz", "fluid ounce", "fluid ounces")
        add("vol", 0.01478676478125, "tbsp", "tablespoon", "tablespoons")
        add("vol", 0.00492892159375, "tsp", "teaspoon", "teaspoons")
        add("speed", 0.44704, "mph", "miles per hour")
        add("speed", 1 / 3.6, "km/h", "kmh", "kph", "kilometers per hour", "kilometres per hour")
        add("speed", 0.514444, "kt", "knot", "knots", "kts")
        add("speed", 1.0, "m/s", "meters per second")
        add("time", 1.0, "s", "sec", "secs", "second", "seconds")
        add("time", 60.0, "min", "mins", "minute", "minutes")
        add("time", 3600.0, "h", "hr", "hrs", "hour", "hours")
        add("time", 86400.0, "day", "days")
        add("time", 604800.0, "week", "weeks")
        add("temp", 1.0, "°c", "c", "celsius", "degrees c", "degrees celsius", "centigrade")
        add("temp", 1.0, "°f", "f", "fahrenheit", "degrees f", "degrees fahrenheit")
        add("temp", 1.0, "k", "kelvin", "kelvins")
        add("data", 1.0, "mb", "megabyte", "megabytes")
        add("data", 1024.0, "gb", "gigabyte", "gigabytes")
        add("data", 1024.0 * 1024, "tb", "terabyte", "terabytes")
    }

    private fun unit(s: String): U? = UNITS[s.trim().removeSuffix(".").trim()]

    private fun convert(q: Question.Convert): Ready? {
        val from = UNITS[q.from] ?: return null
        val to = UNITS[q.to] ?: return null
        val v = if (from.kind == "temp") {
            val c = when (from.key) { "°f" -> (q.amount - 32) * 5 / 9; "k" -> q.amount - 273.15; else -> q.amount }
            when (to.key) { "°f" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> c }
        } else {
            q.amount * from.factor / to.factor
        }
        val out = fmt(v)
        val title = "$out ${label(to.key, v)}"
        return answer("⇄", title, "${fmt(q.amount)} ${label(from.key, q.amount)}", out)
    }

    private fun label(key: String, v: Double) = when (key) {
        "°c" -> "°C"
        "°f" -> "°F"
        "k" -> "K"
        "mi" -> if (v == 1.0) "mile" else "miles"
        "ft" -> if (v == 1.0) "foot" else "feet"
        "in" -> if (v == 1.0) "inch" else "inches"
        "cup" -> if (v == 1.0) "cup" else "cups"
        "day" -> if (v == 1.0) "day" else "days"
        "week" -> if (v == 1.0) "week" else "weeks"
        else -> key
    }

    private fun fmt(v: Double): String {
        val a = kotlin.math.abs(v)
        return when {
            a >= 1000 -> "%,.0f".format(v)
            a >= 100 -> "%.1f".format(v).removeSuffix(".0")
            a >= 1 -> "%.2f".format(v).trimEnd('0').trimEnd('.')
            else -> "%.4f".format(v).trimEnd('0').trimEnd('.')
        }
    }

    // ---- flights --------------------------------------------------------------------------

    private val AIRLINE = mapOf(
        "UA" to "UAL", "AA" to "AAL", "DL" to "DAL", "WN" to "SWA", "B6" to "JBU", "AS" to "ASA", "NK" to "NKS", "F9" to "FFT",
        "G4" to "AAY", "HA" to "HAL", "SY" to "SCX", "AC" to "ACA", "WS" to "WJA", "AM" to "AMX", "BA" to "BAW", "VS" to "VIR",
        "LH" to "DLH", "AF" to "AFR", "KL" to "KLM", "LO" to "LOT", "AY" to "FIN", "SK" to "SAS", "IB" to "IBE", "EI" to "EIN",
        "LX" to "SWR", "OS" to "AUA", "TK" to "THY", "EK" to "UAE", "QR" to "QTR", "EY" to "ETD", "SQ" to "SIA", "CX" to "CPA",
        "NH" to "ANA", "JL" to "JAL", "KE" to "KAL", "QF" to "QFA", "FR" to "RYR", "U2" to "EZY", "OO" to "SKW", "YX" to "RPA",
        "MQ" to "ENY", "9E" to "EDV", "OH" to "JIA", "ZW" to "AWI", "C5" to "UCA", "G7" to "GJS", "YV" to "ASH", "QX" to "QXE",
    )

    private fun flight(context: Context, f: Question.Flight): Ready {
        val icao = if (f.airline.length == 3) f.airline else AIRLINE[f.airline] ?: f.airline
        val callsign = icao + f.number
        val shown = f.airline + f.number
        val route = com.kinetic.launcher.planes.PlaneData.route(callsign) ?: com.kinetic.launcher.planes.PlaneData.route(shown)
        val live = com.kinetic.launcher.planes.PlaneData.byCallsign(callsign)
        val routeText = route?.let { "${it.fromCode} → ${it.toCode}" }
        val title = shown + (routeText?.let { " · $it" } ?: "")
        val sub = if (live != null) {
            val me = if (has(context, Manifest.permission.ACCESS_COARSE_LOCATION)) lastLocation(context) else null
            buildString {
                append("In the air · %,d ft".format(live.altitudeFt))
                live.speedKt?.let { append(" · ${(it * 1.15078).roundToInt()} mph") }
                if (me != null) {
                    val mi = com.kinetic.launcher.planes.PlaneData.distanceM(me.latitude, me.longitude, live.lat, live.lon) / 1609.34
                    append(" · %,.0f mi from you".format(mi))
                }
            }
        } else {
            (route?.let { "${it.fromCity} to ${it.toCity} · " } ?: "") + "Not in the air right now"
        }
        return answer("✈", title, sub, title) { ctx ->
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flightaware.com/live/flight/$callsign")))
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun lastLocation(context: Context): Location? = try {
        val lm = context.getSystemService(LocationManager::class.java)
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    private fun get(url: String): JSONObject? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

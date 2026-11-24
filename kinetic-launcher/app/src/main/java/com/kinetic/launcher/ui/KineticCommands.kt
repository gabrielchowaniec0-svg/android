package com.kinetic.launcher.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.SearchManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Process
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Kinetic Commands: type what you want in the search box — "call Mom", "text Dad
 * on my way", "route to O'Hare", "timer 10 min", "alarm 6:30am", "play lofi",
 * "youtube landing tutorials", "flashlight on" — and Kinetic does it with the app
 * you use most for that kind of thing.
 */
object KineticCommands {

    sealed interface Parsed {
        data class Call(val who: String) : Parsed
        data class Text(val who: String, val body: String) : Parsed
        data class Email(val who: String) : Parsed
        data class Route(val place: String) : Parsed
        data class Timer(val seconds: Int, val label: String) : Parsed
        data class Alarm(val hour: Int, val minute: Int, val label: String) : Parsed
        data class Play(val query: String) : Parsed
        data class YouTube(val query: String) : Parsed
        data class Torch(val on: Boolean) : Parsed
        data class Panel(val action: String, val label: String) : Parsed
        data object Planes : Parsed
        data class Open(val app: String) : Parsed
        data class Act(val action: Assistant.Action) : Parsed
        data class Ask(val question: Assistant.Question) : Parsed
        data class Search(val query: String) : Parsed
        data class FocusTimer(val seconds: Int) : Parsed
        data class Note(val text: String) : Parsed
        data class Chain(val name: String, val lines: List<String>) : Parsed
        data class More(val cmd: MoreCommands.Cmd, val original: String) : Parsed
        data class Three(val cmd: Commands3.Cmd, val original: String) : Parsed
    }

    /** Hidden apps (while they're hidden): "open …" never reveals them. Set by the home screen. */
    @Volatile
    var hiddenPackages: Set<String> = emptySet()

    /** A command ready to run, shown as a card at the top of search. */
    data class Ready(
        val badge: String,
        val title: String,
        val subtitle: String,
        val actionLabel: String,
        /** Runtime permission needed first (asked when you run it), if any. */
        val permission: String? = null,
        /** Written with the S Pen: show it in search to confirm instead of running it (e.g. sending a text). */
        val confirm: Boolean = false,
        /** An answer to a question (weather, calendar…): show it, don't "run" it. */
        val answer: Boolean = false,
        /** Don't show the "✎ …" pill (it would end up in a screenshot). */
        val quiet: Boolean = false,
        /** What the island says once it's done ("Calling Mom", "100 °C = 212 °F"). Defaults to [title]. */
        val done: String? = null,
        val run: (Context) -> Boolean,
    )

    // ---- understanding what was typed ----------------------------------------------------

    private val CALL = Regex("""^(?:call|ring|phone|dial|facetime)\s+(.+)$""", RegexOption.IGNORE_CASE)
    // "text Mom Smith saying hi" / "text Mom: hi" (name before the marker) …
    private val TEXT_MARKED = Regex("""^(?:text|message|msg|sms|tell)\s+(.+?)(?:\s+(?:saying|that)\s+|\s*[:,]\s*)(.+)$""", RegexOption.IGNORE_CASE)
    // … or "text Dad on my way" (first word is the name).
    private val TEXT = Regex("""^(?:text|message|msg|sms|tell)\s+(\S+)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""^(?:email|e-mail|mail)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val ROUTE = Regex("""^(?:route to|directions to|navigate to|take me to|drive to|go to|get me to|how do i get to)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val TIMER = Regex("""^(?:set (?:a )?)?timer(?: for)?\s+(.+)$|^(.+?)\s+timer$""", RegexOption.IGNORE_CASE)
    private val ALARM = Regex("""^(?:set (?:an )?)?(?:alarm|wake me(?: up)?)(?: for| at)?\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val PLAY = Regex("""^(?:play|listen to)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val YOUTUBE = Regex("""^(?:youtube|yt|watch)\s+(.+)$|^(.+?)\s+on youtube$""", RegexOption.IGNORE_CASE)
    private val TORCH = Regex("""^(?:turn\s+)?(?:the\s+)?(?:flashlight|torch|flash)\s*(on|off)?$|^(?:turn\s+)?(on|off)\s+(?:the\s+)?(?:flashlight|torch)$""", RegexOption.IGNORE_CASE)

    private val VERBS = listOf(
        "call", "ring", "phone", "dial", "text", "message", "tell", "email", "route", "directions", "navigate",
        "drive", "timer", "alarm", "play", "youtube", "watch", "flashlight", "torch",
    )

    fun parse(written: String): Parsed? {
        // Your own shortcuts ("m" → "call mom") are expanded first.
        val input = com.kinetic.launcher.KineticApp.context?.let { Commands3.shortcut(it, written) } ?: written
        // Forgive a typo in the first word ("rout to ohare", "cal mom", "tiemr 5 min").
        val q = Fuzzy.fixFirstWord(input, VERBS)
        if (q.length < 3) return null
        // Your own command chains come first ("gaming mode").
        com.kinetic.launcher.KineticApp.context?.let { c -> com.kinetic.launcher.pen.CommandChains.find(c, input)?.let { return Parsed.Chain(it.first, it.second) } }
        NOTE.matchEntire(input.trim())?.let { return Parsed.Note(it.groupValues[1].trim()) }
        Commands3.parse(input)?.let { return Parsed.Three(it, input) }
        MoreCommands.parse(input)?.let { return Parsed.More(it, input) }
        // Things the assistant should get first ("open camera" isn't "open" + a contact…).
        Assistant.parse(input)?.let { if (it is Parsed.Act || it is Parsed.Ask) return it }
        CALL.matchEntire(q)?.let { return Parsed.Call(it.groupValues[1].trim()) }
        TEXT_MARKED.matchEntire(q)?.let { return Parsed.Text(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        TEXT.matchEntire(q)?.let { return Parsed.Text(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        EMAIL.matchEntire(q)?.let { return Parsed.Email(it.groupValues[1].trim()) }
        ROUTE.matchEntire(q)?.let { return Parsed.Route(it.groupValues[1].trim()) }
        TIMER.matchEntire(q)?.let { m ->
            val spec = m.groupValues[1].ifBlank { m.groupValues[2] }
            parseDuration(spec)?.let { return Parsed.Timer(it, "") }
        }
        ALARM.matchEntire(q)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3].lowercase().replace(".", "")
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return Parsed.Alarm(h, min, m.groupValues[4].trim())
        }
        YOUTUBE.matchEntire(q)?.let { m -> return Parsed.YouTube(m.groupValues[1].ifBlank { m.groupValues[2] }.trim()) }
        PLAY.matchEntire(q)?.let { m ->
            // "play store", "play games"… are app names, not songs.
            val what = m.groupValues[1].trim()
            if (what.lowercase() !in setOf("store", "games", "services", "books", "movies")) return Parsed.Play(what)
        }
        TORCH.matchEntire(q)?.let { m ->
            val word = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase()
            return Parsed.Torch(word != "off")
        }
        when (q.lowercase()) {
            "wifi", "wi-fi", "internet" -> return Parsed.Panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Internet")
            "volume", "sound" -> return Parsed.Panel(Settings.Panel.ACTION_VOLUME, "Volume")
            "nfc" -> return Parsed.Panel(Settings.Panel.ACTION_NFC, "NFC")
            "plane", "planes", "plane spotter", "spot planes", "spot plane", "flights", "what plane", "whats that plane", "what's that plane" ->
                return Parsed.Planes
        }
        return Assistant.parse(input)
    }

    /** "10 min", "1h 30m", "90 seconds", "half an hour", "an hour", "5". */
    fun parseDuration(text: String): Int? {
        val t = text.lowercase().trim()
        if (t == "half an hour" || t == "half hour") return 1800
        if (t == "an hour" || t == "one hour") return 3600
        var total = 0
        var found = false
        Regex("""(\d+(?:\.\d+)?)\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)?""").findAll(t).forEach { m ->
            if (m.value.isBlank()) return@forEach
            val n = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") -> (n * 3600).toInt()
                unit.startsWith("s") -> n.toInt()
                else -> (n * 60).toInt() // minutes by default
            }
            found = true
        }
        return if (found && total in 1..86_400) total else null
    }

    // ---- turning it into an action -------------------------------------------------------

    /** Looks up contacts and apps (call off the main thread). Null = nothing sensible to do. */
    fun resolve(context: Context, parsed: Parsed): Ready? = when (parsed) {
        is Parsed.Call -> {
            val direct = parsed.who.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 3 && parsed.who.none { c -> c.isLetter() } }
            val c = if (direct == null) findPhone(context, parsed.who) else null
            val number = direct ?: c?.second
            if (number == null) {
                if (!hasContacts(context)) Ready("☏", "Call ${parsed.who}", "Allow contacts so Kinetic can find them", "Allow", Manifest.permission.READ_CONTACTS) { false }
                else null
            } else {
                val name = c?.first ?: number
                Ready("☏", "Call $name", number + (c?.third?.let { " · $it" } ?: ""), "Call", Manifest.permission.CALL_PHONE, confirm = true, done = "Calling $name") { ctx ->
                    // With permission it calls straight away; otherwise the dialer opens ready to go.
                    val action = if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
                    start(ctx, Intent(action, Uri.parse("tel:" + Uri.encode(number))))
                }
            }
        }
        is Parsed.Text -> {
            val c = findPhone(context, parsed.who)
            when {
                c != null -> {
                    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.second))).putExtra("sms_body", parsed.body)
                    val app = mostUsed(context, sms)
                    if (parsed.body.isNotBlank()) {
                        // With a message: send it straight away (Android asks once for SMS
                        // permission). If that isn't allowed, Messages opens with it typed in.
                        Ready("✉", "Text ${c.first}", "\u201C${parsed.body}\u201D", "Send", Manifest.permission.SEND_SMS, confirm = true) { ctx ->
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED && sendSms(ctx, c.second, parsed.body)) {
                                val said = "Texted \u201C${parsed.body}\u201D to ${c.first}"
                                if (!com.kinetic.launcher.island.IslandBus.say("✉", said)) {
                                    android.widget.Toast.makeText(ctx, said, android.widget.Toast.LENGTH_SHORT).show()
                                }
                                // "be there in 10" → offer a reminder for that time.
                                Commands3.followUp(ctx, parsed.body, c.first)
                                true
                            } else {
                                start(ctx, sms.apply { if (app != null) setPackage(app) })
                            }
                        }
                    } else {
                        Ready("✉", "Text ${c.first}", "New message · " + appLabel(context, app, "Messages"), "Text") { ctx ->
                            start(ctx, sms.apply { if (app != null) setPackage(app) })
                        }
                    }
                }
                !hasContacts(context) -> Ready("✉", "Text ${parsed.who}", "Allow contacts so Kinetic can find them", "Allow", Manifest.permission.READ_CONTACTS) { false }
                else -> null
            }
        }
        is Parsed.Email -> {
            val e = findEmail(context, parsed.who)
            if (e == null) null
            else {
                val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(e.second)))
                val app = mostUsed(context, mail)
                Ready("@", "Email ${e.first}", e.second + " · " + appLabel(context, app, "Mail"), "Write") { ctx ->
                    start(ctx, mail.apply { if (app != null) setPackage(app) })
                }
            }
        }
        is Parsed.Route -> {
            // Whichever of your maps apps you use most.
            val candidates = listOf(
                "com.google.android.apps.maps" to Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(parsed.place))),
                "com.waze" to Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=" + Uri.encode(parsed.place) + "&navigate=yes")),
                "com.sec.android.app.samsungmaps" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(parsed.place))),
            ).filter { installed(context, it.first) }
            val best = pickMostUsed(context, candidates.map { it.first })
            val chosen = candidates.firstOrNull { it.first == best } ?: candidates.firstOrNull()
            val intent = chosen?.second?.setPackage(chosen.first) ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(parsed.place)))
            Ready("➤", "Route to ${parsed.place}", appLabel(context, chosen?.first, "Maps"), "Go", done = "Directions to ${parsed.place}") { ctx -> start(ctx, intent) }
        }
        is Parsed.Timer -> Ready(
            "◷", "Timer for ${prettyDuration(parsed.seconds)}", parsed.label.ifBlank { "Starts right away" }, "Start",
            done = (if (parsed.label.isNotBlank()) "Reminder in " else "Timer · ") + prettyDuration(parsed.seconds),
        ) { ctx ->
            start(ctx, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, parsed.seconds).putExtra(AlarmClock.EXTRA_MESSAGE, parsed.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        }
        is Parsed.Alarm -> Ready("◔", "Alarm for %d:%02d %s".format(if (parsed.hour % 12 == 0) 12 else parsed.hour % 12, parsed.minute, if (parsed.hour < 12) "AM" else "PM"), parsed.label.ifBlank { "Clock" }, "Set") { ctx ->
            start(
                ctx,
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, parsed.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, parsed.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, parsed.label)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            )
        }
        is Parsed.Play -> {
            val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, parsed.query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            val app = mostUsed(context, play)
            Ready("♫", "Play ${parsed.query}", appLabel(context, app, "Music"), "Play", done = "Playing ${parsed.query}") { ctx -> start(ctx, play.apply { if (app != null) setPackage(app) }) }
        }
        is Parsed.YouTube -> {
            val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, parsed.query)
            Ready("▶", "Search YouTube", parsed.query, "Search") { ctx ->
                start(ctx, yt) || start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(parsed.query))))
            }
        }
        is Parsed.Torch -> Ready("✺", "Flashlight ${if (parsed.on) "on" else "off"}", "Camera flash", if (parsed.on) "On" else "Off") { ctx -> torch(ctx, parsed.on) }
        is Parsed.Panel -> Ready("⚙", parsed.label, "Quick settings panel", "Open") { ctx -> start(ctx, Intent(parsed.action)) }
        Parsed.Planes -> Ready("✈", "Plane spotter", "Point your phone at a plane in the sky", "Open") { ctx ->
            start(ctx, Intent(ctx, com.kinetic.launcher.planes.PlaneSpotterActivity::class.java))
        }
        is Parsed.Open -> Assistant.resolveOpen(context, parsed.app)
        is Parsed.Act -> Assistant.resolveAction(context, parsed.action)
        is Parsed.Ask -> Assistant.resolveQuestion(context, parsed.question)
        is Parsed.Search -> Assistant.resolveSearch(parsed.query)
        is Parsed.FocusTimer -> Assistant.resolveFocusTimer(context, parsed.seconds)
        is Parsed.More -> MoreCommands.resolve(context, parsed.cmd, parsed.original)
        is Parsed.Three -> Commands3.resolve(context, parsed.cmd, parsed.original)
        is Parsed.Note -> Ready("📝", "Sticky note", parsed.text, "Add", done = "Note added to your home screen") { ctx ->
            com.kinetic.launcher.pen.StickyNotes.add(ctx, parsed.text)
            true
        }
        is Parsed.Chain -> Ready(
            "⛓", parsed.name, parsed.lines.joinToString(" · "), "Run",
            done = "${parsed.name} · ${parsed.lines.size} steps",
        ) { ctx -> runChain(ctx, parsed.lines) }
    }

    // ---- "the app you use most" --------------------------------------------------------

    /** True once Kinetic may see which apps you use most (Usage access). */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccess(context: Context) {
        start(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** Of the apps that can handle [intent], the one you've used most in the last two weeks. */
    private fun mostUsed(context: Context, intent: Intent): String? {
        val pkgs = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
        if (pkgs.size <= 1) return pkgs.firstOrNull()
        return pickMostUsed(context, pkgs)
    }

    private fun pickMostUsed(context: Context, pkgs: List<String>): String? {
        if (pkgs.isEmpty() || !hasUsageAccess(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(now - 14L * 24 * 3600 * 1000, now)
        return pkgs.maxByOrNull { stats[it]?.totalTimeInForeground ?: 0L }?.takeIf { (stats[it]?.totalTimeInForeground ?: 0L) > 0L }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun hasContacts(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** (name, number, label like "Mobile") of the best matching contact — typos forgiven. */
    private fun findPhone(context: Context, who: String): Triple<String, String, String?>? {
        if (!hasContacts(context)) return null
        findPhoneExact(context, who)?.let { return it }
        // No direct match ("call mon"): find the closest name and try that.
        val closest = closestContactName(context, who) ?: return null
        return findPhoneExact(context, closest)
    }

    private fun closestContactName(context: Context, who: String): String? = try {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            ContactsContract.Contacts.HAS_PHONE_NUMBER + "=1", null, null,
        )?.use { c ->
            var best: String? = null
            var bestD = Int.MAX_VALUE
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val d = (name.split(' ') + name).minOf { Fuzzy.distance(who, it, 2) }
                if (d < bestD) {
                    bestD = d
                    best = name
                }
            }
            best?.takeIf { bestD <= maxOf(1, Fuzzy.allowance(who)) }
        }
    } catch (_: Exception) {
        null
    }

    private fun findPhoneExact(context: Context, who: String): Triple<String, String, String?>? {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who))
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC, " + ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED + " DESC",
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                Triple(c.getString(0) ?: who, c.getString(1) ?: return null, label)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findEmail(context: Context, who: String): Pair<String, String>? {
        if (who.contains("@")) return who to who
        if (!hasContacts(context)) return null
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(who))
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: who) to (c.getString(1) ?: return null) else null }
        } catch (_: Exception) {
            null
        }
    }

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun appLabel(context: Context, pkg: String?, fallback: String): String =
        pkg?.let { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
            ?: fallback

    fun prettyDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hr" },
            m.takeIf { it > 0 }?.let { "$it min" },
            s.takeIf { it > 0 }?.let { "$it sec" },
        ).joinToString(" ")
    }

    private fun torch(context: Context, on: Boolean): Boolean = try {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id != null) {
            cm.setTorchMode(id, on)
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    /** Runs each command of a chain in turn (a moment apart, so apps can open). */
    private fun runChain(context: Context, lines: List<String>): Boolean {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            lines.forEach { line ->
                val p = parse(line) ?: return@forEach
                if (p is Parsed.Chain) return@forEach // no chains inside chains
                val r = runCatching { resolve(context, p) }.getOrNull() ?: return@forEach
                if (r.permission != null && ContextCompat.checkSelfPermission(context, r.permission) != PackageManager.PERMISSION_GRANTED) return@forEach
                main.post { runCatching { r.run(context) } }
                Thread.sleep(800)
            }
        }.start()
        return true
    }

    private val NOTE = Regex("""^(?:note|sticky note|sticky|jot|jot down)[:\s]+(.+)$""", RegexOption.IGNORE_CASE)

    private fun sendSms(context: Context, number: String, body: String): Boolean = try {
        val sm = context.getSystemService(android.telephony.SmsManager::class.java)
        val parts = sm.divideMessage(body)
        if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, null, null) else sm.sendTextMessage(number, null, body, null, null)
        true
    } catch (_: Exception) {
        false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

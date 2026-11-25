package com.kinetic.launcher.island

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.NotificationHub
import com.kinetic.launcher.planes.PlaneData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PURPLE = 0xFFBF5AF2.toInt()
private const val ORANGE = 0xFFFF9F0A.toInt()
private const val SKY = 0xFF64D2FF.toInt()
private const val BLUE = 0xFF0A84FF.toInt()

private fun hhmm(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

private fun lastLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
}

private fun getJson(url: String): JSONObject? = runCatching {
    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("User-Agent", "Kinetic-Launcher")
    }
    try {
        if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().readText())
    } finally {
        c.disconnect()
    }
}.getOrNull()

/** Says something in the island, or as a toast when the island is off. */
private fun tell(context: Context, icon: String, text: String) = IslandBus.tell(context, icon, text)

/**
 * The island's own live things: up next, rain, brightness, Focus, stopwatch,
 * plane watch, tracked flights and "done" ticks for progress bars.
 */
object LiveFeatures {
    private var observer: ContentObserver? = null

    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        FocusLive.restore(app)
        Stopwatch.restore(app)
        PlaneWatch.restore(app)
        FlightWatch.restore(app)
        // Brightness slider (only when auto-brightness is off; otherwise it changes by itself all day).
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!KineticSettings.current.islandBrightness) return
                if (!app.getSystemService(PowerManager::class.java).isInteractive) return
                val auto = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
                if (auto) return
                IslandBus.level("brightness", brightnessPercent(app))
            }
        }
        observer = obs
        runCatching { app.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, obs) }

        TorchLive.start(app)
        scope.launch(Dispatchers.IO) { UpNext.loop(app) }
        scope.launch(Dispatchers.IO) { Rain.loop(app) }
        // A progress bar that finishes gets a tick ("Download done ✓").
        scope.launch {
            var last: NotificationHub.TripInfo? = null
            NotificationHub.trip.collect { t ->
                val prev = last
                if (prev != null && prev.generic && (prev.progress ?: 0f) >= 0.85f && t?.key != prev.key) {
                    IslandBus.say("✓", "${prev.title} · done")
                }
                last = t
            }
        }
    }

    fun stop(context: Context) {
        TorchLive.stop(context.applicationContext)
        observer?.let { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    /** 0..100 as the slider shows it (the slider isn't linear, so neither is this). */
    fun brightnessPercent(context: Context): Int {
        val v = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return (sqrt(v.coerceIn(0, 255) / 255.0) * 100).roundToInt()
    }
}

// ======================================================================================
// Volume buttons → the island's slider (instead of One UI's panel).
// ======================================================================================
object VolumeHud {
    private var consumedDown = false
    private val handler = Handler(Looper.getMainLooper())
    private var repeatDir = 0
    private var repeatCtx: Context? = null

    /** Holding a volume key: one step every key-repeat interval, like the system does. */
    private val repeater = object : Runnable {
        override fun run() {
            val c = repeatCtx ?: return
            // Never keep going with the screen off (a missed key-up must not run the volume away).
            if (!c.getSystemService(PowerManager::class.java).isInteractive) {
                stopRepeat()
                return
            }
            step(c, repeatDir)
            handler.postDelayed(this, repeatInterval())
        }
    }

    // Android's own key-repeat timings (the phone's settings), with sensible limits.
    private fun repeatTimeout() = android.view.ViewConfiguration.getKeyRepeatTimeout().toLong().coerceIn(250L, 600L)
    private fun repeatInterval() = android.view.ViewConfiguration.getKeyRepeatDelay().toLong().coerceIn(45L, 120L)

    fun enabled(): Boolean = KineticSettings.current.islandVolume && DynamicIslandService.isRunning

    fun onKey(context: Context, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (e.action == KeyEvent.ACTION_UP) {
            stopRepeat()
            return consumedDown.also { consumedDown = false }
        }
        if (e.action != KeyEvent.ACTION_DOWN) return consumedDown
        // Repeats from the system (if any) are ignored: Kinetic runs its own while you hold.
        if (e.repeatCount > 0) return consumedDown
        consumedDown = false
        if (!enabled()) return false
        val audio = context.getSystemService(AudioManager::class.java)
        // Calls keep One UI's own controls; so does the screen being off.
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        if (!context.getSystemService(PowerManager::class.java).isInteractive) return false
        val dir = if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        if (!step(context, dir)) return false
        consumedDown = true
        startRepeat(context, dir)
        return true
    }

    /** Which volume the buttons change right now: music while it plays, otherwise your choice. */
    fun stream(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.isMusicActive) return AudioManager.STREAM_MUSIC
        // On silent/vibrate the ringtone can't move, so media it is.
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return AudioManager.STREAM_MUSIC
        return if (KineticSettings.current.volumeKeys == "ring") AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
    }

    /** One volume step, shown straight away in the island. */
    fun step(context: Context, dir: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val s = stream(context)
        val ok = runCatching { audio.adjustStreamVolume(s, dir, 0) }.isSuccess
        if (ok) show(context, s)
        return ok
    }

    fun startRepeat(context: Context, dir: Int) {
        stopRepeat()
        repeatDir = dir
        repeatCtx = context.applicationContext
        handler.postDelayed(repeater, repeatTimeout())
    }

    fun stopRepeat() {
        handler.removeCallbacks(repeater)
        repeatCtx = null
    }

    fun show(context: Context, forced: Int? = null) {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = forced ?: stream(context)
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val pct = audio.getStreamVolume(stream) * 100 / max
        IslandBus.level(if (stream == AudioManager.STREAM_MUSIC) "volume" else "ring", pct)
    }
}

// ======================================================================================
// Stopwatch: counts live in the island, with Lap and Stop.
// ======================================================================================
object Stopwatch {
    @Volatile
    var startedAt = 0L
        private set
    private val laps = ArrayList<Long>()
    val running get() = startedAt != 0L

    fun start(context: Context) {
        startedAt = SystemClock.elapsedRealtime()
        laps.clear()
        save(context)
        publish(context)
    }

    // Saved by the wall clock, so a running stopwatch survives Kinetic (or the phone) restarting.
    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    private fun save(c: Context) {
        if (startedAt == 0L) {
            prefs(c).edit().remove("sw_wall").remove("sw_laps").apply()
            return
        }
        val wallStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt)
        prefs(c).edit().putLong("sw_wall", wallStart).putString("sw_laps", laps.joinToString(",")).apply()
    }

    fun restore(c: Context) {
        if (running) return
        val wallStart = prefs(c).getLong("sw_wall", 0L)
        startedAt = RestoreRules.stopwatchStart(wallStart, System.currentTimeMillis(), SystemClock.elapsedRealtime()) ?: return
        laps.clear()
        laps += RestoreRules.laps(prefs(c).getString("sw_laps", ""))
        publish(c)
    }

    fun lap(context: Context) {
        if (!running) {
            tell(context, "⏱", "Stopwatch isn't running. Write “stopwatch”")
            return
        }
        laps += SystemClock.elapsedRealtime() - startedAt
        save(context)
        publish(context)
        if (!DynamicIslandService.isRunning) tell(context, "⏱", "Lap ${laps.size} · ${LiveHub.clock(laps.last(), true)}")
    }

    fun stop(context: Context) {
        if (!running) {
            tell(context, "⏱", "Stopwatch isn't running")
            return
        }
        val t = SystemClock.elapsedRealtime() - startedAt
        startedAt = 0L
        save(context)
        LiveHub.remove("stopwatch")
        tell(context, "⏱", "Stopped at ${LiveHub.clock(t, true)}" + if (laps.isNotEmpty()) " · ${laps.size} laps" else "")
    }

    private fun publish(context: Context) {
        val app = context.applicationContext
        val detail = if (laps.isEmpty()) "Pull down for Lap and Stop" else {
            val n = laps.size
            val split = laps.last() - (if (n > 1) laps[n - 2] else 0L)
            "Lap $n · ${LiveHub.clock(split, true)}"
        }
        LiveHub.put(
            Live(
                "stopwatch", "⏱", "Stopwatch", detail, countUpFrom = startedAt, accent = ORANGE, priority = 3,
                actions = listOf(LiveAction("Lap") { lap(app) }, LiveAction("Stop") { stop(app) }),
            ),
        )
    }
}

// ======================================================================================
// Focus sessions: a ring that empties, then a little celebration.
// ======================================================================================
object FocusLive {
    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    fun start(context: Context, seconds: Int, name: String, color: Int) {
        val end = System.currentTimeMillis() + seconds * 1000L
        prefs(context).edit().putLong("focus_end", end).putLong("focus_len", seconds * 1000L).putString("focus_name", name).putInt("focus_color", color).apply()
        publish(context.applicationContext)
    }

    fun restore(context: Context) {
        if (prefs(context).getLong("focus_end", 0L) > System.currentTimeMillis()) publish(context) else clear(context)
    }

    private fun publish(app: Context) {
        val p = prefs(app)
        val end = p.getLong("focus_end", 0L)
        LiveHub.put(
            Live(
                "focus", "☾", (p.getString("focus_name", null) ?: "Focus"), "Ends at ${hhmm(end)}",
                countDownTo = end, totalMs = p.getLong("focus_len", 0L).takeIf { it > 0 },
                accent = p.getInt("focus_color", PURPLE).let { if (it == 0) PURPLE else it }, priority = 2,
                actions = listOf(LiveAction("End now") { endEarly(app) }),
            ),
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().remove("focus_end").remove("focus_len").remove("focus_name").remove("focus_color").apply()
        LiveHub.remove("focus")
    }

    /** The session ran its full length. */
    fun finished(context: Context) {
        val len = prefs(context).getLong("focus_len", 0L)
        clear(context)
        val mins = (len / 60_000).toInt()
        runCatching { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
        if (!IslandBus.celebrate("🎉", "Focus done" + if (mins > 0) " · $mins min" else "")) tell(context, "🎉", "Focus done")
    }

    /** A Focus session with a timer is running. */
    fun running(context: Context) = prefs(context).getLong("focus_end", 0L) > System.currentTimeMillis()

    /** "end focus": stops the session now. */
    fun end(context: Context) = endEarly(context)

    private fun endEarly(context: Context) {
        com.kinetic.launcher.FocusModes.turnOff(context)
        runCatching {
            val pi = PendingIntent.getBroadcast(
                context, 7, Intent(context, com.kinetic.launcher.FocusEndReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        }
        clear(context)
        tell(context, "☾", "Focus ended")
    }
}

// ======================================================================================
// Up next: the next calendar event counts down 15 minutes before it starts.
// ======================================================================================
private object UpNext {
    private val hidden = HashSet<Long>()

    suspend fun loop(app: Context) {
        val power = app.getSystemService(PowerManager::class.java)
        while (true) {
            if (KineticSettings.current.islandUpNext && power.isInteractive &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { check(app) }
            } else if (!KineticSettings.current.islandUpNext) {
                LiveHub.remove("upnext")
            }
            delay(30_000)
        }
    }

    private fun check(app: Context) {
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 5 * 60_000)
        ContentUris.appendId(b, now + 16 * 60_000)
        var found: Triple<Long, String, Long>? = null
        app.contentResolver.query(
            b.build(),
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) == 1) continue
                val begin = c.getLong(2)
                val id = c.getLong(0) * 31 + begin
                if (begin < now - 5 * 60_000 || begin > now + 15 * 60_000 || id in hidden) continue
                found = Triple(id, c.getString(1) ?: "Event", begin)
                break
            }
        }
        val f = found
        if (f == null) {
            LiveHub.remove("upnext")
            return
        }
        val (id, title, begin) = f
        val started = begin <= now
        LiveHub.put(
            Live(
                "upnext", "📅", title, if (started) "Started at ${hhmm(begin)}" else "Starts at ${hhmm(begin)}",
                value = if (started) "Now" else null, countDownTo = if (started) null else begin, totalMs = 15 * 60_000L,
                accent = PURPLE, priority = 0,
                actions = listOf(
                    LiveAction("Open") {
                        runCatching {
                            app.startActivity(
                                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id / 31))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    LiveAction("Hide") {
                        hidden += id
                        LiveHub.remove("upnext")
                    },
                ),
            ),
        )
    }
}

// ======================================================================================
// Rain alerts: "Rain in about 15 min" (Open-Meteo, 15-minute forecast).
// ======================================================================================
private object Rain {
    private var lastCheck = 0L
    private var lastAlert = 0L

    suspend fun loop(app: Context) {
        val power = app.getSystemService(PowerManager::class.java)
        while (true) {
            val now = System.currentTimeMillis()
            if (KineticSettings.current.islandRain && power.isInteractive && now - lastCheck > 20 * 60_000) {
                lastCheck = now
                runCatching { check(app) }
            }
            delay(60_000)
        }
    }

    private fun check(app: Context) {
        if (System.currentTimeMillis() - lastAlert < 3 * 3600_000L) return
        val loc = lastLocation(app) ?: return
        val json = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&minutely_15=precipitation&forecast_minutely_15=4&timezone=auto"
                .format(Locale.US, loc.latitude, loc.longitude),
        ) ?: return
        val arr = json.optJSONObject("minutely_15")?.optJSONArray("precipitation") ?: return
        if (arr.length() < 2) return
        if (arr.optDouble(0, 0.0) >= 0.1) return // already raining
        for (i in 1 until arr.length()) {
            if (arr.optDouble(i, 0.0) >= 0.2) {
                lastAlert = System.currentTimeMillis()
                IslandBus.say("🌧", "Rain starting in about ${i * 15} min")
                return
            }
        }
    }
}

// ======================================================================================
// Plane watch: planes passing over you pop up in the island (for an hour at a time).
// ======================================================================================
object PlaneWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var hideJob: Job? = null
    val on get() = job?.isActive == true

    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    /** Picks up a plane watch that was running when Kinetic restarted. */
    fun restore(c: Context) {
        val until = prefs(c).getLong("pw_until", 0L)
        if (!on && RestoreRules.planeWatchResumes(until, System.currentTimeMillis())) start(c, until)
    }

    fun start(context: Context, untilAt: Long = System.currentTimeMillis() + 60 * 60_000L) {
        val app = context.applicationContext
        prefs(app).edit().putLong("pw_until", untilAt).apply()
        job?.cancel()
        job = scope.launch {
            val seen = HashMap<String, Long>()
            val until = untilAt
            while (isActive && System.currentTimeMillis() < until) {
                val me = lastLocation(app)
                if (me != null && app.getSystemService(PowerManager::class.java).isInteractive) {
                    val now = System.currentTimeMillis()
                    val near = runCatching { PlaneData.nearby(me.latitude, me.longitude, 5) }.getOrDefault(emptyList())
                        .map { it to PlaneData.distanceM(me.latitude, me.longitude, it.lat, it.lon) }
                        .filter { (p, d) -> d < 6_000 && p.altitudeFt in 200..20_000 && (seen[p.hex] ?: 0L) < now - 15 * 60_000 }
                        .minByOrNull { it.second }
                    if (near != null) {
                        val (p, d) = near
                        seen[p.hex] = now
                        announce(app, p, d)
                    }
                }
                delay(15_000)
            }
            if (isActive) {
                prefs(app).edit().remove("pw_until").apply()
                tell(app, "✈", "Plane watch off")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        LiveHub.remove("plane")
        com.kinetic.launcher.KineticApp.context?.let { prefs(it).edit().remove("pw_until").apply() }
    }

    private fun announce(app: Context, p: com.kinetic.launcher.planes.Plane, distM: Double) {
        val route = p.callsign.takeIf { it.isNotBlank() }?.let { runCatching { PlaneData.route(it) }.getOrNull() }
        val title = p.label + (route?.let { " · ${it.fromCode} → ${it.toCode}" } ?: "")
        val detail = buildList {
            add("%,d ft".format(p.altitudeFt))
            p.typeCode?.let { add(it) }
            add("%.1f mi away".format(distM / 1609.34))
            route?.toCity?.let { add("to $it") }
        }.joinToString(" · ")
        LiveHub.put(
            Live(
                "plane", "✈", title, detail, value = route?.toCode ?: "%,d ft".format(p.altitudeFt), accent = SKY, priority = 4,
                onTap = {
                    runCatching {
                        app.startActivity(Intent(app, com.kinetic.launcher.planes.PlaneSpotterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            ),
        )
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(25_000)
            LiveHub.remove("plane")
        }
    }
}

// ======================================================================================
// Flight tracking: follow one flight live until it lands.
// ======================================================================================
object FlightWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    var tracking: String? = null
        private set

    /** [shown] e.g. "UA123"; [callsign] the ICAO one, e.g. "UAL123". */
    private fun prefs(c: Context) = com.kinetic.launcher.KineticApp.pen(c)

    /** Picks up a flight that was being tracked when Kinetic restarted (up to 20 h). */
    fun restore(c: Context) {
        val p = prefs(c)
        val shown = p.getString("fw_shown", null) ?: return
        val callsign = p.getString("fw_call", null) ?: return
        if (tracking == null && RestoreRules.flightResumes(p.getLong("fw_at", 0L), System.currentTimeMillis())) track(c, shown, callsign, fresh = false)
    }

    fun track(context: Context, shown: String, callsign: String, fresh: Boolean = true) {
        val app = context.applicationContext
        job?.cancel()
        tracking = shown
        if (fresh) prefs(app).edit().putString("fw_shown", shown).putString("fw_call", callsign).putLong("fw_at", System.currentTimeMillis()).apply()
        job = scope.launch {
            val route = runCatching { PlaneData.route(callsign) ?: PlaneData.route(shown) }.getOrNull()
            val routeText = route?.let { " · ${it.fromCode} → ${it.toCode}" }.orEmpty()
            var seen = false
            var lastAlt = -1
            var lastSeenAt = 0L
            val until = System.currentTimeMillis() + 20 * 3600_000L
            while (isActive && System.currentTimeMillis() < until) {
                val p = runCatching { PlaneData.byCallsign(callsign) }.getOrNull()
                val now = System.currentTimeMillis()
                if (p != null) {
                    seen = true
                    lastSeenAt = now
                    val phase = when {
                        lastAlt < 0 -> "In the air"
                        p.altitudeFt > lastAlt + 300 -> "Climbing"
                        p.altitudeFt < lastAlt - 300 -> if (p.altitudeFt < 12_000) "Landing soon" else "Descending"
                        else -> "Cruising"
                    }
                    lastAlt = p.altitudeFt
                    val detail = listOfNotNull(phase, "%,d ft".format(p.altitudeFt), p.speedKt?.let { "${(it * 1.15078).roundToInt()} mph" }).joinToString(" · ")
                    publish(app, shown, callsign, shown + routeText, detail, if (phase == "Landing soon") "Landing" else "${(p.altitudeFt + 500) / 1000}k ft")
                } else if (seen && (lastAlt in 0..5_000 || now - lastSeenAt > 25 * 60_000)) {
                    // Gone from the radar low down: it's landed.
                    withContext(Dispatchers.Main) {
                        if (!IslandBus.celebrate("🛬", "$shown landed" + (route?.toCity?.let { " in $it" } ?: ""))) tell(app, "🛬", "$shown landed")
                    }
                    break
                } else if (!seen) {
                    publish(app, shown, callsign, shown + routeText, route?.let { "${it.fromCity} to ${it.toCity} · not in the air yet" } ?: "Not in the air yet", "Waiting")
                }
                delay(if (lastAlt in 0..12_000) 30_000 else 60_000)
            }
            LiveHub.remove("flight")
            tracking = null
            prefs(app).edit().remove("fw_shown").remove("fw_call").remove("fw_at").apply()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        tracking = null
        LiveHub.remove("flight")
        com.kinetic.launcher.KineticApp.context?.let { prefs(it).edit().remove("fw_shown").remove("fw_call").remove("fw_at").apply() }
    }

    private fun publish(app: Context, shown: String, callsign: String, title: String, detail: String, value: String) {
        LiveHub.put(
            Live(
                "flight", "✈", title, detail, value = value, accent = BLUE, priority = 1,
                actions = listOf(
                    LiveAction("Map") {
                        runCatching {
                            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flightaware.com/live/flight/$callsign")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    LiveAction("Stop") {
                        stop()
                        tell(app, "✈", "Stopped tracking $shown")
                    },
                ),
            ),
        )
    }
}

// ======================================================================================
// Flashlight, like iOS 18: a torch icon in the island; tap it open for a top-down
// flashlight with its beam. Drag up/down to change brightness, tap the torch for off/on.
// ======================================================================================
object TorchLive {
    data class Torch(val on: Boolean, val level: Int, val max: Int)

    private val _state = MutableStateFlow<Torch?>(null)
    /** Null when the flashlight is off (and not just switched off from the island). */
    val state: StateFlow<Torch?> = _state.asStateFlow()

    private var callback: android.hardware.camera2.CameraManager.TorchCallback? = null
    private var torchId: String? = null
    private var maxLevel = 1
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val clearOff = Runnable { if (_state.value?.on == false) _state.value = null }

    // Samsung briefly reports "off", then "on at full", while it changes the brightness.
    // Kinetic trusts its own last setting for a moment instead of bouncing back.
    @Volatile
    private var lastSetAt = 0L
    private var lastSetLevel = 1
    private var pendingLevel = 0
    private var lastSentAt = 0L
    private val sendPending = Runnable { flush() }

    private fun settling() = SystemClock.elapsedRealtime() - lastSetAt < 900

    private fun cm(c: Context) = c.getSystemService(android.hardware.camera2.CameraManager::class.java)

    fun start(context: Context) {
        stop(context)
        val a = context.applicationContext
        app = a
        val cm = cm(a) ?: return
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull() ?: return
        torchId = id
        maxLevel = runCatching {
            cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        }.getOrDefault(1).coerceAtLeast(1)
        val cb = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id) return
                handler.removeCallbacks(clearOff)
                if (enabled) {
                    val l = if (settling()) lastSetLevel
                    else runCatching { cm.getTorchStrengthLevel(id) }.getOrDefault(maxLevel).coerceIn(1, maxLevel)
                    _state.value = Torch(true, l, maxLevel)
                } else if (settling() && _state.value?.on == true) {
                    // Just a blink while the brightness changes: ignore.
                } else if (_state.value != null) {
                    // Stays (dark) for a moment so it can be switched back on from the island.
                    _state.value = _state.value?.copy(on = false)
                    handler.postDelayed(clearOff, 4000)
                }
            }

            override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                if (cameraId != id) return
                val s = _state.value ?: return
                if (settling() && newStrengthLevel != lastSetLevel) return
                _state.value = s.copy(level = newStrengthLevel.coerceIn(1, maxLevel))
            }
        }
        runCatching { cm.registerTorchCallback(cb, handler) }
        callback = cb
    }

    fun stop(context: Context) {
        callback?.let { cb -> runCatching { cm(context.applicationContext)?.unregisterTorchCallback(cb) } }
        callback = null
        _state.value = null
    }

    /** Tap on the torch: off, or back on at the same brightness. */
    fun toggle() {
        val a = app ?: return
        val id = torchId ?: return
        val s = _state.value ?: return
        runCatching {
            if (s.on) cm(a)?.setTorchMode(id, false)
            else if (maxLevel > 1) cm(a)?.turnOnTorchWithStrengthLevel(id, s.level) else cm(a)?.setTorchMode(id, true)
        }
    }

    /** Brightness 1..max (only while it's on). */
    fun setLevel(level: Int) {
        val a = app ?: return
        val id = torchId ?: return
        val s = _state.value ?: return
        if (!s.on || maxLevel <= 1) return
        val l = level.coerceIn(1, maxLevel)
        if (l == s.level && pendingLevel == 0) return
        _state.value = s.copy(level = l)
        lastSetAt = SystemClock.elapsedRealtime()
        lastSetLevel = l
        // At most one change every 90 ms while dragging (the newest one wins).
        pendingLevel = l
        handler.removeCallbacks(sendPending)
        val wait = 90 - (SystemClock.elapsedRealtime() - lastSentAt)
        if (wait <= 0) flush() else handler.postDelayed(sendPending, wait)
    }

    private fun flush() {
        val a = app ?: return
        val id = torchId ?: return
        val l = pendingLevel
        if (l <= 0) return
        pendingLevel = 0
        lastSentAt = SystemClock.elapsedRealtime()
        lastSetAt = lastSentAt
        runCatching { cm(a)?.turnOnTorchWithStrengthLevel(id, l) }
    }
}

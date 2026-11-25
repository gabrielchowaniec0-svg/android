package com.kinetic.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.kinetic.wallpaper.WallpaperSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Feeds the "living wallpaper": rain from the local weather, and one firefly per app
 * with unread notifications. (The battery tide is read by the wallpaper itself.)
 *
 * Weather comes from Open-Meteo (free, no account). Only a rough location (2 decimal
 * places, about 1 km) is sent, every 30 minutes.
 */
object LivingWallpaper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var weatherJob: Job? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val prefs = WallpaperSignals.prefs(app)
        // Last known rain, so the wallpaper is right straight after a restart.
        WallpaperSignals.rain = if (prefs.getBoolean(WallpaperSignals.KEY_WEATHER_RAIN, true)) prefs.getFloat("wp_last_rain", 0f) else 0f

        scope.launch {
            NotificationHub.packages.collect { pkgs ->
                val on = prefs.getBoolean(WallpaperSignals.KEY_FIREFLIES, true)
                WallpaperSignals.fireflies = if (on) pkgs.count { it != app.packageName }.coerceAtMost(8) else 0
            }
        }
        sunrise = prefs.getLong("wp_sunrise", 0L)
        sunset = prefs.getLong("wp_sunset", 0L)
        weatherJob = scope.launch {
            while (isActive) {
                refreshWeather(app)
                delay(30 * 60_000L)
            }
        }
        // The sky (golden hour, night, the moon) moves every minute.
        scope.launch {
            while (isActive) {
                updateSky(app)
                delay(60_000L)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Planes and the space station over you, while the sky is on screen.
        scope.launch {
            while (isActive) {
                val wait = runCatching { updateFlyers(app) }.getOrDefault(15_000L)
                delay(wait)
            }
        }
    }

    private var lastIss: Triple<Double, Double, Long>? = null

    /** Fetches planes (and the space station) nearby. Returns how long to wait before the next look. */
    private fun updateFlyers(context: Context): Long {
        val prefs = WallpaperSignals.prefs(context)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val lat = WallpaperSignals.lat
        val lon = WallpaperSignals.lon
        if (!prefs.getBoolean(WallpaperSignals.KEY_SKY_PLANES, true) || !WallpaperSignals.skyShowing ||
            power?.isInteractive != true || power.isPowerSaveMode || lat.isNaN() || lon.isNaN()
        ) {
            if (!WallpaperSignals.skyShowing) WallpaperSignals.planes = emptyList()
            return 4_000L
        }
        val now = android.os.SystemClock.elapsedRealtime()
        WallpaperSignals.planes = com.kinetic.launcher.planes.PlaneData.nearby(lat, lon, 12)
            .filter { it.altitudeFt in 200..45_000 && (it.speedKt ?: 0) > 60 && it.trackDeg != null }
            .map {
                WallpaperSignals.SkyPlane(
                    id = it.hex, lat = it.lat, lon = it.lon, altM = it.altitudeFt * 0.3048,
                    trackDeg = it.trackDeg ?: 0.0, speedMps = (it.speedKt ?: 0) * 0.514444, at = now,
                )
            }
        // The space station: closer looks while it's anywhere near.
        val iss = runCatching {
            val c = (URL("https://api.wheretheiss.at/v1/satellites/25544").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        }.getOrNull()
        if (iss != null) {
            val ilat = iss.getDouble("latitude")
            val ilon = iss.getDouble("longitude")
            val prev = lastIss
            lastIss = Triple(ilat, ilon, now)
            val ground = com.kinetic.launcher.planes.PlaneData.distanceM(lat, lon, ilat, ilon)
            WallpaperSignals.station = if (prev != null && now - prev.third < 120_000 && ground < 2_600_000) {
                // Heading from the last two fixes.
                val y = kotlin.math.sin(Math.toRadians(ilon - prev.second)) * kotlin.math.cos(Math.toRadians(ilat))
                val x = kotlin.math.cos(Math.toRadians(prev.first)) * kotlin.math.sin(Math.toRadians(ilat)) -
                    kotlin.math.sin(Math.toRadians(prev.first)) * kotlin.math.cos(Math.toRadians(ilat)) * kotlin.math.cos(Math.toRadians(ilon - prev.second))
                WallpaperSignals.SkyPlane(
                    "ISS", ilat, ilon, iss.optDouble("altitude", 420.0) * 1000.0,
                    (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0, iss.optDouble("velocity", 27_600.0) / 3.6, now,
                )
            } else {
                null
            }
            if (ground < 2_600_000) return 6_000L
        }
        return 12_000L
    }

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        val ctx = KineticApp.context ?: return@OnSharedPreferenceChangeListener
        when (key) {
            WallpaperSignals.KEY_FIREFLIES ->
                WallpaperSignals.fireflies = if (p.getBoolean(key, true)) NotificationHub.packages.value.count { it != ctx.packageName }.coerceAtMost(8) else 0
            WallpaperSignals.KEY_WEATHER_EXTRA, WallpaperSignals.KEY_SKY -> scope.launch {
                refreshWeather(ctx)
                updateSky(ctx)
            }
            WallpaperSignals.KEY_WEATHER_RAIN ->
                if (p.getBoolean(key, true)) scope.launch { refreshWeather(ctx) } else WallpaperSignals.rain = 0f
        }
    }

    /** Fetches the weather now (e.g. right after location was allowed). */
    fun refreshNow(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshWeather(app) }
    }

    fun hasLocation(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshWeather(context: Context) {
        val prefs = WallpaperSignals.prefs(context)
        val rainOn = prefs.getBoolean(WallpaperSignals.KEY_WEATHER_RAIN, true)
        val extraOn = prefs.getBoolean(WallpaperSignals.KEY_WEATHER_EXTRA, true)
        val skyOn = prefs.getBoolean(WallpaperSignals.KEY_SKY, true)
        if (!rainOn) WallpaperSignals.rain = 0f
        if (!extraOn) {
            WallpaperSignals.snow = 0f
            WallpaperSignals.fog = 0f
            WallpaperSignals.wind = 0f
            WallpaperSignals.clouds = 0f
        }
        if (!(rainOn || extraOn || skyOn) || !hasLocation(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val loc = try {
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        } ?: return
        val lat = "%.2f".format(java.util.Locale.US, loc.latitude)
        val lon = "%.2f".format(java.util.Locale.US, loc.longitude)
        // For the stars and planes over you (kept on the phone).
        WallpaperSignals.lat = loc.latitude
        WallpaperSignals.lon = loc.longitude
        prefs.edit().putFloat("wp_lat", loc.latitude.toFloat()).putFloat("wp_lon", loc.longitude.toFloat()).apply()
        val rain = runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=precipitation,weather_code,wind_speed_10m,wind_direction_10m,cloud_cover&daily=sunrise,sunset&timezone=auto&forecast_days=1"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val cur = json.getJSONObject("current")
                val code = cur.optInt("weather_code", 0)
                if (extraOn) {
                    WallpaperSignals.snow = when (code) {
                        71, 85 -> 0.4f
                        73, 77 -> 0.7f
                        75, 86 -> 1f
                        else -> 0f
                    }
                    WallpaperSignals.fog = if (code == 45 || code == 48) 1f else 0f
                    WallpaperSignals.clouds = (cur.optDouble("cloud_cover", 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                    // Wind slants the rain and pushes the snow (the side it blows towards).
                    val speed = (cur.optDouble("wind_speed_10m", 0.0) / 40.0).coerceIn(0.0, 1.0)
                    val from = Math.toRadians(cur.optDouble("wind_direction_10m", 0.0))
                    WallpaperSignals.wind = (-kotlin.math.sin(from) * speed).toFloat()
                }
                // Sunrise and sunset (local time) for the golden hour and the night sky.
                runCatching {
                    val daily = json.getJSONObject("daily")
                    val zone = java.util.TimeZone.getTimeZone(json.optString("timezone", java.util.TimeZone.getDefault().id))
                    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).apply { timeZone = zone }
                    sunrise = f.parse(daily.getJSONArray("sunrise").getString(0))?.time ?: 0L
                    sunset = f.parse(daily.getJSONArray("sunset").getString(0))?.time ?: 0L
                    prefs.edit().putLong("wp_sunrise", sunrise).putLong("wp_sunset", sunset).apply()
                }
                updateSky(context)
                if (rainOn) rainFor(code, cur.optDouble("precipitation", 0.0)) else 0f
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return
        WallpaperSignals.rain = rain
        prefs.edit().putFloat("wp_last_rain", rain).apply()
    }

    @Volatile
    private var sunrise = 0L

    @Volatile
    private var sunset = 0L

    /** Golden hour, night and the moon's phase, from today's sunrise/sunset (or the clock if unknown). */
    private fun updateSky(context: Context) {
        val prefs = WallpaperSignals.prefs(context)
        if (!prefs.getBoolean(WallpaperSignals.KEY_SKY, true)) {
            WallpaperSignals.warm = 0f
            WallpaperSignals.night = 0f
            WallpaperSignals.moon = -1f
            return
        }
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val dayMs = 86_400_000L
        // Saved times may be from an earlier day: move them to today (they shift by only minutes).
        fun today(t: Long): Long {
            if (t <= 0L) return 0L
            val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
            c.set(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
            return c.timeInMillis
        }
        val rise = today(sunrise).takeIf { it > 0 } ?: (now - (now % dayMs)) // unlikely fallback
        val set = today(sunset).takeIf { it > 0 }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val window = 45 * 60_000L
        if (set == null) {
            WallpaperSignals.night = if (hour >= 19 || hour < 6) 1f else 0f
            WallpaperSignals.warm = 0f
        } else {
            WallpaperSignals.warm = maxOf(
                1f - kotlin.math.abs(now - set).toFloat() / window,
                1f - kotlin.math.abs(now - rise).toFloat() / window,
            ).coerceIn(0f, 1f)
            WallpaperSignals.night = when {
                now > set + 30 * 60_000L || now < rise - 30 * 60_000L -> 1f
                now > set -> (now - set) / (30 * 60_000f)
                now < rise -> (rise - now) / (30 * 60_000f)
                else -> 0f
            }.coerceIn(0f, 1f)
        }
        // Moon phase from a known new moon (6 Jan 2000, 18:14 UTC) and the 29.53-day cycle.
        val days = (now - 947_182_440_000L) / dayMs.toDouble()
        WallpaperSignals.moon = ((days / 29.530588853) % 1.0).toFloat().let { if (it < 0) it + 1f else it }
    }

    /** WMO weather codes → how hard it's raining (0..1). */
    private fun rainFor(code: Int, mm: Double): Float {
        val fromCode = when (code) {
            51, 53, 56 -> 0.3f            // drizzle
            55, 57, 61, 80 -> 0.5f        // light rain / showers
            63, 66, 81 -> 0.75f           // rain
            65, 67, 82, 95, 96, 99 -> 1f  // heavy rain, storms
            else -> 0f
        }
        val fromAmount = (mm / 4.0).toFloat().coerceIn(0f, 1f)
        return maxOf(fromCode, fromAmount)
    }
}

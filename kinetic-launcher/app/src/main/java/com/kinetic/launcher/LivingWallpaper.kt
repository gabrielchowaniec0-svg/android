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
        weatherJob = scope.launch {
            while (isActive) {
                refreshWeather(app)
                delay(30 * 60_000L)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        val ctx = KineticApp.context ?: return@OnSharedPreferenceChangeListener
        when (key) {
            WallpaperSignals.KEY_FIREFLIES ->
                WallpaperSignals.fireflies = if (p.getBoolean(key, true)) NotificationHub.packages.value.count { it != ctx.packageName }.coerceAtMost(8) else 0
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
        if (!prefs.getBoolean(WallpaperSignals.KEY_WEATHER_RAIN, true) || !hasLocation(context)) {
            WallpaperSignals.rain = 0f
            return
        }
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
        val rain = runCatching {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=precipitation,weather_code&forecast_days=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                val cur = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("current")
                rainFor(cur.optInt("weather_code", 0), cur.optDouble("precipitation", 0.0))
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return
        WallpaperSignals.rain = rain
        prefs.edit().putFloat("wp_last_rain", rain).apply()
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

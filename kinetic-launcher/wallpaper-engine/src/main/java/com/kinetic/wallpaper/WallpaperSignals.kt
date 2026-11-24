package com.kinetic.wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How the rest of Kinetic talks to the live wallpaper (they run in the same app
 * process): ripples from app launches, unlocks, notifications and charging, and a
 * soft glow in the colour of whatever music is playing.
 *
 * The on/off switches live in the shared "kinetic_settings" preferences so the
 * wallpaper honours them even when only the wallpaper is running.
 */
object WallpaperSignals {

    const val PREFS = "kinetic_settings"
    const val KEY_MUSIC_GLOW = "wp_music_glow"
    const val KEY_UNLOCK_RIPPLE = "wp_unlock_ripple"
    const val KEY_APP_RIPPLES = "wp_app_ripples"
    const val KEY_NOTIFICATION_PULSE = "wp_notification_pulse"
    const val KEY_CHARGING_WAVE = "wp_charging_wave"
    const val KEY_BATTERY_CALM = "wp_battery_calm"
    const val KEY_ICON_WAVE = "wp_icon_wave"
    // Living wallpaper: information drawn into the wallpaper itself.
    const val KEY_WEATHER_RAIN = "wp_weather_rain"
    const val KEY_BATTERY_TIDE = "wp_battery_tide"
    const val KEY_FIREFLIES = "wp_fireflies"
    const val KEY_WEATHER_EXTRA = "wp_weather_extra"
    const val KEY_SKY = "wp_sky"

    enum class Kind { App, Unlock, Notification, Charging }

    /**
     * A ripple spreading from ([x], [y]) in screen pixels. A negative x means the
     * middle; a negative y means the top (-1), the bottom (-2) or the middle (-3).
     */
    data class Ripple(
        val x: Float,
        val y: Float,
        val color: Int,
        val strength: Float,
        val kind: Kind,
        val at: Long = SystemClock.uptimeMillis(),
    )

    fun interface Listener {
        fun onRipple(ripple: Ripple)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context, key: String): Boolean = prefs(context).getBoolean(key, true)

    private fun keyFor(kind: Kind) = when (kind) {
        Kind.App -> KEY_APP_RIPPLES
        Kind.Unlock -> KEY_UNLOCK_RIPPLE
        Kind.Notification -> KEY_NOTIFICATION_PULSE
        Kind.Charging -> KEY_CHARGING_WAVE
    }

    /** Sends a ripple to the wallpaper (and to the home screen icons), if that reaction is switched on. */
    fun ripple(context: Context, kind: Kind, x: Float = -1f, y: Float = -1f, color: Int = 0xFF8FA6D8.toInt(), strength: Float = 1f) {
        if (!enabled(context, keyFor(kind))) return
        val r = Ripple(x, y, color, strength, kind)
        listeners.forEach { it.onRipple(r) }
    }

    // ---- living wallpaper ---------------------------------------------------------------

    /** 0 = dry, 1 = heavy rain right now (from the weather, set by the launcher). */
    @Volatile
    var rain: Float = 0f

    /** 0..1 snowfall, 0..1 fog, -1..1 wind (negative = blowing left). */
    @Volatile
    var snow: Float = 0f

    @Volatile
    var fog: Float = 0f

    @Volatile
    var wind: Float = 0f

    /** 0..1 golden-hour warmth around sunrise/sunset; 0..1 how much it's night. */
    @Volatile
    var warm: Float = 0f

    @Volatile
    var night: Float = 0f

    /** Moon phase 0..1 (0 new, 0.5 full), or -1 to hide the moon. */
    @Volatile
    var moon: Float = -1f

    /** Apps with unread notifications (each one is a firefly), 0–8. */
    @Volatile
    var fireflies: Int = 0

    /** 0 = normal, 1 = fully dimmed (set by the active Focus). */
    @Volatile
    var focusDim: Float = 0f

    // ---- music glow -------------------------------------------------------------------

    @Volatile
    var glowColor: Int = 0
        private set

    /** 0 = off, 1 = full glow. The wallpaper eases towards it. */
    @Volatile
    var glowAmount: Float = 0f
        private set

    fun setMusicGlow(context: Context, color: Int, playing: Boolean) {
        if (!enabled(context, KEY_MUSIC_GLOW)) {
            glowAmount = 0f
            return
        }
        if (playing) glowColor = color
        glowAmount = if (playing) 1f else 0f
    }
}

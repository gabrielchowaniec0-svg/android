package com.kinetic.launcher

import android.content.Context
import android.content.SharedPreferences
import com.kinetic.wallpaper.WallpaperSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All of Kinetic's switches in one place (shown on the Settings screen). Stored in
 * the same "kinetic_settings" file the wallpaper reads, so its reactions follow
 * these switches too.
 */
object KineticSettings {

    data class Values(
        // Dynamic Island
        val islandRestingPill: Boolean = true,
        val islandChowaniecPeek: Boolean = true,
        val islandAllMessagesPeek: Boolean = false,
        val islandTimers: Boolean = true,
        val islandNavigation: Boolean = true,
        val islandUnlock: Boolean = true,
        val islandMinimalSeconds: Int = 10,
        /** How far below "centred on the camera" the island's top edge sits. */
        val islandGapDp: Int = 5,
        // Home
        val notificationRings: Boolean = true,
        val iconSizeDp: Int = 60,
        val haptics: Boolean = true,
        /** "default", "tinted", "glass" or "mono". */
        val iconStyle: String = "default",
        /** "depth" (default), "flat", "cube", "carousel" or "fade". */
        val pageTransition: String = "depth",
        // Gestures
        val doubleTapLock: Boolean = true,
        val multiFinger: Boolean = true,
        /** "off", "flashlight", "camera", "search", "focus" or "app". */
        val shakeAction: String = "off",
        val shakeApp: String = "",
        /** Volume Up, Up, Down, Down saves a screenshot straight into the Vault. */
        val secureScreenshot: Boolean = true,
        // Wallpaper reactions (keys shared with the wallpaper)
        val wpMusicGlow: Boolean = true,
        val wpUnlockRipple: Boolean = true,
        val wpAppRipples: Boolean = true,
        val wpNotificationPulse: Boolean = true,
        val wpChargingWave: Boolean = true,
        val wpBatteryCalm: Boolean = true,
        val wpIconWave: Boolean = true,
        val wpWeatherRain: Boolean = true,
        val wpBatteryTide: Boolean = true,
        val wpFireflies: Boolean = true,
        /** S Pen: write on the home screen to run Commands / search. */
        val penInk: Boolean = true,
    )

    const val ISLAND_RESTING = "island_resting"
    const val ISLAND_CHOWANIEC = "island_chowaniec_peek"
    const val ISLAND_ALL_MESSAGES = "island_all_messages_peek"
    const val ISLAND_TIMERS = "island_timers"
    const val ISLAND_NAVIGATION = "island_navigation"
    const val ISLAND_UNLOCK = "island_unlock"
    const val ISLAND_MINIMAL_SECONDS = "island_minimal_seconds"
    const val ISLAND_GAP = "island_gap_dp"
    const val RINGS = "notification_rings"
    const val ICON_SIZE = "icon_size_dp"
    const val HAPTICS = "haptics"
    const val ICON_STYLE = "icon_style"
    const val PAGE_TRANSITION = "page_transition"
    const val DOUBLE_TAP_LOCK = "double_tap_lock"
    const val MULTI_FINGER = "multi_finger"
    const val SHAKE_ACTION = "shake_action"
    const val SHAKE_APP = "shake_app"
    const val SECURE_SCREENSHOT = "secure_screenshot"
    const val PEN_INK = "pen_ink"

    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values.asStateFlow()

    /** The current values (after [init]). */
    val current: Values get() = _values.value

    private var prefs: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> _values.value = read(p) }

    /** Loads the settings once per process; safe to call from anywhere. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = WallpaperSignals.prefs(context)
        prefs = p
        _values.value = read(p)
        p.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setBoolean(context: Context, key: String, value: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun setString(context: Context, key: String, value: String) {
        init(context)
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun setInt(context: Context, key: String, value: Int) {
        init(context)
        prefs?.edit()?.putInt(key, value)?.apply()
    }

    private fun read(p: SharedPreferences) = Values(
        islandRestingPill = p.getBoolean(ISLAND_RESTING, true),
        islandChowaniecPeek = p.getBoolean(ISLAND_CHOWANIEC, true),
        islandAllMessagesPeek = p.getBoolean(ISLAND_ALL_MESSAGES, false),
        islandTimers = p.getBoolean(ISLAND_TIMERS, true),
        islandNavigation = p.getBoolean(ISLAND_NAVIGATION, true),
        islandUnlock = p.getBoolean(ISLAND_UNLOCK, true),
        islandMinimalSeconds = p.getInt(ISLAND_MINIMAL_SECONDS, 10),
        islandGapDp = p.getInt(ISLAND_GAP, 5),
        notificationRings = p.getBoolean(RINGS, true),
        iconSizeDp = p.getInt(ICON_SIZE, 60),
        haptics = p.getBoolean(HAPTICS, true),
        iconStyle = p.getString(ICON_STYLE, "default") ?: "default",
        pageTransition = p.getString(PAGE_TRANSITION, "depth") ?: "depth",
        doubleTapLock = p.getBoolean(DOUBLE_TAP_LOCK, true),
        multiFinger = p.getBoolean(MULTI_FINGER, true),
        shakeAction = p.getString(SHAKE_ACTION, "off") ?: "off",
        shakeApp = p.getString(SHAKE_APP, "") ?: "",
        secureScreenshot = p.getBoolean(SECURE_SCREENSHOT, true),
        wpMusicGlow = p.getBoolean(WallpaperSignals.KEY_MUSIC_GLOW, true),
        wpUnlockRipple = p.getBoolean(WallpaperSignals.KEY_UNLOCK_RIPPLE, true),
        wpAppRipples = p.getBoolean(WallpaperSignals.KEY_APP_RIPPLES, true),
        wpNotificationPulse = p.getBoolean(WallpaperSignals.KEY_NOTIFICATION_PULSE, true),
        wpChargingWave = p.getBoolean(WallpaperSignals.KEY_CHARGING_WAVE, true),
        wpBatteryCalm = p.getBoolean(WallpaperSignals.KEY_BATTERY_CALM, true),
        wpIconWave = p.getBoolean(WallpaperSignals.KEY_ICON_WAVE, true),
        wpWeatherRain = p.getBoolean(WallpaperSignals.KEY_WEATHER_RAIN, true),
        wpBatteryTide = p.getBoolean(WallpaperSignals.KEY_BATTERY_TIDE, true),
        wpFireflies = p.getBoolean(WallpaperSignals.KEY_FIREFLIES, true),
        penInk = p.getBoolean(PEN_INK, true),
    )
}

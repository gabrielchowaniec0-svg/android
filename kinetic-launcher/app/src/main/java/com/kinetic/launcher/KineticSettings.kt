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
        /** After 3 wrong Kinetic codes, the front camera photographs whoever tried (into the Vault). */
        val intruderPhoto: Boolean = true,
        /** StandBy: charging + sideways = nightstand clock. */
        val standBy: Boolean = true,
        /** App list shown as the App Library (automatic groups). */
        val libraryView: Boolean = false,
        /** Red unread-count badges on app icons. */
        val badges: Boolean = true,
        /** Newly installed apps wait in a "New" folder for a week. */
        val newAppsFolder: Boolean = true,
        /** Earbuds/headphones connecting show in the island, with battery. */
        val islandEarbuds: Boolean = true,
        /** Volume keys show Kinetic's slider in the island instead of One UI's. */
        val islandVolume: Boolean = true,
        /** Brightness changes show a slider in the island. */
        val islandBrightness: Boolean = true,
        /** The next calendar event counts down in the island 15 minutes before. */
        val islandUpNext: Boolean = true,
        /** "Rain in 15 min" when rain is about to start where you are. */
        val islandRain: Boolean = true,
        /** "black", "glass" or "outline". */
        val islandTheme: String = "black",
        /** A soft glow in the album's colours around the island while music plays. */
        val islandMusicGlow: Boolean = true,
        /** Downloads, uploads, orders… any app's progress bar shows in the island. */
        val islandProgress: Boolean = true,
        /** Comma-separated team names; when set, only their games show in the island. */
        val myTeams: String = "",
        /** Every (non-silent) notification drops down from the island. */
        val islandNotifications: Boolean = true,
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
        val wpWeatherExtra: Boolean = true,
        val wpSky: Boolean = true,
        /** S Pen: write on the home screen to run Commands / search. */
        val penInk: Boolean = true,
    )

    const val ISLAND_RESTING = "island_resting"
    const val ISLAND_CHOWANIEC = "island_chowaniec_peek"
    const val ISLAND_ALL_MESSAGES = "island_all_messages_peek"
    const val ISLAND_NOTIFICATIONS = "island_notifications_peek"
    const val ISLAND_THEME = "island_theme"
    const val BADGES = "number_badges"
    const val STANDBY = "standby"
    const val LIBRARY_VIEW = "library_view"
    const val INTRUDER = "intruder_photo"
    const val NEW_APPS_FOLDER = "new_apps_folder"
    const val ISLAND_EARBUDS = "island_earbuds"
    const val ISLAND_VOLUME = "island_volume"
    const val ISLAND_BRIGHTNESS = "island_brightness"
    const val ISLAND_UP_NEXT = "island_up_next"
    const val ISLAND_RAIN = "island_rain"
    const val ISLAND_MUSIC_GLOW = "island_music_glow"
    const val ISLAND_PROGRESS = "island_progress"
    const val MY_TEAMS = "my_teams"
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
        islandNotifications = p.getBoolean(ISLAND_NOTIFICATIONS, true),
        islandTheme = p.getString(ISLAND_THEME, "black") ?: "black",
        badges = p.getBoolean(BADGES, true),
        standBy = p.getBoolean(STANDBY, true),
        libraryView = p.getBoolean(LIBRARY_VIEW, false),
        intruderPhoto = p.getBoolean(INTRUDER, true),
        newAppsFolder = p.getBoolean(NEW_APPS_FOLDER, true),
        islandEarbuds = p.getBoolean(ISLAND_EARBUDS, true),
        islandVolume = p.getBoolean(ISLAND_VOLUME, true),
        islandBrightness = p.getBoolean(ISLAND_BRIGHTNESS, true),
        islandUpNext = p.getBoolean(ISLAND_UP_NEXT, true),
        islandRain = p.getBoolean(ISLAND_RAIN, true),
        islandMusicGlow = p.getBoolean(ISLAND_MUSIC_GLOW, true),
        islandProgress = p.getBoolean(ISLAND_PROGRESS, true),
        myTeams = p.getString(MY_TEAMS, "") ?: "",
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
        wpWeatherExtra = p.getBoolean(WallpaperSignals.KEY_WEATHER_EXTRA, true),
        wpSky = p.getBoolean(WallpaperSignals.KEY_SKY, true),
        penInk = p.getBoolean(PEN_INK, true),
    )
}

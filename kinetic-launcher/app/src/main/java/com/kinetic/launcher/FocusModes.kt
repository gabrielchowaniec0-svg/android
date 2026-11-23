package com.kinetic.launcher

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import com.kinetic.wallpaper.WallpaperSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Focus modes, like iOS: Do Not Disturb, Sleep, School, Gaming… Each one can
 * silence the phone, jump to a home page, dim the wallpaper and only let chosen
 * apps show notification rings and island pop-ups.
 */
object FocusModes {

    enum class Glyph { Moon, Bed, Book, Gamepad, Person, Briefcase, Car, Leaf }

    /** Turn on at [startMin] and off at [endMin] (minutes after midnight) on [days]. */
    data class Schedule(
        val enabled: Boolean = false,
        val startMin: Int = 22 * 60,
        val endMin: Int = 7 * 60,
        /** Bit 0 = Monday … bit 6 = Sunday. */
        val days: Int = 0b1111111,
    ) {
        fun onDay(dayIndexMonday0: Int) = days and (1 shl dayIndexMonday0) != 0
    }

    data class Mode(
        val id: String,
        val name: String,
        val glyph: Glyph,
        val color: Int,
        /** Turn on Android's Do Not Disturb while this Focus is on. */
        val silence: Boolean = true,
        /** Home page to show (0-based), or -1 to stay where you are. */
        val homePage: Int = -1,
        /** 0 = normal wallpaper, up to 0.8 = much darker. */
        val wallpaperDim: Float = 0f,
        /** Apps still allowed to light up (rings, island messages). Empty = none. */
        val allowedApps: Set<String> = emptySet(),
        /** Let every app through anyway (only change the look/page). */
        val allowAll: Boolean = false,
        val schedule: Schedule = Schedule(),
        /** Turn on while the phone is connected to a car over Bluetooth. */
        val carTrigger: Boolean = false,
    )

    private val defaults = listOf(
        Mode("dnd", "Do Not Disturb", Glyph.Moon, 0xFF7D6BFF.toInt(), silence = true, wallpaperDim = 0.25f),
        Mode("sleep", "Sleep", Glyph.Bed, 0xFF5E8BFF.toInt(), silence = true, wallpaperDim = 0.7f, schedule = Schedule(false, 22 * 60 + 30, 7 * 60)),
        Mode("school", "School", Glyph.Book, 0xFFFF9F0A.toInt(), silence = true, wallpaperDim = 0.15f, schedule = Schedule(false, 8 * 60, 15 * 60, 0b0011111)),
        Mode("gaming", "Gaming", Glyph.Gamepad, 0xFFFF453A.toInt(), silence = true, wallpaperDim = 0.3f),
        Mode("personal", "Personal", Glyph.Person, 0xFF30D158.toInt(), silence = false, allowAll = true),
        Mode("driving", "Driving", Glyph.Car, 0xFF64D2FF.toInt(), silence = true, wallpaperDim = 0.4f, carTrigger = true),
    )

    private val _modes = MutableStateFlow(defaults)
    val modes: StateFlow<List<Mode>> = _modes.asStateFlow()

    private val _active = MutableStateFlow<Mode?>(null)
    /** The Focus that's on right now, if any. */
    val active: StateFlow<Mode?> = _active.asStateFlow()

    private var prefs: SharedPreferences? = null
    private const val K_MODES = "focus_modes"
    private const val K_ACTIVE = "focus_active"
    private const val K_LAST = "focus_last"
    private const val K_PREV_FILTER = "focus_prev_filter"
    /** Read by the wallpaper (it can't see this class). */
    const val K_DIM = "focus_dim"

    fun init(context: Context) {
        if (prefs != null) return
        val p = WallpaperSignals.prefs(context)
        prefs = p
        _modes.value = p.getString(K_MODES, null)?.let(::parse) ?: defaults
        _active.value = p.getString(K_ACTIVE, null)?.let { id -> _modes.value.firstOrNull { it.id == id } }
        WallpaperSignals.focusDim = _active.value?.wallpaperDim ?: 0f
    }

    /** The Focus used most recently (what the quick-settings tile turns on). */
    fun last(context: Context): Mode {
        init(context)
        val id = prefs?.getString(K_LAST, null)
        return _modes.value.firstOrNull { it.id == id } ?: _modes.value.first()
    }

    /** Should [pkg] still light up (ring / island) right now? */
    fun allows(pkg: String): Boolean {
        val m = _active.value ?: return true
        return m.allowAll || pkg in m.allowedApps
    }

    fun toggle(context: Context, id: String) {
        init(context)
        if (_active.value?.id == id) turnOff(context) else turnOn(context, id)
    }

    fun turnOn(context: Context, id: String) {
        init(context)
        val mode = _modes.value.firstOrNull { it.id == id } ?: return
        val p = prefs ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (_active.value == null) p.edit().putInt(K_PREV_FILTER, nm.currentInterruptionFilter).apply()
        if (mode.silence && nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else if (!mode.silence && nm.isNotificationPolicyAccessGranted) {
            restoreFilter(nm, p)
        }
        p.edit().putString(K_ACTIVE, id).putString(K_LAST, id).putFloat(K_DIM, mode.wallpaperDim).apply()
        WallpaperSignals.focusDim = mode.wallpaperDim
        _active.value = mode
    }

    fun turnOff(context: Context) {
        init(context)
        val p = prefs ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted && _active.value?.silence == true) restoreFilter(nm, p)
        p.edit().remove(K_ACTIVE).putFloat(K_DIM, 0f).apply()
        WallpaperSignals.focusDim = 0f
        _active.value = null
    }

    private fun restoreFilter(nm: NotificationManager, p: SharedPreferences) {
        val prev = p.getInt(K_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        nm.setInterruptionFilter(if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else prev)
    }

    fun update(context: Context, mode: Mode) {
        init(context)
        val list = _modes.value.map { if (it.id == mode.id) mode else it }
        _modes.value = list
        prefs?.edit()?.putString(K_MODES, serialize(list))?.apply()
        if (_active.value?.id == mode.id) turnOn(context, mode.id)
        FocusScheduler.scheduleNext(context)
    }

    /** Can Kinetic switch Do Not Disturb? (A one-time Android permission.) */
    fun canSilence(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun openSilencePermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun serialize(list: List<Mode>): String = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("glyph", m.glyph.name)
                put("color", m.color)
                put("silence", m.silence)
                put("page", m.homePage)
                put("dim", m.wallpaperDim.toDouble())
                put("apps", JSONArray(m.allowedApps.toList()))
                put("all", m.allowAll)
                put("sched", JSONObject().apply {
                    put("on", m.schedule.enabled)
                    put("start", m.schedule.startMin)
                    put("end", m.schedule.endMin)
                    put("days", m.schedule.days)
                })
                put("car", m.carTrigger)
            })
        }
    }.toString()

    private fun parse(raw: String): List<Mode>? = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val apps = o.optJSONArray("apps")
            Mode(
                id = o.getString("id"),
                name = o.getString("name"),
                glyph = runCatching { Glyph.valueOf(o.getString("glyph")) }.getOrDefault(Glyph.Moon),
                color = o.getInt("color"),
                silence = o.optBoolean("silence", true),
                homePage = o.optInt("page", -1),
                wallpaperDim = o.optDouble("dim", 0.0).toFloat(),
                allowedApps = if (apps == null) emptySet() else (0 until apps.length()).mapTo(HashSet()) { apps.getString(it) },
                allowAll = o.optBoolean("all", false),
                schedule = o.optJSONObject("sched")?.let { sj ->
                    Schedule(sj.optBoolean("on", false), sj.optInt("start", 22 * 60), sj.optInt("end", 7 * 60), sj.optInt("days", 0b1111111))
                } ?: Schedule(),
                carTrigger = o.optBoolean("car", false),
            )
        }.ifEmpty { null }
    }.getOrNull()
}

package com.kinetic.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which apps currently have notifications waiting (drives the spinning rings on icons). */
object NotificationHub {
    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    internal fun set(packages: Set<String>) {
        _packages.value = packages
    }

    /** A phone call in progress or ringing (shown in the Dynamic Island). */
    data class CallInfo(
        val key: String,
        val name: String,
        /** When the call connected (System.currentTimeMillis), or null while it's ringing. */
        val startedAt: Long?,
        val incoming: Boolean,
        val packageName: String,
        val contentIntent: PendingIntent?,
        /** The call notification's own buttons (Answer, Decline, Hang up…). */
        val actions: List<Notification.Action>,
    )

    private val _connected = MutableStateFlow(false)
    /** True while Kinetic's notification listener is running (Notification access is on). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun setConnected(on: Boolean) {
        _connected.value = on
    }

    /** A Clock timer, stopwatch or ringing alarm. */
    data class TimerInfo(
        val key: String,
        val packageName: String,
        val kind: Kind,
        val label: String,
        /** Countdown end (System.currentTimeMillis) when the notification gives one. */
        val endsAt: Long?,
        /** Stopwatch start (System.currentTimeMillis) when the notification gives one. */
        val startedAt: Long?,
        /** The time as the notification shows it, for clocks that don't use a chronometer. */
        val text: String?,
        val paused: Boolean,
        val contentIntent: PendingIntent?,
        val actions: List<Notification.Action>,
    ) {
        enum class Kind { Timer, Stopwatch, Alarm }
    }

    /** Turn-by-turn directions from Google Maps (or Waze). */
    data class NavInfo(
        val key: String,
        val packageName: String,
        val distance: String,
        val instruction: String,
        val eta: String?,
        val arrow: Bitmap?,
        val contentIntent: PendingIntent?,
    )

    /** A new message to drop down from the island. */
    data class MessagePeek(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val contentIntent: PendingIntent?,
        /** An action that takes typed text (Reply), if the app offers one. */
        val reply: Notification.Action?,
        val postedAt: Long,
    )

    private val _timer = MutableStateFlow<TimerInfo?>(null)
    val timer: StateFlow<TimerInfo?> = _timer.asStateFlow()

    private val _nav = MutableStateFlow<NavInfo?>(null)
    val nav: StateFlow<NavInfo?> = _nav.asStateFlow()

    private val _message = MutableStateFlow<MessagePeek?>(null)
    /** The latest message worth a peek (the island shows it for a few seconds). */
    val message: StateFlow<MessagePeek?> = _message.asStateFlow()

    private val _playingPackage = MutableStateFlow<String?>(null)
    /** The app showing a media ("now playing") notification, if any. Used by smart stacks. */
    val playingPackage: StateFlow<String?> = _playingPackage.asStateFlow()

    internal fun setTimer(t: TimerInfo?) {
        _timer.value = t
    }

    internal fun setNav(n: NavInfo?) {
        _nav.value = n
    }

    internal fun postMessage(m: MessagePeek) {
        _message.value = m
    }

    internal fun setPlayingPackage(pkg: String?) {
        _playingPackage.value = pkg
    }

    private val _call = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = _call.asStateFlow()

    internal fun setCall(call: CallInfo?) {
        _call.value = call
    }

    /** True once the user has allowed Kinetic to see notifications. */
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Opens the system page where the user turns on Kinetic's notification access. */
    fun openSettings(context: Context) {
        val component = ComponentName(context, KineticNotificationListener::class.java)
        val detail = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(detail)
        } catch (e: Exception) {
            context.startActivity(
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * Lets Kinetic see *which apps* have notifications (not their contents) so it can
 * show a ring around those icons. Only runs if the user grants Notification access.
 */
class KineticNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationHub.setConnected(true)
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationHub.set(emptySet())
        NotificationHub.setCall(null)
        NotificationHub.setTimer(null)
        NotificationHub.setNav(null)
        NotificationHub.setPlayingPackage(null)
        NotificationHub.setConnected(false)
    }

    /** Post times we've already reacted to, so updates to a notification don't peek again. */
    private val seen = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
        if (sbn != null) maybePeek(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { seen.remove(it) }
        refresh()
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        KineticSettings.init(this)
        val settings = KineticSettings.current
        // Only things the user can clear: skips "music playing", "charging" and similar.
        NotificationHub.set(active.filter { it.isClearable }.mapTo(HashSet()) { it.packageName })
        NotificationHub.setCall(active.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.let(::callInfo))
        NotificationHub.setTimer(if (settings.islandTimers) active.firstNotNullOfOrNull(::timerInfo) else null)
        NotificationHub.setNav(if (settings.islandNavigation) active.firstNotNullOfOrNull(::navInfo) else null)
        NotificationHub.setPlayingPackage(
            active.firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }?.packageName
        )
    }

    // ---- timers & alarms --------------------------------------------------------------

    private fun timerInfo(sbn: StatusBarNotification): NotificationHub.TimerInfo? {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName
        val isClock = pkg in CLOCK_PACKAGES || pkg.contains("clock", ignoreCase = true)
        if (!isClock) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val actions = n.actions?.toList().orEmpty()
        val actionTitles = actions.joinToString(" ") { it.title?.toString().orEmpty() }.lowercase()
        val ringing = n.category == Notification.CATEGORY_ALARM && n.fullScreenIntent != null
        val chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val ongoing = sbn.isOngoing
        if (!ringing && !ongoing) return null
        val lower = (title + " " + text).lowercase()
        val kind = when {
            ringing -> NotificationHub.TimerInfo.Kind.Alarm
            chrono && !countDown -> NotificationHub.TimerInfo.Kind.Stopwatch
            "stopwatch" in lower -> NotificationHub.TimerInfo.Kind.Stopwatch
            chrono || "timer" in lower || TIME_TEXT.containsMatchIn(lower) -> NotificationHub.TimerInfo.Kind.Timer
            else -> return null // e.g. "upcoming alarm" reminders
        }
        val timeText = listOf(title, text).firstOrNull { TIME_TEXT.matches(it.trim()) }
            ?: TIME_TEXT.find(text)?.value ?: TIME_TEXT.find(title)?.value
        val label = when (kind) {
            NotificationHub.TimerInfo.Kind.Alarm -> title.ifBlank { "Alarm" }
            NotificationHub.TimerInfo.Kind.Stopwatch -> "Stopwatch"
            NotificationHub.TimerInfo.Kind.Timer -> title.takeIf { it.isNotBlank() && !TIME_TEXT.matches(it.trim()) } ?: "Timer"
        }
        return NotificationHub.TimerInfo(
            key = sbn.key,
            packageName = pkg,
            kind = kind,
            label = label,
            endsAt = if (chrono && countDown) n.`when` else null,
            startedAt = if (chrono && !countDown) n.`when` else null,
            text = timeText,
            paused = !chrono && ("resume" in actionTitles || "start" in actionTitles),
            contentIntent = n.contentIntent,
            actions = actions,
        )
    }

    // ---- navigation ---------------------------------------------------------------------

    private fun navInfo(sbn: StatusBarNotification): NotificationHub.NavInfo? {
        val n = sbn.notification
        if (sbn.packageName !in NAV_PACKAGES || !sbn.isOngoing) return null
        if (n.category != null && n.category != Notification.CATEGORY_NAVIGATION && n.category != Notification.CATEGORY_TRANSPORT) {
            // Maps' other ongoing notifications (e.g. location sharing) aren't directions.
            if (n.category != Notification.CATEGORY_SERVICE) return null
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val arrow = runCatching { n.getLargeIcon()?.loadDrawable(this)?.toBitmap(96, 96) }.getOrNull()
        // Maps puts the distance first ("300 ft") and the street second; some versions swap them.
        val titleIsDistance = DISTANCE.containsMatchIn(title) && title.length < 16
        return NotificationHub.NavInfo(
            key = sbn.key,
            packageName = sbn.packageName,
            // No distance yet (e.g. "Head toward S 80th Ave"): leave it blank, the island shows the instruction.
            distance = if (titleIsDistance) title else DISTANCE.find(text)?.value.orEmpty(),
            instruction = if (titleIsDistance) text else title,
            eta = sub,
            arrow = arrow,
            contentIntent = n.contentIntent,
        )
    }

    // ---- message peeks ------------------------------------------------------------------

    private val labels = HashMap<String, String>()

    private fun appName(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun maybePeek(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (!sbn.isClearable || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val last = seen[sbn.key]
        seen[sbn.key] = sbn.postTime
        // Updates of something we've already shown (and "alert once" refreshes) don't peek again.
        if (last != null && (last == sbn.postTime || n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)) return
        if (System.currentTimeMillis() - sbn.postTime > 10_000) return

        val settings = KineticSettings.current
        val name = appName(sbn.packageName)
        val isChowaniec = name.startsWith("Chowaniec", ignoreCase = true)
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || n.extras.containsKey(Notification.EXTRA_MESSAGES)

        // Any notification gives the wallpaper a soft pulse from the top.
        if (sbn.packageName != packageName) {
            com.kinetic.wallpaper.WallpaperSignals.ripple(
                this, com.kinetic.wallpaper.WallpaperSignals.Kind.Notification, y = -1f, strength = 0.6f,
            )
        }

        FocusModes.init(this)
        val wanted = ((isChowaniec && settings.islandChowaniecPeek) || (isMessage && settings.islandAllMessagesPeek)) &&
            FocusModes.allows(sbn.packageName)
        if (!wanted) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: name
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val icon = runCatching {
            (n.getLargeIcon()?.loadDrawable(this) ?: packageManager.getApplicationIcon(sbn.packageName)).toBitmap(128, 128)
        }.getOrNull()
        NotificationHub.postMessage(
            NotificationHub.MessagePeek(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = name,
                title = title,
                text = text,
                icon = icon,
                contentIntent = n.contentIntent,
                reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() },
                postedAt = sbn.postTime,
            )
        )
    }

    private companion object {
        val CLOCK_PACKAGES = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock")
        val NAV_PACKAGES = setOf("com.google.android.apps.maps", "com.waze", "com.google.android.apps.mapslite")
        val TIME_TEXT = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
        val DISTANCE = Regex("""\d+(\.\d+)?\s?(ft|mi|m|km|yd)\b""", RegexOption.IGNORE_CASE)
    }

    private fun callInfo(sbn: StatusBarNotification): NotificationHub.CallInfo {
        val n = sbn.notification
        val extras = n.extras
        val name = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: "Call"
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val timing = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val incoming = callType == Notification.CallStyle.CALL_TYPE_INCOMING || (callType == 0 && !timing)
        return NotificationHub.CallInfo(
            key = sbn.key,
            name = name,
            startedAt = if (timing && !incoming) n.`when` else null,
            incoming = incoming,
            packageName = sbn.packageName,
            contentIntent = n.contentIntent,
            actions = n.actions?.toList().orEmpty(),
        )
    }
}

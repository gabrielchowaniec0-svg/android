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
        /** A couple of words for the small pill: "New message", "Claude responded"… */
        val summary: String = "",
        /** The notification's other buttons (Mark as read, Archive…), at most 3. */
        val actions: List<Notification.Action> = emptyList(),
    )

    /** A delivery or ride on its way (Uber, DoorDash, Lyft…), from their live notification. */
    data class TripInfo(
        val key: String,
        val packageName: String,
        val isRide: Boolean,
        val title: String,
        val text: String,
        /** 0..1 when the app shows a progress bar. */
        val progress: Float?,
        /** "6 min" if the notification mentions minutes. */
        val eta: String?,
        val icon: Bitmap?,
        val contentIntent: PendingIntent?,
        /** Any app's progress bar (a download, an upload…), not a ride or delivery. */
        val generic: Boolean = false,
    )

    /** A live game score from a sports app's notification. */
    data class ScoreInfo(
        val key: String,
        val packageName: String,
        val teamA: String,
        val scoreA: String,
        val teamB: String,
        val scoreB: String,
        val status: String,
        val contentIntent: PendingIntent?,
    )

    private val _trip = MutableStateFlow<TripInfo?>(null)
    val trip: StateFlow<TripInfo?> = _trip.asStateFlow()

    private val _score = MutableStateFlow<ScoreInfo?>(null)
    val score: StateFlow<ScoreInfo?> = _score.asStateFlow()

    private val _recording = MutableStateFlow(false)
    /** The screen is being recorded (Samsung's screen recorder or similar). */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    internal fun setTrip(t: TripInfo?) {
        _trip.value = t
    }

    internal fun setScore(s: ScoreInfo?) {
        _score.value = s
    }

    internal fun setRecording(on: Boolean) {
        _recording.value = on
    }

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

    /** Clears a notification (the island's Dismiss button). */
    fun cancel(key: String) {
        runCatching { KineticNotificationListener.instance?.cancelNotification(key) }
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
        instance = this
        NotificationHub.setConnected(true)
        refresh()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
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
        NotificationHub.setTrip(
            active.firstNotNullOfOrNull(::tripInfo)
                ?: if (settings.islandProgress) active.sortedByDescending { it.postTime }.firstNotNullOfOrNull(::progressInfo) else null
        )
        NotificationHub.setScore(active.sortedByDescending { it.postTime }.firstNotNullOfOrNull(::scoreInfo))
        NotificationHub.setRecording(active.any(::isScreenRecording))
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

    // ---- deliveries & rides ---------------------------------------------------------------

    private fun tripInfo(sbn: StatusBarNotification): NotificationHub.TripInfo? {
        val pkg = sbn.packageName
        val ride = pkg in RIDE_PACKAGES
        if (!ride && pkg !in DELIVERY_PACKAGES) return null
        val n = sbn.notification
        val extras = n.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        // Their live trackers are ongoing (or show a progress bar); promos aren't.
        if (!sbn.isOngoing && max <= 0) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val eta = MINUTES.find("$title $text")?.let { "${it.groupValues[1]} min" }
        val icon = runCatching {
            (n.getLargeIcon()?.loadDrawable(this) ?: packageManager.getApplicationIcon(pkg)).toBitmap(96, 96)
        }.getOrNull()
        return NotificationHub.TripInfo(
            key = sbn.key,
            packageName = pkg,
            isRide = ride,
            title = title.ifBlank { appName(pkg) },
            text = text,
            progress = if (max > 0) (prog.toFloat() / max).coerceIn(0f, 1f) else null,
            eta = eta,
            icon = icon,
            contentIntent = n.contentIntent,
        )
    }

    /** Any other app's ongoing progress bar (downloads, uploads, installs…), like iOS Live Activities. */
    private fun progressInfo(sbn: StatusBarNotification): NotificationHub.TripInfo? {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in CLOCK_PACKAGES || pkg in NAV_PACKAGES) return null
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return null
        val extras = n.extras
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return null
        val prog = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val fraction = (prog.toFloat() / max).coerceIn(0f, 1f)
        if (fraction >= 1f) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        val icon = runCatching { packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
        return NotificationHub.TripInfo(
            key = sbn.key,
            packageName = pkg,
            isRide = false,
            title = title.ifBlank { appName(pkg) },
            text = text.ifBlank { appName(pkg) },
            progress = fraction,
            eta = "${(fraction * 100).toInt()}%",
            icon = icon,
            contentIntent = n.contentIntent,
            generic = true,
        )
    }

    // ---- live scores --------------------------------------------------------------------

    private fun scoreInfo(sbn: StatusBarNotification): NotificationHub.ScoreInfo? {
        val s = scoreInfoAny(sbn) ?: return null
        // "My teams": when set, only their games.
        val teams = KineticSettings.current.myTeams.split(',').map { it.trim().lowercase() }.filter { it.length >= 2 }
        if (teams.isEmpty()) return s
        val both = "${s.teamA} ${s.teamB}".lowercase()
        return if (teams.any { both.contains(it) }) s else null
    }

    private fun scoreInfoAny(sbn: StatusBarNotification): NotificationHub.ScoreInfo? {
        if (sbn.packageName !in SPORTS_PACKAGES) return null
        if (System.currentTimeMillis() - sbn.postTime > 4 * 3600_000L) return null
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        for (line in listOf(title, text)) {
            val dash = SCORE_DASH.find(line)
            val comma = if (dash == null) SCORE_COMMA.find(line) else null
            val g = (dash ?: comma ?: continue).groupValues
            // "A 21 - 17 B" → scores are groups 2 and 3; "A 21, B 17" → groups 2 and 4.
            val (teamA, scoreA, teamB, scoreB) = if (dash != null) listOf(g[1], g[2], g[4], g[3]) else listOf(g[1], g[2], g[3], g[4])
            val status = listOf(if (line == title) text else title, sub).firstOrNull { it.isNotBlank() }.orEmpty()
            return NotificationHub.ScoreInfo(
                key = sbn.key,
                packageName = sbn.packageName,
                teamA = teamA.trim(),
                scoreA = scoreA,
                teamB = teamB.trim(),
                scoreB = scoreB,
                status = status.take(40),
                contentIntent = sbn.notification.contentIntent,
            )
        }
        return null
    }

    // ---- screen recording ---------------------------------------------------------------

    private fun isScreenRecording(sbn: StatusBarNotification): Boolean {
        if (!sbn.isOngoing) return false
        val pkg = sbn.packageName
        if (!(pkg.contains("smartcapture") || pkg.contains("screenrecord") || pkg == "com.android.systemui")) return false
        val extras = sbn.notification.extras
        val all = (extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() + " " +
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).lowercase()
        return "record" in all
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
        // Silent / low-importance notifications don't drop down (like iOS "Deliver quietly").
        val ranking = android.service.notification.NotificationListenerService.Ranking()
        val quiet = runCatching { currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT }.getOrDefault(false)
        val isMedia = n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        val wanted = (
            (isChowaniec && settings.islandChowaniecPeek) ||
                (isMessage && settings.islandAllMessagesPeek) ||
                (settings.islandNotifications && !quiet && !isMedia)
            ) && sbn.packageName != packageName && FocusModes.allows(sbn.packageName)
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
                summary = summarize(n, name, title, text, isMessage),
                actions = n.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null && !it.title.isNullOrBlank() }.take(3),
            )
        )
    }

    /** A couple of words for the island's small pill. */
    private fun summarize(n: Notification, app: String, title: String, text: String, isMessage: Boolean): String {
        if (isMessage) return "New message"
        when (n.category) {
            Notification.CATEGORY_EMAIL -> return "New email"
            Notification.CATEGORY_MISSED_CALL -> return "Missed call"
            Notification.CATEGORY_CALL -> return "Call"
            Notification.CATEGORY_EVENT, Notification.CATEGORY_REMINDER -> return title.take(22)
        }
        // Otherwise the first few words of the title (or of the text, if the title is just the app's name).
        val source = if (title.isBlank() || title.equals(app, ignoreCase = true)) text.ifBlank { title } else title
        val words = source.replace('\n', ' ').split(' ').filter { it.isNotBlank() }
        var out = ""
        for (w in words) {
            val next = if (out.isEmpty()) w else "$out $w"
            if (next.length > 22) break
            out = next
        }
        return out.ifBlank { source.take(20) }.trimEnd(',', '.', ':', ';', '-')
    }

    companion object {
        /** The running listener (to clear a notification from the island). */
        @Volatile
        var instance: KineticNotificationListener? = null
            private set

        private val CLOCK_PACKAGES = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock")
        private val NAV_PACKAGES = setOf("com.google.android.apps.maps", "com.waze", "com.google.android.apps.mapslite")
        private val RIDE_PACKAGES = setOf("com.ubercab", "me.lyft.android")
        private val DELIVERY_PACKAGES = setOf(
            "com.ubercab.eats", "com.dd.doordash", "com.grubhub.android", "com.instacart.client",
            "com.postmates.android", "com.amazon.mShop.android.shopping", "com.gopuff.consumer", "com.mcdonalds.app",
            "com.chipotle.ordering", "com.starbucks.mobilecard", "com.dominospizza",
        )
        private val SPORTS_PACKAGES = setOf(
            "com.espn.score_center", "com.fivemobile.thescore", "com.yahoo.mobile.client.android.sportacular",
            "com.gotv.nflgamecenter.us.lite", "com.nbaimd.gametime.nba2011", "com.bamnetworks.mobile.android.gameday.atbat",
            "com.mobilefootie.wc2010", "de.motain.iliga", "com.google.android.googlequicksearchbox", "com.cbssports.sportscaster",
            "com.foxsports.android", "com.nhl.gc1112.free",
        )
        private val MINUTES = Regex("""(\d{1,3})\s*(?:min|mins|minutes)\b""", RegexOption.IGNORE_CASE)
        // "Bears 21 - 17 Packers"
        private val SCORE_DASH = Regex("""([A-Za-z][A-Za-z .'&]{1,24}?)\s+(\d{1,3})\s*[-–]\s*(\d{1,3})\s+([A-Za-z][A-Za-z .'&]{1,24})""")
        // "Bears 21, Packers 17"
        val SCORE_COMMA = Regex("""([A-Za-z][A-Za-z .'&]{1,24}?)\s+(\d{1,3}),\s*([A-Za-z][A-Za-z .'&]{1,24}?)\s+(\d{1,3})""")
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

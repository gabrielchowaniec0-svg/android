package com.kinetic.launcher.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import android.app.KeyguardManager
import androidx.compose.ui.graphics.toArgb
import com.kinetic.launcher.KineticNotificationListener
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.NotificationHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Music / video that's playing, from Android's media sessions. */
data class MediaInfo(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    /** A bright colour taken from the album art, for the waveform (like iOS). */
    val accent: Color,
    /** The playing app's own icon (shown when the island shrinks to a little logo). */
    val appIcon: ImageBitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() when [positionMs] was measured. */
    val positionAt: Long,
    val speed: Float,
) {
    fun positionNow(): Long =
        if (!playing) positionMs
        else (positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()).coerceAtMost(
            if (durationMs > 0) durationMs else Long.MAX_VALUE
        )
}

/** Short-lived pop-ups, like iOS's "Silent mode" and "Charging". */
sealed interface IslandAlert {
    /** [fullAt] e.g. "11:40 PM" once Android has an estimate. */
    data class Charging(val level: Int, val fullAt: String? = null) : IslandAlert
    /** Earbuds or headphones just connected. */
    data class Earbuds(val name: String, val battery: Int?) : IslandAlert
    data class Ringer(val mode: Int) : IslandAlert
    /** Lock-screen padlock: [open] animates the shackle opening after you unlock. */
    data class Lock(val open: Boolean) : IslandAlert
    /** Face ID–style scan while the lock screen is waiting; [success] = recognised. */
    /** Face scan: scanning, then a tick when it works, or a little shake if it didn't. */
    data class Scan(val success: Boolean, val failed: Boolean = false) : IslandAlert
    /** A short message from elsewhere in Kinetic (S Pen answers, "Calling Mom"…). */
    data class Say(val icon: String, val text: String) : IslandAlert
    /** A message with a button, e.g. a command asking for permission. */
    data class Ask(val icon: String, val text: String, val button: String, val secondary: String? = null) : IslandAlert
    /** Battery just dropped to 15% (when One UI warns too). */
    data class LowBattery(val level: Int) : IslandAlert
    /** Volume or brightness slider ([kind]: "volume", "ring", "call", "brightness"). */
    data class Level(val kind: String, val percent: Int) : IslandAlert
    /** A screenshot was just taken: thumbnail with Edit / Share / Vault. */
    data class Screenshot(val id: Long, val thumb: androidx.compose.ui.graphics.ImageBitmap?) : IslandAlert
    /** Confetti and a message (homework done, Focus finished…). */
    data class Celebrate(val icon: String, val text: String) : IslandAlert
    /** A Focus was switched on or off. */
    data class Focus(val name: String, val glyph: com.kinetic.launcher.FocusModes.Glyph, val color: Int, val on: Boolean) : IslandAlert
}

/**
 * Everything the Dynamic Island shows, gathered from the system: the playing media
 * session, an ongoing call (from Kinetic's notification listener), charging and the
 * ringer switch.
 */
class IslandModel(private val context: Context, private val scope: CoroutineScope) {

    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media.asStateFlow()

    private val _alert = MutableStateFlow<IslandAlert?>(null)
    val alert: StateFlow<IslandAlert?> = _alert.asStateFlow()

    /** True while the user has long-pressed the island open. */
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    val call: StateFlow<NotificationHub.CallInfo?> get() = NotificationHub.call
    val timer: StateFlow<NotificationHub.TimerInfo?> get() = NotificationHub.timer
    val nav: StateFlow<NotificationHub.NavInfo?> get() = NotificationHub.nav
    val trip: StateFlow<NotificationHub.TripInfo?> get() = NotificationHub.trip
    val score: StateFlow<NotificationHub.ScoreInfo?> get() = NotificationHub.score

    /** Island stacks: which live thing is in front (swipe sideways on the island to flip). */
    private val _stackShift = MutableStateFlow(0)
    val stackShift: StateFlow<Int> = _stackShift.asStateFlow()

    /** Bumped when the swipe flips the stack (shows the little dots for a moment). */
    private val _stackTick = MutableStateFlow(0)
    val stackTick: StateFlow<Int> = _stackTick.asStateFlow()

    /** Which way the last sideways swipe went (+1 / -1), for the slide animation. */
    @Volatile
    var lastStackDir = 1
        private set

    fun cycleStack(direction: Int) {
        lastStackDir = if (direction >= 0) 1 else -1
        _stackShift.value += direction
        _stackTick.value++
        collapse()
    }

    /** Bumped when your team's score changes (the island bounces). */
    private val _scoreBump = MutableStateFlow(0)
    val scoreBump: StateFlow<Int> = _scoreBump.asStateFlow()

    /** Privacy dot next to the island, like iOS: what's using the mic / camera / screen. */
    enum class Privacy { None, Mic, Camera, ScreenRecord }

    private val _privacy = MutableStateFlow(Privacy.None)
    val privacy: StateFlow<Privacy> = _privacy.asStateFlow()
    private var micOn = false
    private val camerasInUse = HashSet<String>()

    private fun updatePrivacy() {
        _privacy.value = when {
            NotificationHub.recording.value -> Privacy.ScreenRecord
            camerasInUse.isNotEmpty() -> Privacy.Camera
            micOn -> Privacy.Mic
            else -> Privacy.None
        }
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            micOn = !configs.isNullOrEmpty()
            updatePrivacy()
        }
    }
    private val cameraManager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
    private val cameraCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            camerasInUse -= cameraId
            updatePrivacy()
        }
        override fun onCameraUnavailable(cameraId: String) {
            camerasInUse += cameraId
            updatePrivacy()
        }
    }

    private var lastBattery = -1

    private val _peek = MutableStateFlow<NotificationHub.MessagePeek?>(null)
    /** A message dropping down from the island right now. */
    val peek: StateFlow<NotificationHub.MessagePeek?> = _peek.asStateFlow()

    /** The notification peek is open (buttons and reply) rather than the small pill. */
    private val _peekOpen = MutableStateFlow(false)
    val peekOpen: StateFlow<Boolean> = _peekOpen.asStateFlow()

    private val _replying = MutableStateFlow(false)
    /** True while the reply box is open (the island's window needs the keyboard then). */
    val replying: StateFlow<Boolean> = _replying.asStateFlow()
    private var peekJob: Job? = null
    private var lastPeekAt = 0L

    /**
     * How the media should look. Like iOS: nothing while you're inside the app that's
     * playing; compact while it plays elsewhere; and after it's been paused for
     * a few seconds (Settings ▸ Dynamic Island) outside its app, it shrinks to just a little logo.
     */
    enum class MediaLook { Hidden, Compact, Minimal }

    private val _mediaLook = MutableStateFlow(MediaLook.Hidden)
    val mediaLook: StateFlow<MediaLook> = _mediaLook.asStateFlow()

    private var foreground: String? = null
    private var minimalJob: Job? = null
    private var lookKey: Pair<Boolean, Boolean>? = null

    /** Called by the service whenever another app comes to the front. */
    fun setForeground(packageName: String?) {
        if (packageName == null || packageName == foreground) return
        // The notification shade and keyboards aren't really "leaving" the app.
        if (packageName == "com.android.systemui" || packageName.contains("inputmethod") ||
            packageName.contains("honeyboard")
        ) return
        foreground = packageName
        updateLook()
    }

    private fun updateLook() {
        val m = _media.value
        if (m == null) {
            lookKey = null
            minimalJob?.cancel()
            _mediaLook.value = MediaLook.Hidden
            return
        }
        val inApp = m.packageName == foreground
        val key = m.playing to inApp
        if (key == lookKey) return
        lookKey = key
        minimalJob?.cancel()
        _mediaLook.value = when {
            inApp -> MediaLook.Hidden
            else -> if (_mediaLook.value == MediaLook.Minimal && !m.playing) MediaLook.Minimal else MediaLook.Compact
        }
        if (!m.playing && !inApp && _mediaLook.value == MediaLook.Compact) {
            minimalJob = scope.launch {
                delay(KineticSettings.current.islandMinimalSeconds * 1000L)
                _mediaLook.value = MediaLook.Minimal
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, KineticNotificationListener::class.java)
    private var controller: MediaController? = null
    private var alertJob: Job? = null
    /** Watches the real lock state after the screen wakes (see [onScreenOn]). */
    private var unlockWatch: Job? = null
    private var collapseJob: Job? = null
    private var pausedHideJob: Job? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
        override fun onSessionDestroyed() = pickController()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickController() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    val bm = c.getSystemService(BatteryManager::class.java)
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    showAlert(IslandAlert.Charging(level))
                    // Android needs a moment to estimate; add "Full at …" as soon as it can.
                    scope.launch {
                        repeat(12) {
                            delay(250)
                            val ms = bm.computeChargeTimeRemaining()
                            if (ms > 0) {
                                val at = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis() + ms))
                                if (_alert.value is IslandAlert.Charging) _alert.value = IslandAlert.Charging(level, at)
                                return@launch
                            }
                        }
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> earbudsConnected(intent)
                BATTERY_LEVEL_CHANGED -> {
                    // Some earbuds report their battery a moment after connecting.
                    val lvl = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    val a = _alert.value
                    if (lvl in 0..100 && a is IslandAlert.Earbuds && a.battery == null) _alert.value = a.copy(battery = lvl)
                }
                Intent.ACTION_SCREEN_ON -> onScreenOn(c)
                Intent.ACTION_USER_PRESENT -> {
                    // On the home screen / in an app now. Let a tick that's already
                    // showing finish (it's half a second); anything else goes straight away.
                    unlockWatch?.cancel()
                    val a = _alert.value
                    if (a is IslandAlert.Scan && !a.success && !a.failed) {
                        showAlert(IslandAlert.Scan(success = true))
                    } else if (a is IslandAlert.Lock || (a is IslandAlert.Scan && !a.success)) {
                        alertJob?.cancel()
                        _alert.value = null
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                    val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (level < 0 || scale <= 0) return
                    val pct = level * 100 / scale
                    // Warn as it crosses 15% (the same moment One UI does), only when unplugged.
                    if (lastBattery > 15 && pct <= 15 && !plugged) showAlert(IslandAlert.LowBattery(pct))
                    lastBattery = pct
                }
                Intent.ACTION_SCREEN_OFF -> {
                    unlockWatch?.cancel()
                    alertJob?.cancel()
                    _alert.value = null
                    // Screen off: fold the island back up (it shouldn't wake up opened).
                    _expanded.value = false
                    collapseJob?.cancel()
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    // The current mode is re-sent when we register; only show real changes.
                    if (isInitialStickyBroadcast) return
                    showAlert(IslandAlert.Ringer(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)))
                }
            }
        }
    }

    fun start() {
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BATTERY_LEVEL_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        listenForSessions()
        // Privacy dots.
        runCatching { audio.registerAudioRecordingCallback(recordingCallback, main) }
        micOn = runCatching { audio.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false)
        runCatching { cameraManager.registerAvailabilityCallback(cameraCallback, main) }
        scope.launch { NotificationHub.recording.collect { updatePrivacy() } }
        // Switching a Focus shows its name and icon, like iOS.
        scope.launch {
            var last: String? = com.kinetic.launcher.FocusModes.active.value?.id
            com.kinetic.launcher.FocusModes.active.collect { m ->
                if (m?.id == last) return@collect
                // A Focus switched off some other way ends its ring too.
                if (m == null) com.kinetic.launcher.island.FocusLive.clear(context)
                val shown = m ?: com.kinetic.launcher.FocusModes.modes.value.firstOrNull { it.id == last }
                last = m?.id
                if (shown != null) showAlert(IslandAlert.Focus(shown.name, shown.glyph, shown.color, on = m != null))
            }
        }
        // New messages drop down for a few seconds (longer if you touch them).
        scope.launch {
            NotificationHub.message.collect { m ->
                if (m == null || m.postedAt <= lastPeekAt) return@collect
                lastPeekAt = m.postedAt
                _replying.value = false
                _peekOpen.value = false
                _peek.value = m
                schedulePeekEnd(4500)
            }
        }
        // A score change in the game you're following: the island bounces.
        scope.launch {
            var last: NotificationHub.ScoreInfo? = null
            NotificationHub.score.collect { s ->
                val prev = last
                if (s != null && prev != null && s.key == prev.key && (s.scoreA != prev.scoreA || s.scoreB != prev.scoreB)) _scoreBump.value++
                last = s
            }
        }
        // Messages from the rest of Kinetic (S Pen answers…).
        scope.launch {
            IslandBus.says.collect { s -> showAlert(IslandAlert.Say(Glyphs.plain(s.icon), Glyphs.text(s.text))) }
        }
        scope.launch {
            IslandBus.levels.collect { l ->
                if (l.kind == "hide") {
                    if (_alert.value is IslandAlert.Level) {
                        alertJob?.cancel()
                        _alert.value = null
                    }
                } else {
                    showAlert(IslandAlert.Level(l.kind, l.percent))
                }
            }
        }
        scope.launch { IslandBus.celebrations.collect { c -> showAlert(IslandAlert.Celebrate(Glyphs.plain(c.icon), Glyphs.text(c.text))) } }
        LiveFeatures.start(context, scope)
        // Screenshot preview (iOS-style thumbnail with Edit / Share / Vault).
        ScreenshotPreview.onShot = { shot -> showAlert(IslandAlert.Screenshot(shot.id, shot.thumb)) }
        ScreenshotPreview.onDismiss = {
            if (_alert.value is IslandAlert.Screenshot) {
                alertJob?.cancel()
                _alert.value = null
            }
        }
        ScreenshotPreview.start(context, scope)
        scope.launch {
            IslandBus.asks.collect { a ->
                askAction = a.onTap
                askSecondary = a.onSecondary
                showAlert(IslandAlert.Ask(Glyphs.plain(a.icon), Glyphs.text(a.text), Glyphs.text(a.button), a.secondary?.let(Glyphs::text)))
            }
        }
    }

    /** (Re)connects to media sessions — needs Kinetic's Notification access. */
    fun listenForSessions() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener)
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
            pickController()
        } catch (_: SecurityException) {
            // Notification access is off: no music in the island until it's turned on.
        }
    }

    fun stop() {
        ScreenshotPreview.stop()
        LiveFeatures.stop(context)
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { cameraManager.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun schedulePeekEnd(ms: Long) {
        peekJob?.cancel()
        peekJob = scope.launch {
            delay(ms)
            if (!_replying.value) {
                _peek.value = null
                _peekOpen.value = false
            }
        }
    }

    fun dismissPeek() {
        peekJob?.cancel()
        _replying.value = false
        _peekOpen.value = false
        _peek.value = null
    }

    private var askAction: (() -> Unit)? = null

    /** The island's button was pressed (e.g. "Allow"). */
    private var askSecondary: (() -> Unit)? = null

    /** One of the island's buttons was pressed ([primary] = the blue one). */
    /** Swiped away (e.g. a "Search / Web" question you didn't mean to ask). */
    fun dismissAlert() {
        askAction = null
        askSecondary = null
        alertJob?.cancel()
        _alert.value = null
    }

    fun answerAsk(primary: Boolean = true) {
        val a = if (primary) askAction else askSecondary
        askAction = null
        askSecondary = null
        alertJob?.cancel()
        _alert.value = null
        a?.invoke()
    }

    /** Opens the notification peek into its buttons (pull down or hold). */
    fun openPeekActions() {
        if (_peek.value == null) return
        _peekOpen.value = true
        schedulePeekEnd(9000)
    }

    /** Clears the notification itself (the Dismiss button). */
    fun clearPeekNotification() {
        _peek.value?.key?.let { NotificationHub.cancel(it) }
        dismissPeek()
    }

    /** Presses one of the peeked notification's buttons (Mark as read, Archive…). */
    fun pressPeekAction(action: android.app.Notification.Action) {
        action.actionIntent?.let { sendPending(context, it) }
        dismissPeek()
    }

    /** A pull on the island: [dy] px from where it started (down is positive). */
    data class Pull(
        val dy: Float,
        val done: Boolean = false,
        val vy: Float = 0f,
        /**
         * Android's own status-bar gesture took the touch away part-way (a swipe down
         * from the top of the screen belongs to the notification shade). The pull
         * still counts, going by which way it was heading.
         */
        val cancelled: Boolean = false,
    )

    private val _pull = MutableStateFlow<Pull?>(null)
    val pull: StateFlow<Pull?> = _pull.asStateFlow()

    fun pullMove(dy: Float) {
        _pull.value = Pull(dy)
    }

    fun pullEnd(dy: Float, vy: Float, cancelled: Boolean = false) {
        _pull.value = Pull(dy, done = true, vy = vy, cancelled = cancelled)
    }

    fun clearPull() {
        if (_pull.value?.done == true) _pull.value = null
    }

    fun openPeek() {
        _peek.value?.contentIntent?.let { sendPending(context, it) }
        dismissPeek()
    }

    fun touchPeek() {
        if (_peek.value != null && !_replying.value) schedulePeekEnd(if (_peekOpen.value) 9000 else 6000)
    }

    fun startReply() {
        if (_peek.value?.reply == null) return
        peekJob?.cancel()
        _peekOpen.value = true
        _replying.value = true
    }

    /** Sends [text] through the message's Reply action, like replying from the shade. */
    fun sendReply(text: String) {
        val m = _peek.value ?: return
        val action = m.reply ?: return
        val inputs = action.remoteInputs ?: return
        val intent = Intent()
        val results = android.os.Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            action.actionIntent?.send(context, 0, intent)
        } catch (_: Exception) {
        }
        dismissPeek()
    }

    /** Presses one of a notification's buttons (timer Pause/Cancel, alarm Snooze/Dismiss…). */
    fun sendAction(action: android.app.Notification.Action) {
        action.actionIntent?.let { sendPending(context, it) }
        touched()
    }

    fun openTimer() {
        timer.value?.contentIntent?.let { sendPending(context, it) }
        collapse()
    }

    fun openTrip() {
        trip.value?.contentIntent?.let { sendPending(context, it) }
        collapse()
    }

    fun openScore() {
        score.value?.contentIntent?.let { sendPending(context, it) }
        collapse()
    }

    fun openBatterySaver() {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        alertJob?.cancel()
        _alert.value = null
    }

    fun openNav() {
        nav.value?.contentIntent?.let { sendPending(context, it) }
        collapse()
    }

    fun expand() {
        if (_peek.value != null) {
            openPeekActions()
            return
        }
        // Kinetic's own live things (stopwatch, Focus, flashlight…) can open too.
        if (_media.value == null && call.value == null && timer.value == null && nav.value == null &&
            trip.value == null && score.value == null && LiveHub.lives.value.isEmpty() && TorchLive.state.value == null
        ) return
        _expanded.value = true
        scheduleCollapse()
    }

    fun collapse() {
        _expanded.value = false
        collapseJob?.cancel()
    }

    /** Keeps the expanded island open a little longer after each touch. */
    fun touched() {
        if (_expanded.value) scheduleCollapse()
    }

    private fun scheduleCollapse() {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(7000)
            _expanded.value = false
        }
    }

    // ---- earbuds -------------------------------------------------------------------------

    private fun earbudsConnected(intent: Intent) {
        if (!KineticSettings.current.islandEarbuds) return
        try {
            val device: android.bluetooth.BluetoothDevice = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java) ?: return
            val major = device.bluetoothClass?.majorDeviceClass
            if (major != android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) return
            val name = device.alias ?: device.name ?: "Headphones"
            // Battery isn't public API; most phones (Samsung included) still answer this.
            val battery = runCatching { device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int }.getOrNull()?.takeIf { it in 0..100 }
            showAlert(IslandAlert.Earbuds(name, battery))
        } catch (_: SecurityException) {
            // Nearby devices permission not given: skip quietly.
        }
    }

    // ---- face unlock -----------------------------------------------------------------
    //
    // Apps can't see Samsung's face scanner, but they can see the moment the phone stops
    // being locked. So: when the screen wakes, check ~14 times a second whether it's
    // unlocked yet; that's when face (or fingerprint) unlock actually succeeded.

    private fun onScreenOn(c: Context) {
        if (!KineticSettings.current.islandUnlock) return
        val km = c.getSystemService(KeyguardManager::class.java) ?: return
        if (!km.isKeyguardLocked) return
        unlockWatch?.cancel()
        unlockWatch = scope.launch {
            // Already unlocked (Smart Lock, e.g. your watch or car): no fake scan.
            if (!km.isDeviceLocked) {
                showAlert(IslandAlert.Lock(open = true))
                return@launch
            }
            // Woken by a notification on the table or in a pocket: nobody's looking,
            // so don't scan. Still celebrate if they unlock in the next few seconds.
            val looking = heldUp(c)
            if (looking) showAlert(IslandAlert.Scan(success = false))
            val start = android.os.SystemClock.uptimeMillis()
            val scanFor = if (looking) 3_200L else 0L
            var failedShown = false
            while (android.os.SystemClock.uptimeMillis() - start < 30_000L) {
                if (!km.isDeviceLocked || !km.isKeyguardLocked) {
                    if (!failedShown && looking) showAlert(IslandAlert.Scan(success = true))
                    else if (km.isKeyguardLocked) showAlert(IslandAlert.Lock(open = true))
                    return@launch
                }
                if (looking && !failedShown && android.os.SystemClock.uptimeMillis() - start > scanFor) {
                    failedShown = true
                    showAlert(IslandAlert.Scan(success = false, failed = true))
                }
                delay(if (android.os.SystemClock.uptimeMillis() - start < 5_000L) 70L else 200L)
            }
        }
    }

    /** True if the phone looks like it's held up in front of someone (not flat, not in a pocket). */
    private suspend fun heldUp(c: Context): Boolean {
        val sm = c.getSystemService(android.hardware.SensorManager::class.java) ?: return true
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return true
        val prox = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)
        var gravity: FloatArray? = null
        var near = false
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                if (e.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) gravity = e.values.clone()
                else near = e.values[0] < minOf(e.sensor.maximumRange, 3f)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        sm.registerListener(listener, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        prox?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        try {
            kotlinx.coroutines.withTimeoutOrNull(250L) { while (gravity == null) delay(15) }
            delay(40) // give the proximity sensor a moment too
        } finally {
            sm.unregisterListener(listener)
        }
        if (near) return false
        val g = gravity ?: return true
        val z = g[2] / 9.81f
        // Lying flat face up (~1) or face down (~-1) on a table: not being looked at.
        return z < 0.93f && z > -0.95f
    }

    /** A secure screenshot was sealed into the Vault: the padlock closes. */
    fun showVaultSaved() = showAlert(IslandAlert.Lock(open = false))

    private fun showAlert(alert: IslandAlert) {
        alertJob?.cancel()
        _alert.value = alert
        alertJob = scope.launch {
            delay(
                when (alert) {
                    is IslandAlert.Charging -> 3000
                    is IslandAlert.Lock -> if (alert.open) 1300 else 2500
                    is IslandAlert.Scan -> when {
                        alert.success -> 520
                        alert.failed -> 900
                        else -> 4000
                    }
                    is IslandAlert.LowBattery -> 5000
                    is IslandAlert.Say -> (2600L + alert.text.length * 45L).coerceAtMost(7000L)
                    is IslandAlert.Earbuds -> 3500
                    is IslandAlert.Ask -> 9000
                    is IslandAlert.Level -> 1500
                    is IslandAlert.Celebrate -> 3200
                    is IslandAlert.Screenshot -> 6500
                    else -> 2200
                }
            )
            _alert.value = null
            // After the tick, the padlock opens.
            // Still on the lock screen (Samsung's "stay on lock screen"): show the open padlock there.
            if (alert is IslandAlert.Scan && alert.success && context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                showAlert(IslandAlert.Lock(open = true))
            }
        }
    }

    // ---- calls -------------------------------------------------------------------------

    fun openCall() {
        call.value?.contentIntent?.let { sendPending(context, it) }
        collapse()
    }

    fun callAction(action: android.app.Notification.Action) {
        action.actionIntent?.let { sendPending(context, it) }
        touched()
    }

    // ---- media ------------------------------------------------------------------------

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        touched()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
        touched()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
        touched()
    }

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms)
        touched()
    }

    /** Opens the app that's playing. */
    fun openMediaApp(): Boolean {
        val c = controller ?: return false
        val intent = c.sessionActivity
        return try {
            if (intent != null) sendPending(context, intent)
            else context.packageManager.getLaunchIntentForPackage(c.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let(context::startActivity) ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pickController() {
        val list = try {
            sessions.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            emptyList()
        }
        // Prefer whatever is playing right now; otherwise the most recent session.
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            best?.registerCallback(controllerCallback, main)
        }
        publishMedia()
    }

    private var lastArt: Bitmap? = null
    private var lastArtImage: ImageBitmap? = null
    private var lastAccent: Color = Color.White

    private fun publishMedia() {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState
        if (c == null || meta == null || state == null || state.state == PlaybackState.STATE_NONE ||
            state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_ERROR
        ) {
            _media.value = null
            com.kinetic.wallpaper.WallpaperSignals.setMusicGlow(context, 0, false)
            updateLook()
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art !== lastArt) {
            lastArt = art
            lastArtImage = art?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            lastAccent = art?.let(::accentOf) ?: Color.White
        }
        val playing = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
        _media.value = MediaInfo(
            packageName = c.packageName,
            title = title,
            artist = artist,
            art = lastArtImage,
            accent = lastAccent,
            appIcon = iconFor(c.packageName),
            playing = playing,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state.position.coerceAtLeast(0),
            positionAt = state.lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = if (playing) state.playbackSpeed.takeIf { it > 0f } ?: 1f else 0f,
        )
        // Like iOS, a paused song leaves the island after a while.
        pausedHideJob?.cancel()
        if (!playing) {
            pausedHideJob = scope.launch {
                delay(10 * 60_000L)
                if (_media.value?.playing == false) {
                    _media.value = null
                    _expanded.value = false
                    updateLook()
                }
            }
        }
        updateLook()
        // The wallpaper glows in the album's colours while music plays.
        com.kinetic.wallpaper.WallpaperSignals.setMusicGlow(context, lastAccent.toArgb(), playing)
    }

    private val icons = HashMap<String, ImageBitmap?>()

    private fun iconFor(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        try {
            val size = (context.resources.displayMetrics.density * 48).toInt()
            context.packageManager.getApplicationIcon(pkg).toBitmap(size, size).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    /** Average colour of the art, pushed brighter and more saturated so it reads on black. */
    private fun accentOf(bitmap: Bitmap): Color = try {
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val px = Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(px, hsv)
        hsv[1] = (hsv[1] * 1.3f).coerceIn(0.35f, 1f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        Color(AndroidColor.HSVToColor(hsv))
    } catch (_: Exception) {
        Color.White
    }

    companion object {

        /** Sends a PendingIntent, allowing it to open its screen from the island. */
        fun sendPending(context: Context, intent: android.app.PendingIntent) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    val options = android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                    intent.send(context, 0, null, null, null, null, options)
                } else {
                    intent.send()
                }
            } catch (_: Exception) {
            }
        }
    }
}

private const val BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

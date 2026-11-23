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
import com.kinetic.launcher.KineticNotificationListener
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
    data class Charging(val level: Int) : IslandAlert
    data class Ringer(val mode: Int) : IslandAlert
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

    /**
     * How the media should look. Like iOS: nothing while you're inside the app that's
     * playing; compact while it plays elsewhere; and after it's been paused for
     * [MINIMAL_AFTER_MS] outside its app, it shrinks to just a little logo.
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
                delay(MINIMAL_AFTER_MS)
                _mediaLook.value = MediaLook.Minimal
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, KineticNotificationListener::class.java)
    private var controller: MediaController? = null
    private var alertJob: Job? = null
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
                    showAlert(IslandAlert.Charging(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)))
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
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        listenForSessions()
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
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun expand() {
        if (_media.value == null && call.value == null) return
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

    private fun showAlert(alert: IslandAlert) {
        alertJob?.cancel()
        _alert.value = alert
        alertJob = scope.launch {
            delay(if (alert is IslandAlert.Charging) 3000 else 2200)
            _alert.value = null
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
        /** Paused and away from its app this long → the island shrinks to a logo. */
        const val MINIMAL_AFTER_MS = 10_000L

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

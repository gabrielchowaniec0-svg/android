package com.kinetic.launcher.island

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.view.Display
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kinetic's own status bar: time (or carrier on the lock screen) and notification icons
 * on the left of the island, signal / Wi-Fi / sound / battery on the right. It moves
 * and fades with the island, frame by frame. Samsung's own icons are hidden with
 * Good Lock → QuickStar.
 */
object StatusBarData {
    data class State(
        val battery: Int = 100,
        val charging: Boolean = false,
        val saver: Boolean = false,
        /** 0..4, or -1 for no service. */
        val signal: Int = -1,
        /** "5G", "5G+", "LTE"…, when known. */
        val net: String? = null,
        /** 0..4 while on Wi-Fi, else -1. */
        val wifi: Int = -1,
        val airplane: Boolean = false,
        val ringer: Int = AudioManager.RINGER_MODE_NORMAL,
        val dnd: Boolean = false,
        val carrier: String = "",
        val locked: Boolean = false,
        /** True when the screen behind is light (so the text turns dark). */
        val lightBehind: Boolean = false,
        /** Same, for the bottom edge (Kinetic's home bar). */
        val lightBottom: Boolean = false,
        /** False while an app hides the status bar (full-screen videos, games). */
        val systemBarShown: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun update(f: (State) -> State) {
        _state.value = f(_state.value)
    }

    private var service: AccessibilityService? = null
    private var receiver: BroadcastReceiver? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var scope: CoroutineScope? = null
    private var sampleJob: Job? = null
    private var loopJob: Job? = null
    private var overrideNet: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    private var dataType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN

    /** Set once the overlay has ever been told the status bar is showing: only then is "hidden" believable. */
    private var insetsWork = false

    fun setSystemBarShown(shown: Boolean) {
        // Overlay windows aren't always told about the status bar (they can report "hidden"
        // all the time). Only hide for full-screen apps once we've seen it report "shown".
        if (shown) insetsWork = true
        val v = shown || !insetsWork
        if (_state.value.systemBarShown != v) update { it.copy(systemBarShown = v) }
    }

    fun start(s: AccessibilityService, sc: CoroutineScope) {
        stop()
        service = s
        scope = sc
        val app = s.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        if (level >= 0 && scale > 0) {
                            update {
                                it.copy(
                                    battery = level * 100 / scale,
                                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                                )
                            }
                        }
                    }
                    else -> refreshMisc(app)
                }
            }
        }
        receiver = r
        app.registerReceiver(
            r,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        refreshMisc(app)

        // Signal bars and 5G / LTE.
        runCatching {
            val tm = app.getSystemService(TelephonyManager::class.java)
            val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener {
                override fun onSignalStrengthsChanged(ss: SignalStrength) {
                    update { it.copy(signal = ss.level.coerceIn(0, 4)) }
                }

                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    overrideNet = info.overrideNetworkType
                    dataType = info.networkType
                    update { it.copy(net = netLabel()) }
                }
            }
            tm.registerTelephonyCallback(app.mainExecutor, cb)
            telephonyCallback = cb
        }.onFailure {
            // Display info needs a permission on some versions: try signal bars alone.
            runCatching {
                val tm = app.getSystemService(TelephonyManager::class.java)
                val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(ss: SignalStrength) {
                        update { it.copy(signal = ss.level.coerceIn(0, 4)) }
                    }
                }
                tm.registerTelephonyCallback(app.mainExecutor, cb)
                telephonyCallback = cb
            }
        }

        // Wi-Fi bars.
        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val wm = app.getSystemService(WifiManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val level = if (wifi) {
                        val rssi = caps.signalStrength
                        if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 3
                        else runCatching { wm.calculateSignalLevel(rssi) * 4 / wm.maxSignalLevel.coerceAtLeast(1) }.getOrDefault(3)
                    } else -1
                    update { it.copy(wifi = level.coerceIn(-1, 4)) }
                }

                override fun onLost(network: Network) {
                    update { it.copy(wifi = -1) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }

        // Light or dark behind the bar: check when apps change, and every 10 s.
        loopJob = sc.launch {
            while (true) {
                delay(10_000)
                if (wantsSampling(app)) sample()
            }
        }
    }

    fun stop() {
        val app = service?.applicationContext
        receiver?.let { r -> runCatching { app?.unregisterReceiver(r) } }
        receiver = null
        telephonyCallback?.let { cb -> runCatching { app?.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb) } }
        telephonyCallback = null
        netCallback?.let { cb -> runCatching { app?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        loopJob?.cancel()
        sampleJob?.cancel()
        service = null
    }

    private fun netLabel(): String? = when (overrideNet) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE+"
        else -> when (dataType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
            else -> null
        }
    }

    private fun refreshMisc(app: Context) {
        val audio = app.getSystemService(AudioManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        val tm = runCatching { app.getSystemService(TelephonyManager::class.java) }.getOrNull()
        update {
            it.copy(
                saver = power.isPowerSaveMode,
                airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
                ringer = audio.ringerMode,
                dnd = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
                carrier = runCatching { tm?.networkOperatorName?.takeIf { n -> n.isNotBlank() } ?: tm?.simOperatorName }.getOrNull().orEmpty(),
                locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            )
        }
    }

    /** Re-checks the lock state (the lock screen can come and go without a broadcast). */
    fun refreshLock(context: Context) {
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (locked != _state.value.locked) update { it.copy(locked = locked) }
    }

    private fun wantsSampling(app: Context): Boolean {
        val s = com.kinetic.launcher.KineticSettings.current
        return ((s.statusBar && s.statusBarTint == "auto") || s.homeBar) &&
            app.getSystemService(PowerManager::class.java).isInteractive && _state.value.systemBarShown
    }

    /** Called when another app (or screen) comes to the front. */
    fun onWindowChanged() {
        val s = service ?: return
        refreshLock(s)
        if (!wantsSampling(s)) return
        sampleJob?.cancel()
        sampleJob = scope?.launch {
            delay(550) // let the app's opening animation finish
            sample()
        }
    }

    /** True while a screenshot for colour sampling is being taken (the secure screenshot waits). */
    @Volatile
    var sampling = false
        private set

    private fun sample() {
        val s = service ?: return
        if (sampling) return
        sampling = true
        runCatching {
            s.takeScreenshot(
                Display.DEFAULT_DISPLAY, s.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        scope?.launch(Dispatchers.Default) {
                            val buffer = result.hardwareBuffer
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                if (hw != null) {
                                    val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                                    hw.recycle()
                                    if (bmp != null) {
                                        val light = isLight(s, bmp)
                                        val lightBottom = isLightBottom(bmp)
                                        bmp.recycle()
                                        if (light != _state.value.lightBehind || lightBottom != _state.value.lightBottom) {
                                            update { it.copy(lightBehind = light, lightBottom = lightBottom) }
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                buffer.close()
                                sampling = false
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        sampling = false
                    }
                },
            )
        }.onFailure { sampling = false }
    }

    /** The bottom edge, either side of the home bar. */
    private fun isLightBottom(bmp: Bitmap): Boolean {
        val w = bmp.width
        var sum = 0.0
        var n = 0
        for (y in listOf(bmp.height - 4, bmp.height - (bmp.height / 60).coerceAtLeast(8))) {
            var x = (w * 0.04f).toInt()
            while (x < w * 0.96f) {
                if (x < w * 0.3f || x > w * 0.7f) {
                    val p = bmp.getPixel(x, y.coerceIn(0, bmp.height - 1))
                    sum += (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0
                    n++
                }
                x += (w / 40).coerceAtLeast(1)
            }
        }
        return n > 0 && sum / n > 0.62
    }

    /** Looks at the very top edge and just under the status bar, away from the island. */
    private fun isLight(context: Context, bmp: Bitmap): Boolean {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val barH = if (id > 0) context.resources.getDimensionPixelSize(id) else (40 * context.resources.displayMetrics.density).toInt()
        val w = bmp.width
        val rows = listOf(3, (barH + 6).coerceAtMost(bmp.height - 1))
        var sum = 0.0
        var n = 0
        for (y in rows) {
            var x = (w * 0.04f).toInt()
            while (x < w * 0.96f) {
                // Skip the middle, where the island is.
                if (x < w * 0.33f || x > w * 0.67f) {
                    val p = bmp.getPixel(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                    n++
                }
                x += (w / 40).coerceAtLeast(1)
            }
        }
        return n > 0 && sum / n > 0.62
    }
}

/**
 * Drawn behind the island in the island's own window. [islandLeft]/[islandRight] are
 * where the island's edges are right now (they change every frame while it morphs).
 */
@Composable
fun KineticStatusBar(screenWidth: Dp, top: Dp, pillH: Dp, islandLeft: Dp, islandRight: Dp, busy: Boolean = false) {
    val st by StatusBarData.state.collectAsState()
    val settings by com.kinetic.launcher.KineticSettings.values.collectAsState()
    if (!settings.statusBar || !st.systemBarShown) return
    val dark = when (settings.statusBarTint) {
        "dark" -> true
        "light" -> false
        else -> st.lightBehind
    }
    val tint by animateColorAsState(if (dark) Color(0xFF111114) else Color.White, tween(250), label = "sbTint")
    val icons by com.kinetic.launcher.NotificationHub.statusIcons.collectAsState()
    val more by com.kinetic.launcher.NotificationHub.statusMore.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current

    // The time, updated on the minute.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            StatusBarData.refreshLock(context)
            delay(60_000 - value % 60_000 + 50)
        }
    }
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val time = remember(now, pattern) { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)) }

    // Out to the edges, like One UI (just clear of the rounded corners).
    val side = 15.dp
    val gap = 10.dp
    val leftEar = (islandLeft - gap - side).coerceAtLeast(0.dp)
    val rightEar = (screenWidth - side - islandRight - gap).coerceAtLeast(0.dp)
    var leftW by remember { mutableIntStateOf(0) }
    var rightW by remember { mutableIntStateOf(0) }
    // Items are dropped to fit (below), so a side only fades when even the time or the
    // battery alone can't fit (the island has grown right over it).
    val leftA = with(density) { ((leftEar.toPx() - leftW + 4.dp.toPx()) / 6.dp.toPx()).coerceIn(0f, 1f) }
    val rightA = with(density) { ((rightEar.toPx() - rightW + 4.dp.toPx()) / 6.dp.toPx()).coerceIn(0f, 1f) }

    // While the island is showing something, each side is centred in the space between
    // the island and the screen edge, with as many icons as fit (time and battery first).
    val centred by androidx.compose.animation.core.animateFloatAsState(if (busy) 1f else 0f, tween(260), label = "sbBusy")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val timeW = with(density) {
        measurer.measure(time, androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp))
            .size.width.toDp().value
    }
    // Always fit what there's room for (busy or not), most important first.
    val leftRoom = (leftEar - 6.dp).value
    val rightRoom = (rightEar - 6.dp).value
    val (iconCount, showMore) = StatusFit.left(leftRoom, timeW, icons.size, more)
    val netW = (st.net?.length ?: 0) * 8f + 7f
    val wifiOrNetW = when {
        st.airplane -> null
        st.wifi >= 0 -> 23f
        st.net != null -> netW
        else -> null
    }
    // Order of importance after the battery: Wi-Fi/5G, signal, silent/vibrate, Do Not Disturb.
    val wanted = listOf(
        wifiOrNetW,
        if (st.airplane) 18f else 22f,
        if (st.ringer != AudioManager.RINGER_MODE_NORMAL) 20f else null,
        if (st.dnd) 19f else null,
    )
    val keep = StatusFit.right(rightRoom, 30f, wanted)
    val showWifiOrNet = keep[0]
    val showSignal = keep[1]
    val showRinger = keep[2]
    val showDnd = keep[3]

    Box(Modifier.fillMaxWidth().padding(top = top).height(pillH)) {
        // ---- left: time (carrier on the lock screen) + notification icons ----
        Box(Modifier.offset(x = side).width(leftEar).fillMaxHeight().alpha(leftA), contentAlignment = androidx.compose.ui.BiasAlignment(-1f + centred, 0f)) {
            Row(
                Modifier.wrapContentWidth(unbounded = true).onSizeChanged { leftW = it.width },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (st.locked) {
                    if (st.carrier.isNotBlank()) {
                        Text(st.carrier, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(time, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, letterSpacing = 0.2.sp)
                    icons.take(iconCount).forEachIndexed { i, bmp ->
                        val img = remember(bmp) { bmp.asImageBitmap() }
                        Spacer(Modifier.width(if (i == 0) 8.dp else 6.dp))
                        Image(img, null, colorFilter = ColorFilter.tint(tint.copy(alpha = 0.85f)), modifier = Modifier.size(14.dp))
                    }
                    if (showMore) {
                        Spacer(Modifier.width(6.dp))
                        Canvas(Modifier.size(4.dp)) { drawCircle(tint.copy(alpha = 0.85f)) }
                    }
                }
            }
        }
        // ---- right: Do Not Disturb, sound, signal, network, battery ----
        Box(Modifier.offset(x = islandRight + gap).width(rightEar).fillMaxHeight().alpha(rightA), contentAlignment = androidx.compose.ui.BiasAlignment(1f - centred, 0f)) {
            Row(
                Modifier.wrapContentWidth(unbounded = true).onSizeChanged { rightW = it.width },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showDnd) {
                    MoonGlyph(tint, Modifier.size(12.dp))
                    Spacer(Modifier.width(7.dp))
                }
                if (showRinger) {
                    if (st.ringer == AudioManager.RINGER_MODE_VIBRATE) VibrateGlyph(tint, Modifier.size(13.dp))
                    else MuteGlyph(tint, Modifier.size(13.dp))
                    Spacer(Modifier.width(7.dp))
                }
                if (showSignal) {
                    if (st.airplane) {
                        Text("✈", color = tint, fontSize = 13.sp)
                        Spacer(Modifier.width(5.dp))
                    } else {
                        SignalBars(st.signal, tint, Modifier.size(16.dp, 11.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                }
                if (showWifiOrNet) {
                    if (st.wifi >= 0) {
                        WifiGlyph(st.wifi, tint, Modifier.size(16.dp, 12.dp))
                    } else {
                        Text(st.net.orEmpty(), color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Spacer(Modifier.width(7.dp))
                }
                BatteryPill(st.battery, tint, dark)
            }
        }
    }
}

/**
 * Plain white (or black) battery, red only at 5% or less. The number is cut out of
 * the battery (you see what's behind through it), like iOS.
 */
@Composable
private fun BatteryPill(level: Int, tint: Color, @Suppress("UNUSED_PARAMETER") dark: Boolean) {
    val low = level <= 5
    val fill = if (low) Color(0xFFFF453A) else tint
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val label = remember(level) {
        measurer.measure(
            androidx.compose.ui.text.AnnotatedString("$level"),
            style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        )
    }
    Canvas(
        Modifier.size(30.dp, 14.dp)
            // Drawn on its own layer so the number can punch a hole through it.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        val nub = 1.7.dp.toPx()
        val bodyW = size.width - nub - 1.dp.toPx()
        val r = CornerRadius(size.height * 0.34f)
        drawRoundRect(tint.copy(alpha = 0.32f), Offset.Zero, Size(bodyW, size.height), r)
        drawRoundRect(fill, Offset.Zero, Size(bodyW * (level / 100f).coerceIn(0.06f, 1f), size.height), r)
        drawRoundRect(tint.copy(alpha = 0.4f), Offset(bodyW + 1.dp.toPx(), size.height * 0.32f), Size(nub, size.height * 0.36f), CornerRadius(nub / 2))
        val tw = label.size.width.toFloat()
        val th = label.size.height.toFloat()
        drawText(
            label,
            color = Color.Black,
            topLeft = Offset((bodyW - tw) / 2f, (size.height - th) / 2f),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
        )
    }
}

@Composable
private fun SignalBars(level: Int, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.08f
        val bw = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.4f + 0.2f * i)
            val on = level > i
            drawRoundRect(
                if (on) tint else tint.copy(alpha = 0.3f),
                Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(bw * 0.3f),
            )
        }
    }
}

@Composable
private fun WifiGlyph(level: Int, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2
        val bottom = size.height
        val stroke = size.height * 0.16f
        for (i in 0 until 3) {
            val r = size.height * (0.38f + 0.3f * i)
            val on = level >= i + 2 || (i == 0 && level >= 1)
            drawArc(
                if (on) tint else tint.copy(alpha = 0.3f), 225f, 90f, false,
                Offset(cx - r, bottom - r), Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(if (level >= 0) tint else tint.copy(alpha = 0.3f), stroke * 0.8f, Offset(cx, bottom - stroke * 0.6f))
    }
}

@Composable
private fun MoonGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val p = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(0f, 0f), Size(r * 2, r * 2)))
        }
        val cut = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(r * 0.75f, -r * 0.25f), Size(r * 2, r * 2)))
        }
        val moon = Path().apply { op(p, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
        drawPath(moon, tint)
    }
}

@Composable
private fun VibrateGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = w * 0.1f
        drawRoundRect(tint, Offset(w * 0.32f, h * 0.12f), Size(w * 0.36f, h * 0.76f), CornerRadius(w * 0.08f), style = Stroke(s))
        drawLine(tint, Offset(w * 0.15f, h * 0.3f), Offset(w * 0.15f, h * 0.7f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.85f, h * 0.3f), Offset(w * 0.85f, h * 0.7f), s, StrokeCap.Round)
    }
}

@Composable
private fun MuteGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f)
            lineTo(w * 0.3f, h * 0.36f)
            lineTo(w * 0.55f, h * 0.12f)
            lineTo(w * 0.55f, h * 0.88f)
            lineTo(w * 0.3f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.64f)
            close()
        }
        drawPath(body, tint)
        drawLine(tint, Offset(w * 0.05f, h * 0.08f), Offset(w * 0.95f, h * 0.92f), w * 0.1f, StrokeCap.Round)
    }
}

/** Which status bar items fit (all sizes in dp). Kept separate so it can be tested. */
internal object StatusFit {
    /** Notification icons after the time: how many fit, and whether the "more" dot fits too. */
    fun left(room: Float, timeW: Float, available: Int, more: Boolean): Pair<Int, Boolean> {
        val maxIcons = available.coerceAtMost(3)
        var n = 0
        var used = timeW
        while (n < maxIcons && used + (if (n == 0) 8f else 6f) + 14f <= room) {
            used += (if (n == 0) 8f else 6f) + 14f
            n++
        }
        // The dot means "there are more than shown" (only if at least one icon shows).
        val hidden = more || available > n
        val dot = n > 0 && hidden && used + 10f <= room
        return n to dot
    }

    /** [wanted] in order of importance (null = not needed); the battery always stays. */
    fun right(room: Float, battery: Float, wanted: List<Float?>): List<Boolean> {
        var used = battery
        return wanted.map { w ->
            if (w != null && used + w <= room) {
                used += w
                true
            } else {
                false
            }
        }
    }
}

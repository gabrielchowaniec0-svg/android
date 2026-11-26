package com.kinetic.launcher

import android.app.WallpaperManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.kinetic.core.GridConfig
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.apps.AppRepository
import com.kinetic.core.home.HomeLayoutStore
import com.kinetic.core.system.SystemGestures
import com.kinetic.core.widgets.WidgetHostManager
import com.kinetic.wallpaper.WallpaperSignals
import com.kinetic.launcher.ui.LocalIconWave
import com.kinetic.launcher.ui.IconWave
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import com.kinetic.launcher.ui.HomeDeps
import com.kinetic.launcher.ui.KineticHome
import com.kinetic.launcher.ui.UnlockPhase
import com.kinetic.security.StealthCustomizationManager
import com.kinetic.wallpaper.FluidWallpaperService

/**
 * The launcher (MAIN/LAUNCHER and HOME). Owns the long-lived pieces — installed
 * apps, the saved layout, the widget host — and hands them to the Compose UI.
 *
 * Lock screen: the phone's own lock screen is used. When you unlock straight to
 * the home screen, the icons glide into place along the wallpaper's flow lines.
 */
class MainActivity : FragmentActivity() {

    private lateinit var deps: HomeDeps

    private val homePressSignal = mutableIntStateOf(0)
    private val unlockPhase = mutableStateOf(UnlockPhase.NONE)

    private val handler = Handler(Looper.getMainLooper())
    private var isResumedNow = false
    private val resumedState = mutableStateOf(false)
    private var lastScreenOffAt = 0L
    private var lastUserPresentAt = 0L

    /** x/y < 0 means "wherever there's room". */
    private data class PendingWidget(val id: Int, val page: Int, val spanX: Int, val spanY: Int, val x: Int = -1, val y: Int = -1)
    private var pendingWidget: PendingWidget? = null
    /** When set, the widget being added goes into this widget's smart stack instead. */
    private var pendingStackTarget: String? = null

    /** Widgets still to add for "my phone" layout, one at a time (each may show a system prompt). */
    private val widgetQueue = ArrayDeque<Pair<AppWidgetProviderInfo, MyPhoneLayout.WidgetSlot>>()

    private val prefs by lazy { getSharedPreferences("kinetic_activity", MODE_PRIVATE) }
    /** Bumped when something asks Kinetic to open search (e.g. a shake). */
    private val openSearchSignal = androidx.compose.runtime.mutableIntStateOf(0)
    private val myLayoutPending = mutableStateOf(false)

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pendingWidget ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) configureOrPlace(p) else cancelWidget(p)
    }

    /** If the screen came on but no unlock followed quickly, just show the icons. */
    private val disarmUnlock = Runnable {
        if (unlockPhase.value == UnlockPhase.ARMED) unlockPhase.value = UnlockPhase.NONE
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastScreenOffAt = SystemClock.elapsedRealtime()
                    deps.customization.lockToPrimary()
                    unlockPhase.value = UnlockPhase.ARMED
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    // A green wave rises from the bottom when you plug in.
                    WallpaperSignals.ripple(context, WallpaperSignals.Kind.Charging, y = -2f, color = 0xFF30D158.toInt(), strength = 1.2f)
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Unlocking sends a ripple out from the middle of the screen (icons ride it too).
                    WallpaperSignals.ripple(context, WallpaperSignals.Kind.Unlock, y = -3f, color = 0xFFB8C8F0.toInt(), strength = 1.1f)
                    lastUserPresentAt = SystemClock.elapsedRealtime()
                    if (isResumedNow && unlockPhase.value == UnlockPhase.ARMED) {
                        handler.removeCallbacks(disarmUnlock)
                        unlockPhase.value = UnlockPhase.PLAY
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KineticSettings.init(this)
        KineticApp.init(this)
        LivingWallpaper.start(this)
        FocusModes.init(this)
        // Alarms and place alerts are system calls: do them once, off the main thread,
        // so the home screen draws sooner.
        if (!startupDone) {
            startupDone = true
            val app = applicationContext
            Thread {
                runCatching { FocusScheduler.scheduleNext(app) }
                runCatching { FocusTriggers.registerPlaces(app) }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
        }

        // 4 columns like One UI Home; the 7th row doubles as the dock.
        // Same geometry as One UI on the S25 Ultra: 14dp side margins, 4 equal columns, no gaps.
        val config = GridConfig(columns = 4, rows = 7, gutterDp = 0, marginDp = 14, halfSnap = true)
        deps = HomeDeps(
            apps = AppRepository(this),
            layout = HomeLayoutStore(this, config.columns, config.rows),
            widgets = WidgetHostManager(this),
            customization = StealthCustomizationManager(this),
            config = config,
        )
        savedInstanceState?.getIntArray(STATE_PENDING_WIDGET)?.let { a ->
            if (a.size == 6) pendingWidget = PendingWidget(a[0], a[1], a[2], a[3], a[4], a[5])
        }
        myLayoutPending.value = prefs.getInt(KEY_MY_LAYOUT_VERSION, 0) < MyPhoneLayout.VERSION
        deps.apps.start()

        // Both are protected system broadcasts, so they still arrive with NOT_EXPORTED.
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
            },
            RECEIVER_NOT_EXPORTED,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val settings by KineticSettings.values.collectAsState()
                val wave = remember { IconWave() }
                // Ripples (unlock, launches, charging, notifications) make the icons hop.
                DisposableEffect(Unit) {
                    val listener = WallpaperSignals.Listener { r ->
                        handler.post {
                            if (KineticSettings.current.wpIconWave) {
                                val root = window.decorView
                                wave.start(r, root.width.toFloat(), root.height.toFloat())
                            }
                        }
                    }
                    WallpaperSignals.addListener(listener)
                    onDispose { WallpaperSignals.removeListener(listener) }
                }
                LaunchedEffect(wave.startedAt) {
                    if (wave.startedAt == 0L) return@LaunchedEffect
                    while (SystemClock.uptimeMillis() - wave.startedAt < 2600) {
                        withFrameMillis { wave.now = SystemClock.uptimeMillis() }
                    }
                }
                // A new icon size rebuilds the home screen so every icon picks it up.
                CompositionLocalProvider(LocalIconWave provides wave) {
                    key(settings.iconSizeDp, settings.iconStyle) {
                        KineticHome(
                            deps = deps,
                            activity = this,
                            homePressSignal = homePressSignal.intValue,
                            openSearchSignal = openSearchSignal.intValue,
                            unlockPhase = unlockPhase.value,
                            onUnlockFinished = { unlockPhase.value = UnlockPhase.NONE },
                            onRequestWidget = ::requestWidget,
                            onOpenWallpaperPicker = ::openWallpaperPicker,
                            onExpandNotifications = ::expandNotifications,
                            onWallpaperFocus = ::setWallpaperFocus,
                            autoApplyMyLayout = myLayoutPending.value,
                            onApplyMyLayout = ::applyMyPhoneLayout,
                            isResumed = resumedState.value,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Widgets keep listening while you're in other apps (see onDestroy), so coming
        // home doesn't have to re-draw every widget at once.
        deps.widgets.startListening()
    }

    override fun onResume() {
        super.onResume()
        homeVisible = true
        isResumedNow = true
        resumedState.value = true
        // Tell the Dynamic Island we're in front (so music shows again straight away).
        com.kinetic.launcher.island.ForegroundHint.resumed(packageName)
        com.kinetic.launcher.island.IslandWatch.check(this)
        if (unlockPhase.value == UnlockPhase.ARMED) {
            val now = SystemClock.elapsedRealtime()
            val unlockedSinceScreenOff = lastUserPresentAt > lastScreenOffAt
            when {
                // Unlocked straight to home: play the glide.
                unlockedSinceScreenOff && now - lastUserPresentAt < UNLOCK_WINDOW_MS -> unlockPhase.value = UnlockPhase.PLAY
                // Unlocked into another app earlier and only now came home: no glide.
                unlockedSinceScreenOff -> unlockPhase.value = UnlockPhase.NONE
                // Unlock broadcast hasn't arrived yet; give it a moment.
                else -> handler.postDelayed(disarmUnlock, UNLOCK_WAIT_MS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        homeVisible = false
        isResumedNow = false
        resumedState.value = false
        handler.removeCallbacks(disarmUnlock)
    }

    override fun onStop() {
        super.onStop()
        deps.customization.lockToPrimary()
    }

    override fun onDestroy() {
        deps.widgets.stopListening()
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        deps.apps.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false)) openSearchSignal.intValue++
        // Home button pressed while we're already the home screen.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePressSignal.intValue++
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingWidget?.let { outState.putIntArray(STATE_PENDING_WIDGET, intArrayOf(it.id, it.page, it.spanX, it.spanY, it.x, it.y)) }
    }

    // ---- "my phone" layout -----------------------------------------------------------

    private fun applyMyPhoneLayout(apps: List<AppEntry>) {
        val result = MyPhoneLayout.build(apps, deps.config.columns, deps.config.rows)
        deps.layout.replaceLayout(result.layout).forEach { deps.widgets.deleteId(it) }
        prefs.edit().putInt(KEY_MY_LAYOUT_VERSION, MyPhoneLayout.VERSION).apply()
        myLayoutPending.value = false

        val providers = deps.widgets.providers()
        val missingWidgets = mutableListOf<String>()
        widgetQueue.clear()
        result.widgets.forEach { slot ->
            val info = MyPhoneLayout.pickProvider(slot, providers) { deps.widgets.label(it) }
            if (info != null) widgetQueue.addLast(info to slot) else missingWidgets += slot.description
        }

        val notes = (result.missing + missingWidgets.map { "$it widget" }).take(6)
        val message = if (notes.isEmpty()) "Your phone's layout is set up" else "Layout set up. Not found: ${notes.joinToString()}"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        if (pendingWidget == null) processWidgetQueue()
    }

    private fun processWidgetQueue() {
        val (info, slot) = widgetQueue.removeFirstOrNull() ?: return
        requestWidgetAt(info, slot.page, slot.x, slot.y, slot.spanX, slot.spanY)
    }

    // ---- widgets: allocate id -> bind permission -> optional configure -> place ----

    private fun requestWidget(info: AppWidgetProviderInfo, page: Int, stackOnto: String?) {
        pendingStackTarget = stackOnto
        requestWidgetAt(info, page, -1, -1, 0, 0)
    }

    /** Adds a widget at a cell ([x]/[y] < 0 = anywhere) with a given size (0 = the widget's default). */
    private fun requestWidgetAt(info: AppWidgetProviderInfo, page: Int, x: Int, y: Int, spanX: Int, spanY: Int) {
        val id = deps.widgets.allocateId()
        val (defaultX, defaultY) = deps.widgets.defaultSpan(info, deps.config.columns, deps.config.rows)
        val p = PendingWidget(
            id = id,
            page = page,
            spanX = if (spanX > 0) spanX else defaultX,
            spanY = if (spanY > 0) spanY else defaultY,
            x = x,
            y = y,
        )
        pendingWidget = p
        if (deps.widgets.bindIfAllowed(id, info)) {
            configureOrPlace(p)
        } else {
            try {
                bindWidget.launch(deps.widgets.bindPermissionIntent(id, info))
            } catch (e: ActivityNotFoundException) {
                cancelWidget(p)
            }
        }
    }

    private fun configureOrPlace(p: PendingWidget) {
        val info = deps.widgets.infoFor(p.id)
        if (info == null) {
            cancelWidget(p)
            return
        }
        if (deps.widgets.needsConfiguration(info)) {
            if (!deps.widgets.startConfiguration(this, p.id, REQUEST_CONFIGURE_WIDGET)) cancelWidget(p)
        } else {
            placeWidget(p)
        }
    }

    private fun placeWidget(p: PendingWidget) {
        pendingWidget = null
        val stackOnto = pendingStackTarget
        pendingStackTarget = null
        if (stackOnto != null && deps.layout.addToStack(p.page, stackOnto, p.id)) {
            Toast.makeText(this, "Added to the stack — swipe up or down on it", Toast.LENGTH_SHORT).show()
        } else if (p.x >= 0 && p.y >= 0) deps.layout.addWidgetAt(p.page, p.id, p.x, p.y, p.spanX, p.spanY)
        else deps.layout.addWidget(p.page, p.id, p.spanX, p.spanY)
        processWidgetQueue()
    }

    private fun cancelWidget(p: PendingWidget) {
        pendingWidget = null
        pendingStackTarget = null
        deps.widgets.deleteId(p.id)
        Toast.makeText(this, "Widget wasn't added", Toast.LENGTH_SHORT).show()
        processWidgetQueue()
    }

    /** Widget configuration screens still report back through onActivityResult. */
    @Deprecated("Required for AppWidgetHost.startAppWidgetConfigureActivityForResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        val p = pendingWidget ?: return
        if (resultCode == RESULT_OK) placeWidget(p) else cancelWidget(p)
    }

    // ---- system ----------------------------------------------------------------

    private fun expandNotifications() {
        if (!SystemGestures.expandNotifications(this)) {
            Toast.makeText(this, "Couldn't open notifications on this device", Toast.LENGTH_SHORT).show()
        }
    }

    /** Tells Kinetic's live wallpaper to soften (drawer open) or return to normal. */
    private fun setWallpaperFocus(focused: Boolean) {
        val token = window?.decorView?.windowToken ?: return
        runCatching {
            WallpaperManager.getInstance(this).sendWallpaperCommand(
                token,
                if (focused) FluidWallpaperService.ACTION_FOCUS_ON else FluidWallpaperService.ACTION_FOCUS_OFF,
                0, 0, 0, null,
            )
        }
    }

    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, FluidWallpaperService::class.java),
        )
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    companion object {
        /** Once per process: the one-off startup jobs have been started. */
        @Volatile
        private var startupDone = false

        /** True while the home screen is in front (StandBy only opens from home or the lock screen). */
        @Volatile
        var homeVisible = false

        /** Opens Kinetic straight into search (shake action, Commands). */
        const val EXTRA_OPEN_SEARCH = "kinetic_open_search"
        private const val REQUEST_CONFIGURE_WIDGET = 1001
        private const val STATE_PENDING_WIDGET = "pending_widget"
        private const val KEY_MY_LAYOUT_VERSION = "my_phone_layout_version"
        private const val UNLOCK_WINDOW_MS = 2000L
        private const val UNLOCK_WAIT_MS = 800L
    }
}

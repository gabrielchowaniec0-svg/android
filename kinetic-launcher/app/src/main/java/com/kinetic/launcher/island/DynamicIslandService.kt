package com.kinetic.launcher.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.NotificationHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Kinetic's Dynamic Island.
 *
 * It's an accessibility service only because that is the one kind of app window
 * Android lets sit *above* the status bar and notification shade (normal "draw over
 * apps" windows sit underneath them, so the island couldn't be tapped there). It
 * doesn't read the screen or anything you type — it only draws the island.
 */
/** Kinetic's own screens report here when they come to the front (same process). */
object ForegroundHint {
    val packageName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun resumed(pkg: String) {
        // Re-emit even if unchanged, so a returning home screen always counts.
        packageName.value = null
        packageName.value = pkg
    }
}

class DynamicIslandService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var model: IslandModel
    private var windowManager: WindowManager? = null
    /**
     * Two windows: a full-width one that only *draws* the island (it never moves
     * sideways or changes width, so nothing can jerk), and a small invisible one on
     * top that catches touches exactly where the island is and hands them over.
     */
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var touchView: View? = null
    /** Kinetic's home bar at the bottom (its own window, never touchable). */
    private var homeBarView: ComposeView? = null
    private var touchParams: WindowManager.LayoutParams? = null

    /** Camera hole: centre x and centre y in screen pixels. */
    private var holeX = 0f
    private var holeY = 0f
    private var holeTop = 0f

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        shake = ShakeDetector(this).also { it.start() }
        standBy = com.kinetic.launcher.standby.StandByWatcher(this).also { it.start() }
        secureShot = SecureScreenshot(
            this, scope,
            onSaved = {
                view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                if (::model.isInitialized) model.showVaultSaved()
            },
            onFailed = { view?.performHapticFeedback(HapticFeedbackConstants.REJECT) },
        )
        KineticSettings.init(this)
        com.kinetic.launcher.FocusModes.init(this)
        model = IslandModel(this, scope)
        model.start()
        // When Notification access is switched on later, start showing music too.
        scope.launch { NotificationHub.connected.collect { if (it) model.listenForSessions() } }
        // The home screen also says when it comes to the front (more reliable than events).
        scope.launch { ForegroundHint.packageName.collect { if (it != null) model.setForeground(it) } }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        addIsland()
    }

    /**
     * Only used to know which app is on screen (so the island hides a song while
     * you're in the app playing it, like iOS). Nothing on the screen is read.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !::model.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        StatusBarData.onWindowChanged()
        // Only full-screen windows count as "this app is open": a YouTube picture-in-picture
        // window, pop-ups and dialogs don't (they used to hide the playing song's island).
        if (!event.isFullScreen) return
        model.setForeground(pkg)
        // Focus app triggers (e.g. Gaming while a game is open).
        com.kinetic.launcher.FocusTriggers.onForegroundApp(this, pkg)
    }

    override fun onInterrupt() = Unit

    /** Volume keys: watches for the secure screenshot gesture (Up, Up, Down, Down). */
    override fun onKeyEvent(event: KeyEvent): Boolean =
        secureShot?.onKey(event)?.takeIf { it } ?: VolumeHud.onKey(this, event)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The camera moves to the side in landscape; iOS hides the island there too.
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        view?.visibility = if (landscape) View.GONE else View.VISIBLE
        touchView?.visibility = if (landscape) View.GONE else View.VISIBLE
        homeBarView?.visibility = if (landscape) View.GONE else View.VISIBLE
        if (!landscape) measureHole()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeIsland()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        shake?.stop()
        standBy?.stop()
        removeIsland()
        if (::model.isInitialized) model.stop()
        scope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun measureHole() {
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val cutout = metrics.windowInsets.displayCutout
        val rect = cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        val density = resources.displayMetrics.density
        if (rect != null) {
            holeX = rect.exactCenterX()
            holeY = rect.exactCenterY()
            holeTop = rect.top.toFloat()
        } else {
            holeX = metrics.bounds.width() / 2f
            holeY = 18 * density
            holeTop = 10 * density
        }
    }

    private fun addIsland() {
        if (view != null) return
        val wm = getSystemService(WindowManager::class.java)
        windowManager = wm
        measureHole()
        val density = resources.displayMetrics.density
        val screenWidth = (wm.currentWindowMetrics.bounds.width() / density).dp

        val screenWidthPx = wm.currentWindowMetrics.bounds.width()
        // Drawing window: full width, never touchable (touches go to the touch window).
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Kinetic Dynamic Island"
        }
        params = p

        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DynamicIslandService)
            setViewTreeSavedStateRegistryOwner(this@DynamicIslandService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val settings by KineticSettings.values.collectAsState()
                // The top edge sits [islandGapDp] below where a 35dp pill centred on the
                // camera would start; the pill is then exactly tall enough to stay
                // centred on the camera, so it gets slimmer as the gap grows.
                val holeCentre = holeY / density
                val gap = settings.islandGapDp.coerceIn(0, 7)
                val top = (holeCentre - 35f / 2f + gap).coerceAtLeast(2f)
                val pillH = (2f * (holeCentre - top)).coerceAtLeast(18f)
                DynamicIsland(
                    model = model,
                    screenWidth = screenWidth,
                    top = top.dp,
                    pillH = pillH.dp,
                    // In case the camera isn't exactly in the middle of the screen.
                    centerOffset = ((holeX - screenWidthPx / 2f) / density).dp,
                    onWindowSize = { _, h -> resizeWindow(h) },
                    onTouchArea = { r -> placeTouchArea(r) },
                    onHaptic = { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                    onFocusable = { on -> setFocusable(on) },
                )
            }
        }
        view = v
        // Full-screen apps (videos, games) hide the status bar; Kinetic's hides with it.
        v.setOnApplyWindowInsetsListener { _, insets ->
            StatusBarData.setSystemBarShown(insets.isVisible(android.view.WindowInsets.Type.statusBars()))
            insets
        }
        try {
            wm.addView(v, p)
        } catch (_: Exception) {
            view = null
            return
        }
        StatusBarData.start(this, scope)

        // Touch window: an invisible box kept over the island. Its touches are passed
        // to the drawing window in screen coordinates, so moving it never matters.
        val tp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Kinetic Dynamic Island touch"
        }
        touchParams = tp
        // Pulls are tracked here, for the whole gesture: once a finger that started on
        // the island moves up or down, it's a pull wherever it goes (fast or slow), and
        // the island's own tap/hold handling is told to stand down.
        var downX = 0f
        var downY = 0f
        var pulling = false
        var flipped = false
        var lastMoveDy = 0f
        var tracker: android.view.VelocityTracker? = null
        // Small, so a pull is recognised before the system's own swipe can take it.
        val slop = 6 * density
        val t = View(this).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // A tap anywhere outside the island folds it back up.
                    model.collapse()
                    if (!model.replying.value) model.dismissPeek()
                    return@setOnTouchListener true
                }
                val screen = MotionEvent.obtain(e).apply { offsetLocation(e.rawX - e.x, e.rawY - e.y) }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = e.rawX
                    downY = e.rawY
                    lastMoveDy = 0f
                    pulling = false
                    flipped = false
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                }
                tracker?.addMovement(screen)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (e.actionMasked == MotionEvent.ACTION_MOVE) lastMoveDy = dy
                // Sideways swipe on a small island: flip to the next live thing (island stacks).
                if (!pulling && !flipped && e.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.abs(dx) > 36 * density &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f && !model.expanded.value && model.peek.value == null
                ) {
                    flipped = true
                    model.cycleStack(if (dx < 0) 1 else -1)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val cancel = MotionEvent.obtain(screen).apply { action = MotionEvent.ACTION_CANCEL }
                    v.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (flipped) {
                    screen.recycle()
                    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        tracker?.recycle()
                        tracker = null
                    }
                    return@setOnTouchListener true
                }
                // While the flashlight is on, up/down drags set its brightness instead of pulling the island.
                val torchOn = TorchLive.state.value != null
                if (!pulling && !torchOn && e.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) && !model.replying.value) {
                    pulling = true
                    // Not a tap or a hold any more.
                    val cancel = MotionEvent.obtain(screen).apply { action = MotionEvent.ACTION_CANCEL }
                    v.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // The system took the touch (its status-bar swipe). Go with where the
                    // finger was heading instead of throwing the pull away.
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    val lastDy = if (dy == 0f) lastMoveDy else dy
                    if (!torchOn && (pulling || kotlin.math.abs(lastDy) > 4 * density || kotlin.math.abs(vy) > 400 * density)) {
                        model.pullEnd(lastDy, vy, cancelled = true)
                    }
                    if (!pulling) v.dispatchTouchEvent(screen)
                } else if (pulling) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_MOVE -> model.pullMove(dy)
                        MotionEvent.ACTION_UP -> {
                            tracker?.computeCurrentVelocity(1000)
                            model.pullEnd(dy, tracker?.yVelocity ?: 0f)
                        }
                    }
                } else {
                    v.dispatchTouchEvent(screen)
                }
                screen.recycle()
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    tracker?.recycle()
                    tracker = null
                }
                true
            }
        }
        touchView = t
        runCatching { wm.addView(t, tp) }.onFailure { touchView = null }
        addHomeBar(wm)
    }

    /** Moves the touch window over the island ([r] in screen px), or switches it off (null). */
    private fun placeTouchArea(r: android.graphics.Rect?) {
        val tp = touchParams ?: return
        val t = touchView ?: return
        if (r == null || r.isEmpty) {
            tp.flags = tp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            tp.width = 1
            tp.height = 1
        } else {
            tp.flags = tp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            tp.x = r.left
            tp.y = r.top
            tp.width = r.width()
            tp.height = r.height()
        }
        runCatching { windowManager?.updateViewLayout(t, tp) }
    }

    /** Only the height changes (anchored at the top), so the island never shifts. */
    private fun resizeWindow(height: Dp) {
        val p = params ?: return
        val v = view ?: return
        val h = (height.value * resources.displayMetrics.density).roundToInt()
        if (h == p.height) return
        p.height = h
        runCatching { windowManager?.updateViewLayout(v, p) }
    }

    /** Lets the keyboard in while you type a reply, then gives focus back to the app. */
    private fun setFocusable(on: Boolean) {
        val p = params ?: return
        val v = view ?: return
        // While typing, the drawing window takes touches and focus itself (the reply box).
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        runCatching { windowManager?.updateViewLayout(v, p) }
    }

    private fun addHomeBar(wm: WindowManager) {
        if (homeBarView != null) return
        val hp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (28 * resources.displayMetrics.density).roundToInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Kinetic home bar"
        }
        val hv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DynamicIslandService)
            setViewTreeSavedStateRegistryOwner(this@DynamicIslandService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { KineticHomeBar(model) }
        }
        homeBarView = hv
        runCatching { wm.addView(hv, hp) }.onFailure { homeBarView = null }
    }

    private fun removeIsland() {
        homeBarView?.let { h -> runCatching { windowManager?.removeView(h) } }
        homeBarView = null
        StatusBarData.stop()
        touchView?.let { t -> runCatching { windowManager?.removeView(t) } }
        touchView = null
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
    }

    private var shake: ShakeDetector? = null
    private var secureShot: SecureScreenshot? = null
    private var standBy: com.kinetic.launcher.standby.StandByWatcher? = null

    companion object {
        @Volatile
        private var instance: DynamicIslandService? = null

        /** True while the island is on screen (so other parts of Kinetic can talk to it). */
        val isRunning: Boolean get() = instance?.view != null

        /** Locks the phone (double-tap on the wallpaper). False if the island service is off. */
        fun lockScreen(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /** Runs a system action (screenshot, notifications…). False if the island service is off. */
        fun globalAction(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        /** A normal screenshot after [delayMs] (so Kinetic's own ink/search has faded away). */
        fun screenshotLater(delayMs: Long): Boolean {
            val s = instance ?: return false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ s.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }, delayMs)
            return true
        }

        /** A screenshot sealed straight into the Vault after [delayMs]. False if it can't be done. */
        fun secureScreenshotLater(delayMs: Long): Boolean {
            val s = instance ?: return false
            val shot = s.secureShot ?: return false
            if (!com.kinetic.launcher.vault.VaultCrypto.canDrop(s)) return false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ shot.capture() }, delayMs)
            return true
        }

        /** True once the user has switched the island on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java)
            val me = ComponentName(context, DynamicIslandService::class.java)
            return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.let { s -> ComponentName(s.packageName, s.name) } == me }
        }

        /** Opens the page where the user switches the island on. */
        fun openSettings(context: Context) {
            val me = ComponentName(context, DynamicIslandService::class.java).flattenToString()
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, me)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(detail)
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

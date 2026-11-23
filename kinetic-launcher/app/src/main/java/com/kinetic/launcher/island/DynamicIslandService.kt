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
        model.setForeground(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The camera moves to the side in landscape; iOS hides the island there too.
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        view?.visibility = if (landscape) View.GONE else View.VISIBLE
        touchView?.visibility = if (landscape) View.GONE else View.VISIBLE
        if (!landscape) measureHole()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeIsland()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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
        try {
            wm.addView(v, p)
        } catch (_: Exception) {
            view = null
            return
        }

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
        val t = View(this).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // A tap anywhere outside the island folds it back up.
                    model.collapse()
                    if (!model.replying.value) model.dismissPeek()
                } else {
                    val ev = MotionEvent.obtain(e)
                    ev.offsetLocation(e.rawX - e.x, e.rawY - e.y)
                    v.dispatchTouchEvent(ev)
                    ev.recycle()
                }
                true
            }
        }
        touchView = t
        runCatching { wm.addView(t, tp) }.onFailure { touchView = null }
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

    private fun removeIsland() {
        touchView?.let { t -> runCatching { windowManager?.removeView(t) } }
        touchView = null
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
    }

    companion object {

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

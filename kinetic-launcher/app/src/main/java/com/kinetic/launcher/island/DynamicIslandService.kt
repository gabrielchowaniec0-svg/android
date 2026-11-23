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
class DynamicIslandService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var model: IslandModel
    private var windowManager: WindowManager? = null
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Camera hole: centre x and centre y in screen pixels. */
    private var holeX = 0f
    private var holeY = 0f

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        model = IslandModel(this, scope)
        model.start()
        // When Notification access is switched on later, start showing music too.
        scope.launch { NotificationHub.connected.collect { if (it) model.listenForSessions() } }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        addIsland()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The camera moves to the side in landscape; iOS hides the island there too.
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        view?.visibility = if (landscape) View.GONE else View.VISIBLE
        if (!landscape) {
            measureHole()
            params?.let { p ->
                p.x = (holeX - p.width / 2f).roundToInt()
                runCatching { windowManager?.updateViewLayout(view, p) }
            }
        }
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
        } else {
            holeX = metrics.bounds.width() / 2f
            holeY = 18 * density
        }
    }

    private fun addIsland() {
        if (view != null) return
        val wm = getSystemService(WindowManager::class.java)
        windowManager = wm
        measureHole()
        val density = resources.displayMetrics.density
        val screenWidth = (wm.currentWindowMetrics.bounds.width() / density).dp
        // Keep the resting pill centred on the camera hole; bigger shapes grow downwards.
        val top = (holeY / density - 35 / 2f).coerceAtLeast(2f).dp

        val p = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Kinetic Dynamic Island"
        }
        params = p

        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DynamicIslandService)
            setViewTreeSavedStateRegistryOwner(this@DynamicIslandService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            // A tap anywhere outside the island folds it back up.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) model.collapse()
                false
            }
            setContent {
                DynamicIsland(
                    model = model,
                    screenWidth = screenWidth,
                    top = top,
                    onWindowSize = { w, h -> resizeWindow(w, h) },
                    onHaptic = { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                    onTouchable = { on -> setTouchable(on) },
                )
            }
        }
        view = v
        try {
            wm.addView(v, p)
        } catch (_: Exception) {
            view = null
        }
    }

    private fun resizeWindow(width: Dp, height: Dp) {
        val p = params ?: return
        val v = view ?: return
        val density = resources.displayMetrics.density
        p.width = (width.value * density).roundToInt()
        p.height = (height.value * density).roundToInt()
        p.x = (holeX - p.width / 2f).roundToInt()
        p.y = 0
        runCatching { windowManager?.updateViewLayout(v, p) }
    }

    private fun setTouchable(on: Boolean) {
        val p = params ?: return
        val v = view ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager?.updateViewLayout(v, p) }
    }

    private fun removeIsland() {
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

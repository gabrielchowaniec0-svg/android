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
import com.kinetic.core.apps.AppRepository
import com.kinetic.core.home.HomeLayoutStore
import com.kinetic.core.system.SystemGestures
import com.kinetic.core.widgets.WidgetHostManager
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
    private var lastScreenOffAt = 0L
    private var lastUserPresentAt = 0L

    private data class PendingWidget(val id: Int, val page: Int, val spanX: Int, val spanY: Int)
    private var pendingWidget: PendingWidget? = null

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
                Intent.ACTION_USER_PRESENT -> {
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

        // 4 columns like One UI Home; the 7th row doubles as the dock.
        val config = GridConfig(columns = 4, rows = 7, halfSnap = true)
        deps = HomeDeps(
            apps = AppRepository(this),
            layout = HomeLayoutStore(this, config.columns, config.rows),
            widgets = WidgetHostManager(this),
            customization = StealthCustomizationManager(this),
            config = config,
        )
        savedInstanceState?.getIntArray(STATE_PENDING_WIDGET)?.let { a ->
            if (a.size == 4) pendingWidget = PendingWidget(a[0], a[1], a[2], a[3])
        }
        deps.apps.start()

        // Both are protected system broadcasts, so they still arrive with NOT_EXPORTED.
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                KineticHome(
                    deps = deps,
                    activity = this,
                    homePressSignal = homePressSignal.intValue,
                    unlockPhase = unlockPhase.value,
                    onUnlockFinished = { unlockPhase.value = UnlockPhase.NONE },
                    onRequestWidget = ::requestWidget,
                    onOpenWallpaperPicker = ::openWallpaperPicker,
                    onExpandNotifications = ::expandNotifications,
                    onWallpaperFocus = ::setWallpaperFocus,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        deps.widgets.startListening()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
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
        isResumedNow = false
        handler.removeCallbacks(disarmUnlock)
    }

    override fun onStop() {
        super.onStop()
        deps.widgets.stopListening()
        deps.customization.lockToPrimary()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        deps.apps.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while we're already the home screen.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePressSignal.intValue++
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingWidget?.let { outState.putIntArray(STATE_PENDING_WIDGET, intArrayOf(it.id, it.page, it.spanX, it.spanY)) }
    }

    // ---- widgets: allocate id -> bind permission -> optional configure -> place ----

    private fun requestWidget(info: AppWidgetProviderInfo, page: Int) {
        val id = deps.widgets.allocateId()
        val (spanX, spanY) = deps.widgets.defaultSpan(info, deps.config.columns, deps.config.rows)
        val p = PendingWidget(id, page, spanX, spanY)
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
        deps.layout.addWidget(p.page, p.id, p.spanX, p.spanY)
    }

    private fun cancelWidget(p: PendingWidget) {
        pendingWidget = null
        deps.widgets.deleteId(p.id)
        Toast.makeText(this, "Widget wasn't added", Toast.LENGTH_SHORT).show()
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

    private companion object {
        const val REQUEST_CONFIGURE_WIDGET = 1001
        const val STATE_PENDING_WIDGET = "pending_widget"
        const val UNLOCK_WINDOW_MS = 2000L
        const val UNLOCK_WAIT_MS = 800L
    }
}

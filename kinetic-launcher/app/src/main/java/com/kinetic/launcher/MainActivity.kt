package com.kinetic.launcher

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kinetic.core.GridConfig
import com.kinetic.core.GridGeometry
import com.kinetic.core.GridItem
import com.kinetic.core.LauncherGrid
import com.kinetic.lockscreen.BiometricUnlockBridge
import com.kinetic.lockscreen.CinematicUnlock
import com.kinetic.lockscreen.FlowDirection
import com.kinetic.lockscreen.UnlockIcon
import com.kinetic.lockscreen.rememberUnlockController
import com.kinetic.security.StealthCustomizationManager
import com.kinetic.wallpaper.FluidWallpaperService
import kotlinx.coroutines.launch

/**
 * App entry point (MAIN/LAUNCHER and HOME). Wires the feature modules together:
 *
 *  - :launcher-core          -> LauncherGrid home screen
 *  - :security-engine        -> label masks + private workspace filtering
 *  - :lockscreen-transition  -> BiometricPrompt + cinematic unlock into the grid
 *  - :wallpaper-engine       -> "Wallpaper" button opens the live wallpaper picker
 *
 * FragmentActivity (a ComponentActivity) so BiometricPrompt can attach and
 * Compose's setContent works.
 */
class MainActivity : FragmentActivity() {

    private lateinit var customization: StealthCustomizationManager

    /** Launcher-level lock state; re-armed whenever the screen turns off. */
    private val locked = mutableStateOf(true)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            locked.value = true
            customization.lockToPrimary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customization = StealthCustomizationManager(this)

        // SCREEN_OFF is a protected system broadcast, so it still arrives with NOT_EXPORTED.
        registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            RECEIVER_NOT_EXPORTED,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                KineticHome(
                    activity = this,
                    customization = customization,
                    locked = locked.value,
                    onUnlocked = { locked.value = false },
                    onOpenWallpaperPicker = ::openWallpaperPicker,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Collapse the private space whenever we leave the foreground.
        customization.lockToPrimary()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
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
}

@Composable
private fun KineticHome(
    activity: FragmentActivity,
    customization: StealthCustomizationManager,
    locked: Boolean,
    onUnlocked: () -> Unit,
    onOpenWallpaperPicker: () -> Unit,
) {
    val config = remember { GridConfig(columns = 5, rows = 6, halfSnap = true) }
    val scope = rememberCoroutineScope()
    val controller = rememberUnlockController()
    val bridge = remember(activity) { BiometricUnlockBridge(activity, scope, controller) }

    val workspace by customization.activeWorkspace.collectAsState()
    // Subscribing makes the grid redraw when masks or the private set change.
    val masks by customization.masks.collectAsState()
    val privateApps by customization.privateApps.collectAsState()

    // Session-only layout; persist this (e.g. DataStore) when you're ready.
    val positions = remember { mutableStateMapOf<String, Pair<Float, Float>>() }

    val packages = rememberExamplePackages()
    val visible = remember(packages, workspace, privateApps) {
        customization.visiblePackages(packages, workspace)
    }
    val cells = visible.mapIndexed { i, pkg ->
        pkg to (positions[pkg] ?: ((i % config.columns).toFloat() to (i / config.columns).toFloat()))
    }
    // Reads the collected masks state, so a label change recomposes the grid.
    fun labelFor(pkg: String): String =
        masks[pkg]?.overrideLabel ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    // Snap the unlock timeline back to 0 each time we re-lock.
    LaunchedEffect(locked) { if (locked) controller.reset() }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (locked) {
                val density = LocalDensity.current
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val geometry = GridGeometry.of(config, w, h, with(density) { config.gutterDp.dp.toPx() })
                val iconW = with(density) { geometry.spanWidthPx(1).toDp() }
                val iconH = with(density) { geometry.spanHeightPx(1).toDp() }
                val p = controller.master.value

                CinematicUnlock(
                    controller = controller,
                    direction = FlowDirection.TOP_BOTTOM,
                    canvasSize = Offset(w, h),
                    // Each icon glides to the exact pixel spot the grid will use.
                    icons = cells.map { (pkg, cell) ->
                        UnlockIcon(id = pkg, home = geometry.cellOrigin(cell.first, cell.second)) {
                            Box(Modifier.size(iconW, iconH)) { AppIconCell(label = labelFor(pkg)) }
                        }
                    },
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (controller.master.isRunning) return@clickable
                        if (bridge.canAuthenticate()) {
                            bridge.authenticate { tag ->
                                customization.workspaceForCredential(tag)
                                onUnlocked()
                            }
                        } else {
                            // No biometric or screen lock enrolled: play the transition anyway.
                            scope.launch {
                                controller.runUnlock()
                                onUnlocked()
                            }
                        }
                    },
                    background = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f * (1f - p)))
                        )
                    },
                )

                Text(
                    text = "Tap to unlock",
                    color = Color(0xFFCAD4E8),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .graphicsLayer { alpha = (1f - p * 4f).coerceAtLeast(0f) },
                )
            } else {
                LauncherGrid(
                    config = config,
                    items = cells.map { (pkg, cell) ->
                        GridItem(id = pkg, cellX = cell.first, cellY = cell.second) {
                            AppIconCell(label = labelFor(pkg))
                        }
                    },
                    onMove = { id, cx, cy -> positions[id] = cx to cy },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenWallpaperPicker) {
                Text("Wallpaper", color = Color(0xFFCAD4E8))
            }
        }
    }
}

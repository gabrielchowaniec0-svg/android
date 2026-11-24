@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

import android.app.ActivityOptions
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.util.SizeF
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.kinetic.core.GridConfig
import com.kinetic.core.GridGeometry
import com.kinetic.core.GridItem
import com.kinetic.core.LauncherGrid
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.apps.AppRepository
import com.kinetic.core.home.DrawerFolder
import com.kinetic.core.home.HomeItem
import com.kinetic.core.home.HomeLayoutStore
import com.kinetic.core.home.HomeLayout
import com.kinetic.core.widgets.WidgetHostManager
import com.kinetic.launcher.FocusActivity
import com.kinetic.launcher.FocusModes
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.WallpaperStudioActivity
import com.kinetic.launcher.NotificationHub
import com.kinetic.launcher.SettingsActivity
import com.kinetic.launcher.Updater
import com.kinetic.wallpaper.WallpaperSignals
import com.kinetic.launcher.island.DynamicIslandService
import com.kinetic.launcher.defaultHomeKeys
import com.kinetic.lockscreen.CinematicUnlock
import com.kinetic.lockscreen.FlowDirection
import com.kinetic.lockscreen.UnlockIcon
import com.kinetic.lockscreen.UnlockTransitionController
import com.kinetic.lockscreen.UnlockWidget
import com.kinetic.lockscreen.rememberUnlockController
import com.kinetic.security.StealthCustomizationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/** How long you hold empty wallpaper before the home screen unlocks for editing. */
const val EDIT_HOLD_MS = 800L

/** NONE: normal. ARMED: screen went off, icons hidden. PLAY: device unlocked, icons glide in. */
enum class UnlockPhase { NONE, ARMED, PLAY }

class HomeDeps(
    val apps: AppRepository,
    val layout: HomeLayoutStore,
    val widgets: WidgetHostManager,
    val customization: StealthCustomizationManager,
    val config: GridConfig,
)

/** Where a long-pressed app lives, which decides the actions in its quick menu. */
private sealed interface QuickSource {
    data class Home(val page: Int, val itemId: String) : QuickSource
    data class HomeFolder(val page: Int, val folderId: String) : QuickSource
    data class Drawer(val folder: DrawerFolder?) : QuickSource
}

private data class QuickMenuState(val entry: AppEntry, val anchor: Rect?, val source: QuickSource)

private sealed interface FolderRef {
    data class Home(val page: Int, val folderId: String) : FolderRef
    data class Drawer(val folderId: String) : FolderRef
}

@Composable
fun KineticHome(
    deps: HomeDeps,
    activity: FragmentActivity,
    homePressSignal: Int,
    openSearchSignal: Int = 0,
    unlockPhase: UnlockPhase,
    onUnlockFinished: () -> Unit,
    /** (widget, page, stack onto this widget item id or null). */
    onRequestWidget: (AppWidgetProviderInfo, Int, String?) -> Unit,
    onOpenWallpaperPicker: () -> Unit,
    onExpandNotifications: () -> Unit,
    onWallpaperFocus: (Boolean) -> Unit,
    /** True until the built-in "my phone" layout has been applied once. */
    autoApplyMyLayout: Boolean,
    onApplyMyLayout: (List<AppEntry>) -> Unit,
    /** True while the launcher is on screen (drives the tilt sensor). */
    isResumed: Boolean,
) {
    val config = deps.config
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val allApps by deps.apps.apps.collectAsState()
    val layout by deps.layout.layout.collectAsState()
    val privateApps by deps.customization.privateApps.collectAsState()
    val masks by deps.customization.masks.collectAsState()
    val appsByKey = remember(allApps) { allApps.orEmpty().associateBy { it.key } }
    val chowaniecPackage = remember(allApps) { SmartSearch.chowaniecApp(allApps.orEmpty())?.packageName }

    var editMode by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerPickMode by remember { mutableStateOf(false) }
    var privateRevealed by remember { mutableStateOf(false) }
    var askingCode by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<FolderRef?>(null) }
    var widgetPickerOpen by remember { mutableStateOf(false) }
    /** Bumped by the three-finger tap to open search. */
    var searchSignal by remember { mutableIntStateOf(0) }
    /** Text to put in search when it opens (from S Pen handwriting). */
    var searchPrefill by remember { mutableStateOf("") }
    /** Set while picking a widget to add to an existing widget's smart stack. */
    var stackTarget by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<MenuSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }
    var quickMenu by remember { mutableStateOf<QuickMenuState?>(null) }
    var folderOrigin by remember { mutableStateOf<Rect?>(null) }
    var lastLaunchedKey by remember { mutableStateOf<String?>(null) }
    var popKey by remember { mutableStateOf<String?>(null) }
    val notifying by NotificationHub.packages.collectAsState()
    val playingPackage by NotificationHub.playingPackage.collectAsState()

    val pagerState = rememberPagerState(pageCount = { layout.pages.size })
    val controller = rememberUnlockController()

    // 0 = closed, 1 = open. Follows the finger while dragging, springs when released.
    val drawerProgress = remember { Animatable(0f) }
    val drawerShowing by remember { derivedStateOf { drawerProgress.value > 0f } }
    val drawerMostlyOpen by remember { derivedStateOf { drawerProgress.value > 0.15f } }
    val drawerSpring = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f) }
    val dragRangePx = with(density) { 360.dp.toPx() }

    // 3D parallax: the home screen leans slightly as you tilt the phone (paused in Battery Saver).
    val powerSave = rememberPowerSaveMode()
    val tilt = rememberDeviceTilt(active = isResumed && !drawerShowing && !editMode && !powerSave)

    /** [velocityPx] is the finger's speed (px/s, negative = up) so the motion carries straight on. */
    fun openDrawer(velocityPx: Float = 0f) {
        drawerOpen = true
        scope.launch { drawerProgress.animateTo(1f, drawerSpring, initialVelocity = -velocityPx / dragRangePx) }
    }

    fun closeDrawer(velocityPx: Float = 0f) {
        drawerOpen = false
        scope.launch { drawerProgress.animateTo(0f, drawerSpring, initialVelocity = -velocityPx / dragRangePx) }
    }

    fun dragDrawerBy(dy: Float) {
        // UNDISPATCHED: applied in this same frame, so the drawer sticks to the finger.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerProgress.snapTo((drawerProgress.value - dy / dragRangePx).coerceIn(0f, 1f))
        }
    }

    fun labelFor(e: AppEntry): String = masks[e.packageName]?.overrideLabel ?: e.label
    fun isHidden(e: AppEntry): Boolean = e.packageName in privateApps
    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    val kineticSettings by KineticSettings.values.collectAsState()
    val focus by FocusModes.active.collectAsState()
    // During a Focus, only its allowed apps get rings.
    fun hasNotification(e: AppEntry): Boolean =
        kineticSettings.notificationRings && e.packageName in notifying && (focus == null || FocusModes.allows(e.packageName))

    // Switching on a Focus can jump to its home page.
    LaunchedEffect(focus?.id) {
        val page = focus?.homePage ?: -1
        if (page >= 0 && page < pagerState.pageCount) pagerState.animateScrollToPage(page)
    }

    fun openFocus() = FocusActivity.open(activity)

    fun openWallpaperStudio() {
        activity.startActivity(android.content.Intent(activity, WallpaperStudioActivity::class.java))
    }

    fun openSettings() {
        activity.startActivity(android.content.Intent(activity, SettingsActivity::class.java))
    }

    /** Launch options that make the app zoom out of the tapped icon. */
    fun launchOrigin(): Pair<android.graphics.Rect?, android.os.Bundle?> {
        val r = TapOrigin.rect ?: return null to null
        val bounds = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        val options = runCatching {
            ActivityOptions.makeScaleUpAnimation(
                activity.window.decorView, bounds.left, bounds.top, bounds.width(), bounds.height(),
            ).toBundle()
        }.getOrNull()
        return bounds to options
    }

    fun launchApp(e: AppEntry) {
        val (bounds, options) = launchOrigin()
        if (deps.apps.launch(e, bounds, options)) {
            lastLaunchedKey = e.key
            // The wallpaper ripples out from the icon you tapped.
            if (bounds != null) {
                WallpaperSignals.ripple(
                    activity, WallpaperSignals.Kind.App, bounds.exactCenterX(), bounds.exactCenterY(),
                    color = 0xFF8FA6D8.toInt(), strength = 0.8f,
                )
            }
        }
    }

    fun openHomeFolder(pageIndex: Int, folderId: String) {
        folderOrigin = TapOrigin.rect
        openFolder = FolderRef.Home(pageIndex, folderId)
    }

    /** Scrolls once the pager knows about page [index] (it may have just been created). */
    fun scrollToPageSoon(index: Int) {
        scope.launch {
            snapshotFlow { pagerState.pageCount }.first { it > index }
            pagerState.animateScrollToPage(index)
        }
    }

    // Hidden apps are protected by the Kinetic code (the Vault's PIN or pattern), not by
    // the phone's fingerprints: Android can't tell apps which finger was used.
    fun revealPrivate() {
        if (com.kinetic.launcher.vault.VaultCrypto.isSetUp(activity)) {
            askingCode = true
        } else {
            menu = MenuSpec(
                title = "Set a Kinetic code first",
                actions = listOf("Set up code" to fun() { com.kinetic.launcher.vault.VaultActivity.open(activity) }),
                message = "Hidden apps and the Vault are protected by your own 6-digit PIN or pattern, " +
                    "separate from your phone's lock screen and fingerprints.",
            )
        }
    }

    // ---- S Pen: write on the home screen ----------------------------------------
    // What you write runs as a Kinetic Command, opens a matching app, or searches.
    lateinit var ink: InkState
    fun handleInk(text: String) {
        val parsed = KineticCommands.parse(text)
        scope.launch {
            val ready = parsed?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { KineticCommands.resolve(activity, it) } }
            val allowed = ready?.permission?.let { activity.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED } ?: true
            // Sums: "15% of 84", "12 x 7"…
            if (parsed == null) {
                SmartSearch.calculate(text)?.let { result ->
                    ink.say("$text = $result", 6000)
                    return@launch
                }
            }
            if (ready != null && ready.answer && allowed) {
                // Questions: the answer appears right where you wrote.
                ink.say("${ready.badge}  ${ready.title}" + if (ready.subtitle.isNotBlank()) "\n${ready.subtitle}" else "", 7000)
                return@launch
            }
            if (ready != null && allowed && !ready.confirm) {
                if (!ready.quiet) ink.say("✎ ${ready.title}")
                if (!ready.run(activity)) ink.say("Couldn't do “$text”")
                return@launch
            }
            val apps = allApps.orEmpty().filter { privateRevealed || !isHidden(it) }
            val squash = text.replace(" ", "").lowercase()
            val app = apps.firstOrNull { labelFor(it).replace(" ", "").lowercase() == squash }
                ?: apps.map { it to Fuzzy.score(text, labelFor(it)) }.filter { it.second >= 2 }.maxByOrNull { it.second }?.first
            if (app != null && ready == null) {
                ink.say("✎ ${labelFor(app)}")
                launchApp(app)
                return@launch
            }
            // Anything else (or a command that needs a permission first): open search with it.
            searchPrefill = text
            searchSignal++
            openDrawer()
        }
    }
    ink = remember { InkState(scope) { handleInk(it) } }
    // "open …" commands never reveal hidden apps.
    LaunchedEffect(privateApps, privateRevealed) { KineticCommands.hiddenPackages = if (privateRevealed) emptySet() else privateApps.toSet() }
    LaunchedEffect(kineticSettings.penInk) { if (kineticSettings.penInk) PenRecognizer.prepare() }

    // ---- menus ----------------------------------------------------------------

    fun movePageActions(pageIndex: Int, itemId: String): List<Pair<String, () -> Unit>> = buildList {
        if (pageIndex > 0) {
            add("Move to previous page" to fun() {
                if (deps.layout.moveToPage(pageIndex, itemId, pageIndex - 1)) scrollToPageSoon(pageIndex - 1)
                else toast("No space on that page")
            })
        }
        val next = pageIndex + 1
        add((if (next < layout.pages.size) "Move to next page" else "Move to a new page") to fun() {
            if (deps.layout.moveToPage(pageIndex, itemId, next)) scrollToPageSoon(next)
            else toast("No space on that page")
        })
    }

    fun appItemMenu(pageIndex: Int, item: HomeItem.App, entry: AppEntry): MenuSpec {
        val folders = layout.pages.getOrNull(pageIndex)?.items?.filterIsInstance<HomeItem.Folder>().orEmpty()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Remove from home screen" to fun() { deps.layout.removeItem(pageIndex, item.id) }
        actions += "New folder with this app" to fun() {
            prompt = PromptSpec("Folder name", "Folder") { name ->
                deps.layout.createFolder(pageIndex, item.id, name.ifBlank { "Folder" })
            }
        }
        folders.forEach { f ->
            actions += "Add to \"${f.name}\"" to fun() { deps.layout.addToFolder(pageIndex, item.id, f.id) }
        }
        actions += movePageActions(pageIndex, item.id)
        actions += "App info" to fun() { deps.apps.openAppInfo(entry) }
        return MenuSpec(labelFor(entry), actions)
    }

    fun folderMenu(pageIndex: Int, folder: HomeItem.Folder): MenuSpec {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Open" to fun() { openHomeFolder(pageIndex, folder.id) }
        actions += "Rename" to fun() {
            prompt = PromptSpec("Rename folder", folder.name) { name ->
                deps.layout.renameFolder(folder.id, name.ifBlank { folder.name })
            }
        }
        actions += (if (folder.isLarge) "Make small" else "Make large (2 × 2)") to fun() {
            if (!deps.layout.setFolderLarge(pageIndex, folder.id, !folder.isLarge)) toast("No space to make it large here")
        }
        actions += "Remove folder" to fun() { deps.layout.removeItem(pageIndex, folder.id) }
        actions += movePageActions(pageIndex, folder.id)
        return MenuSpec(folder.name.ifBlank { "Folder" }, actions)
    }

    fun widgetMenu(pageIndex: Int, item: HomeItem.Widget, shownId: Int = item.appWidgetId): MenuSpec {
        fun resize(dx: Int, dy: Int): () -> Unit = fun() {
            if (!deps.layout.resizeWidget(pageIndex, item.id, dx, dy)) toast("Can't resize — no space")
        }
        val name = deps.widgets.infoFor(shownId)?.let { deps.widgets.label(it) } ?: "Widget"
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Wider" to resize(1, 0)
        actions += "Narrower" to resize(-1, 0)
        actions += "Taller" to resize(0, 1)
        actions += "Shorter" to resize(0, -1)
        actions += movePageActions(pageIndex, item.id)
        // Smart stacks: several widgets in this one spot.
        actions += "Add widget to stack…" to fun() {
            stackTarget = item.id
            widgetPickerOpen = true
        }
        if (item.isStack) {
            actions += (if (item.smart) "Smart rotate: on (turn off)" else "Smart rotate: off (turn on)") to fun() {
                deps.layout.setStackSmart(pageIndex, item.id, !item.smart)
            }
            actions += "Remove $name from stack" to fun() {
                if (deps.layout.removeFromStack(pageIndex, item.id, shownId)) deps.widgets.deleteId(shownId)
            }
        }
        actions += (if (item.isStack) "Remove whole stack" else "Remove widget") to fun() {
            deps.layout.removeItem(pageIndex, item.id)
            item.allIds.forEach { deps.widgets.deleteId(it) }
        }
        val title = if (item.isStack) "$name · stack of ${item.allIds.size}" else name
        return MenuSpec("$title (${item.spanX} × ${item.spanY})", actions)
    }

    fun drawerAppMenu(e: AppEntry, inFolder: DrawerFolder?): MenuSpec {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Add to home screen" to fun() {
            if (deps.layout.addApp(pagerState.currentPage, e.key)) toast("Added to home screen")
            else toast("Already on this page")
        }
        actions += "New folder…" to fun() {
            prompt = PromptSpec("Folder name", "Folder") { name ->
                deps.layout.createDrawerFolder(name.ifBlank { "Folder" }, e.key)
            }
        }
        layout.drawerFolders.filter { it.id != inFolder?.id }.forEach { f ->
            actions += "Move to \"${f.name}\"" to fun() { deps.layout.moveToDrawerFolder(f.id, e.key) }
        }
        if (inFolder != null) {
            actions += "Remove from folder" to fun() { deps.layout.removeFromDrawerFolder(inFolder.id, e.key) }
        }
        actions += "Rename…" to fun() {
            prompt = PromptSpec("Rename app", labelFor(e)) { name ->
                if (name.isBlank() || name == e.label) deps.customization.clearMask(e.packageName)
                else deps.customization.setMask(StealthCustomizationManager.IconMask(e.packageName, overrideLabel = name))
            }
        }
        if (isHidden(e)) {
            actions += "Unhide app" to fun() { deps.customization.moveToPrimary(e.packageName) }
        } else {
            actions += "Hide app" to fun() {
                deps.customization.moveToPrivate(e.packageName)
                toast("Hidden. Tap \"Hidden\" in the app list to see it again.")
            }
        }
        actions += "App info" to fun() { deps.apps.openAppInfo(e) }
        return MenuSpec(labelFor(e), actions)
    }

    // ---- long-press quick menu ------------------------------------------------------

    fun quickActions(q: QuickMenuState): List<Pair<String, () -> Unit>> {
        val e = q.entry
        return when (val src = q.source) {
            is QuickSource.Home -> listOf(
                "App info" to fun() {
                    quickMenu = null
                    deps.apps.openAppInfo(e)
                },
                "Edit home" to fun() {
                    quickMenu = null
                    editMode = true
                },
                "Remove" to fun() {
                    quickMenu = null
                    deps.layout.removeItem(src.page, src.itemId)
                },
            )
            is QuickSource.HomeFolder -> listOf(
                "App info" to fun() {
                    quickMenu = null
                    deps.apps.openAppInfo(e)
                },
                "To home" to fun() {
                    quickMenu = null
                    if (!deps.layout.removeFromFolder(src.page, src.folderId, e.key, placeOnHome = true)) toast("No space on this page")
                },
                "Remove" to fun() {
                    quickMenu = null
                    deps.layout.removeFromFolder(src.page, src.folderId, e.key, placeOnHome = false)
                },
            )
            is QuickSource.Drawer -> listOf(
                "Add to home" to fun() {
                    quickMenu = null
                    if (deps.layout.addApp(pagerState.currentPage, e.key)) toast("Added to home screen") else toast("Already on this page")
                },
                "App info" to fun() {
                    quickMenu = null
                    deps.apps.openAppInfo(e)
                },
                "More…" to fun() {
                    quickMenu = null
                    menu = drawerAppMenu(e, src.folder)
                },
            )
        }
    }

    // ---- what each home item looks like --------------------------------------

    /** Content for one home item, or null if it shouldn't show (app missing or hidden). */
    fun itemContent(item: HomeItem, pageIndex: Int, interactive: Boolean): (@Composable () -> Unit)? = when (item) {
        is HomeItem.App -> {
            val entry = appsByKey[item.appKey]
            if (entry == null || isHidden(entry)) {
                null
            } else {
                val content: @Composable () -> Unit = {
                    AppTile(
                        icon = entry.icon,
                        label = labelFor(entry),
                        jiggle = interactive && editMode,
                        ring = hasNotification(entry),
                        pop = interactive && entry.key == popKey,
                        onClick = when {
                            !interactive -> null
                            editMode -> fun() { menu = appItemMenu(pageIndex, item, entry) }
                            else -> fun() { launchApp(entry) }
                        },
                        // Icons stay frozen; long-press shows shortcuts instead of moving them.
                        onLongClick = if (interactive && !editMode) {
                            fun() { quickMenu = QuickMenuState(entry, TapOrigin.rect, QuickSource.Home(pageIndex, item.id)) }
                        } else {
                            null
                        },
                    )
                }
                content
            }
        }
        is HomeItem.Folder -> {
            val content: @Composable () -> Unit = {
                val members = item.appKeys.mapNotNull { appsByKey[it] }.filterNot { isHidden(it) }
                val onTap: (() -> Unit)? = when {
                    !interactive -> null
                    editMode -> fun() { menu = folderMenu(pageIndex, item) }
                    else -> fun() { openHomeFolder(pageIndex, item.id) }
                }
                if (item.isLarge) {
                    LargeFolderTile(
                        name = item.name,
                        icons = members.map { it.icon },
                        rings = members.map { hasNotification(it) },
                        jiggle = interactive && editMode,
                        // Like One UI: the visible icons launch directly.
                        onIconClick = if (interactive && !editMode) {
                            fun(i: Int) {
                                members.getOrNull(i)?.let { launchApp(it) }
                            }
                        } else {
                            null
                        },
                        onClick = onTap,
                    )
                } else {
                    FolderTile(
                        name = item.name,
                        icons = members.map { it.icon },
                        jiggle = interactive && editMode,
                        ring = members.any { hasNotification(it) },
                        onClick = onTap,
                    )
                }
            }
            content
        }
        is HomeItem.Widget -> {
            val content: @Composable () -> Unit = {
                if (item.isStack) {
                    WidgetStack(
                        item = item,
                        widgets = deps.widgets,
                        editMode = interactive && editMode,
                        isResumed = isResumed,
                        playingPackage = playingPackage,
                        onEditTap = { shown -> menu = widgetMenu(pageIndex, item, shown) },
                        onResize = { dx, dy ->
                            deps.layout.resizeWidget(pageIndex, item.id, dx, dy).also { ok ->
                                if (ok) haptics.performHapticFeedback(HapticFeedbackType.LongPress) else toast("Not enough room")
                            }
                        },
                        cell = { id, edit, onTap, resize ->
                            WidgetCell(
                                appWidgetId = id,
                                widgets = deps.widgets,
                                editMode = edit,
                                onEditTap = onTap,
                                spanX = item.spanX,
                                spanY = item.spanY,
                                onResize = resize,
                            )
                        },
                    )
                } else {
                WidgetCell(
                    appWidgetId = item.appWidgetId,
                    widgets = deps.widgets,
                    editMode = interactive && editMode,
                    onEditTap = { menu = widgetMenu(pageIndex, item) },
                    spanX = item.spanX,
                    spanY = item.spanY,
                    onResize = { dx, dy ->
                        deps.layout.resizeWidget(pageIndex, item.id, dx, dy).also { ok ->
                            if (ok) haptics.performHapticFeedback(HapticFeedbackType.LongPress) else toast("Not enough room")
                        }
                    },
                )
                }
            }
            content
        }
    }

    // ---- app drawer contents -------------------------------------------------------

    /**
     * Drawer contents as sections (each starts a new page), in the user's custom
     * order if there is one, else folders first then A–Z.
     */
    fun drawerSections(): List<List<DrawerEntry>> {
        val visibleApps = allApps.orEmpty().filter { privateRevealed || !isHidden(it) }
        val foldered = layout.drawerFolders.flatMapTo(HashSet()) { it.appKeys }
        val folderEntries = LinkedHashMap<String, DrawerEntry.Folder>()
        layout.drawerFolders.forEach { f ->
            val members = f.appKeys.mapNotNull { appsByKey[it] }.filter { privateRevealed || !isHidden(it) }
            if (members.isNotEmpty()) folderEntries[f.id] = DrawerEntry.Folder(f, members)
        }
        val loose = visibleApps.filter { it.key !in foldered }
        if (layout.drawerOrder.isEmpty()) return listOf(folderEntries.values.toList() + loose.map { DrawerEntry.App(it) })

        val looseByKey = loose.associateBy { it.key }
        val placed = HashSet<String>()
        val sections = mutableListOf(mutableListOf<DrawerEntry>())
        layout.drawerOrder.forEach { token ->
            if (token == HomeLayout.PAGE_BREAK) {
                if (sections.last().isNotEmpty()) sections += mutableListOf<DrawerEntry>()
                return@forEach
            }
            val entry: DrawerEntry? = when {
                token.startsWith("app:") -> looseByKey[token.removePrefix("app:")]?.let { DrawerEntry.App(it) }
                token.startsWith("folder:") -> folderEntries[token.removePrefix("folder:")]
                else -> null
            }
            if (entry != null && placed.add(entry.key)) sections.last() += entry
        }
        // Anything not in the saved order (e.g. newly installed apps) goes at the end, like One UI.
        val tail = sections.last()
        folderEntries.values.forEach { if (placed.add(it.key)) tail += it }
        loose.forEach { val e = DrawerEntry.App(it); if (placed.add(e.key)) tail += e }
        return sections.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }

    fun useMyLayoutMenu() = MenuSpec(
        title = "Use my phone's layout?",
        actions = listOf("Reset layout" to fun() {
            val apps = allApps
            if (apps == null) toast("Apps are still loading — try again in a moment") else onApplyMyLayout(apps)
        }),
        message = "Puts your home screen, dock, folders and app-list pages back the way they are on One UI. " +
            "Changes you've made in Kinetic will be replaced.",
    )

    // ---- effects ---------------------------------------------------------------

    // Soften the live wallpaper behind the drawer, folders and widget picker (One UI–style blur).
    val wallpaperFocused = drawerMostlyOpen || openFolder != null || widgetPickerOpen
    LaunchedEffect(wallpaperFocused) { onWallpaperFocus(wallpaperFocused) }

    LaunchedEffect(allApps) {
        val list = allApps ?: return@LaunchedEffect
        deps.layout.prune(list.mapTo(HashSet()) { it.key })
        if (autoApplyMyLayout) onApplyMyLayout(list)
        else if (deps.layout.isFresh) deps.layout.seed(defaultHomeKeys(activity, list))
    }

    // Home button while already home: close everything and go to the first page.
    LaunchedEffect(openSearchSignal) {
        if (openSearchSignal > 0) {
            searchSignal++
            openDrawer()
        }
    }

    LaunchedEffect(homePressSignal) {
        if (homePressSignal == 0) return@LaunchedEffect
        prompt = null
        menu = null
        widgetPickerOpen = false
        openFolder = null
        closeDrawer()
        editMode = false
        if (pagerState.pageCount > 0) pagerState.animateScrollToPage(0)
    }

    // Coming back from an app: its icon gives a little landing bounce.
    LaunchedEffect(isResumed) {
        if (isResumed) {
            val key = lastLaunchedKey ?: return@LaunchedEffect
            lastLaunchedKey = null
            popKey = key
            delay(900)
            popKey = null
        }
    }

    // A new Kinetic build on GitHub? Offer it once (checked at most every few hours).
    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        delay(1200) // after the home screen has settled
        val st = Updater.check(activity)
        if (st is Updater.State.Available) {
            val prefs = activity.getSharedPreferences("kinetic_updater", android.content.Context.MODE_PRIVATE)
            if (prefs.getLong("prompted", 0L) != st.release.code) {
                prefs.edit().putLong("prompted", st.release.code).apply()
                menu = MenuSpec(
                    title = "Kinetic update ready",
                    actions = listOf(
                        "Update now" to fun() { scope.launch { Updater.downloadAndInstall(activity, st.release) } },
                    ),
                    message = "Build ${st.release.code} is on GitHub. " + st.release.notes.lineSequence().firstOrNull().orEmpty(),
                )
            }
        }
    }

    // Ask once to turn on notification access for the spinning rings.
    LaunchedEffect(allApps != null) {
        if (allApps == null) return@LaunchedEffect
        val prefs = activity.getSharedPreferences("kinetic_activity", android.content.Context.MODE_PRIVATE)
        if (!NotificationHub.isEnabled(activity) && !prefs.getBoolean("asked_notification_rings", false)) {
            delay(1500)
            prefs.edit().putBoolean("asked_notification_rings", true).apply()
            menu = MenuSpec(
                title = "Turn on notification rings?",
                actions = listOf("Open settings" to fun() { NotificationHub.openSettings(activity) }),
                message = "Kinetic can spin a ring around apps that have notifications. Android needs you to " +
                    "allow Notification access for Kinetic — it only checks which apps have notifications.",
            )
        }
    }

    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) {
            privateRevealed = false
            drawerPickMode = false
        }
    }

    LaunchedEffect(unlockPhase) {
        when (unlockPhase) {
            UnlockPhase.ARMED -> controller.reset()
            UnlockPhase.PLAY -> {
                controller.runUnlock()
                onUnlockFinished()
            }
            UnlockPhase.NONE -> Unit
        }
    }

    BackHandler(enabled = widgetPickerOpen || openFolder != null || drawerOpen || editMode) {
        when {
            widgetPickerOpen -> widgetPickerOpen = false
            openFolder != null -> openFolder = null
            drawerOpen -> closeDrawer()
            editMode -> editMode = false
        }
    }

    // ---- layout ----------------------------------------------------------------

    val gesturesEnabled = !editMode && !drawerOpen && openFolder == null && !widgetPickerOpen
    // Distance OR speed is enough. Even a tiny flick counts (~90dp/s), and pulling down
    // opens the shade as soon as the finger has moved ~10dp — no need to let go.
    val swipeThresholdPx = with(density) { 10.dp.toPx() }
    val flingVelocityPx = with(density) { 90.dp.toPx() }
    val currentExpandNotifications by rememberUpdatedState(onExpandNotifications)

    Box(
        Modifier
            .fillMaxSize()
            // S Pen: write anywhere (fingers and pen taps aren't affected).
            .penInk(gesturesEnabled && kineticSettings.penInk, ink)
            // Two fingers down: Focus. Three-finger tap: search / Commands.
            .pointerInput(gesturesEnabled, kineticSettings.multiFinger) {
                if (!gesturesEnabled || !kineticSettings.multiFinger) return@pointerInput
                val swipe = 70.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val t0 = System.currentTimeMillis()
                    var maxFingers = 1
                    var startY = 0f
                    var moved = 0f
                    var fired = false
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val down = ev.changes.filter { it.pressed }
                        if (down.isEmpty()) break
                        val avgY = down.map { it.position.y }.average().toFloat()
                        if (down.size > maxFingers) {
                            maxFingers = down.size
                            startY = avgY
                        }
                        if (maxFingers >= 2) {
                            // Keep one-finger swipes (drawer, shade, pages) out of it.
                            ev.changes.forEach { it.consume() }
                            moved = maxOf(moved, kotlin.math.abs(avgY - startY))
                            if (!fired && maxFingers == 2 && avgY - startY > swipe) {
                                fired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                openFocus()
                            }
                        }
                    }
                    if (!fired && maxFingers == 3 && moved < swipe / 2 && System.currentTimeMillis() - t0 < 500) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        searchSignal++
                        openDrawer()
                    }
                }
            }
            // Double-tap empty wallpaper: lock the phone.
            .pointerInput(gesturesEnabled, kineticSettings.doubleTapLock) {
                if (!gesturesEnabled || !kineticSettings.doubleTapLock) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    if (!com.kinetic.launcher.island.DynamicIslandService.lockScreen()) {
                        toast("Turn on Kinetic Dynamic Island (Accessibility) to double-tap to lock")
                    }
                })
            }
            // Swipe up anywhere: app drawer. Swipe down anywhere: notification shade.
            .pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                var total = 0f
                var mode = 0 // 0 = undecided, 1 = pulling up the drawer, 2 = notification shade
                var shadeOpened = false
                val velocity = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        mode = 0
                        shadeOpened = false
                        velocity.resetTracking()
                    },
                    onDragEnd = {
                        // A quick flick counts even if the finger barely moved; otherwise go by
                        // which way it was last moving, then by how far it got.
                        val vy = velocity.calculateVelocity().y
                        when (mode) {
                            1 -> when {
                                vy < -flingVelocityPx -> openDrawer(vy)
                                vy > flingVelocityPx -> closeDrawer(vy)
                                drawerProgress.value > 0.3f -> openDrawer(vy)
                                else -> closeDrawer(vy)
                            }
                            2 -> if (!shadeOpened && (vy > flingVelocityPx || total > swipeThresholdPx)) currentExpandNotifications()
                        }
                    },
                    onDragCancel = { if (mode == 1) closeDrawer() },
                ) { change, dragAmount ->
                    velocity.addPointerInputChange(change)
                    change.consume()
                    total += dragAmount
                    if (mode == 0) mode = if (dragAmount < 0f) 1 else 2
                    if (mode == 1) dragDrawerBy(dragAmount)
                    // Open the shade mid-swipe, the moment it's clearly a pull down.
                    if (mode == 2 && !shadeOpened && total > swipeThresholdPx) {
                        shadeOpened = true
                        currentExpandNotifications()
                    }
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read in the draw phase, so dragging the drawer doesn't recompose the home screen.
                    val p = drawerProgress.value
                    alpha = 1f - p
                    val s = 1f - 0.06f * p
                    scaleX = s
                    scaleY = s
                    val r = 24.dp.toPx() * p
                    renderEffect = if (r > 0.5f) BlurEffect(r, r) else null
                    // Tilt parallax (also read in the draw phase).
                    val t = tilt.value
                    rotationY = t.x * 5f
                    rotationX = -t.y * 5f
                    translationX = t.x * 8.dp.toPx()
                    translationY = t.y * 8.dp.toPx()
                    cameraDistance = 16f * this.density // the layer scope's density (a number), not the outer Density object
                }
                .systemBarsPadding()
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (unlockPhase != UnlockPhase.NONE && !editMode) {
                    val pageIndex = pagerState.currentPage
                    UnlockGlide(
                        controller = controller,
                        config = config,
                        items = layout.pages.getOrNull(pageIndex)?.items.orEmpty(),
                        contentFor = { item -> itemContent(item, pageIndex, interactive = false) },
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // Change page after a short drag (15%) or any quick flick.
                        flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.15f),
                        beyondViewportPageCount = 1,
                        key = { index -> layout.pages.getOrNull(index)?.id ?: "page-$index" },
                    ) { pageIndex ->
                        val page = layout.pages.getOrNull(pageIndex)
                        if (page != null) {
                            LauncherGrid(
                                // Page transition (Settings ▸ Home screen ▸ Page transition).
                                modifier = Modifier.graphicsLayer {
                                    val raw = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                                    val off = raw.absoluteValue.coerceIn(0f, 1f)
                                    when (kineticSettings.pageTransition) {
                                        "flat" -> Unit
                                        "fade" -> alpha = 1f - off
                                        "cube" -> {
                                            // Pages turn like the faces of a cube.
                                            cameraDistance = 14f * this.density
                                            transformOrigin = TransformOrigin(if (raw > 0) 1f else 0f, 0.5f)
                                            rotationY = -raw.coerceIn(-1f, 1f) * 70f
                                        }
                                        "carousel" -> {
                                            val s = 1f - 0.18f * off
                                            scaleX = s
                                            scaleY = s
                                            translationX = raw * size.width * 0.25f
                                            rotationY = -raw.coerceIn(-1f, 1f) * 18f
                                            cameraDistance = 14f * this.density
                                        }
                                        else -> {
                                            // depth: pages shrink and fade a little as they slide away.
                                            val s = 1f - 0.1f * off
                                            scaleX = s
                                            scaleY = s
                                            alpha = 1f - 0.5f * off
                                        }
                                    }
                                },
                                config = config,
                                items = page.items.mapNotNull { item ->
                                    itemContent(item, pageIndex, interactive = true)?.let { c ->
                                        GridItem(item.id, item.cellX, item.cellY, item.spanX, item.spanY, c)
                                    }
                                },
                                editable = editMode,
                                canPlace = { id, x, y -> deps.layout.canPlace(pageIndex, id, x, y) },
                                onMove = { id, x, y -> deps.layout.moveItem(pageIndex, id, x, y) },
                                // Drop an app onto an app (new folder) or onto a folder (joins it).
                                canMerge = { dragged, target ->
                                    val items = layout.pages.getOrNull(pageIndex)?.items.orEmpty()
                                    items.firstOrNull { it.id == dragged } is HomeItem.App &&
                                        items.firstOrNull { it.id == target }.let { it is HomeItem.App || it is HomeItem.Folder }
                                },
                                onMerge = { dragged, target ->
                                    if (deps.layout.mergeInto(pageIndex, dragged, target)) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onSwap = { dragged, target -> deps.layout.swapItems(pageIndex, dragged, target) },
                                onEmptyLongPress = if (editMode) null else fun() {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    editMode = true
                                },
                                emptyHoldMillis = EDIT_HOLD_MS,
                            )
                        }
                    }
                }
            }

            if (layout.pages.size > 1 || editMode) {
                PageDots(count = layout.pages.size, current = pagerState.currentPage)
            }

            if (editMode) {
                val currentPage = layout.pages.getOrNull(pagerState.currentPage)
                EditToolbar(
                    canDeletePage = layout.pages.size > 1 && currentPage?.items?.isEmpty() == true,
                    onAddApps = {
                        drawerPickMode = true
                        openDrawer()
                    },
                    onAddWidget = { widgetPickerOpen = true },
                    onWallpaper = { openWallpaperStudio() },
                    onAddPage = { scrollToPageSoon(deps.layout.addPage()) },
                    onDeletePage = {
                        val cur = pagerState.currentPage
                        if (deps.layout.removePage(cur)) scrollToPageSoon((cur - 1).coerceAtLeast(0))
                    },
                    onDone = { editMode = false },
                    onSettings = { openSettings() },
                )
            }
        }

        if (drawerShowing) {
            // Dim layer; the wallpaper itself softens via onWallpaperFocus.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black.copy(alpha = 0.3f * drawerProgress.value)) }
            )
            AppDrawer(
                sections = drawerSections(),
                progress = { drawerProgress.value },
                horizontalMargin = config.marginDp.dp,
                searchable = allApps.orEmpty().filter { privateRevealed || !isHidden(it) },
                labelFor = { labelFor(it) },
                isHidden = { isHidden(it) },
                pickMode = drawerPickMode,
                privateRevealed = privateRevealed,
                customOrder = layout.drawerOrder.isNotEmpty(),
                onAppClick = { e ->
                    if (drawerPickMode) {
                        if (deps.layout.addApp(pagerState.currentPage, e.key)) toast("Added ${labelFor(e)}")
                        else toast("Already on this page")
                    } else {
                        launchApp(e)
                    }
                    closeDrawer()
                },
                onAppLongPress = { e -> quickMenu = QuickMenuState(e, TapOrigin.rect, QuickSource.Drawer(null)) },
                onFolderClick = { f ->
                    folderOrigin = TapOrigin.rect
                    openFolder = FolderRef.Drawer(f.id)
                },
                hasNotification = { hasNotification(it) },
                onNotificationRings = { NotificationHub.openSettings(activity) },
                onSettings = { openSettings() },
                onFocus = { openFocus() },
                onVault = { com.kinetic.launcher.vault.VaultActivity.open(activity) },
                onPlanes = { activity.startActivity(android.content.Intent(activity, com.kinetic.launcher.planes.PlaneSpotterActivity::class.java)) },
                onDynamicIsland = {
                    val on = DynamicIslandService.isEnabled(activity)
                    menu = MenuSpec(
                        title = "Dynamic Island",
                        actions = listOf(
                            (if (on) "Turn off in Settings" else "Turn on in Settings") to fun() { DynamicIslandService.openSettings(activity) },
                        ) + if (!NotificationHub.isEnabled(activity)) {
                            listOf("Allow music & calls (Notification access)" to fun() { NotificationHub.openSettings(activity) })
                        } else {
                            emptyList()
                        },
                        message = (if (on) "The island is on. " else "") +
                            "A black iPhone-style island around the camera that shows music, calls, charging and the " +
                            "ringer, above every app. Tap it to open the app, hold it to expand. Android only lets " +
                            "accessibility services draw above the status bar, so you switch it on under " +
                            "Accessibility ▸ Installed apps ▸ Kinetic Dynamic Island. It doesn't read your screen.",
                    )
                },
                chowaniecPackage = chowaniecPackage,
                onLeave = { closeDrawer() },
                onTogglePrivate = { if (privateRevealed) privateRevealed = false else revealPrivate() },
                onSortAlphabetical = { deps.layout.clearDrawerOrder() },
                onUseMyLayout = { menu = useMyLayoutMenu() },
                onDrag = { dy -> dragDrawerBy(dy) },
                searchSignal = searchSignal,
                searchPrefill = searchPrefill,
                onSearchPrefillUsed = { searchPrefill = "" },
                onDragEnd = { vy ->
                    when {
                        vy > flingVelocityPx -> closeDrawer(vy)          // quick flick down
                        vy < -flingVelocityPx -> openDrawer(vy)          // quick flick back up
                        drawerProgress.value < 0.85f -> closeDrawer(vy)  // dragged down a little
                        else -> openDrawer(vy)
                    }
                },
                modifier = Modifier.graphicsLayer {
                    val p = drawerProgress.value
                    alpha = p
                    translationY = (1f - p) * 96.dp.toPx()
                    val s = 0.94f + 0.06f * p
                    scaleX = s
                    scaleY = s
                },
            )
        }

        when (val ref = openFolder) {
            is FolderRef.Home -> {
                val folder = layout.pages.getOrNull(ref.page)?.items?.firstOrNull { it.id == ref.folderId } as? HomeItem.Folder
                LaunchedEffect(folder == null) { if (folder == null) openFolder = null }
                if (folder != null) {
                    FolderOverlay(
                        name = folder.name,
                        apps = folder.appKeys.mapNotNull { appsByKey[it] }.filterNot { isHidden(it) },
                        labelFor = { labelFor(it) },
                        onAppClick = { e ->
                            launchApp(e)
                            openFolder = null
                        },
                        onAppLongPress = { e ->
                            quickMenu = QuickMenuState(e, TapOrigin.rect, QuickSource.HomeFolder(ref.page, folder.id))
                        },
                        origin = folderOrigin,
                        hasNotification = { hasNotification(it) },
                        onRename = {
                            prompt = PromptSpec("Rename folder", folder.name) { name ->
                                deps.layout.renameFolder(folder.id, name.ifBlank { folder.name })
                            }
                        },
                        onDismiss = { openFolder = null },
                    )
                }
            }
            is FolderRef.Drawer -> {
                val folder = layout.drawerFolders.firstOrNull { it.id == ref.folderId }
                LaunchedEffect(folder == null) { if (folder == null) openFolder = null }
                if (folder != null) {
                    FolderOverlay(
                        name = folder.name,
                        apps = folder.appKeys.mapNotNull { appsByKey[it] }.filter { privateRevealed || !isHidden(it) },
                        labelFor = { labelFor(it) },
                        onAppClick = { e ->
                            launchApp(e)
                            openFolder = null
                            closeDrawer()
                        },
                        onAppLongPress = { e -> quickMenu = QuickMenuState(e, TapOrigin.rect, QuickSource.Drawer(folder)) },
                        origin = folderOrigin,
                        hasNotification = { hasNotification(it) },
                        onRename = {
                            prompt = PromptSpec("Rename folder", folder.name) { name ->
                                deps.layout.renameFolder(folder.id, name.ifBlank { folder.name })
                            }
                        },
                        onDismiss = { openFolder = null },
                    )
                }
            }
            null -> Unit
        }

        if (widgetPickerOpen) {
            WidgetPicker(
                widgets = deps.widgets,
                columns = config.columns,
                rows = config.rows,
                onPick = { info ->
                    widgetPickerOpen = false
                    onRequestWidget(info, pagerState.currentPage, stackTarget)
                    stackTarget = null
                },
                onDismiss = {
                    widgetPickerOpen = false
                    stackTarget = null
                },
            )
        }

        quickMenu?.let { q ->
            QuickMenu(
                anchor = q.anchor,
                title = labelFor(q.entry),
                loadShortcuts = { deps.apps.shortcuts(q.entry) },
                onShortcut = { shortcut ->
                    quickMenu = null
                    val (bounds, options) = launchOrigin()
                    if (deps.apps.startShortcut(shortcut, bounds, options)) {
                        openFolder = null
                        if (q.source is QuickSource.Drawer) closeDrawer()
                    } else {
                        toast("Couldn't open that shortcut")
                    }
                },
                actions = quickActions(q),
                onDismiss = { quickMenu = null },
                // Chowaniec's pages, in case Chrome didn't publish its app shortcuts.
                fallbackRows = if (SmartSearch.isChowaniec(q.entry)) {
                    SmartSearch.chowaniecMenuPages.map { page ->
                        page.title to fun() {
                            quickMenu = null
                            SmartSearch.openChowaniec(activity, page.path, q.entry.packageName)
                        }
                    }
                } else {
                    emptyList()
                },
            )
        }

        InkLayer(ink)
        menu?.let { spec -> ActionMenu(spec, onDismiss = { menu = null }) }
        prompt?.let { spec -> TextPrompt(spec, onDismiss = { prompt = null }) }
        if (askingCode) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { askingCode = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    com.kinetic.launcher.vault.UnlockWithCode("Hidden apps", "Enter your Kinetic code") {
                        privateRevealed = true
                        askingCode = false
                    }
                }
            }
        }
    }
}

/**
 * A live Android widget. Its shadow, rounded corners and bevel follow the
 * widget's *own* card edges (found by [WidgetEdgeDetector]), not the grid cell,
 * so there's no frame around widgets that have a margin. In edit mode a
 * transparent layer on top takes the taps instead.
 */
@Composable
private fun WidgetCell(
    appWidgetId: Int,
    widgets: WidgetHostManager,
    editMode: Boolean,
    onEditTap: () -> Unit,
    spanX: Int = 1,
    spanY: Int = 1,
    /** Grow/shrink by whole cells; returns false if there's no room. */
    onResize: ((dx: Int, dy: Int) -> Boolean)? = null,
) {
    val view = remember(appWidgetId) { widgets.viewFor(appWidgetId) }
    val padPx = with(LocalDensity.current) { 6.dp.toPx() }
    val stretchX = remember { Animatable(0f) }
    val stretchY = remember { Animatable(0f) }
    var resizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val minRadiusPx = with(LocalDensity.current) { 18.dp.toPx() }
    var edges by remember(appWidgetId) { mutableStateOf<WidgetEdges?>(null) }
    var layoutTick by remember(appWidgetId) { mutableIntStateOf(0) }
    val cardShape: WidgetCardShape? = edges?.takeIf { it.isCard }?.let { WidgetCardShape(it, minRadiusPx) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .jiggle(editMode)
            .padding(6.dp)
    ) {
        val cellWpx = (constraints.maxWidth + padPx * 2) / spanX.coerceAtLeast(1)
        val cellHpx = (constraints.maxHeight + padPx * 2) / spanY.coerceAtLeast(1)
        // Read the cell size here: the inner Box below hides BoxWithConstraints' values.
        val boxWidthDp = maxWidth.value
        val boxHeightDp = maxHeight.value
        val boxWidthPx = constraints.maxWidth.toFloat()
        val boxHeightPx = constraints.maxHeight.toFloat()
        // While the resize handle is dragged the widget stretches live (scaled from its
        // top-left corner); on release it snaps to whole cells with a springy settle.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val w = size.width.coerceAtLeast(1f)
                    val h = size.height.coerceAtLeast(1f)
                    scaleX = ((w + stretchX.value) / w).coerceAtLeast(0.3f)
                    scaleY = ((h + stretchY.value) / h).coerceAtLeast(0.3f)
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            if (view == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Widget unavailable", color = Color.White, fontSize = 12.sp)
                }
            } else {
                val widthDp = boxWidthDp
                val heightDp = boxHeightDp
                LaunchedEffect(view, widthDp, heightDp) {
                    // Lets responsive widgets pick the right layout for their cell size.
                    view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
                }
                // Find the card after the widget has drawn, and again later in case it loads slowly.
                LaunchedEffect(view, widthDp, heightDp, layoutTick) {
                    for (wait in longArrayOf(300, 1200, 4000)) {
                        delay(wait)
                        WidgetEdgeDetector.detect(view)?.let { edges = it }
                    }
                }
                AndroidView(
                    factory = { _ ->
                        // The same view object is reused across screens, so detach it first.
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
                            if (r - l != orr - ol || b - t != ob - ot) layoutTick++
                        }
                        view
                    },
                    update = { v ->
                        // Round the view itself too, for widgets that draw outside Compose's clip.
                        val shape = cardShape
                        if (shape == null) {
                            v.clipToOutline = false
                        } else {
                            v.outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(target: View, outline: Outline) {
                                    val e = shape.edges
                                    outline.setRoundRect(
                                        e.left.roundToInt(), e.top.roundToInt(),
                                        e.right.roundToInt().coerceAtMost(target.width),
                                        e.bottom.roundToInt().coerceAtMost(target.height),
                                        shape.radiusFor(target.width.toFloat(), target.height.toFloat()),
                                    )
                                }
                            }
                            v.clipToOutline = true
                            v.invalidateOutline()
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        // Shadow, rounded clip and bevel exactly on the widget's own edges.
                        .then(if (cardShape != null) Modifier.depth3D(cardShape, elevation = 8.dp, gloss = 0.5f) else Modifier),
                )
            }
            if (editMode) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(1.dp, Color.White.copy(alpha = 0.5f), cardShape ?: RoundedCornerShape(24.dp))
                        .clickable(onClick = onEditTap)
                )
            }
        }
        if (editMode && onResize != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset { IntOffset(stretchX.value.roundToInt() + 10.dp.roundToPx(), stretchY.value.roundToInt() + 10.dp.roundToPx()) }
                    .size(30.dp)
                    .graphicsLayer {
                        val s = if (resizing) 1.25f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .depth3D(CircleShape, elevation = 4.dp)
                    .background(Brush.linearGradient(listOf(WallpaperPalette.Glint, WallpaperPalette.Light)), CircleShape)
                    .pointerInput(spanX, spanY) {
                        detectDragGestures(
                            onDragStart = { resizing = true },
                            onDragEnd = {
                                resizing = false
                                val dx = (stretchX.value / cellWpx).roundToInt()
                                val dy = (stretchY.value / cellHpx).roundToInt()
                                val ok = (dx != 0 || dy != 0) && onResize(dx, dy)
                                scope.launch {
                                    // The widget is now (dx, dy) cells bigger, so keep only the leftover stretch.
                                    if (ok) {
                                        launch { stretchX.snapTo(stretchX.value - dx * cellWpx) }
                                        launch { stretchY.snapTo(stretchY.value - dy * cellHpx) }
                                    }
                                    launch { stretchX.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 420f)) }
                                    launch { stretchY.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 420f)) }
                                }
                            },
                            onDragCancel = {
                                resizing = false
                                scope.launch { stretchX.animateTo(0f, spring(dampingRatio = 0.42f)) }
                                scope.launch { stretchY.animateTo(0f, spring(dampingRatio = 0.42f)) }
                            },
                        ) { change, delta ->
                            change.consume()
                            val minX = -(boxWidthPx - cellWpx * 0.6f).coerceAtLeast(0f)
                            val minY = -(boxHeightPx - cellHpx * 0.6f).coerceAtLeast(0f)
                            // Rubber-band resistance past the smallest size.
                            fun band(v: Float, d: Float, min: Float) = if (v + d < min) v + d * 0.25f else v + d
                            val nx = band(stretchX.value, delta.x, minX)
                            val ny = band(stretchY.value, delta.y, minY)
                            scope.launch { stretchX.snapTo(nx) }
                            scope.launch { stretchY.snapTo(ny) }
                        }
                    }
            )
        }
    }
}

/** The icons/widgets of one page, flying in along the wallpaper's flow lines. */
@Composable
private fun UnlockGlide(
    controller: UnlockTransitionController,
    config: GridConfig,
    items: List<HomeItem>,
    contentFor: (HomeItem) -> (@Composable () -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val geometry = GridGeometry.of(config, w, h, density)
        val icons = mutableListOf<UnlockIcon>()
        val widgets = mutableListOf<UnlockWidget>()
        items.forEach { item ->
            val content = contentFor(item) ?: return@forEach
            val origin = geometry.cellOrigin(item.cellX, item.cellY)
            val wDp = with(density) { geometry.spanWidthPx(item.spanX).toDp() }
            val hDp = with(density) { geometry.spanHeightPx(item.spanY).toDp() }
            if (item is HomeItem.Widget) {
                widgets += UnlockWidget(item.id) {
                    Box(
                        Modifier
                            .offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }
                            .size(wDp, hDp)
                    ) { content() }
                }
            } else {
                icons += UnlockIcon(item.id, origin) {
                    Box(Modifier.size(wDp, hDp)) { content() }
                }
            }
        }
        CinematicUnlock(
            controller = controller,
            direction = FlowDirection.TOP_BOTTOM,
            canvasSize = Offset(w, h),
            icons = icons,
            widgets = widgets,
            maxBlur = 0.dp,
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.4f))
            )
        }
    }
}

@Composable
private fun EditToolbar(
    canDeletePage: Boolean,
    onAddApps: () -> Unit,
    onAddWidget: () -> Unit,
    onWallpaper: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onDone: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xCC141B2D))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onAddApps) { Text("Add apps") }
        TextButton(onClick = onAddWidget) { Text("Widgets") }
        TextButton(onClick = onWallpaper) { Text("Wallpaper") }
        TextButton(onClick = onAddPage) { Text("+ Page") }
        if (canDeletePage) TextButton(onClick = onDeletePage) { Text("Delete page") }
        TextButton(onClick = onSettings) { Text("Settings") }
        TextButton(onClick = onDone) { Text("Done") }
    }
}

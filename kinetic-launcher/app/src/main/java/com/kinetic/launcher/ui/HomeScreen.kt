@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.util.SizeF
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.kinetic.core.widgets.WidgetHostManager
import com.kinetic.launcher.defaultHomeKeys
import com.kinetic.lockscreen.BiometricUnlockBridge
import com.kinetic.lockscreen.CinematicUnlock
import com.kinetic.lockscreen.FlowDirection
import com.kinetic.lockscreen.UnlockIcon
import com.kinetic.lockscreen.UnlockTransitionController
import com.kinetic.lockscreen.UnlockWidget
import com.kinetic.lockscreen.rememberUnlockController
import com.kinetic.security.StealthCustomizationManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

private sealed interface FolderRef {
    data class Home(val page: Int, val folderId: String) : FolderRef
    data class Drawer(val folderId: String) : FolderRef
}

@Composable
fun KineticHome(
    deps: HomeDeps,
    activity: FragmentActivity,
    homePressSignal: Int,
    unlockPhase: UnlockPhase,
    onUnlockFinished: () -> Unit,
    onRequestWidget: (AppWidgetProviderInfo, Int) -> Unit,
    onOpenWallpaperPicker: () -> Unit,
    onExpandNotifications: () -> Unit,
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

    var editMode by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerPickMode by remember { mutableStateOf(false) }
    var privateRevealed by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<FolderRef?>(null) }
    var widgetPickerOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }

    val pagerState = rememberPagerState(pageCount = { layout.pages.size })
    val controller = rememberUnlockController()
    val bridge = remember(activity) { BiometricUnlockBridge(activity, scope) }

    fun labelFor(e: AppEntry): String = masks[e.packageName]?.overrideLabel ?: e.label
    fun isHidden(e: AppEntry): Boolean = e.packageName in privateApps
    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    /** Scrolls once the pager knows about page [index] (it may have just been created). */
    fun scrollToPageSoon(index: Int) {
        scope.launch {
            snapshotFlow { pagerState.pageCount }.first { it > index }
            pagerState.animateScrollToPage(index)
        }
    }

    fun revealPrivate() {
        if (bridge.canAuthenticate()) {
            bridge.authenticate(title = "Show hidden apps", subtitle = "Confirm it's you") { privateRevealed = true }
        } else {
            privateRevealed = true
        }
    }

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
        actions += "Open" to fun() { openFolder = FolderRef.Home(pageIndex, folder.id) }
        actions += "Rename" to fun() {
            prompt = PromptSpec("Rename folder", folder.name) { name ->
                deps.layout.renameFolder(folder.id, name.ifBlank { folder.name })
            }
        }
        actions += "Remove folder" to fun() { deps.layout.removeItem(pageIndex, folder.id) }
        actions += movePageActions(pageIndex, folder.id)
        return MenuSpec(folder.name, actions)
    }

    fun widgetMenu(pageIndex: Int, item: HomeItem.Widget): MenuSpec {
        fun resize(dx: Int, dy: Int): () -> Unit = fun() {
            if (!deps.layout.resizeWidget(pageIndex, item.id, dx, dy)) toast("Can't resize — no space")
        }
        val name = deps.widgets.infoFor(item.appWidgetId)?.let { deps.widgets.label(it) } ?: "Widget"
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Wider" to resize(1, 0)
        actions += "Narrower" to resize(-1, 0)
        actions += "Taller" to resize(0, 1)
        actions += "Shorter" to resize(0, -1)
        actions += movePageActions(pageIndex, item.id)
        actions += "Remove widget" to fun() {
            deps.layout.removeItem(pageIndex, item.id)
            deps.widgets.deleteId(item.appWidgetId)
        }
        return MenuSpec("$name (${item.spanX} × ${item.spanY})", actions)
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
                        onClick = when {
                            !interactive -> null
                            editMode -> fun() { menu = appItemMenu(pageIndex, item, entry) }
                            else -> fun() { deps.apps.launch(entry) }
                        },
                    )
                }
                content
            }
        }
        is HomeItem.Folder -> {
            val content: @Composable () -> Unit = {
                val icons = item.appKeys.mapNotNull { appsByKey[it] }.filterNot { isHidden(it) }.map { it.icon }
                FolderTile(
                    name = item.name,
                    icons = icons,
                    jiggle = interactive && editMode,
                    onClick = when {
                        !interactive -> null
                        editMode -> fun() { menu = folderMenu(pageIndex, item) }
                        else -> fun() { openFolder = FolderRef.Home(pageIndex, item.id) }
                    },
                )
            }
            content
        }
        is HomeItem.Widget -> {
            val content: @Composable () -> Unit = {
                WidgetCell(
                    appWidgetId = item.appWidgetId,
                    widgets = deps.widgets,
                    editMode = interactive && editMode,
                    onEditTap = { menu = widgetMenu(pageIndex, item) },
                )
            }
            content
        }
    }

    // ---- effects ---------------------------------------------------------------

    LaunchedEffect(allApps) {
        val list = allApps ?: return@LaunchedEffect
        deps.layout.prune(list.mapTo(HashSet()) { it.key })
        if (deps.layout.isFresh) deps.layout.seed(defaultHomeKeys(activity, list))
    }

    // Home button while already home: close everything and go to the first page.
    LaunchedEffect(homePressSignal) {
        if (homePressSignal == 0) return@LaunchedEffect
        prompt = null
        menu = null
        widgetPickerOpen = false
        openFolder = null
        drawerOpen = false
        editMode = false
        if (pagerState.pageCount > 0) pagerState.animateScrollToPage(0)
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
            drawerOpen -> drawerOpen = false
            editMode -> editMode = false
        }
    }

    // ---- layout ----------------------------------------------------------------

    val gesturesEnabled = !editMode && !drawerOpen && openFolder == null && !widgetPickerOpen
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    val currentExpandNotifications by rememberUpdatedState(onExpandNotifications)

    Box(
        Modifier
            .fillMaxSize()
            // Swipe up anywhere: app drawer. Swipe down anywhere: notification shade.
            .pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -swipeThresholdPx) drawerOpen = true
                        else if (total > swipeThresholdPx) currentExpandNotifications()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    total += dragAmount
                }
            }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
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
                        beyondViewportPageCount = 1,
                        key = { index -> layout.pages.getOrNull(index)?.id ?: "page-$index" },
                    ) { pageIndex ->
                        val page = layout.pages.getOrNull(pageIndex)
                        if (page != null) {
                            LauncherGrid(
                                config = config,
                                items = page.items.mapNotNull { item ->
                                    itemContent(item, pageIndex, interactive = true)?.let { c ->
                                        GridItem(item.id, item.cellX, item.cellY, item.spanX, item.spanY, c)
                                    }
                                },
                                editable = editMode,
                                canPlace = { id, x, y -> deps.layout.canPlace(pageIndex, id, x, y) },
                                onMove = { id, x, y -> deps.layout.moveItem(pageIndex, id, x, y) },
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
                        drawerOpen = true
                    },
                    onAddWidget = { widgetPickerOpen = true },
                    onWallpaper = onOpenWallpaperPicker,
                    onAddPage = { scrollToPageSoon(deps.layout.addPage()) },
                    onDeletePage = {
                        val cur = pagerState.currentPage
                        if (deps.layout.removePage(cur)) scrollToPageSoon((cur - 1).coerceAtLeast(0))
                    },
                    onDone = { editMode = false },
                )
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val folderedKeys = layout.drawerFolders.flatMapTo(HashSet()) { it.appKeys }
            AppDrawer(
                apps = allApps.orEmpty().filter { privateRevealed || !isHidden(it) },
                folders = layout.drawerFolders.map { f ->
                    f to f.appKeys.mapNotNull { appsByKey[it] }.filter { privateRevealed || !isHidden(it) }
                },
                folderedKeys = folderedKeys,
                labelFor = { labelFor(it) },
                isHidden = { isHidden(it) },
                pickMode = drawerPickMode,
                privateRevealed = privateRevealed,
                onAppClick = { e ->
                    if (drawerPickMode) {
                        if (deps.layout.addApp(pagerState.currentPage, e.key)) toast("Added ${labelFor(e)}")
                        else toast("Already on this page")
                    } else {
                        deps.apps.launch(e)
                    }
                    drawerOpen = false
                },
                onAppLongPress = { e -> menu = drawerAppMenu(e, null) },
                onFolderClick = { f -> openFolder = FolderRef.Drawer(f.id) },
                onTogglePrivate = { if (privateRevealed) privateRevealed = false else revealPrivate() },
                onClose = { drawerOpen = false },
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
                            deps.apps.launch(e)
                            openFolder = null
                        },
                        onAppLongPress = { e ->
                            menu = MenuSpec(labelFor(e), listOf(
                                "Move to home screen" to fun() {
                                    if (!deps.layout.removeFromFolder(ref.page, folder.id, e.key, placeOnHome = true)) {
                                        toast("No space on this page")
                                    }
                                },
                                "Remove from folder" to fun() {
                                    deps.layout.removeFromFolder(ref.page, folder.id, e.key, placeOnHome = false)
                                },
                                "App info" to fun() { deps.apps.openAppInfo(e) },
                            ))
                        },
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
                            deps.apps.launch(e)
                            openFolder = null
                            drawerOpen = false
                        },
                        onAppLongPress = { e -> menu = drawerAppMenu(e, folder) },
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
                    onRequestWidget(info, pagerState.currentPage)
                },
                onDismiss = { widgetPickerOpen = false },
            )
        }

        menu?.let { spec -> ActionMenu(spec, onDismiss = { menu = null }) }
        prompt?.let { spec -> TextPrompt(spec, onDismiss = { prompt = null }) }
    }
}

/** A live Android widget. In edit mode a transparent layer on top takes the taps instead. */
@Composable
private fun WidgetCell(
    appWidgetId: Int,
    widgets: WidgetHostManager,
    editMode: Boolean,
    onEditTap: () -> Unit,
) {
    val view = remember(appWidgetId) { widgets.viewFor(appWidgetId) }
    BoxWithConstraints(Modifier.fillMaxSize().jiggle(editMode)) {
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
            val widthDp = maxWidth.value
            val heightDp = maxHeight.value
            LaunchedEffect(view, widthDp, heightDp) {
                // Lets responsive widgets pick the right layout for their cell size.
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            }
            AndroidView(
                factory = { _ ->
                    // The same view object is reused across screens, so detach it first.
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (editMode) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onEditTap)
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
        val geometry = GridGeometry.of(config, w, h, with(density) { config.gutterDp.dp.toPx() })
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
        TextButton(onClick = onDone) { Text("Done") }
    }
}

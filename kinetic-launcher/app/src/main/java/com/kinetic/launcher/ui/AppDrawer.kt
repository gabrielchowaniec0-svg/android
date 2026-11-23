@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.apps.SquircleShape
import com.kinetic.core.home.DrawerFolder

// Same as One UI on the S25 Ultra: 4 columns × 6 rows per page, ~101dp per row.
private const val DRAWER_COLUMNS = 4
private const val DRAWER_ROWS = 6
private val DRAWER_CELL_HEIGHT = 101.dp
private val DRAWER_ICON = 60.dp
private val DrawerText = Color(0xFFE3E8F4)
private val DrawerSubtle = Color(0xFF9AA6C2)
private val PillColor = Color(0xE6151924)

/** One slot in the app drawer: an app or a drawer folder. */
sealed interface DrawerEntry {
    val key: String

    data class App(val app: AppEntry) : DrawerEntry {
        override val key: String get() = "app:" + app.key
    }

    data class Folder(val folder: DrawerFolder, val members: List<AppEntry>) : DrawerEntry {
        override val key: String get() = "folder:" + folder.id
    }
}

/**
 * One UI–style app drawer: no solid panel (the wallpaper softens behind it),
 * a 4 × 6 grid in horizontal pages with page dots, a Search pill at the bottom
 * and a ⋮ menu at the top. Dragging down anywhere follows the finger and closes it.
 *
 * [sections] are runs of entries; each starts on a new page (One UI lets you leave
 * a page part-empty). [horizontalMargin] matches the home grid so columns line up.
 */
@Composable
fun AppDrawer(
    sections: List<List<DrawerEntry>>,
    horizontalMargin: Dp,
    /** Drawer open fraction (0..1), read during drawing for the row cascade. */
    progress: () -> Float,
    searchable: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    isHidden: (AppEntry) -> Boolean,
    pickMode: Boolean,
    privateRevealed: Boolean,
    customOrder: Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onFolderClick: (DrawerFolder) -> Unit,
    onTogglePrivate: () -> Unit,
    onSortAlphabetical: () -> Unit,
    onUseMyLayout: () -> Unit,
    onDrag: (Float) -> Unit,
    /** True when the app has notifications (draws the spinning ring). */
    hasNotification: (AppEntry) -> Boolean = { false },
    /** Opens Android's Notification access page for Kinetic. */
    onNotificationRings: () -> Unit = {},
    /** Explains and switches on the Dynamic Island. */
    onDynamicIsland: () -> Unit = {},
    /** Opens Kinetic's Settings screen. */
    onSettings: () -> Unit = {},
    /** Package of the installed Chowaniec app, if any (search opens its pages there). */
    chowaniecPackage: String? = null,
    /** Called after search opened something outside Kinetic (a contact, a setting…). */
    onLeave: () -> Unit = {},
    /** Called with the release velocity (px/s, positive = downward). */
    onDragEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    Box(
        modifier
            .fillMaxSize()
            // Vertical drags anywhere (that nothing scrollable claimed) move the drawer.
            .pointerInput(Unit) {
                val velocity = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { velocity.resetTracking() },
                    onDragEnd = { currentOnDragEnd(velocity.calculateVelocity().y) },
                    onDragCancel = { currentOnDragEnd(0f) },
                ) { change, dragAmount ->
                    velocity.addPointerInputChange(change)
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            }
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (searching) {
            SearchPanel(
                query = query,
                onQueryChange = { query = it },
                results = rankApps(searchable, query, labelFor),
                labelFor = labelFor,
                isHidden = isHidden,
                hasNotification = hasNotification,
                onAppClick = onAppClick,
                onAppLongPress = onAppLongPress,
                horizontalMargin = horizontalMargin,
                chowaniecPackage = chowaniecPackage,
                onLeave = {
                    searching = false
                    query = ""
                    onLeave()
                },
                onCancel = {
                    searching = false
                    query = ""
                },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                DrawerTopBar(
                    pickMode = pickMode,
                    privateRevealed = privateRevealed,
                    customOrder = customOrder,
                    onTogglePrivate = onTogglePrivate,
                    onSortAlphabetical = onSortAlphabetical,
                    onUseMyLayout = onUseMyLayout,
                    onNotificationRings = onNotificationRings,
                    onDynamicIsland = onDynamicIsland,
                    onSettings = onSettings,
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Leave room for the page dots under the grid.
                    val cellHeight = minOf(DRAWER_CELL_HEIGHT, (maxHeight - 28.dp) / DRAWER_ROWS)
                    val pages = remember(sections) {
                        sections.flatMap { it.chunked(DRAWER_ROWS * DRAWER_COLUMNS) }.ifEmpty { listOf(emptyList()) }
                    }
                    val pagerState = rememberPagerState(pageCount = { pages.size })
                    Column(Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.15f),
                        ) { pageIndex ->
                            DrawerPage(
                                entries = pages.getOrNull(pageIndex).orEmpty(),
                                cellHeight = cellHeight,
                                horizontalMargin = horizontalMargin,
                                progress = progress,
                                labelFor = labelFor,
                                isHidden = isHidden,
                                onAppClick = onAppClick,
                                onAppLongPress = onAppLongPress,
                                onFolderClick = onFolderClick,
                                hasNotification = hasNotification,
                            )
                        }
                        if (pages.size > 1) DrawerDots(count = pages.size, current = pagerState.currentPage)
                    }
                }
                SearchPill(onClick = { searching = true })
            }
        }
    }
}

@Composable
private fun DrawerTopBar(
    pickMode: Boolean,
    privateRevealed: Boolean,
    customOrder: Boolean,
    onTogglePrivate: () -> Unit,
    onSortAlphabetical: () -> Unit,
    onUseMyLayout: () -> Unit,
    onNotificationRings: () -> Unit,
    onDynamicIsland: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                pickMode -> "Tap an app to add it to the home screen"
                privateRevealed -> "Showing hidden apps"
                else -> ""
            },
            color = DrawerSubtle,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Box {
            Text(
                "⋮",
                color = DrawerText,
                fontSize = 22.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { menuOpen = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (privateRevealed) "Hide hidden apps" else "Show hidden apps") },
                    onClick = {
                        menuOpen = false
                        onTogglePrivate()
                    },
                )
                if (customOrder) {
                    DropdownMenuItem(
                        text = { Text("Sort A–Z") },
                        onClick = {
                            menuOpen = false
                            onSortAlphabetical()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Notification rings…") },
                    onClick = {
                        menuOpen = false
                        onNotificationRings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Kinetic settings") },
                    onClick = {
                        menuOpen = false
                        onSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Dynamic Island…") },
                    onClick = {
                        menuOpen = false
                        onDynamicIsland()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Reset to my phone's layout") },
                    onClick = {
                        menuOpen = false
                        onUseMyLayout()
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerPage(
    entries: List<DrawerEntry>,
    cellHeight: Dp,
    horizontalMargin: Dp,
    progress: () -> Float,
    labelFor: (AppEntry) -> String,
    isHidden: (AppEntry) -> Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onFolderClick: (DrawerFolder) -> Unit,
    hasNotification: (AppEntry) -> Boolean,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = horizontalMargin)) {
        entries.chunked(DRAWER_COLUMNS).forEachIndexed { rowIndex, rowEntries ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(cellHeight)
                    // Rows rise into place one after another as the drawer opens.
                    .graphicsLayer {
                        val rowProgress = ((progress() - rowIndex * 0.06f) / 0.64f).coerceIn(0f, 1f)
                        translationY = (1f - rowProgress) * 56.dp.toPx()
                        alpha = rowProgress
                    }
            ) {
                rowEntries.forEach { entry ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (entry) {
                            is DrawerEntry.App -> AppTile(
                                icon = entry.app.icon,
                                label = labelFor(entry.app),
                                iconSize = DRAWER_ICON,
                                dimmed = isHidden(entry.app),
                                ring = hasNotification(entry.app),
                                onClick = { onAppClick(entry.app) },
                                onLongClick = { onAppLongPress(entry.app) },
                            )
                            is DrawerEntry.Folder -> FolderTile(
                                name = entry.folder.name,
                                icons = entry.members.map { it.icon },
                                tileSize = DRAWER_ICON,
                                color = if (entry.folder.color != 0) Color(entry.folder.color) else DefaultFolderColor,
                                ring = entry.members.any(hasNotification),
                                onClick = { onFolderClick(entry.folder) },
                            )
                        }
                    }
                }
                repeat(DRAWER_COLUMNS - rowEntries.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DrawerDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.4f))
            )
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth(0.62f)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(PillColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph(Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Search", color = DrawerText, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SearchGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.11f
        drawCircle(
            color = DrawerText,
            radius = s * 0.3f,
            center = Offset(s * 0.42f, s * 0.42f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = DrawerText,
            start = Offset(s * 0.64f, s * 0.64f),
            end = Offset(s * 0.92f, s * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Apps whose name starts with the query first, then word starts, then anywhere. */
private fun rankApps(apps: List<AppEntry>, query: String, labelFor: (AppEntry) -> String): List<AppEntry> {
    val q = query.trim()
    if (q.isEmpty()) return apps
    return apps.mapNotNull { e ->
        val label = labelFor(e)
        val score = when {
            label.startsWith(q, ignoreCase = true) -> 0
            label.split(' ', '-', '.').any { it.startsWith(q, ignoreCase = true) } -> 1
            label.contains(q, ignoreCase = true) -> 2
            else -> return@mapNotNull null
        }
        score to e
    }.sortedWith(compareBy({ it.first }, { labelFor(it.second).lowercase() })).map { it.second }
}

/**
 * Smart search: apps as icons, then quick maths, Chowaniec pages, contacts,
 * Settings pages and a web search — all from one box.
 */
@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    isHidden: (AppEntry) -> Boolean,
    hasNotification: (AppEntry) -> Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    horizontalMargin: Dp,
    chowaniecPackage: String?,
    onLeave: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    var contactsAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsAllowed = granted
    }

    val q = query.trim()
    val calc = remember(q) { SmartSearch.calculate(q) }
    val chowPages = remember(q, chowaniecPackage) {
        if (chowaniecPackage == null) emptyList() else SmartSearch.matchChowaniec(q)
    }
    val settings = remember(q) { SmartSearch.matchSettings(q) }
    val contacts by produceState(initialValue = emptyList<SmartSearch.Contact>(), q, contactsAllowed) {
        value = emptyList()
        if (!contactsAllowed || q.length < 2) return@produceState
        delay(120) // wait for typing to settle
        value = withContext(Dispatchers.IO) { SmartSearch.contacts(context, q) }
    }
    val apps = if (q.isEmpty()) results else results.take(12)

    fun leaveIf(ok: Boolean) {
        if (ok) onLeave()
    }

    /** Enter: open the top app (the highlighted one), otherwise search the web. */
    fun submit() {
        val top = apps.firstOrNull()
        when {
            q.isEmpty() -> Unit
            top != null -> onAppClick(top)
            else -> leaveIf(SmartSearch.webSearch(context, q))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search apps, contacts, maths", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PillColor,
                    unfocusedContainerColor = PillColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                leadingIcon = { SearchGlyph(Modifier.size(20.dp)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Enter (whatever the keyboard calls it) opens the highlighted best match.
                keyboardActions = KeyboardActions(
                    onSearch = { submit() },
                    onDone = { submit() },
                    onGo = { submit() },
                    onSend = { submit() },
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(DRAWER_COLUMNS),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = horizontalMargin, end = horizontalMargin, bottom = 24.dp),
        ) {
            if (calc != null) {
                item(key = "calc", span = { GridItemSpan(maxLineSpan) }) {
                    SearchRow(
                        badge = "=",
                        title = calc,
                        subtitle = "Tap to copy",
                        large = true,
                        onClick = {
                            clipboard.setText(AnnotatedString(calc.replace(",", "")))
                            Toast.makeText(context, "Copied $calc", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            itemsIndexed(apps, key = { _, e -> e.key }) { index, e ->
                // The app Enter will open gets a soft highlight.
                val best = q.isNotEmpty() && index == 0
                Box(
                    Modifier
                        .height(DRAWER_CELL_HEIGHT)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (best) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                ) {
                    AppTile(
                        icon = e.icon,
                        label = labelFor(e),
                        iconSize = DRAWER_ICON,
                        dimmed = isHidden(e),
                        ring = hasNotification(e),
                        onClick = { onAppClick(e) },
                        onLongClick = { onAppLongPress(e) },
                    )
                }
            }

            if (q.isNotEmpty()) {
                if (chowPages.isNotEmpty()) {
                    header("chow-h", "Chowaniec")
                    items(chowPages, key = { "chow:" + it.path }, span = { GridItemSpan(maxLineSpan) }) { page ->
                        SearchRow(badge = "CH", title = page.title, subtitle = "chowaniec.net" + page.path) {
                            leaveIf(SmartSearch.openChowaniec(context, page.path, chowaniecPackage))
                        }
                    }
                }

                if (contacts.isNotEmpty()) {
                    header("contacts-h", "Contacts")
                    items(contacts, key = { "contact:" + it.lookupUri }, span = { GridItemSpan(maxLineSpan) }) { c ->
                        SearchRow(badge = c.name.take(1).uppercase(), title = c.name, round = true) {
                            leaveIf(SmartSearch.openContact(context, c))
                        }
                    }
                } else if (!contactsAllowed && q.length >= 2) {
                    item(key = "contacts-ask", span = { GridItemSpan(maxLineSpan) }) {
                        SearchRow(badge = "＋", title = "Search contacts too", subtitle = "Allow access to your contacts", round = true) {
                            askContacts.launch(Manifest.permission.READ_CONTACTS)
                        }
                    }
                }

                if (settings.isNotEmpty()) {
                    header("settings-h", "Settings")
                    items(settings, key = { "setting:" + it.title }, span = { GridItemSpan(maxLineSpan) }) { page ->
                        SearchRow(badge = "⚙", title = page.title, subtitle = "Settings") {
                            leaveIf(SmartSearch.openSettings(context, page))
                        }
                    }
                }

                header("more-h", "Search")
                if (chowaniecPackage != null) {
                    item(key = "chow-search", span = { GridItemSpan(maxLineSpan) }) {
                        SearchRow(badge = "CH", title = "Search Chowaniec for “$q”") {
                            leaveIf(SmartSearch.openChowaniec(context, SmartSearch.chowaniecSearchPath(q), chowaniecPackage))
                        }
                    }
                }
                item(key = "web", span = { GridItemSpan(maxLineSpan) }) {
                    SearchRow(badge = "G", title = "Search the web for “$q”", round = true) {
                        leaveIf(SmartSearch.webSearch(context, q))
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.header(key: String, title: String) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title,
            color = DrawerSubtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
        )
    }
}

/** One full-width result row with a small wallpaper-tinted badge. */
@Composable
private fun SearchRow(
    badge: String,
    title: String,
    subtitle: String? = null,
    round: Boolean = false,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PillColor.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (large) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(if (round) CircleShape else SquircleShape)
                .background(
                    Brush.linearGradient(listOf(WallpaperPalette.Light, WallpaperPalette.Deep))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge, color = WallpaperPalette.Glint, fontSize = if (badge.length > 1) 12.sp else 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = DrawerText,
                fontSize = if (large) 24.sp else 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, color = DrawerSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

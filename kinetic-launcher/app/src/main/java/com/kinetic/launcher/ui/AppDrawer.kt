@file:OptIn(ExperimentalFoundationApi::class)

package com.kinetic.launcher.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.home.DrawerFolder

private const val DRAWER_COLUMNS = 4
private val DRAWER_CELL_HEIGHT = 112.dp
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
 * a 4-column grid in horizontal pages with page dots, a Search pill at the bottom
 * and a ⋮ menu at the top. Dragging down anywhere follows the finger and closes it.
 */
@Composable
fun AppDrawer(
    entries: List<DrawerEntry>,
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
    onImportLayout: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
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
                detectVerticalDragGestures(
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                ) { change, dragAmount ->
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
                results = searchable.filter { query.isBlank() || labelFor(it).contains(query.trim(), ignoreCase = true) },
                labelFor = labelFor,
                isHidden = isHidden,
                onAppClick = onAppClick,
                onAppLongPress = onAppLongPress,
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
                    onImportLayout = onImportLayout,
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val rows = (maxHeight / DRAWER_CELL_HEIGHT).toInt().coerceAtLeast(3)
                    val pages = remember(entries, rows) {
                        entries.chunked(rows * DRAWER_COLUMNS).ifEmpty { listOf(emptyList()) }
                    }
                    val pagerState = rememberPagerState(pageCount = { pages.size })
                    Column(Modifier.fillMaxSize()) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { pageIndex ->
                            DrawerPage(
                                entries = pages.getOrNull(pageIndex).orEmpty(),
                                labelFor = labelFor,
                                isHidden = isHidden,
                                onAppClick = onAppClick,
                                onAppLongPress = onAppLongPress,
                                onFolderClick = onFolderClick,
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
    onImportLayout: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
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
                    text = { Text("Import layout from One UI Home") },
                    onClick = {
                        menuOpen = false
                        onImportLayout()
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerPage(
    entries: List<DrawerEntry>,
    labelFor: (AppEntry) -> String,
    isHidden: (AppEntry) -> Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onFolderClick: (DrawerFolder) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        entries.chunked(DRAWER_COLUMNS).forEach { rowEntries ->
            Row(Modifier.fillMaxWidth().height(DRAWER_CELL_HEIGHT)) {
                rowEntries.forEach { entry ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (entry) {
                            is DrawerEntry.App -> AppTile(
                                icon = entry.app.icon,
                                label = labelFor(entry.app),
                                iconSize = 58.dp,
                                dimmed = isHidden(entry.app),
                                onClick = { onAppClick(entry.app) },
                                onLongClick = { onAppLongPress(entry.app) },
                            )
                            is DrawerEntry.Folder -> FolderTile(
                                name = entry.folder.name,
                                icons = entry.members.map { it.icon },
                                tileSize = 58.dp,
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

@Composable
private fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    isHidden: (AppEntry) -> Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
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
                placeholder = { Text("Search") },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PillColor,
                    unfocusedContainerColor = PillColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                leadingIcon = { SearchGlyph(Modifier.size(20.dp)) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (results.isEmpty()) {
            Text("No apps found", color = DrawerSubtle, modifier = Modifier.padding(24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(DRAWER_COLUMNS),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                items(results, key = { it.key }) { e ->
                    Box(Modifier.height(DRAWER_CELL_HEIGHT)) {
                        AppTile(
                            icon = e.icon,
                            label = labelFor(e),
                            iconSize = 58.dp,
                            dimmed = isHidden(e),
                            onClick = { onAppClick(e) },
                            onLongClick = { onAppLongPress(e) },
                        )
                    }
                }
            }
        }
    }
}

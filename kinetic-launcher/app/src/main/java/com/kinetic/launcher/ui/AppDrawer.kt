package com.kinetic.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.home.DrawerFolder

/**
 * All apps, alphabetical, with search and drawer folders at the top.
 * Swipe down (from the handle, or past the top of the list) or press Back to close.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    folders: List<Pair<DrawerFolder, List<AppEntry>>>,
    folderedKeys: Set<String>,
    labelFor: (AppEntry) -> String,
    isHidden: (AppEntry) -> Boolean,
    pickMode: Boolean,
    privateRevealed: Boolean,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onFolderClick: (DrawerFolder) -> Unit,
    onTogglePrivate: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val closeThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val currentOnClose by rememberUpdatedState(onClose)

    // Pulling down past the top of the list closes the drawer.
    val closeOnPull = remember(closeThresholdPx) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled > closeThresholdPx) {
                        pulled = 0f
                        currentOnClose()
                    }
                } else if (consumed.y != 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    val q = query.trim()
    val shownFolders = if (q.isEmpty()) folders else emptyList()
    val shownApps = if (q.isEmpty()) apps.filter { it.key !in folderedKeys }
    else apps.filter { labelFor(it).contains(q, ignoreCase = true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2070A12))
            .systemBarsPadding()
            .imePadding(),
    ) {
        // Drag handle
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total > closeThresholdPx / 2) currentOnClose() },
                    ) { change, dragAmount ->
                        change.consume()
                        total += dragAmount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }

        if (pickMode) {
            Text(
                "Tap an app to add it to the home screen",
                color = Color(0xFF9AA6C2),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onTogglePrivate) {
                Text(if (privateRevealed) "Hide hidden" else "Hidden")
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(closeOnPull),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            items(shownFolders, key = { "folder:" + it.first.id }) { (folder, members) ->
                Box(Modifier.height(100.dp)) {
                    FolderTile(name = folder.name, icons = members.map { it.icon }, onClick = { onFolderClick(folder) })
                }
            }
            items(shownApps, key = { "app:" + it.key }) { e ->
                Box(Modifier.height(100.dp)) {
                    AppTile(
                        icon = e.icon,
                        label = labelFor(e),
                        dimmed = isHidden(e),
                        onClick = { onAppClick(e) },
                        onLongClick = { onAppLongPress(e) },
                    )
                }
            }
        }
    }
}

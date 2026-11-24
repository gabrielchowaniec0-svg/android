package com.kinetic.launcher.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.core.apps.AppEntry

/**
 * App Library (like iPhone): every app sorted into groups automatically. Each group is a
 * card: tap one of its big icons to open that app, or the little grid to see the whole
 * group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppLibrary(
    apps: List<AppEntry>,
    horizontalMargin: Dp,
    labelFor: (AppEntry) -> String,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    hasNotification: (AppEntry) -> Boolean,
) {
    val groups = remember(apps) { groupApps(apps, labelFor) }
    var open by remember { mutableStateOf<Pair<String, List<AppEntry>>?>(null) }
    val shown = open
    if (shown != null) {
        BackHandler { open = null }
        Column(Modifier.fillMaxSize().padding(horizontal = horizontalMargin)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹  ${shown.first}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { open = null })
            }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown.second, key = { it.key }) { e ->
                    Box(Modifier.height(104.dp)) {
                        AppTile(
                            icon = e.icon,
                            label = labelFor(e),
                            iconSize = 58.dp,
                            ring = hasNotification(e),
                            badgePackage = e.packageName,
                            onClick = { onAppClick(e) },
                            onLongClick = { onAppLongPress(e) },
                        )
                    }
                }
            }
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = horizontalMargin, end = horizontalMargin, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(groups, key = { it.first }) { (name, list) ->
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(0, 2).forEach { start ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (i in start until start + 2) {
                                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                                        val lastSlot = i == 3 && list.size > 4
                                        if (lastSlot) {
                                            // The rest of the group as a tiny 2×2, tap to open the group.
                                            MiniGrid(list.drop(3).take(4)) { open = name to list }
                                        } else {
                                            list.getOrNull(i)?.let { e ->
                                                Image(
                                                    e.icon, labelFor(e),
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .combinedClickable(onClick = { onAppClick(e) }, onLongClick = { onAppLongPress(e) }),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    name, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { open = name to list },
                )
            }
        }
    }
}

@Composable
private fun MiniGrid(apps: List<AppEntry>, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().clickable(onClick = onClick).padding(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0, 2).forEach { start ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in start until start + 2) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        apps.getOrNull(i)?.let { Image(it.icon, null, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))) }
                    }
                }
            }
        }
    }
}

/** Sorts apps into groups by what they are (from the app's own category, or its name). */
private fun groupApps(apps: List<AppEntry>, labelFor: (AppEntry) -> String): List<Pair<String, List<AppEntry>>> {
    fun guess(e: AppEntry): String {
        val p = e.packageName.lowercase()
        val l = labelFor(e).lowercase()
        fun has(vararg w: String) = w.any { p.contains(it) || l.contains(it) }
        if (p == "com.kinetic.launcher" || p.startsWith("com.kinetic.")) return "Kinetic"
        when (e.category) {
            ApplicationInfo.CATEGORY_GAME -> return "Games"
            ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
            ApplicationInfo.CATEGORY_AUDIO -> return "Music & Audio"
            ApplicationInfo.CATEGORY_VIDEO -> return "Entertainment"
            ApplicationInfo.CATEGORY_IMAGE -> return "Photos & Creativity"
            ApplicationInfo.CATEGORY_NEWS -> return "News & Reading"
            ApplicationInfo.CATEGORY_MAPS -> return "Travel"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "Productivity"
        }
        return when {
            has("message", "chat", "whatsapp", "telegram", "discord", "snapchat", "instagram", "facebook", "messenger", "tiktok", "twitter", "reddit", "signal") -> "Social"
            has("game", "roblox", "minecraft") -> "Games"
            has("music", "spotify", "podcast", "radio", "audio", "sound") -> "Music & Audio"
            has("video", "youtube", "netflix", "tv", "disney", "hulu", "prime", "twitch", "movie") -> "Entertainment"
            has("camera", "photo", "gallery", "draw", "paint", "pen", "edit", "lightroom", "canva") -> "Photos & Creativity"
            has("map", "uber", "lyft", "airline", "united", "flight", "travel", "transit", "waze") -> "Travel"
            has("bank", "pay", "wallet", "money", "finance", "cash", "venmo") -> "Finance"
            has("shop", "amazon", "store", "ebay", "target", "walmart", "doordash", "food") -> "Shopping & Food"
            has("mail", "calendar", "docs", "drive", "sheets", "office", "word", "excel", "notes", "keep", "slack", "teams", "zoom", "classroom", "claude") -> "Productivity"
            has("news", "read", "book", "kindle") -> "News & Reading"
            has("health", "fit", "workout", "sleep") -> "Health & Fitness"
            has("setting", "clock", "calculator", "file", "phone", "contacts", "samsung", "android", "google", "weather") -> "Utilities"
            else -> "Other"
        }
    }
    return apps.groupBy(::guess)
        .map { (k, v) -> k to v.sortedBy { labelFor(it).lowercase() } }
        .sortedWith(compareBy({ it.first == "Other" }, { -it.second.size }))
}

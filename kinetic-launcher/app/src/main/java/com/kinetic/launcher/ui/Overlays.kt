package com.kinetic.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.kinetic.core.apps.AppEntry
import com.kinetic.core.widgets.WidgetHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val PanelColor = Color(0xFF141B2D)
private val TextColor = Color(0xFFE3E8F4)
private val SubtleText = Color(0xFF9AA6C2)

/** A titled list of actions shown as a dialog. */
data class MenuSpec(val title: String, val actions: List<Pair<String, () -> Unit>>)

/** A single-line text prompt (folder names, renaming apps). */
data class PromptSpec(val title: String, val initial: String, val onConfirm: (String) -> Unit)

@Composable
fun ActionMenu(spec: MenuSpec, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(spec.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                spec.actions.forEach { (label, action) ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            action()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
    )
}

@Composable
fun TextPrompt(spec: PromptSpec, onDismiss: () -> Unit) {
    var text by remember(spec) { mutableStateOf(spec.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                spec.onConfirm(text.trim())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An open folder: a floating panel of app icons over a dimmed background. */
@Composable
fun FolderOverlay(
    name: String,
    apps: List<AppEntry>,
    labelFor: (AppEntry) -> String,
    onAppClick: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(PanelColor)
                // swallow taps so tapping inside the panel doesn't close it
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
        ) {
            Text(
                name,
                color = TextColor,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRename)
                    .padding(8.dp),
            )
            if (apps.isEmpty()) {
                Text("This folder is empty", color = SubtleText, modifier = Modifier.padding(16.dp))
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 440.dp)) {
                    items(apps, key = { it.key }) { e ->
                        Box(Modifier.height(96.dp)) {
                            AppTile(
                                icon = e.icon,
                                label = labelFor(e),
                                onClick = { onAppClick(e) },
                                onLongClick = { onAppLongPress(e) },
                            )
                        }
                    }
                }
            }
            Text(
                "Tap the name to rename · long-press an app for options",
                color = SubtleText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

private data class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appName: String,
    val span: Pair<Int, Int>,
)

/** Full-screen list of every widget installed on the phone. */
@Composable
fun WidgetPicker(
    widgets: WidgetHostManager,
    columns: Int,
    rows: Int,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices by produceState<List<WidgetChoice>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            widgets.providers()
                .map { WidgetChoice(it, widgets.label(it), widgets.appName(it), widgets.defaultSpan(it, columns, rows)) }
                .sortedWith(compareBy({ it.appName.lowercase() }, { it.label.lowercase() }))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2070A12))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add a widget", color = TextColor, fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        val list = choices
        when {
            list == null -> Text("Loading widgets…", color = SubtleText, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text("No widgets installed", color = SubtleText, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { "${it.info.provider.flattenToShortString()}@${it.info.profile.hashCode()}" }) { choice ->
                    WidgetRow(widgets, choice.info, choice.label, choice.appName, choice.span) { onPick(choice.info) }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(
    widgets: WidgetHostManager,
    info: AppWidgetProviderInfo,
    label: String,
    appName: String,
    span: Pair<Int, Int>,
    onClick: () -> Unit,
) {
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                widgets.preview(info)?.let { d ->
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
                    val scale = min(1f, 480f / max(w, h))
                    d.toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                        .asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 104.dp, height = 76.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(6.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = TextColor, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$appName · ${span.first} × ${span.second}", color = SubtleText, fontSize = 12.sp, maxLines = 1)
        }
    }
}

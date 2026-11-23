package com.kinetic.launcher

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.launcher.ui.FocusGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Sheet = Color(0xF2121726)
private val Tile = Color(0xFF1C2234)
private val Subtle = Color(0xFF8C93A6)

/**
 * The Focus picker: a glass sheet of big tiles (tap to switch a Focus on or off),
 * each with its own options. Opened from the quick-settings tile, the app list ⋮
 * menu or Kinetic settings.
 */
class FocusActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusModes.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var editing by remember { mutableStateOf<String?>(null) }
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(shown, enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } + fadeIn()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .navigationBarsPadding()
                                .clip(RoundedCornerShape(34.dp))
                                .background(Sheet)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                                .padding(18.dp),
                        ) {
                            val id = editing
                            if (id == null) Picker(onEdit = { editing = it }) else Editor(id, onDone = { editing = null })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Picker(onEdit: (String) -> Unit) {
        val modes by FocusModes.modes.collectAsState()
        val active by FocusModes.active.collectAsState()
        Text("Focus", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Tap to turn on or off · hold to customise", color = Subtle, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            modes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { m ->
                        val on = active?.id == m.id
                        val fill by animateFloatAsState(if (on) 1f else 0f, label = "on")
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Tile)
                                .background(Color(m.color).copy(alpha = 0.85f * fill))
                                .combinedClickableCompat(
                                    onClick = { FocusModes.toggle(this@FocusActivity, m.id) },
                                    onLongClick = { onEdit(m.id) },
                                )
                                .padding(16.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (on) Color.White else Color(m.color).copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                FocusGlyph(m.glyph, if (on) Color(m.color) else Color(m.color), Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(m.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (on) "On" else "Off", color = if (on) Color.White.copy(alpha = 0.85f) else Subtle, fontSize = 13.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (!FocusModes.canSilence(this)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Allow Kinetic to switch Do Not Disturb ›",
                color = Color(0xFF8FA6D8),
                fontSize = 14.sp,
                modifier = Modifier.clickable { FocusModes.openSilencePermission(this) }.padding(vertical = 6.dp),
            )
        }
    }

    @Composable
    private fun Editor(id: String, onDone: () -> Unit) {
        val modes by FocusModes.modes.collectAsState()
        val m = modes.firstOrNull { it.id == id } ?: return
        fun save(n: FocusModes.Mode) = FocusModes.update(this, n)
        val apps by produceState(initialValue = emptyList<Pair<String, String>>()) {
            value = withContext(Dispatchers.Default) {
                val la = getSystemService(LauncherApps::class.java)
                la.getActivityList(null, Process.myUserHandle())
                    .map { it.applicationInfo.packageName to it.label.toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }
        }
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FocusGlyph(m.glyph, Color(m.color), Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(m.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Done", color = Color(0xFF8FA6D8), fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDone).padding(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Option("Silence notifications", "Turns on Do Not Disturb", m.silence) { save(m.copy(silence = it)) }
            Option("Let every app through", "Only change the look and home page", m.allowAll) { save(m.copy(allowAll = it)) }

            // ---- schedule (like iOS: turn on at a time, on chosen days) ----
            Text("Schedule", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Option("Turn on automatically", "At the times and days below", m.schedule.enabled) {
                save(m.copy(schedule = m.schedule.copy(enabled = it)))
            }
            if (m.schedule.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    TimeChip("From", m.schedule.startMin, Color(m.color)) { save(m.copy(schedule = m.schedule.copy(startMin = it))) }
                    TimeChip("To", m.schedule.endMin, Color(m.color)) { save(m.copy(schedule = m.schedule.copy(endMin = it))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                        val on = m.schedule.onDay(i)
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (on) Color(m.color) else Tile)
                                .clickable { save(m.copy(schedule = m.schedule.copy(days = m.schedule.days xor (1 shl i)))) },
                            contentAlignment = Alignment.Center,
                        ) { Text(d, color = if (on) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) save(m.copy(carTrigger = true))
            }
            Option("When connected to car Bluetooth", "Turns on when your car connects, off when it disconnects", m.carTrigger) { on ->
                if (on && ContextCompat.checkSelfPermission(this@FocusActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    save(m.copy(carTrigger = on))
                }
            }

            Text("Dim wallpaper", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            var dim by remember(m.wallpaperDim) { mutableFloatStateOf(m.wallpaperDim) }
            Slider(
                value = dim,
                onValueChange = { dim = it },
                onValueChangeFinished = { save(m.copy(wallpaperDim = dim)) },
                valueRange = 0f..0.8f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(m.color)),
            )

            Text("Home page", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1, 0, 1, 2, 3).forEach { page ->
                    val sel = m.homePage == page
                    Text(
                        if (page < 0) "Stay" else "${page + 1}",
                        color = if (sel) Color.Black else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sel) Color(m.color) else Tile)
                            .clickable { save(m.copy(homePage = page)) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            if (!m.allowAll) {
                Text("Allowed apps", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Only these can show rings and pop down from the island", color = Subtle, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                apps.forEach { (pkg, label) ->
                    val checked = pkg in m.allowedApps
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { save(m.copy(allowedApps = if (checked) m.allowedApps - pkg else m.allowedApps + pkg)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { save(m.copy(allowedApps = if (it) m.allowedApps + pkg else m.allowedApps - pkg)) },
                            colors = CheckboxDefaults.colors(checkedColor = Color(m.color)),
                        )
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
    }

    /** A time you tap to change (Android's clock picker). */
    @Composable
    private fun TimeChip(label: String, minuteOfDay: Int, color: Color, onPick: (Int) -> Unit) {
        val h = minuteOfDay / 60
        val min = minuteOfDay % 60
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Tile)
                .clickable {
                    TimePickerDialog(this@FocusActivity, { _, hh, mm -> onPick(hh * 60 + mm) }, h, min, false).show()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(label, color = Subtle, fontSize = 12.sp)
            Text(
                "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, min, if (h < 12) "AM" else "PM"),
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun Option(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp)
                Text(subtitle, color = Subtle, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF8FA6D8)))
        }
    }

    companion object {
        fun open(context: android.content.Context) {
            context.startActivity(Intent(context, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** combinedClickable without the opt-in noise at every call site. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

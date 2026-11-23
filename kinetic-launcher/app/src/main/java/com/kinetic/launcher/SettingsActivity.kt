package com.kinetic.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.launcher.island.DynamicIslandService
import com.kinetic.wallpaper.WallpaperSignals
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B0F1A)
private val Card = Color(0xFF151B2B)
private val Accent = Color(0xFF8FA6D8)
private val Subtle = Color(0xFF8C93A6)
private val Good = Color(0xFF30D158)
private val Warn = Color(0xFFFF9F0A)

/** Kinetic's settings: Dynamic Island, home screen, wallpaper reactions and permissions. */
class SettingsActivity : ComponentActivity() {

    /** Bumped on every resume so permission statuses refresh after visiting Android settings. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KineticSettings.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                SettingsScreen(resumeTick.intValue, onBack = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    @Composable
    private fun SettingsScreen(tick: Int, onBack: () -> Unit) {
        val s by KineticSettings.values.collectAsState()
        val islandOn = remember(tick) { DynamicIslandService.isEnabled(this) }
        val notificationsOn = remember(tick) { NotificationHub.isEnabled(this) }
        val isHome = remember(tick) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == packageName
        }
        fun bool(key: String) = { v: Boolean -> KineticSettings.setBoolean(this, key, v) }

        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 30.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 12.dp),
                )
                Text("Kinetic settings", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            // ---- permissions -----------------------------------------------------------
            Section("Setup") {
                LinkRow(
                    "Default Home app",
                    if (isHome) "Kinetic is your Home app" else "Needed for app shortcuts and the home button",
                    on = isHome,
                ) { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                LinkRow(
                    "Notification access",
                    if (notificationsOn) "On — rings, music, calls, timers, directions" else "Needed for rings and most island features",
                    on = notificationsOn,
                ) { NotificationHub.openSettings(this@SettingsActivity) }
                LinkRow(
                    "Dynamic Island",
                    if (islandOn) "On" else "Off — turn on \"Kinetic Dynamic Island\" in Accessibility",
                    on = islandOn,
                ) { DynamicIslandService.openSettings(this@SettingsActivity) }
            }

            // ---- island -------------------------------------------------------------------
            Section("Dynamic Island") {
                SwitchRow("Show resting pill", "The black pill around the camera when nothing's going on", s.islandRestingPill, bool(KineticSettings.ISLAND_RESTING))
                SwitchRow("Chowaniec messages", "New Chowaniec messages drop down from the island, with Reply", s.islandChowaniecPeek, bool(KineticSettings.ISLAND_CHOWANIEC))
                SwitchRow("Messages from all apps", "Same for every messaging app", s.islandAllMessagesPeek, bool(KineticSettings.ISLAND_ALL_MESSAGES))
                SwitchRow("Timers, stopwatch & alarms", "Countdown in orange; alarms ring with Snooze / Stop", s.islandTimers, bool(KineticSettings.ISLAND_TIMERS))
                SwitchRow("Directions", "Next turn and distance from Google Maps", s.islandNavigation, bool(KineticSettings.ISLAND_NAVIGATION))
                SwitchRow("Lock animation", "A padlock that opens when you unlock", s.islandUnlock, bool(KineticSettings.ISLAND_UNLOCK))
                SliderRow(
                    "Shrink paused music after",
                    "${s.islandMinimalSeconds} s",
                    s.islandMinimalSeconds.toFloat(),
                    3f..60f,
                ) { KineticSettings.setInt(this@SettingsActivity, KineticSettings.ISLAND_MINIMAL_SECONDS, it.roundToInt()) }
                SliderRow(
                    "Gap above the island",
                    "${s.islandGapDp} dp (the pill gets slimmer so it stays centred on the camera)",
                    s.islandGapDp.toFloat(),
                    0f..7f,
                    steps = 6,
                ) { KineticSettings.setInt(this@SettingsActivity, KineticSettings.ISLAND_GAP, it.roundToInt()) }
            }

            // ---- home ---------------------------------------------------------------------
            Section("Home screen") {
                SwitchRow("Notification rings", "A spinning ring around apps with notifications", s.notificationRings, bool(KineticSettings.RINGS))
                SwitchRow("Haptics", "Little vibrations for the island and edit mode", s.haptics, bool(KineticSettings.HAPTICS))
                SliderRow(
                    "Icon size",
                    "${s.iconSizeDp} dp",
                    s.iconSizeDp.toFloat(),
                    50f..70f,
                    steps = 9,
                ) { KineticSettings.setInt(this@SettingsActivity, KineticSettings.ICON_SIZE, it.roundToInt()) }
                Text(
                    "Smart stacks: in edit mode, tap a widget ▸ Add widget to stack…",
                    color = Subtle,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            // ---- wallpaper ----------------------------------------------------------------
            Section("Wallpaper reactions") {
                SwitchRow("Music glow", "Ribbons pick up the album's colours while music plays", s.wpMusicGlow, bool(WallpaperSignals.KEY_MUSIC_GLOW))
                SwitchRow("Unlock ripple", "A wave spreads from the middle when you unlock", s.wpUnlockRipple, bool(WallpaperSignals.KEY_UNLOCK_RIPPLE))
                SwitchRow("App ripples", "Opening an app ripples out from its icon", s.wpAppRipples, bool(WallpaperSignals.KEY_APP_RIPPLES))
                SwitchRow("Notification pulse", "A soft pulse from the top when something arrives", s.wpNotificationPulse, bool(WallpaperSignals.KEY_NOTIFICATION_PULSE))
                SwitchRow("Charging wave", "A green wave rises when you plug in", s.wpChargingWave, bool(WallpaperSignals.KEY_CHARGING_WAVE))
                SwitchRow("Calm on low battery", "Slower and dimmer below 20% or in Battery Saver", s.wpBatteryCalm, bool(WallpaperSignals.KEY_BATTERY_CALM))
                SwitchRow("Icons ride the waves", "Home icons hop as a ripple passes them", s.wpIconWave, bool(WallpaperSignals.KEY_ICON_WAVE))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Subtle, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    // Follow the finger locally; save when it's let go.
    var live by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Subtle, fontSize = 13.sp)
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onChange(live) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent),
        )
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Good else Warn))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Subtle, fontSize = 13.sp)
        }
        Text("›", color = Subtle, fontSize = 24.sp)
    }
}

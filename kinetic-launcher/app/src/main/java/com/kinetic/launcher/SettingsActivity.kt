package com.kinetic.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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

    @Deprecated("Simple permission result for the living wallpaper's location")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            LivingWallpaper.refreshNow(this)
            recreate()
        }
    }

    /** Bumped on every resume so permission statuses refresh after visiting Android settings. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KineticSettings.init(this)
        FocusModes.init(this)
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

    private fun textDialog(title: String, message: String, hint: String, start: String, multiline: Boolean = false, onSave: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            this.hint = hint
            setText(start)
            if (multiline) {
                minLines = 3
                gravity = android.view.Gravity.TOP
            } else {
                setSingleLine()
            }
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(box)
            .setPositiveButton("Save") { _, _ -> onSave(field.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** S Pen shapes: pick a shape, type the command it runs. */
    private fun editShapes() {
        val shapes = com.kinetic.launcher.pen.PenShapes.names
        val glyph = mapOf("Heart" to "♥", "Circle" to "○", "Check" to "✓", "Star" to "★", "Triangle" to "△")
        val items = shapes.map { s ->
            val c = com.kinetic.launcher.pen.PenShapes.command(this, s)
            "${glyph[s]}  $s  →  " + c.ifBlank { "nothing" }
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Draw a shape to…")
            .setItems(items) { _, i ->
                val s = shapes[i]
                textDialog(
                    "${glyph[s]} $s", "Type any Kinetic command, e.g. \"call mom\", \"open spotify\", \"camera\". Leave empty to turn it off.",
                    "call mom", com.kinetic.launcher.pen.PenShapes.command(this, s),
                ) { com.kinetic.launcher.pen.PenShapes.setCommand(this, s, it) }
            }
            .setNegativeButton("Done", null).show()
    }

    /** Command chains: a name that runs several commands. */
    private fun editChains() {
        val chains = com.kinetic.launcher.pen.CommandChains.all(this)
        val names = chains.keys.toList()
        val items = (names.map { "$it  (${chains[it]?.size ?: 0} steps)" } + "＋ New chain…").toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Command chains")
            .setItems(items) { _, i ->
                if (i == names.size) {
                    newChain("", "")
                } else {
                    val n = names[i]
                    android.app.AlertDialog.Builder(this).setTitle(n)
                        .setMessage(chains[n].orEmpty().joinToString("\n"))
                        .setPositiveButton("Edit") { _, _ -> newChain(n, chains[n].orEmpty().joinToString("\n")) }
                        .setNeutralButton("Delete") { _, _ -> com.kinetic.launcher.pen.CommandChains.remove(this, n) }
                        .setNegativeButton("Close", null).show()
                }
            }
            .setNegativeButton("Done", null).show()
    }

    private fun newChain(name: String, lines: String) {
        textDialog("Chain name", "What you'll write or type to run it, e.g. \"gaming mode\".", "gaming mode", name) { n ->
            if (n.isBlank()) return@textDialog
            textDialog(
                "Commands for \u201C$n\u201D", "One command per line, run in order. For example:\nfocus 60 min\nopen roblox",
                "focus 60 min\nopen roblox", lines, multiline = true,
            ) { l ->
                if (n != name && name.isNotBlank()) com.kinetic.launcher.pen.CommandChains.remove(this, name)
                com.kinetic.launcher.pen.CommandChains.put(this, n, l)
            }
        }
    }

    /** Writing shortcuts: "m" → "call mom". */
    private fun editShortcuts() {
        val all = com.kinetic.launcher.ui.Commands3.shortcuts(this)
        val keys = all.keys.toList()
        val items = (keys.map { "$it  →  ${all[it]}" } + "＋ New shortcut…").toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Writing shortcuts")
            .setItems(items) { _, i ->
                if (i == keys.size) {
                    newShortcut("", "")
                } else {
                    val k = keys[i]
                    android.app.AlertDialog.Builder(this).setTitle(k).setMessage(all[k])
                        .setPositiveButton("Edit") { _, _ -> newShortcut(k, all[k].orEmpty()) }
                        .setNeutralButton("Delete") { _, _ -> com.kinetic.launcher.ui.Commands3.setShortcut(this, k, "") }
                        .setNegativeButton("Close", null).show()
                }
            }
            .setNegativeButton("Done", null).show()
    }

    private fun newShortcut(short: String, command: String) {
        textDialog("Shortcut", "What you'll write, e.g. \"m\" or \"home\".", "m", short) { k ->
            if (k.isBlank()) return@textDialog
            textDialog("\u201C$k\u201D means…", "The full command, e.g. \"call mom\" or \"route to home\".", "call mom", command) { c ->
                if (k != short && short.isNotBlank()) com.kinetic.launcher.ui.Commands3.setShortcut(this, short, "")
                com.kinetic.launcher.ui.Commands3.setShortcut(this, k.trim(), c.trim())
            }
        }
    }

    /** "My teams" for live scores in the island. */
    private fun editTeams() {
        val field = android.widget.EditText(this).apply {
            hint = "Bears, Cubs, Bulls"
            setText(KineticSettings.current.myTeams)
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("My teams")
            .setMessage("Only these teams' games show in the island. Leave empty for every game.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> KineticSettings.setString(this, KineticSettings.MY_TEAMS, field.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Pick which app a shake opens. */
    private fun pickShakeApp() {
        val la = getSystemService(android.content.pm.LauncherApps::class.java)
        val apps = la.getActivityList(null, android.os.Process.myUserHandle())
            .map { it.applicationInfo.packageName to it.label.toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        android.app.AlertDialog.Builder(this)
            .setTitle("Shake opens…")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                KineticSettings.setString(this, KineticSettings.SHAKE_APP, apps[i].first)
                KineticSettings.setString(this, KineticSettings.SHAKE_ACTION, "app")
            }
            .show()
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
                val usageOn = remember(tick) { com.kinetic.launcher.ui.KineticCommands.hasUsageAccess(this@SettingsActivity) }
                LinkRow(
                    "Usage access (optional)",
                    if (usageOn) "On — Commands use the app you use most" else "Lets Commands pick the app you use most for calls, music, maps…",
                    on = usageOn,
                ) { com.kinetic.launcher.ui.KineticCommands.openUsageAccess(this@SettingsActivity) }
                LinkRow(
                    "Dynamic Island",
                    if (islandOn) "On" else "Off — turn on \"Kinetic Dynamic Island\" in Accessibility",
                    on = islandOn,
                ) { DynamicIslandService.openSettings(this@SettingsActivity) }
            }

            // ---- updates ------------------------------------------------------------------------
            Section("Updates") { UpdatesSection() }

            // ---- focus & wallpaper ------------------------------------------------------------
            Section("Personalise") {
                val focus by FocusModes.active.collectAsState()
                LinkRow("Focus", focus?.let { "${it.name} is on" } ?: "Sleep, School, Gaming… silence and calm things down", on = focus != null) {
                    FocusActivity.open(this@SettingsActivity)
                }
                LinkRow("Wallpaper", "Styles, colours, speed, grain and detail", on = true) {
                    startActivity(Intent(this@SettingsActivity, WallpaperStudioActivity::class.java))
                }
            }

            // ---- privacy ------------------------------------------------------------------
            Section("Privacy") {
                val hasCode = com.kinetic.launcher.vault.VaultCrypto.isSetUp(this@SettingsActivity)
                LinkRow("Vault", "Encrypted photos & videos, opened with your Kinetic code", on = hasCode) {
                    com.kinetic.launcher.vault.VaultActivity.open(this@SettingsActivity)
                }
                SwitchRow(
                    "Secure screenshot",
                    "Volume Up, Up, Down, Down: the screen goes straight into the Vault, never the gallery (needs the Dynamic Island switch)",
                    s.secureScreenshot,
                    bool(KineticSettings.SECURE_SCREENSHOT),
                )
                SwitchRow("Intruder photo", "After 3 wrong codes, the front camera quietly photographs whoever tried, into the Vault", s.intruderPhoto, bool(KineticSettings.INTRUDER))
                if (hasCode) {
                    val hasDecoy = com.kinetic.launcher.vault.VaultCrypto.hasDecoy(this@SettingsActivity)
                    LinkRow("Decoy code", if (hasDecoy) "On · opens a harmless fake Vault. Tap to change" else "A second code that opens a fake, empty Vault", on = hasDecoy) {
                        com.kinetic.launcher.vault.VaultActivity.decoySetup(this@SettingsActivity)
                    }
                    if (hasDecoy) {
                        LinkRow("Remove decoy code", "Deletes the fake Vault and its code", on = false) {
                            com.kinetic.launcher.vault.VaultCrypto.removeDecoy(this@SettingsActivity)
                            android.widget.Toast.makeText(this@SettingsActivity, "Decoy code removed", android.widget.Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    }
                    LinkRow("Change Kinetic code", "The PIN or pattern for the Vault and hidden apps", on = false) {
                        com.kinetic.launcher.vault.VaultActivity.changeCode(this@SettingsActivity)
                    }
                }
            }

            // ---- island -------------------------------------------------------------------
            Section("Dynamic Island") {
                SwitchRow("Show resting pill", "The black pill around the camera when nothing's going on", s.islandRestingPill, bool(KineticSettings.ISLAND_RESTING))
                SwitchRow("Chowaniec messages", "New Chowaniec messages drop down from the island, with Reply", s.islandChowaniecPeek, bool(KineticSettings.ISLAND_CHOWANIEC))
                SwitchRow("Messages from all apps", "Same for every messaging app", s.islandAllMessagesPeek, bool(KineticSettings.ISLAND_ALL_MESSAGES))
                SwitchRow("All notifications", "Every notification shows in the island: pull down or hold for its buttons", s.islandNotifications, bool(KineticSettings.ISLAND_NOTIFICATIONS))
                SwitchRow("Progress bars", "Downloads, uploads and other progress show in the island", s.islandProgress, bool(KineticSettings.ISLAND_PROGRESS))
                SwitchRow("Kinetic status bar", "Kinetic's own time, battery and signal around the island. Hide Samsung's first: Good Lock → QuickStar", s.statusBar, bool(KineticSettings.STATUS_BAR))
                if (s.statusBar) ChoiceRow(
                    "Status bar text",
                    listOf("auto" to "Auto", "light" to "White", "dark" to "Black"),
                    s.statusBarTint,
                ) { KineticSettings.setString(this@SettingsActivity, KineticSettings.STATUS_BAR_TINT, it) }
                SwitchRow("Volume slider", "Volume buttons show a slider in the island instead of One UI's", s.islandVolume, bool(KineticSettings.ISLAND_VOLUME))
                SwitchRow("Brightness slider", "Changing brightness shows it in the island (when auto-brightness is off)", s.islandBrightness, bool(KineticSettings.ISLAND_BRIGHTNESS))
                SwitchRow("Up next", "Your next calendar event counts down 15 minutes before", s.islandUpNext, bool(KineticSettings.ISLAND_UP_NEXT))
                SwitchRow("Rain alerts", "\u201CRain in 15 min\u201D when it's about to rain where you are", s.islandRain, bool(KineticSettings.ISLAND_RAIN))
                SwitchRow("Earbuds & headphones", "Show when they connect, with their battery", s.islandEarbuds, bool(KineticSettings.ISLAND_EARBUDS))
                SwitchRow("Music glow", "A soft glow in the album's colours while music plays", s.islandMusicGlow, bool(KineticSettings.ISLAND_MUSIC_GLOW))
                ChoiceRow(
                    "Island look",
                    listOf("black" to "Black", "glass" to "Glass", "outline" to "Outline"),
                    s.islandTheme,
                ) { KineticSettings.setString(this@SettingsActivity, KineticSettings.ISLAND_THEME, it) }
                LinkRow("My teams", s.myTeams.ifBlank { "Every game · tap to pick your teams" }, on = s.myTeams.isNotBlank()) { editTeams() }
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
                SwitchRow("StandBy", "Charging and turned sideways: a big clock, photos or calendar (red at night)", s.standBy, bool(KineticSettings.STANDBY))
                SwitchRow("Number badges", "A red count on apps with unread notifications", s.badges, bool(KineticSettings.BADGES))
                SwitchRow("\u201CNew\u201D folder", "Newly installed apps wait in a New folder in the app list for a week", s.newAppsFolder, bool(KineticSettings.NEW_APPS_FOLDER))
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

            // ---- looks & gestures ----------------------------------------------------------
            Section("Icons & pages") {
                ChoiceRow(
                    "Icon style",
                    listOf("default" to "Normal", "tinted" to "Tinted", "glass" to "Glass", "mono" to "Mono"),
                    s.iconStyle,
                ) { KineticSettings.setString(this@SettingsActivity, KineticSettings.ICON_STYLE, it) }
                ChoiceRow(
                    "Page transition",
                    listOf("depth" to "Depth", "flat" to "Flat", "cube" to "Cube", "carousel" to "Carousel", "fade" to "Fade"),
                    s.pageTransition,
                ) { KineticSettings.setString(this@SettingsActivity, KineticSettings.PAGE_TRANSITION, it) }
            }
            Section("Gestures") {
                SwitchRow("Double-tap to lock", "Double-tap empty wallpaper to turn the screen off (needs the Dynamic Island switch)", s.doubleTapLock, bool(KineticSettings.DOUBLE_TAP_LOCK))
                SwitchRow("Multi-finger shortcuts", "Two fingers down: Focus · three-finger tap: search & Commands", s.multiFinger, bool(KineticSettings.MULTI_FINGER))
                ChoiceRow(
                    "Shake to…",
                    listOf("off" to "Off", "flashlight" to "Flashlight", "camera" to "Camera", "search" to "Search", "focus" to "Focus", "app" to "An app…"),
                    s.shakeAction,
                ) { choice ->
                    if (choice == "app") pickShakeApp() else KineticSettings.setString(this@SettingsActivity, KineticSettings.SHAKE_ACTION, choice)
                }
                if (s.shakeAction == "app" && s.shakeApp.isNotBlank()) {
                    Text(
                        "Opens " + runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.shakeApp, 0)) }.getOrDefault(s.shakeApp),
                        color = Subtle,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
                    )
                }
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

            // ---- living wallpaper ---------------------------------------------------------
            Section("Living wallpaper") {
                SwitchRow(
                    "Rain when it's raining",
                    if (LivingWallpaper.hasLocation(this@SettingsActivity)) "Streaks fall and colours cool when it rains where you are"
                    else "Needs your rough location (tap to allow)",
                    s.wpWeatherRain && LivingWallpaper.hasLocation(this@SettingsActivity),
                ) { on ->
                    KineticSettings.setBoolean(this@SettingsActivity, WallpaperSignals.KEY_WEATHER_RAIN, on)
                    if (on && !LivingWallpaper.hasLocation(this@SettingsActivity)) {
                        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION), 41)
                    }
                }
                SwitchRow("Snow, fog & wind", "Snowflakes, a rising haze, and rain that slants with the wind", s.wpWeatherExtra, bool(WallpaperSignals.KEY_WEATHER_EXTRA))
                SwitchRow("Sunset & moon", "Warmer colours at sunrise and sunset; at night, the real moon in its current phase", s.wpSky, bool(WallpaperSignals.KEY_SKY))
                SwitchRow("Battery tide", "A glowing waterline: high tide when full, green while charging, red when low", s.wpBatteryTide, bool(WallpaperSignals.KEY_BATTERY_TIDE))
                SwitchRow("Fireflies", "One firefly drifts for each app with unread notifications", s.wpFireflies, bool(WallpaperSignals.KEY_FIREFLIES))
            }

            // ---- S Pen ---------------------------------------------------------------------
            Section("S Pen") {
                SwitchRow("Write on the home screen", "Write \"mom\", \"timer 10 min\" or an app's name anywhere with the S Pen", s.penInk, bool(KineticSettings.PEN_INK))
                LinkRow("Shapes", "Draw a heart, circle, check, star or triangle to run a command", on = false) { editShapes() }
                LinkRow("All commands", "Everything you can write, with Try buttons", on = false) { startActivity(Intent(this@SettingsActivity, com.kinetic.launcher.ui.HelpActivity::class.java)) }
                LinkRow("Command chains", "One name runs several commands (also works typed in search)", on = false) { editChains() }
                LinkRow("Writing shortcuts", "Short words for long commands (\u201Cm\u201D → call mom)", on = false) { editShortcuts() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Version, "Check now", and a one-tap install when GitHub has a newer build. */
@Composable
private fun SettingsActivity.UpdatesSection() {
    val state by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updater.check(this@UpdatesSection, force = false, minIntervalMs = 10 * 60_000L) }
    val installed = remember { Updater.installedCode(this) }
    val (subtitle, action) = when (val st = state) {
        Updater.State.Idle, Updater.State.UpToDate -> "Build $installed · up to date" to "Check now"
        Updater.State.Checking -> "Checking GitHub…" to null
        is Updater.State.Available -> "Build ${st.release.code} is ready (you have $installed)" to "Update"
        is Updater.State.Downloading -> "Downloading… ${(st.progress * 100).toInt()}%" to null
        is Updater.State.Installing -> "Installing — confirm Android's prompt" to null
        is Updater.State.Failed -> st.message to "Try again"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Kinetic updates", color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Subtle, fontSize = 13.sp)
        }
        if (action != null) {
            Text(
                action,
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (state is Updater.State.Available) Good else Accent)
                    .clickable {
                        scope.launch {
                            val st = state
                            if (st is Updater.State.Available) Updater.downloadAndInstall(this@UpdatesSection, st.release)
                            else Updater.check(this@UpdatesSection, force = true)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    val st = state
    if (st is Updater.State.Available && st.release.notes.isNotBlank()) {
        Text(
            st.release.notes.take(400),
            color = Subtle,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
    if (!Updater.canInstall(this)) {
        Text(
            "First time: allow Kinetic to install updates ›",
            color = Accent,
            fontSize = 13.sp,
            modifier = Modifier.clickable { Updater.openInstallPermission(this) }.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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

/** A row of pill choices. */
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Row(
            Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (id, label) ->
                val on = id == selected
                Text(
                    label,
                    color = if (on) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) Accent else Color(0xFF232A3D))
                        .clickable { onSelect(id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
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

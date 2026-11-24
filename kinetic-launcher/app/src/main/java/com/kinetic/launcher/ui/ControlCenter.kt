package com.kinetic.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Control Center (like iPhone): swipe down from the top-right corner of the home screen.
 * Connectivity, music, flashlight, Focus, quick buttons, and brightness/volume sliders.
 * Tap outside or swipe up to close.
 */
@Composable
fun ControlCenter(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 500f)) }
    fun close() {
        scope.launch {
            enter.animateTo(0f, spring(stiffness = 900f))
            onDismiss()
        }
    }
    BackHandler { close() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value }
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -80f) close() }) { c, dy ->
                    total += dy
                    c.consume()
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .graphicsLayer {
                    val s = 0.92f + 0.08f * enter.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                }
                // Taps on the panel itself don't close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectivityCard(Modifier.weight(1f).aspectRatio(1f))
                MusicCard(Modifier.weight(1f).aspectRatio(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TorchButton(Modifier.weight(1f))
                RoundButton("☾", "Focus", Modifier.weight(1f)) { com.kinetic.launcher.FocusActivity.open(context) }
                RoundButton("◷", "Timer", Modifier.weight(1f)) {
                    open(context, Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS))
                }
                RoundButton("◉", "Camera", Modifier.weight(1f)) {
                    open(context, Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton("±", "Calculator", Modifier.weight(1f)) {
                    open(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
                }
                RoundButton("⬚", "Screenshot", Modifier.weight(1f)) {
                    onDismiss()
                    com.kinetic.launcher.island.DynamicIslandService.screenshotLater(500)
                }
                RoundButton("🔒", "Vault", Modifier.weight(1f)) { com.kinetic.launcher.vault.VaultActivity.open(context) }
                RoundButton("⚙", "Settings", Modifier.weight(1f)) { open(context, Intent(Settings.ACTION_SETTINGS)) }
            }
            Row(Modifier.height(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BrightnessSlider(Modifier.weight(1f).fillMaxHeight())
                VolumeSlider(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    WideButton("Sound & vibration", Modifier.weight(1f)) { open(context, Intent(Settings.Panel.ACTION_VOLUME)) }
                    WideButton("Kinetic settings", Modifier.weight(1f)) { open(context, Intent(context, com.kinetic.launcher.SettingsActivity::class.java)) }
                }
            }
        }
    }
}

private val Tile = Color(0xCC2C2C2E)
private val Blue = Color(0xFF0A84FF)

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun ConnectivityCard(modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(Tile).padding(14.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Circle("✈", Color(0xFFFF9F0A), Modifier.weight(1f)) { open(context, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            Circle("⇅", Color(0xFF30D158), Modifier.weight(1f)) { open(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Circle("◠", Blue, Modifier.weight(1f)) { open(context, Intent(Settings.Panel.ACTION_WIFI)) }
            Circle("ᛒ", Blue, Modifier.weight(1f)) { open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }
}

@Composable
private fun Circle(glyph: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(glyph, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MusicCard(modifier: Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            controller = runCatching {
                context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(ComponentName(context, com.kinetic.launcher.KineticNotificationListener::class.java))
                    .firstOrNull()
            }.getOrNull()
            tick++
            delay(1000)
        }
    }
    val c = controller
    val md = c?.metadata
    val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
    val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
    val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(Tile).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(title ?: "Not playing", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (artist != null) Text(artist, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Text("⏮", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable { c?.transportControls?.skipToPrevious() })
            Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable {
                if (playing) c?.transportControls?.pause() else c?.transportControls?.play()
            })
            Text("⏭", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable { c?.transportControls?.skipToNext() })
        }
    }
}

@Composable
private fun TorchButton(modifier: Modifier) {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(CameraManager::class.java) }
    val id = remember {
        runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
    }
    var on by remember { mutableStateOf(false) }
    DisposableEffect(id) {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) on = enabled
            }
        }
        cm.registerTorchCallback(cb, null)
        onDispose { cm.unregisterTorchCallback(cb) }
    }
    RoundButton("⚡", "Flashlight", modifier, active = on) {
        id?.let { runCatching { cm.setTorchMode(it, !on) } }
    }
}

@Composable
private fun RoundButton(glyph: String, label: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (active) Color.White else Tile).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (active) Color.Black else Color.White, fontSize = 24.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun WideButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Tile).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A tall slider like iPhone's (drag up/down). */
@Composable
private fun TallSlider(glyph: String, value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val current by androidx.compose.runtime.rememberUpdatedState(value)
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Tile)
            .pointerInput(Unit) {
                detectDragGestures { c, drag ->
                    c.consume()
                    change((current - drag.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * value).background(Color.White))
        Text(glyph, color = if (value > 0.15f) Color.Black else Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}

@Composable
private fun BrightnessSlider(modifier: Modifier) {
    val context = LocalContext.current
    val read = { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }
    var value by remember { mutableFloatStateOf(runCatching { read() }.getOrDefault(0.5f)) }
    var asked by remember { mutableStateOf(false) }
    TallSlider("☀", value, modifier) { v ->
        if (!Settings.System.canWrite(context)) {
            // Android asks once for "Modify system settings".
            if (!asked) {
                asked = true
                open(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
            }
            return@TallSlider
        }
        value = v
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (v * 255).toInt().coerceIn(1, 255)) }
    }
}

@Composable
private fun VolumeSlider(modifier: Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var value by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    TallSlider("🔊", value, modifier) { v ->
        value = v
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0) }
    }
}

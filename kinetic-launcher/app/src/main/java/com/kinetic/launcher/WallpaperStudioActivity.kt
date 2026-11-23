package com.kinetic.launcher

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.wallpaper.FluidWallpaperService
import com.kinetic.wallpaper.WallpaperSignals
import com.kinetic.wallpaper.WallpaperStyles
import com.kinetic.wallpaper.WallpaperStyles.Look
import com.kinetic.wallpaper.WallpaperStyles.Style

private val Bg = Color(0xFF07090F)
private val Card = Color(0xFF131827)
private val Subtle = Color(0xFF8C93A6)

/**
 * Wallpaper studio: pick a style (smooth, abstract or rough), colours, speed,
 * brightness, grain and detail, with a live preview. Changes apply to the live
 * wallpaper straight away.
 */
class WallpaperStudioActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = WallpaperSignals.prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var look by remember { mutableStateOf(WallpaperStyles.load(prefs)) }
                fun update(l: Look) {
                    look = l
                    WallpaperStyles.save(prefs, l)
                }
                Studio(look, ::update)
            }
        }
    }

    private fun isKineticWallpaper(): Boolean =
        WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName

    private fun setAsWallpaper() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, FluidWallpaperService::class.java),
        )
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    @Composable
    private fun Studio(look: Look, update: (Look) -> Unit) {
        val active = remember { isKineticWallpaper() }
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 30.sp,
                    modifier = Modifier.clip(CircleShape).clickable { finish() }.padding(horizontal = 12.dp),
                )
                Text("Wallpaper", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            // Live preview, phone-shaped. Tap it to see a ripple.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WallpaperPreview(
                    look,
                    interactive = true,
                    modifier = Modifier
                        .width(220.dp)
                        .aspectRatio(9f / 19.5f)
                        .clip(RoundedCornerShape(34.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(34.dp)),
                )
            }
            Text(
                "Tap the preview to see a ripple. Changes apply to your wallpaper right away.",
                color = Subtle,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (!active) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF2E4266))
                        .clickable { setAsWallpaper() }
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Set as wallpaper", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }

            Header("Style")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Style.entries) { st ->
                    val selected = st == look.style
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        WallpaperPreview(
                            look.copy(style = st),
                            interactive = false,
                            modifier = Modifier
                                .width(78.dp)
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    if (selected) 2.5.dp else 1.dp,
                                    if (selected) Color.White else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(16.dp),
                                )
                                .clickable { update(look.copy(style = st)) },
                        )
                        Text(st.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Text(st.feel, color = Subtle, fontSize = 11.sp)
                    }
                }
            }

            Header("Colours")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(WallpaperStyles.palettes) { pal ->
                    val selected = pal.main == look.main && pal.deep == look.deep && pal.glint == look.glint
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(pal.glint), Color(pal.main), Color(pal.deep))))
                                .border(if (selected) 2.5.dp else 0.dp, Color.White, CircleShape)
                                .clickable { update(look.copy(deep = pal.deep, main = pal.main, glint = pal.glint)) },
                        )
                        Text(pal.name, color = Subtle, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Custom colour: one hue slider builds a matching set of three.
            val hue = remember(look.main) {
                val hsv = FloatArray(3)
                AndroidColor.colorToHSV(look.main, hsv)
                hsv[0]
            }
            SliderCard("Custom colour", "Slide to pick your own", hue, 0f..360f, hueTrack = true) { h ->
                update(look.copy(deep = hsv(h, 0.6f, 0.10f), main = hsv(h, 0.55f, 0.45f), glint = hsv((h + 18f) % 360f, 0.4f, 0.95f)))
            }

            Header("Feel")
            SliderCard("Speed", "%.1f×".format(look.speed), look.speed, 0.2f..2f) { update(look.copy(speed = it)) }
            SliderCard("Brightness", "${(look.intensity * 100).toInt()}%", look.intensity, 0.5f..1.6f) { update(look.copy(intensity = it)) }
            SliderCard("Grain", "${(look.grain * 100).toInt()}%", look.grain, 0f..1f) { update(look.copy(grain = it)) }
            SliderCard("Detail", "%.1f×".format(look.detail), look.detail, 0.6f..1.8f) { update(look.copy(detail = it)) }

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Card)
                    .clickable { update(Look()) }
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Reset to default", color = Color.White, fontSize = 15.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }

    private fun hsv(h: Float, s: Float, v: Float) = AndroidColor.HSVToColor(floatArrayOf(h, s, v))
}

@Composable
private fun Header(text: String) {
    Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
}

@Composable
private fun SliderCard(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hueTrack: Boolean = false,
    onChange: (Float) -> Unit,
) {
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Subtle, fontSize = 13.sp)
        }
        if (hueTrack) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.7f, 0.9f) }))
            )
        }
        Slider(
            value = live,
            onValueChange = {
                live = it
                onChange(it)
            },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF8FA6D8)),
        )
    }
}

/**
 * Draws a wallpaper style with the real shader, animated. With [interactive], a tap
 * sends a ripple across it.
 */
@Composable
fun WallpaperPreview(look: Look, interactive: Boolean, modifier: Modifier = Modifier) {
    val shader = remember(look.style) { WallpaperStyles.shaderFor(look.style).also { WallpaperStyles.applyDefaults(it) } }
    val brush = remember(shader) { ShaderBrush(shader) }
    var time by remember { mutableFloatStateOf(0f) }
    var ripple by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    LaunchedEffect(look.speed) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f * look.speed
                last = now
            }
        }
    }
    Canvas(
        modifier.then(
            if (interactive) Modifier.pointerInput(Unit) {
                detectTapGestures { pos -> ripple = Triple(pos.x, pos.y, time) }
            } else Modifier
        )
    ) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        shader.setFloatUniform("iTime", time)
        WallpaperStyles.applyLook(shader, look)
        val r = ripple
        if (r != null) {
            shader.setFloatUniform("iR0", (r.first - size.width / 2f) / size.height, (r.second - size.height / 2f) / size.height, r.third, 1.2f)
            shader.setFloatUniform("iRC0", AndroidColor.red(look.glint) / 255f, AndroidColor.green(look.glint) / 255f, AndroidColor.blue(look.glint) / 255f)
        }
        drawRect(brush)
    }
}

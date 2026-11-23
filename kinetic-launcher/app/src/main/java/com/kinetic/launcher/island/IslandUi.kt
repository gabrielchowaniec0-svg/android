package com.kinetic.launcher.island

import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.launcher.NotificationHub
import kotlinx.coroutines.delay

// Sizes follow Apple's Dynamic Island (iPhone 15 Pro: a 126 × 37 pt resting pill,
// compact leading/trailing views either side of it, a 44 pt corner radius when
// expanded), scaled to the S25 Ultra.
private val RestW = 120.dp
private val PillH = 35.dp
private val CompactW = 240.dp
private val ChargingW = 290.dp
private val ExpandedMediaH = 172.dp
private val ExpandedCallH = 86.dp
private val ExpandedRadius = 44.dp

private val IslandBlack = Color(0xFF000000)
private val Grey = Color(0xFF8E8E93) // iOS secondary label
private val Green = Color(0xFF30D158) // iOS system green (dark)
private val Red = Color(0xFFFF453A) // iOS system red (dark)
private val Orange = Color(0xFFFF9F0A)

private sealed interface IslandMode {
    data object Idle : IslandMode
    data class Alert(val alert: IslandAlert) : IslandMode
    data object MediaCompact : IslandMode
    data object MediaExpanded : IslandMode
    data object CallCompact : IslandMode
    data object CallExpanded : IslandMode
}

/**
 * The island itself: an absolutely black pill around the camera that morphs between
 * shapes with a springy, slightly bouncy motion, like iOS.
 *
 * [onWindowSize] tells the host how big its overlay window must be (it's kept only
 * as big as the island so it never blocks touches elsewhere).
 */
@Composable
fun DynamicIsland(
    model: IslandModel,
    screenWidth: Dp,
    top: Dp,
    onWindowSize: (width: Dp, height: Dp) -> Unit,
    onHaptic: () -> Unit,
    /** False while the island is just resting, so swipes there still pull down the shade. */
    onTouchable: (Boolean) -> Unit = {},
) {
    val media by model.media.collectAsState()
    val call by model.call.collectAsState()
    val alert by model.alert.collectAsState()
    val expanded by model.expanded.collectAsState()

    val c = call
    val mode: IslandMode = when {
        c != null && (c.incoming || expanded) -> IslandMode.CallExpanded
        alert != null -> IslandMode.Alert(alert!!)
        c != null -> IslandMode.CallCompact
        media != null && expanded -> IslandMode.MediaExpanded
        media != null -> IslandMode.MediaCompact
        else -> IslandMode.Idle
    }

    val fullW = screenWidth - 20.dp
    val (targetW, targetH) = when (mode) {
        IslandMode.Idle -> RestW to PillH
        is IslandMode.Alert -> (if (mode.alert is IslandAlert.Charging) ChargingW else CompactW) to PillH
        IslandMode.MediaCompact, IslandMode.CallCompact -> CompactW to PillH
        IslandMode.MediaExpanded -> fullW to ExpandedMediaH
        IslandMode.CallExpanded -> fullW to ExpandedCallH
    }
    val targetR = if (targetH <= PillH) targetH / 2 else ExpandedRadius

    // iOS-like spring: quick, with a little overshoot when it grows.
    val morph = spring<Dp>(dampingRatio = 0.72f, stiffness = 340f)
    val w by animateDpAsState(targetW, morph, label = "w")
    val h by animateDpAsState(targetH, morph, label = "h")
    val r by animateDpAsState(targetR, morph, label = "r")

    // The window grows straight away and shrinks once the spring has settled.
    var windowW by remember { mutableStateOf(targetW) }
    var windowH by remember { mutableStateOf(targetH) }
    LaunchedEffect(targetW, targetH) {
        windowW = maxOf(windowW, targetW)
        windowH = maxOf(windowH, targetH)
        onWindowSize(windowW + 8.dp, top + windowH + 8.dp)
        delay(650)
        windowW = targetW
        windowH = targetH
        onWindowSize(windowW + 8.dp, top + windowH + 8.dp)
    }

    val touchable = mode != IslandMode.Idle
    LaunchedEffect(touchable) { onTouchable(touchable) }

    var pressed by remember { mutableStateOf(false) }
    val squish by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "squish")

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = top)
                .size(w, h)
                .graphicsLayer {
                    scaleX = squish
                    scaleY = squish
                }
                .clip(RoundedCornerShape(r))
                .background(IslandBlack)
                .pointerInput(mode) {
                    detectTapGestures(
                        onPress = {
                            if (mode != IslandMode.Idle) {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            }
                        },
                        onTap = {
                            when (mode) {
                                IslandMode.MediaCompact, IslandMode.MediaExpanded -> {
                                    model.openMediaApp()
                                    model.collapse()
                                }
                                IslandMode.CallCompact, IslandMode.CallExpanded -> model.openCall()
                                else -> Unit
                            }
                        },
                        onLongPress = {
                            if (mode == IslandMode.MediaCompact || mode == IslandMode.CallCompact) {
                                onHaptic()
                                model.expand()
                            }
                        },
                    )
                },
        ) {
            AnimatedContent(
                targetState = mode,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(320, delayMillis = 60), initialScale = 0.88f)) togetherWith
                        (fadeOut(tween(110)) + scaleOut(tween(160), targetScale = 0.92f))
                },
                label = "island",
            ) { m ->
                when (m) {
                    IslandMode.Idle -> Box(Modifier.fillMaxSize())
                    is IslandMode.Alert -> AlertContent(m.alert)
                    IslandMode.MediaCompact -> media?.let { MediaCompact(it) }
                    IslandMode.MediaExpanded -> media?.let { MediaExpanded(it, model) }
                    IslandMode.CallCompact -> c?.let { CallCompact(it) }
                    IslandMode.CallExpanded -> c?.let { CallExpanded(it, model) }
                }
            }
        }
    }
}

// ---- compact & alerts -----------------------------------------------------------------

@Composable
private fun MediaCompact(m: MediaInfo) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(m, 23.dp, 7.dp)
        Spacer(Modifier.weight(1f))
        Waveform(m.playing, m.accent, Modifier.padding(end = 6.dp).size(22.dp, 14.dp))
    }
}

@Composable
private fun CallCompact(call: NotificationHub.CallInfo) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneGlyph(Green, Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(callTime(call), color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Waveform(true, Green, Modifier.size(22.dp, 14.dp))
    }
}

@Composable
private fun AlertContent(alert: IslandAlert) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (alert) {
            is IslandAlert.Ringer -> {
                val silent = alert.mode != AudioManager.RINGER_MODE_NORMAL
                val color = if (silent) Red else Color.White
                BellGlyph(color, slashed = silent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    when (alert.mode) {
                        AudioManager.RINGER_MODE_SILENT -> "Silent"
                        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                        else -> "Ring"
                    },
                    color = if (silent) Orange else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            is IslandAlert.Charging -> {
                Text("Charging", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${alert.level}%", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                BatteryGlyph(alert.level / 100f, Green, Modifier.size(27.dp, 13.dp))
            }
        }
    }
}

// ---- expanded -------------------------------------------------------------------------

@Composable
private fun MediaExpanded(m: MediaInfo, model: IslandModel) {
    val position by produceState(m.positionNow(), m) {
        while (true) {
            value = m.positionNow()
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(m, 52.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist, color = Grey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Waveform(m.playing, m.accent, Modifier.size(28.dp, 18.dp))
        }
        Spacer(Modifier.height(14.dp))
        if (m.durationMs > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(position), color = Grey, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                ProgressBar(
                    fraction = (position.toFloat() / m.durationMs).coerceIn(0f, 1f),
                    onSeek = { f -> model.seekTo((f * m.durationMs).toLong()) },
                    modifier = Modifier.weight(1f).height(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("-" + clock((m.durationMs - position).coerceAtLeast(0)), color = Grey, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ControlButton(onClick = model::previous) { SkipGlyph(forward = false, modifier = Modifier.size(26.dp, 18.dp)) }
            Spacer(Modifier.width(36.dp))
            ControlButton(onClick = model::playPause) {
                if (m.playing) PauseGlyph(Modifier.size(22.dp)) else PlayGlyph(Modifier.size(22.dp))
            }
            Spacer(Modifier.width(36.dp))
            ControlButton(onClick = model::next) { SkipGlyph(forward = true, modifier = Modifier.size(26.dp, 18.dp)) }
        }
    }
}

@Composable
private fun CallExpanded(call: NotificationHub.CallInfo, model: IslandModel) {
    Row(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (call.incoming) "Incoming call" else callTime(call),
                color = if (call.incoming) Grey else Green,
                fontSize = 13.sp,
            )
            Text(call.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        call.actions.take(3).forEach { action ->
            val title = action.title?.toString().orEmpty()
            val lower = title.lowercase()
            val (bg, glyph) = when {
                listOf("decline", "reject", "hang", "end").any { it in lower } -> Red to "end"
                listOf("answer", "accept").any { it in lower } -> Green to "answer"
                else -> Color(0xFF3A3A3C) to "other"
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { model.callAction(action) },
                contentAlignment = Alignment.Center,
            ) {
                when (glyph) {
                    "end" -> PhoneGlyph(Color.White, Modifier.size(22.dp), hangUp = true)
                    "answer" -> PhoneGlyph(Color.White, Modifier.size(22.dp))
                    else -> Text(title.take(1).uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------

@Composable
private fun Art(m: MediaInfo, size: Dp, radius: Dp) {
    val art = m.art
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(radius)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(m.accent.copy(alpha = 0.35f)))
    }
}

/** Five bars bouncing to the music, tinted from the album art (still dots when paused). */
@Composable
private fun Waveform(playing: Boolean, color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val durations = intArrayOf(430, 560, 380, 620, 500)
    val levels = durations.map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d), RepeatMode.Reverse), label = "bar")
    }
    val amount by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "amount")
    Canvas(modifier) {
        val n = levels.size
        val barW = size.width / (n * 2 - 1)
        levels.forEachIndexed { i, level ->
            val hFrac = 0.18f + (level.value - 0.18f) * amount
            val bh = (size.height * hFrac).coerceAtLeast(barW)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * barW * 2, (size.height - bh) / 2),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier) {
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { p -> onSeek((p.x / size.width).coerceIn(0f, 1f)) }
        }
    ) {
        val barH = 5.dp.toPx()
        val y = (size.height - barH) / 2
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, barH), CornerRadius(barH / 2))
        drawRoundRect(Color.White, Offset(0f, y), Size(size.width * fraction, barH), CornerRadius(barH / 2))
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PlayGlyph(modifier: Modifier) = Canvas(modifier) {
    val p = Path().apply {
        moveTo(size.width * 0.18f, 0f)
        lineTo(size.width, size.height / 2)
        lineTo(size.width * 0.18f, size.height)
        close()
    }
    drawPath(p, Color.White)
}

@Composable
private fun PauseGlyph(modifier: Modifier) = Canvas(modifier) {
    val bw = size.width * 0.3f
    drawRoundRect(Color.White, Offset(size.width * 0.12f, 0f), Size(bw, size.height), CornerRadius(bw / 4))
    drawRoundRect(Color.White, Offset(size.width * 0.58f, 0f), Size(bw, size.height), CornerRadius(bw / 4))
}

@Composable
private fun SkipGlyph(forward: Boolean, modifier: Modifier) = Canvas(modifier) {
    fun DrawScope.tri(x: Float, w: Float) {
        val p = Path().apply {
            moveTo(x, 0f)
            lineTo(x + w, size.height / 2)
            lineTo(x, size.height)
            close()
        }
        drawPath(p, Color.White)
    }
    val half = size.width / 2
    if (forward) {
        tri(0f, half)
        tri(half, half)
    } else {
        rotate(180f) {
            tri(0f, half)
            tri(half, half)
        }
    }
}

/** A phone handset; [hangUp] turns it sideways like the red end-call button. */
@Composable
private fun PhoneGlyph(color: Color, modifier: Modifier, hangUp: Boolean = false) = Canvas(modifier) {
    val s = size.minDimension
    rotate(if (hangUp) 135f else 0f) {
        val stroke = s * 0.24f
        val inset = stroke / 2 + s * 0.08f
        drawArc(
            color = color,
            startAngle = 100f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - inset * 2, s - inset * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun BellGlyph(color: Color, slashed: Boolean, modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val bell = Path().apply {
        moveTo(w * 0.15f, h * 0.75f)
        cubicTo(w * 0.25f, h * 0.6f, w * 0.2f, h * 0.12f, w * 0.5f, h * 0.1f)
        cubicTo(w * 0.8f, h * 0.12f, w * 0.75f, h * 0.6f, w * 0.85f, h * 0.75f)
        close()
    }
    drawPath(bell, color)
    drawCircle(color, radius = w * 0.1f, center = Offset(w * 0.5f, h * 0.86f))
    if (slashed) {
        drawLine(Color.Black, Offset(w * 0.05f, h * 0.05f), Offset(w * 0.95f, h * 0.95f), strokeWidth = w * 0.22f)
        drawLine(color, Offset(w * 0.08f, h * 0.08f), Offset(w * 0.92f, h * 0.92f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

@Composable
private fun BatteryGlyph(level: Float, color: Color, modifier: Modifier) = Canvas(modifier) {
    val nub = size.width * 0.08f
    val bodyW = size.width - nub - 1.dp.toPx()
    val r = CornerRadius(size.height * 0.28f)
    drawRoundRect(color.copy(alpha = 0.35f), Offset.Zero, Size(bodyW, size.height), r)
    drawRoundRect(color, Offset.Zero, Size(bodyW * level.coerceIn(0.05f, 1f), size.height), r)
    drawRoundRect(color.copy(alpha = 0.45f), Offset(bodyW + 1.dp.toPx(), size.height * 0.3f), Size(nub, size.height * 0.4f), CornerRadius(nub / 2))
}

// ---- helpers --------------------------------------------------------------------------

@Composable
private fun callTime(call: NotificationHub.CallInfo): String {
    val started = call.startedAt ?: return if (call.incoming) "Incoming" else "Calling…"
    val now by produceState(System.currentTimeMillis(), started) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    return clock((now - started).coerceAtLeast(0))
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

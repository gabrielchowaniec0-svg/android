package com.kinetic.launcher.island

import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.VectorConverter
import com.kinetic.launcher.KineticSettings
import com.kinetic.launcher.ui.FocusGlyph
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.launcher.NotificationHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// Sizes follow Apple's Dynamic Island (a resting pill, compact leading/trailing
// views either side of it, a 44 pt corner radius when expanded), scaled to the S25
// Ultra. The pill's height comes from the service: tall enough to be centred on
// the camera hole while keeping the chosen gap above it.
private val CompactW = 206.dp
private val MinimalW = 150.dp
private val ChargingW = 290.dp
private val ExpandedMediaH = 172.dp
private val ExpandedCallH = 86.dp
private val ExpandedTimerH = 90.dp
private val ExpandedAlarmH = 100.dp
private val ExpandedNavH = 112.dp
private val PeekH = 86.dp
private val ReplyH = 138.dp
private val ExpandedRadius = 44.dp
private val BubbleGap = 6.dp

private val IslandBlack = Color(0xFF000000)
private val Grey = Color(0xFF8E8E93) // iOS secondary label
private val Green = Color(0xFF30D158) // iOS system green (dark)
private val Red = Color(0xFFFF453A) // iOS system red (dark)
private val Orange = Color(0xFFFF9F0A) // iOS system orange (timers)
private val Blue = Color(0xFF0A84FF)

/** The resting pill's height (set by the service from the camera position). */
private val LocalPill = compositionLocalOf { 35.dp }

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Things that can live in the island, most important first. */
private enum class Activity { Call, Nav, Trip, Timer, Score, Media }

private sealed interface IslandMode {
    data object Idle : IslandMode
    data class Alert(val alert: IslandAlert) : IslandMode
    data object MediaCompact : IslandMode
    data object MediaMinimal : IslandMode
    data object MediaExpanded : IslandMode
    data object CallCompact : IslandMode
    data object CallExpanded : IslandMode
    data object TimerCompact : IslandMode
    data object TimerExpanded : IslandMode
    data object AlarmExpanded : IslandMode
    data object NavCompact : IslandMode
    data object NavExpanded : IslandMode
    data object TripCompact : IslandMode
    data object TripExpanded : IslandMode
    data object ScoreCompact : IslandMode
    data object ScoreExpanded : IslandMode
    data object Peek : IslandMode
    /** A notification as a small pill: app icon + a couple of words. */
    data object PeekCompact : IslandMode
}

/**
 * The island itself: an absolutely black pill around the camera that morphs between
 * shapes with a springy, slightly bouncy motion, like iOS. A second activity (say a
 * timer while music plays) splits off into a little bubble beside it.
 *
 * [onWindowSize] tells the host how big its overlay window must be (it's kept only
 * as big as the island so it never blocks touches elsewhere).
 */
@Composable
fun DynamicIsland(
    model: IslandModel,
    screenWidth: Dp,
    top: Dp,
    pillH: Dp,
    onWindowSize: (width: Dp, height: Dp) -> Unit,
    onHaptic: () -> Unit,
    /** False while the island is just resting, so swipes there still pull down the shade. */
    onTouchable: (Boolean) -> Unit = {},
    /** Where on screen (px) the island can be touched right now, or null when it can't. */
    onTouchArea: (android.graphics.Rect?) -> Unit = {},
    /** How far the camera is from the middle of the screen. */
    centerOffset: Dp = 0.dp,
    /** True while typing a reply (the window needs the keyboard then). */
    onFocusable: (Boolean) -> Unit = {},
) {
    val settings by KineticSettings.values.collectAsState()
    val media by model.media.collectAsState()
    val look by model.mediaLook.collectAsState()
    val call by model.call.collectAsState()
    val timer by model.timer.collectAsState()
    val nav by model.nav.collectAsState()
    val trip by model.trip.collectAsState()
    val score by model.score.collectAsState()
    val privacy by model.privacy.collectAsState()
    val peek by model.peek.collectAsState()
    val replying by model.replying.collectAsState()
    val peekOpen by model.peekOpen.collectAsState()
    val alert by model.alert.collectAsState()
    val expanded by model.expanded.collectAsState()

    val c = call
    val m = media
    val t = timer
    val n = nav
    val p = peek

    val activities = buildList {
        if (c != null) add(Activity.Call)
        if (n != null) add(Activity.Nav)
        if (trip != null) add(Activity.Trip)
        if (t != null && t.kind != NotificationHub.TimerInfo.Kind.Alarm) add(Activity.Timer)
        if (score != null) add(Activity.Score)
        if (m != null && look != IslandModel.MediaLook.Hidden) add(Activity.Media)
    }
    val primary = activities.firstOrNull()
    val secondary = activities.getOrNull(1)

    val mode: IslandMode = when {
        c != null && c.incoming -> IslandMode.CallExpanded
        t != null && t.kind == NotificationHub.TimerInfo.Kind.Alarm -> IslandMode.AlarmExpanded
        p != null -> if (peekOpen || replying) IslandMode.Peek else IslandMode.PeekCompact
        alert != null -> IslandMode.Alert(alert!!)
        primary == null -> IslandMode.Idle
        expanded -> when (primary) {
            Activity.Call -> IslandMode.CallExpanded
            Activity.Nav -> IslandMode.NavExpanded
            Activity.Timer -> IslandMode.TimerExpanded
            Activity.Trip -> IslandMode.TripExpanded
            Activity.Score -> IslandMode.ScoreExpanded
            Activity.Media -> IslandMode.MediaExpanded
        }
        primary == Activity.Call -> IslandMode.CallCompact
        primary == Activity.Nav -> IslandMode.NavCompact
        primary == Activity.Timer -> IslandMode.TimerCompact
        primary == Activity.Trip -> IslandMode.TripCompact
        primary == Activity.Score -> IslandMode.ScoreCompact
        look == IslandModel.MediaLook.Minimal -> IslandMode.MediaMinimal
        else -> IslandMode.MediaCompact
    }

    val restW = maxOf(pillH * 3.4f, 84.dp)
    val fullW = screenWidth - 20.dp
    // Big shapes keep the camera's strip at the top clear: their content starts below it.
    val cameraRoom = (pillH - 10.dp).coerceAtLeast(0.dp)
    fun sizeOf(md: IslandMode): Pair<Dp, Dp> = when (md) {
        IslandMode.Idle -> restW to pillH
        is IslandMode.Alert -> when (md.alert) {
            is IslandAlert.Charging -> ChargingW to pillH
            // Face ID: a small rounded square hanging just below the camera
            // (kept short so it clears the lock-screen clock).
            is IslandAlert.Scan -> 74.dp to (pillH + 46.dp)
            is IslandAlert.Focus -> (CompactW + 24.dp) to pillH
            is IslandAlert.LowBattery -> (CompactW + 30.dp) to pillH
            is IslandAlert.Lock -> (restW + 44.dp) to pillH
            // A message hangs below the camera; wider for longer text, two lines if needed.
            is IslandAlert.Say -> {
                val textW = 30.dp + (md.alert.text.length * 7.6f).dp
                val wide = minOf(fullW, maxOf(restW + 40.dp, textW))
                wide to (pillH + if (textW > fullW) 50.dp else 30.dp)
            }
            else -> CompactW to pillH
        }
        IslandMode.MediaCompact, IslandMode.CallCompact, IslandMode.TimerCompact -> CompactW to pillH
        IslandMode.NavCompact -> (CompactW + 56.dp) to pillH
        IslandMode.MediaMinimal -> MinimalW to pillH
        IslandMode.MediaExpanded -> fullW to ExpandedMediaH + cameraRoom
        IslandMode.CallExpanded -> fullW to ExpandedCallH + cameraRoom
        IslandMode.TimerExpanded -> fullW to ExpandedTimerH + cameraRoom
        IslandMode.AlarmExpanded -> fullW to ExpandedAlarmH + cameraRoom
        IslandMode.NavExpanded -> fullW to ExpandedNavH + cameraRoom
        IslandMode.TripCompact, IslandMode.ScoreCompact -> (CompactW + 40.dp) to pillH
        IslandMode.TripExpanded -> fullW to 118.dp + cameraRoom
        IslandMode.ScoreExpanded -> fullW to 104.dp + cameraRoom
        IslandMode.Peek -> fullW to (if (replying) ReplyH else PeekH + 52.dp) + cameraRoom
        IslandMode.PeekCompact -> minOf(fullW, maxOf(CompactW, ((p?.summary?.length ?: 10) * 14).dp + pillH + 70.dp)) to pillH
    }
    fun radiusFor(height: Dp) = if (height <= pillH) height / 2 else ExpandedRadius
    val (targetW, targetH) = sizeOf(mode)
    val targetR = if (mode is IslandMode.Alert && mode.alert is IslandAlert.Scan) 24.dp else radiusFor(targetH)

    // The shape the island turns into when you pull it down (or push it back up).
    val expandsTo: IslandMode? = when (mode) {
        IslandMode.MediaCompact, IslandMode.MediaMinimal -> IslandMode.MediaExpanded
        IslandMode.CallCompact -> IslandMode.CallExpanded
        IslandMode.TimerCompact -> IslandMode.TimerExpanded
        IslandMode.NavCompact -> IslandMode.NavExpanded
        IslandMode.TripCompact -> IslandMode.TripExpanded
        IslandMode.ScoreCompact -> IslandMode.ScoreExpanded
        IslandMode.PeekCompact -> IslandMode.Peek
        else -> null
    }
    val collapsesTo: IslandMode? = when (mode) {
        IslandMode.MediaExpanded -> if (look == IslandModel.MediaLook.Minimal) IslandMode.MediaMinimal else IslandMode.MediaCompact
        IslandMode.CallExpanded -> if (c?.incoming == false) IslandMode.CallCompact else null
        IslandMode.TimerExpanded -> IslandMode.TimerCompact
        IslandMode.NavExpanded -> IslandMode.NavCompact
        IslandMode.TripExpanded -> IslandMode.TripCompact
        IslandMode.ScoreExpanded -> IslandMode.ScoreCompact
        else -> null
    }

    // A second activity splits off into a bubble (only next to small shapes).
    val showBubble = secondary != null && targetH <= pillH && mode !is IslandMode.Alert
    val hideResting = mode == IslandMode.Idle && !settings.islandRestingPill

    // Size is driven by Animatables so a pull can move it directly and a release can
    // spring on from wherever your finger left it.
    val wA = remember { Animatable(targetW, Dp.VectorConverter) }
    val hA = remember { Animatable(targetH, Dp.VectorConverter) }
    val rA = remember { Animatable(targetR, Dp.VectorConverter) }
    val w = wA.value
    val h = hA.value
    val r = rA.value
    /** 0..1: how far a pull has morphed the island towards [morphTo]. */
    val morph = remember { Animatable(0f) }
    var morphTo by remember { mutableStateOf<IslandMode?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var settleTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(targetW, targetH, targetR, settleTick, dragging) {
        if (dragging) return@LaunchedEffect
        // iOS-like spring: quick, with a little overshoot when it grows.
        val spec = spring<Dp>(dampingRatio = 0.74f, stiffness = 360f)
        launch { wA.animateTo(targetW, spec) }
        launch { hA.animateTo(targetH, spec) }
        launch { rA.animateTo(targetR, spec) }
    }
    val restAlpha by animateFloatAsState(if (hideResting) 0f else 1f, tween(250), label = "rest")
    // The bubble pops out of the pill like a drop of gel, and back in when it goes.
    val bubble by animateFloatAsState(
        if (showBubble) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "bubble",
    )

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // The window grows straight away and shrinks once the spring has settled. While
    // you drag, it gets some extra room so the stretch isn't cut off.
    val bubbleRoom = if (showBubble) (BubbleGap + pillH) * 2 else 0.dp
    var windowW by remember { mutableStateOf(targetW) }
    var windowH by remember { mutableStateOf(targetH) }
    // While pulling, the window is already as big as the expanded shape.
    val extraW = if (dragging) (fullW - windowW).coerceAtLeast(0.dp) else 0.dp
    val extraH = if (dragging) (sizeOf(expandsTo ?: mode).second - windowH).coerceAtLeast(0.dp) else 0.dp
    LaunchedEffect(targetW, targetH, dragging, bubbleRoom) {
        windowW = maxOf(windowW, targetW + bubbleRoom)
        windowH = maxOf(windowH, targetH)
        onWindowSize(windowW + 8.dp + extraW, top + windowH + 8.dp + extraH)
        delay(700)
        windowW = targetW + bubbleRoom
        windowH = targetH
        onWindowSize(windowW + 8.dp + extraW, top + windowH + 8.dp + extraH)
    }

    val touchable = mode != IslandMode.Idle || showBubble
    LaunchedEffect(touchable) { onTouchable(touchable) }
    // The touch area follows the shape the island is heading to (not every animation frame).
    LaunchedEffect(touchable, targetW, targetH, showBubble, top, pillH, centerOffset) {
        if (!touchable) {
            onTouchArea(null)
        } else {
            with(density) {
                val cx = screenWidth.toPx() / 2f + centerOffset.toPx()
                val half = targetW.toPx() / 2f
                val extra = if (showBubble) (BubbleGap + pillH).toPx() else 0f
                onTouchArea(
                    android.graphics.Rect(
                        (cx - half).toInt(),
                        top.toPx().toInt(),
                        (cx + half + extra).toInt(),
                        (top + targetH).toPx().toInt() + 1,
                    )
                )
            }
        }
    }
    LaunchedEffect(replying) { onFocusable(replying) }

    var pressed by remember { mutableStateOf(false) }
    val squish by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "squish")

    val compactish = mode == IslandMode.MediaCompact || mode == IslandMode.CallCompact || mode == IslandMode.MediaMinimal ||
        mode == IslandMode.TimerCompact || mode == IslandMode.NavCompact || mode == IslandMode.TripCompact || mode == IslandMode.ScoreCompact ||
        mode == IslandMode.PeekCompact
    val isExpanded = mode == IslandMode.MediaExpanded || mode == IslandMode.TimerExpanded || mode == IslandMode.NavExpanded ||
        mode == IslandMode.TripExpanded || mode == IslandMode.ScoreExpanded ||
        (mode == IslandMode.CallExpanded && c?.incoming == false)
    val haptic = { if (settings.haptics) onHaptic() }

    /** Moves the island part-way between [from] and [to] (p = 0..1), straight from the finger. */
    fun morphStep(from: IslandMode, to: IslandMode, p: Float) {
        val (fw, fh) = sizeOf(from)
        val (tw, th) = sizeOf(to)
        val cw = lerp(fw, tw, p)
        val ch = lerp(fh, th, p)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            wA.snapTo(cw)
            hA.snapTo(ch)
            rA.snapTo(minOf(ch / 2, ExpandedRadius))
            morph.snapTo(p)
        }
    }

    fun open(which: Activity?) {
        when (which) {
            Activity.Call -> model.openCall()
            Activity.Nav -> model.openNav()
            Activity.Timer -> model.openTimer()
            Activity.Trip -> model.openTrip()
            Activity.Score -> model.openScore()
            Activity.Media -> {
                model.openMediaApp()
                model.collapse()
            }
            null -> Unit
        }
    }

    // ---- pulling the island (down to open, up to close) ---------------------------
    // The touch window tracks the whole pull (even when the finger leaves the island)
    // and reports it here; the shape follows the finger, then finishes on release.
    val pull by model.pull.collectAsState()
    val pullDist = with(density) { 110.dp.toPx() }
    val flick = with(density) { 300.dp.toPx() }
    LaunchedEffect(pull) {
        val ps = pull ?: return@LaunchedEffect
        if (!ps.done) {
            if (!dragging && morphTo == null) {
                model.touched()
                model.touchPeek()
                morphTo = if (ps.dy > 0) expandsTo else collapsesTo
                dragging = morphTo != null
            }
            val to = morphTo ?: return@LaunchedEffect
            val p = if (to == expandsTo) ps.dy / pullDist else -ps.dy / pullDist
            morphStep(mode, to, p.coerceIn(0f, 1f))
        } else {
            val small = with(density) { 4.dp.toPx() }
            // A pull the system cut short still knows which way it was going.
            val to = morphTo ?: if (ps.cancelled) (if (ps.dy > 0) expandsTo else collapsesTo) else null
            val p = morph.value
            val vy = ps.vy
            val opening = to != null && to == expandsTo
            val commit = to != null && (
                if (ps.cancelled) (if (opening) ps.dy > small || vy > flick else ps.dy < -small || vy < -flick)
                else if (opening) (p > 0.3f && vy > -flick) || vy > flick
                else (p > 0.3f && vy < flick) || vy < -flick
                )
            if ((mode == IslandMode.Peek || mode == IslandMode.PeekCompact) && !replying &&
                (ps.dy < -pullDist * 0.25f || vy < -flick || (ps.cancelled && ps.dy < -small))
            ) {
                model.dismissPeek()
            }
            if (commit) {
                haptic()
                if (opening) model.expand() else model.collapse()
            }
            // Always let go of the pull, so the island can never get stuck half-way.
            morphTo = null
            dragging = false
            settleTick++
            model.clearPull()
            morph.animateTo(0f, tween(160))
        }
    }
    // If the island changes shape on its own mid-pull (a call, a notification…), drop the pull.
    LaunchedEffect(mode) {
        if (dragging && model.pull.value == null) {
            morphTo = null
            dragging = false
            settleTick++
            morph.snapTo(0f)
        }
    }

    CompositionLocalProvider(LocalPill provides pillH) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // ---- the bubble for a second activity --------------------------------------
            if (bubble > 0.01f && secondary != null) {
                val offsetX = centerOffset + (w / 2 + BubbleGap + pillH / 2) * bubble
                Box(
                    Modifier
                        .padding(top = top)
                        .offset(x = offsetX)
                        .size(pillH)
                        .graphicsLayer {
                            scaleX = 0.35f + 0.65f * bubble
                            scaleY = 0.35f + 0.65f * bubble
                            alpha = bubble.coerceIn(0f, 1f)
                        }
                        .clip(CircleShape)
                        .background(IslandBlack)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open(secondary) },
                    contentAlignment = Alignment.Center,
                ) {
                    BubbleContent(secondary, m, t, n, model)
                }
            }

            // ---- privacy dot (mic orange, camera green, screen recording red), like iOS --
            if (privacy != IslandModel.Privacy.None && h <= pillH + 1.dp) {
                val dotColor = when (privacy) {
                    IslandModel.Privacy.ScreenRecord -> Red
                    IslandModel.Privacy.Camera -> Green
                    else -> Orange
                }
                val beyond = if (bubble > 0.01f) (BubbleGap + pillH) * bubble else 0.dp
                Box(
                    Modifier
                        .padding(top = top + pillH / 2 - 3.dp)
                        .offset(x = centerOffset + w / 2 + beyond + 9.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }

            // ---- the island ----------------------------------------------------------
            Box(
                Modifier
                    .padding(top = top)
                    .offset(x = centerOffset)
                    .size(w, h)
                    .graphicsLayer {
                        scaleX = squish
                        scaleY = squish
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        alpha = restAlpha
                    }
                    .clip(RoundedCornerShape(r))
                    .background(IslandBlack)
                    .pointerInput(mode) {
                        detectTapGestures(
                            onPress = {
                                if (mode != IslandMode.Idle) {
                                    pressed = true
                                    model.touchPeek()
                                    tryAwaitRelease()
                                    pressed = false
                                }
                            },
                            onTap = {
                                when (mode) {
                                    IslandMode.Peek, IslandMode.PeekCompact -> if (!replying) model.openPeek()
                                    IslandMode.AlarmExpanded -> model.openTimer()
                                    IslandMode.MediaCompact, IslandMode.MediaMinimal, IslandMode.MediaExpanded -> open(Activity.Media)
                                    IslandMode.CallCompact, IslandMode.CallExpanded -> open(Activity.Call)
                                    IslandMode.TimerCompact, IslandMode.TimerExpanded -> open(Activity.Timer)
                                    IslandMode.NavCompact, IslandMode.NavExpanded -> open(Activity.Nav)
                                    IslandMode.TripCompact, IslandMode.TripExpanded -> open(Activity.Trip)
                                    IslandMode.ScoreCompact, IslandMode.ScoreExpanded -> open(Activity.Score)
                                    is IslandMode.Alert -> if (mode.alert is IslandAlert.LowBattery) model.openBatterySaver()
                                    else -> Unit
                                }
                            },
                            onLongPress = {
                                if (compactish) {
                                    haptic()
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
                ) { shown ->
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - morph.value * 2.2f).coerceIn(0f, 1f) }) {
                        IslandContent(shown, media, c, t, n, p, replying, model, cameraRoom)
                    }
                }
                // While pulling, the shape it's turning into fades in, revealed by the growing outline.
                val preview = morphTo
                if (preview != null) {
                    val (pw, ph) = sizeOf(preview)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .wrapContentSize(Alignment.TopCenter, unbounded = true)
                            .requiredSize(pw, ph)
                            .graphicsLayer { alpha = ((morph.value - 0.35f) / 0.5f).coerceIn(0f, 1f) }
                    ) {
                        IslandContent(preview, media, c, t, n, p, replying, model, cameraRoom)
                    }
                }
            }
        }
    }
}

/** What's drawn inside the island for [shown]. Big shapes start below the camera. */
@Composable
private fun IslandContent(
    shown: IslandMode,
    media: MediaInfo?,
    c: NotificationHub.CallInfo?,
    t: NotificationHub.TimerInfo?,
    n: NotificationHub.NavInfo?,
    p: NotificationHub.MessagePeek?,
    replying: Boolean,
    model: IslandModel,
    cameraRoom: Dp,
) {
    val big = shown is IslandMode.MediaExpanded || shown is IslandMode.CallExpanded || shown is IslandMode.TimerExpanded ||
        shown is IslandMode.AlarmExpanded || shown is IslandMode.NavExpanded || shown is IslandMode.Peek ||
        shown is IslandMode.TripExpanded || shown is IslandMode.ScoreExpanded
    Box(Modifier.fillMaxSize().padding(top = if (big) cameraRoom else 0.dp)) {
                    when (shown) {
                        IslandMode.Idle -> IdleContent()
                        is IslandMode.Alert -> AlertContent(shown.alert)
                        IslandMode.MediaCompact -> media?.let { MediaCompact(it) }
                        IslandMode.MediaMinimal -> media?.let { MediaMinimal(it) }
                        IslandMode.MediaExpanded -> media?.let { MediaExpanded(it, model) }
                        IslandMode.CallCompact -> c?.let { CallCompact(it) }
                        IslandMode.CallExpanded -> c?.let { CallExpanded(it, model) }
                        IslandMode.TimerCompact -> t?.let { TimerCompact(it) }
                        IslandMode.TimerExpanded -> t?.let { TimerExpanded(it, model) }
                        IslandMode.AlarmExpanded -> t?.let { AlarmExpanded(it, model) }
                        IslandMode.NavCompact -> n?.let { NavCompact(it) }
                        IslandMode.NavExpanded -> n?.let { NavExpanded(it) }
                        IslandMode.TripCompact -> model.trip.value?.let { TripCompact(it) }
                        IslandMode.TripExpanded -> model.trip.value?.let { TripExpanded(it) }
                        IslandMode.ScoreCompact -> model.score.value?.let { ScoreCompact(it) }
                        IslandMode.ScoreExpanded -> model.score.value?.let { ScoreExpanded(it) }
                        IslandMode.Peek -> p?.let { PeekContent(it, replying, model) }
                        IslandMode.PeekCompact -> p?.let { PeekCompactContent(it) }
                    }
    }
}

/** What the little side bubble shows for the second activity. */
@Composable
private fun BubbleContent(which: Activity, m: MediaInfo?, t: NotificationHub.TimerInfo?, n: NotificationHub.NavInfo?, model: IslandModel) {
    val s = LocalPill.current
    when (which) {
        Activity.Media -> {
            val icon = m?.art ?: m?.appIcon
            if (icon != null) Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(s - 8.dp).clip(CircleShape))
            else Waveform(m?.playing == true, m?.accent ?: Color.White, Modifier.size(s * 0.5f, s * 0.36f))
        }
        Activity.Timer -> TimerGlyph(Orange, Modifier.size(s * 0.55f))
        Activity.Nav -> NavArrow(n, Modifier.size(s * 0.6f))
        Activity.Call -> PhoneGlyph(Green, Modifier.size(s * 0.5f))
        Activity.Trip -> model.trip.value?.icon?.let { bmp ->
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.size(s - 8.dp).clip(CircleShape))
        }
        Activity.Score -> Text(model.score.value?.let { "${it.scoreA}-${it.scoreB}" }.orEmpty(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Paused for a while: just the app's little logo beside the camera. */
@Composable
private fun MediaMinimal(m: MediaInfo) {
    val s = LocalPill.current
    Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = m.appIcon
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(s - 8.dp).clip(CircleShape))
        } else {
            Art(m, s - 8.dp, (s - 8.dp) / 2)
        }
    }
}

// ---- compact & alerts -----------------------------------------------------------------

/** Compact text size that fits the pill. */
@Composable
private fun compactSp() = if (LocalPill.current < 30.dp) 12.sp else 13.sp

/**
 * Compact layout: things on the left and right of the camera, never over it. The
 * middle (where the camera hole is) is kept empty.
 */
@Composable
private fun CompactSplit(
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    padding: Dp = 10.dp,
) {
    val s = LocalPill.current
    Row(Modifier.fillMaxSize().padding(horizontal = padding), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) { leading() }
        }
        // The camera sits here.
        Spacer(Modifier.width(s + 6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

@Composable
private fun MediaCompact(m: MediaInfo) {
    val s = LocalPill.current
    CompactSplit(
        leading = { Art(m, s - 9.dp, (s - 9.dp) * 0.3f) },
        trailing = { Waveform(m.playing, m.accent, Modifier.padding(end = 4.dp).size(s * 0.62f, s * 0.4f)) },
        padding = 5.dp,
    )
}

@Composable
private fun CallCompact(call: NotificationHub.CallInfo) {
    val s = LocalPill.current
    CompactSplit(
        leading = {
            PhoneGlyph(Green, Modifier.size(s * 0.45f))
            Spacer(Modifier.width(5.dp))
            Text(callTime(call), color = Green, fontSize = compactSp(), fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
        },
        trailing = { Waveform(true, Green, Modifier.size(s * 0.62f, s * 0.4f)) },
    )
}

@Composable
private fun TimerCompact(t: NotificationHub.TimerInfo) {
    val s = LocalPill.current
    CompactSplit(
        leading = { TimerGlyph(Orange, Modifier.size(s * 0.5f)) },
        trailing = { Text(timerText(t), color = Orange, fontSize = compactSp(), fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1) },
    )
}

@Composable
private fun NavCompact(n: NotificationHub.NavInfo) {
    val s = LocalPill.current
    CompactSplit(
        leading = { NavArrow(n, Modifier.size(s * 0.62f)) },
        trailing = {
            Text(
                n.distance.ifBlank { n.instruction },
                color = Color.White,
                fontSize = compactSp(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun AlertContent(alert: IslandAlert) {
    val s = LocalPill.current
    when (alert) {
        is IslandAlert.Ringer -> {
            val silent = alert.mode != AudioManager.RINGER_MODE_NORMAL
            CompactSplit(
                leading = { BellGlyph(if (silent) Red else Color.White, slashed = silent, modifier = Modifier.size(s * 0.5f)) },
                trailing = {
                    Text(
                        when (alert.mode) {
                            AudioManager.RINGER_MODE_SILENT -> "Silent"
                            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                            else -> "Ring"
                        },
                        color = if (silent) Orange else Color.White,
                        fontSize = compactSp(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                },
            )
        }
        is IslandAlert.Charging -> CompactSplit(
            leading = { Text("Charging", color = Color.White, fontSize = compactSp(), fontWeight = FontWeight.SemiBold, maxLines = 1) },
            trailing = {
                Text("${alert.level}%", color = Green, fontSize = compactSp(), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                BatteryGlyph(alert.level / 100f, Green, Modifier.size(s * 0.78f, s * 0.38f))
            },
        )
        is IslandAlert.Lock -> CompactSplit(
            leading = { LockGlyph(open = alert.open, modifier = Modifier.size(s * 0.55f)) },
            trailing = {},
        )
        is IslandAlert.Scan -> Box(
            Modifier.fillMaxSize().padding(top = (s - 4.dp).coerceAtLeast(0.dp), bottom = 4.dp),
            contentAlignment = Alignment.Center,
        ) { FaceScanGlyph(success = alert.success, failed = alert.failed, modifier = Modifier.size(32.dp)) }
        is IslandAlert.Say -> Box(
            Modifier.fillMaxSize().padding(top = s - 2.dp, start = 14.dp, end = 14.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (alert.icon.isNotBlank()) {
                    Text(alert.icon, color = Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    alert.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        is IslandAlert.LowBattery -> CompactSplit(
            leading = { Text("Low Battery", color = Color.White, fontSize = compactSp(), fontWeight = FontWeight.SemiBold, maxLines = 1) },
            trailing = {
                Text("${alert.level}%", color = Red, fontSize = compactSp(), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                BatteryGlyph(alert.level / 100f, Red, Modifier.size(s * 0.78f, s * 0.38f))
            },
        )
        is IslandAlert.Focus -> CompactSplit(
            leading = {
                FocusGlyph(alert.glyph, Color(alert.color), Modifier.size(s * 0.55f))
                Spacer(Modifier.width(6.dp))
                Text(alert.name, color = Color(alert.color), fontSize = compactSp(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailing = {
                Text(if (alert.on) "On" else "Off", color = if (alert.on) Color.White else Grey, fontSize = compactSp(), fontWeight = FontWeight.SemiBold)
            },
        )
    }
}

/** The resting pill. While a Focus is on, its little icon sits left of the camera. */
@Composable
private fun IdleContent() {
    val focus by com.kinetic.launcher.FocusModes.active.collectAsState()
    val s = LocalPill.current
    Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        val f = focus
        if (f != null) FocusGlyph(f.glyph, Color(f.color), Modifier.size(s * 0.45f))
    }
}

/**
 * Face ID–style animation: a face in corner brackets that turns from side to side
 * while scanning, then becomes a tick when you're recognised.
 */
@Composable
private fun FaceScanGlyph(success: Boolean, failed: Boolean = false, modifier: Modifier) {
    // Didn't work: a quick side-to-side shake, like iPhone.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failed) {
        if (failed) {
            for (x in listOf(1f, -1f, 0.7f, -0.7f, 0.35f, 0f)) shake.animateTo(x, tween(55))
        } else {
            shake.snapTo(0f)
        }
    }
    val t = rememberInfiniteTransition(label = "scan")
    val turn by t.animateFloat(-22f, 22f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "turn")
    val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "pulse")
    val tick = remember { Animatable(0f) }
    LaunchedEffect(success) {
        if (success) tick.animateTo(1f, tween(380)) else tick.snapTo(0f)
    }
    Canvas(
        modifier.graphicsLayer {
            rotationY = if (success || failed) 0f else turn
            translationX = shake.value * 5f * density
            cameraDistance = 12f * density
        }
    ) {
        val w = size.width
        val h = size.height
        val st = w * 0.055f
        val k = tick.value
        // Corner brackets, closing in a little on success.
        val inset = w * 0.06f * k
        val len = w * 0.22f
        val c = Color.White.copy(alpha = if (success || failed) 1f else pulse)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * len, y), strokeWidth = st, cap = StrokeCap.Round)
            drawLine(c, Offset(x, y), Offset(x, y + dy * len), strokeWidth = st, cap = StrokeCap.Round)
        }
        corner(inset + st, inset + st, 1f, 1f)
        corner(w - inset - st, inset + st, -1f, 1f)
        corner(inset + st, h - inset - st, 1f, -1f)
        corner(w - inset - st, h - inset - st, -1f, -1f)
        // The face fades out as the tick draws in.
        val faceA = 1f - k
        if (faceA > 0.01f) {
            val fc = Color.White.copy(alpha = faceA)
            drawLine(fc, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.36f, h * 0.44f), strokeWidth = st, cap = StrokeCap.Round)
            drawLine(fc, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.64f, h * 0.44f), strokeWidth = st, cap = StrokeCap.Round)
            drawLine(fc, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.55f), strokeWidth = st, cap = StrokeCap.Round)
            drawLine(fc, Offset(w * 0.5f, h * 0.55f), Offset(w * 0.45f, h * 0.55f), strokeWidth = st, cap = StrokeCap.Round)
            drawArc(fc, 20f, 140f, false, Offset(w * 0.33f, h * 0.48f), Size(w * 0.34f, h * 0.2f), style = Stroke(st, cap = StrokeCap.Round))
        }
        if (k > 0f) {
            // Tick drawn from left to right.
            val a = Offset(w * 0.3f, h * 0.52f)
            val b = Offset(w * 0.45f, h * 0.66f)
            val e = Offset(w * 0.72f, h * 0.36f)
            val first = (k / 0.4f).coerceIn(0f, 1f)
            val second = ((k - 0.4f) / 0.6f).coerceIn(0f, 1f)
            drawLine(Color.White, a, a + (b - a) * first, strokeWidth = st * 1.2f, cap = StrokeCap.Round)
            if (second > 0f) drawLine(Color.White, b, b + (e - b) * second, strokeWidth = st * 1.2f, cap = StrokeCap.Round)
        }
    }
}

// ---- deliveries, rides & scores --------------------------------------------------------

@Composable
private fun TripIcon(t: NotificationHub.TripInfo, size: Dp) {
    val img = remember(t.icon) { t.icon?.asImageBitmap() }
    if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(Green))
}

@Composable
private fun TripCompact(t: NotificationHub.TripInfo) {
    val s = LocalPill.current
    CompactSplit(
        leading = { TripIcon(t, s - 9.dp) },
        trailing = {
            Text(
                t.eta ?: if (t.isRide) "On the way" else "On its way",
                color = Green,
                fontSize = compactSp(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        padding = 6.dp,
    )
}

@Composable
private fun TripExpanded(t: NotificationHub.TripInfo) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TripIcon(t, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.text, color = Grey, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            t.eta?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, color = Green, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        // Progress like Apple's delivery Live Activities: a track with a moving dot.
        val prog = t.progress
        if (prog != null) {
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val y = size.height / 2
                val bar = 5.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y - bar / 2), Size(size.width, bar), CornerRadius(bar / 2))
                drawRoundRect(Green, Offset(0f, y - bar / 2), Size(size.width * prog, bar), CornerRadius(bar / 2))
                drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * prog, y))
            }
        }
    }
}

/** Short team names for the compact view ("Chicago Bears" → "CHI"). */
private fun abbreviate(team: String): String {
    val words = team.trim().split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(3).uppercase()
        else -> words[0].take(3).uppercase()
    }
}

@Composable
private fun ScoreCompact(sc: NotificationHub.ScoreInfo) {
    CompactSplit(
        leading = {
            Text(abbreviate(sc.teamA), color = Grey, fontSize = compactSp(), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Text(sc.scoreA, color = Color.White, fontSize = compactSp(), fontWeight = FontWeight.Bold, style = Tabular)
        },
        trailing = {
            Text(sc.scoreB, color = Color.White, fontSize = compactSp(), fontWeight = FontWeight.Bold, style = Tabular)
            Spacer(Modifier.width(5.dp))
            Text(abbreviate(sc.teamB), color = Grey, fontSize = compactSp(), fontWeight = FontWeight.SemiBold)
        },
    )
}

@Composable
private fun ScoreExpanded(sc: NotificationHub.ScoreInfo) {
    Column(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(sc.teamA, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${sc.scoreA}  –  ${sc.scoreB}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
            Text(
                sc.teamB,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (sc.status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(sc.status, color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- timers, alarms, directions, messages (expanded) ----------------------------------

@Composable
private fun TimerExpanded(t: NotificationHub.TimerInfo, model: IslandModel) {
    Row(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimerGlyph(Orange, Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, color = Grey, fontSize = 13.sp, maxLines = 1)
            Text(timerText(t), color = Orange, fontSize = 32.sp, fontWeight = FontWeight.Light, style = Tabular)
        }
        t.actions.take(2).forEach { action ->
            val title = action.title?.toString().orEmpty()
            val lower = title.lowercase()
            Spacer(Modifier.width(10.dp))
            RoundAction(
                background = if ("pause" in lower || "resume" in lower || "start" in lower) Orange.copy(alpha = 0.25f) else Color(0xFF3A3A3C),
                onClick = { model.sendAction(action) },
            ) {
                when {
                    "pause" in lower -> PauseGlyph(Modifier.size(16.dp))
                    "resume" in lower || "start" in lower -> PlayGlyph(Modifier.size(16.dp))
                    "cancel" in lower || "stop" in lower || "reset" in lower || "dismiss" in lower -> CrossGlyph(Modifier.size(14.dp))
                    else -> Text(title.take(1).uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AlarmExpanded(t: NotificationHub.TimerInfo, model: IslandModel) {
    Row(
        Modifier.fillMaxSize().padding(start = 22.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Alarm", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(t.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // iOS style: an orange "Snooze" capsule and a grey "Stop".
        t.actions.take(2).forEach { action ->
            val title = action.title?.toString().orEmpty()
            val snooze = "snooze" in title.lowercase()
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (snooze) Orange else Color(0xFF3A3A3C))
                    .clickable { model.sendAction(action) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, color = if (snooze) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NavExpanded(n: NotificationHub.NavInfo) {
    Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavArrow(n, Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (n.distance.isNotBlank()) {
                    Text(n.distance, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(n.instruction, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(n.instruction, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        n.eta?.let { Text(it, color = Green, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The small notification pill: the app's icon on the left, a couple of words on the right. */
@Composable
private fun PeekCompactContent(p: NotificationHub.MessagePeek) {
    val s = LocalPill.current
    val icon = remember(p.icon) { p.icon?.asImageBitmap() }
    CompactSplit(
        leading = {
            if (icon != null) {
                Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(s - 9.dp).clip(RoundedCornerShape((s - 9.dp) * 0.3f)))
            } else {
                Box(Modifier.size(s - 9.dp).clip(RoundedCornerShape((s - 9.dp) * 0.3f)).background(Color(0xFF2C2C2E)))
            }
            Spacer(Modifier.width(6.dp))
            Text(p.appName, color = Grey, fontSize = compactSp(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailing = {
            Text(
                p.summary.ifBlank { "New notification" },
                color = Color.White,
                fontSize = compactSp(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        padding = 6.dp,
    )
}

/** A notification opened from the island: what it says, a reply field and its buttons. */
@Composable
private fun PeekContent(p: NotificationHub.MessagePeek, replying: Boolean, model: IslandModel) {
    val icon = remember(p.icon) { p.icon?.asImageBitmap() }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.appName, color = Grey, fontSize = 11.sp, maxLines = 1)
                Text(p.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.text.isNotBlank()) {
                    Text(p.text, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (replying) {
            Spacer(Modifier.height(12.dp))
            ReplyBox(onSend = model::sendReply, onCancel = model::dismissPeek)
        } else {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.reply != null) {
                    // Looks like a text field; tapping it brings up the keyboard.
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(Color(0xFF1C1C1E))
                            .clickable { model.startReply() }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text("Reply…", color = Grey, fontSize = 14.sp, maxLines = 1) }
                }
                val buttons = if (p.reply != null) p.actions.take(1) else p.actions
                buttons.forEach { a ->
                    PeekButton(a.title.toString(), Modifier.then(if (p.reply == null) Modifier.weight(1f) else Modifier)) { model.pressPeekAction(a) }
                }
                if (p.reply == null && p.actions.isEmpty()) {
                    PeekButton("Open", Modifier.weight(1f)) { model.openPeek() }
                }
                PeekButton("Dismiss", Modifier.then(if (p.reply == null) Modifier.weight(1f) else Modifier), muted = true) { model.clearPeekNotification() }
            }
        }
    }
}

@Composable
private fun PeekButton(label: String, modifier: Modifier = Modifier, muted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (muted) Color(0xFF2C2C2E) else Color(0xFF3A3A3C))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (muted) Color.White.copy(alpha = 0.8f) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReplyBox(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(120) // let the window become focusable first
        focus.requestFocus()
        keyboard?.show()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Message", color = Grey, fontSize = 15.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (text.isNotBlank()) Blue else Color(0xFF3A3A3C))
                .clickable { if (text.isNotBlank()) onSend(text.trim()) else onCancel() },
            contentAlignment = Alignment.Center,
        ) {
            if (text.isNotBlank()) SendGlyph(Modifier.size(16.dp)) else CrossGlyph(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun RoundAction(background: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NavArrow(n: NotificationHub.NavInfo?, modifier: Modifier) {
    val arrow = remember(n?.arrow) { n?.arrow?.asImageBitmap() }
    if (arrow != null) {
        Image(arrow, null, modifier = modifier)
    } else {
        // A plain "go ahead" arrow when Maps gives no picture.
        Canvas(modifier) {
            val path = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height * 0.55f)
                lineTo(size.width * 0.62f, size.height * 0.55f)
                lineTo(size.width * 0.62f, size.height)
                lineTo(size.width * 0.38f, size.height)
                lineTo(size.width * 0.38f, size.height * 0.55f)
                lineTo(0f, size.height * 0.55f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

@Composable
private fun timerText(t: NotificationHub.TimerInfo): String {
    val now by produceState(System.currentTimeMillis(), t) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    return when {
        t.endsAt != null -> clock((t.endsAt - now + 999).coerceAtLeast(0))
        t.startedAt != null -> clock((now - t.startedAt).coerceAtLeast(0))
        else -> t.text ?: t.label
    }
}

@Composable
private fun TimerGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    val stroke = s * 0.13f
    drawCircle(color, radius = s / 2 - stroke / 2, style = Stroke(stroke))
    drawLine(color, center, Offset(center.x, s * 0.22f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, center, Offset(s * 0.72f, center.y), strokeWidth = stroke, cap = StrokeCap.Round)
}

/** A padlock; when [open] the shackle lifts and swings open, like iOS after Face ID. */
@Composable
private fun LockGlyph(open: Boolean, modifier: Modifier) {
    // Opening: the shackle pops straight up out of the body, then slides over to the
    // right so it hangs off the side (no hinge). Locking plays it backwards and snaps shut.
    val lift = remember { Animatable(if (open) 0f else 1f) }
    val slide = remember { Animatable(if (open) 0f else 1f) }
    LaunchedEffect(open) {
        if (open) {
            lift.snapTo(0f)
            slide.snapTo(0f)
            delay(110)
            lift.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 620f))
            slide.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 340f))
        } else {
            lift.snapTo(1f)
            slide.snapTo(1f)
            delay(90)
            slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f))
            lift.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 900f))
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bodyTop = h * 0.46f
        val bodyL = w * 0.12f
        val bodyW = w * 0.76f
        val stroke = w * 0.14f
        // Shackle: an upside-down U, legs sunk into the body when closed.
        val legL = w * 0.29f
        val legR = w * 0.71f
        val arcTop = h * 0.05f
        val up = h * 0.17f * lift.value
        val dx = w * 0.30f * slide.value
        val path = Path().apply {
            val r = (legR - legL) / 2f
            moveTo(legL + dx, bodyTop + stroke * 0.6f - up)
            lineTo(legL + dx, arcTop + r - up)
            arcTo(Rect(legL + dx, arcTop - up, legR + dx, arcTop + 2 * r - up), 180f, 180f, false)
            // Once it has slid off, the right leg hangs a little shorter (it's outside the body).
            lineTo(legR + dx, bodyTop + stroke * 0.6f - up - h * 0.08f * slide.value)
        }
        drawPath(path, Color.White, style = Stroke(stroke, cap = StrokeCap.Round))
        drawRoundRect(Color.White, Offset(bodyL, bodyTop), Size(bodyW, h - bodyTop), CornerRadius(w * 0.14f))
    }
}

@Composable
private fun CrossGlyph(modifier: Modifier) = Canvas(modifier) {
    val st = size.minDimension * 0.18f
    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), strokeWidth = st, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = st, cap = StrokeCap.Round)
}

@Composable
private fun SendGlyph(modifier: Modifier) = Canvas(modifier) {
    val st = size.minDimension * 0.16f
    drawLine(Color.White, Offset(size.width / 2, size.height), Offset(size.width / 2, 0f), strokeWidth = st, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width * 0.12f, size.height * 0.4f), strokeWidth = st, cap = StrokeCap.Round)
    drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width * 0.88f, size.height * 0.4f), strokeWidth = st, cap = StrokeCap.Round)
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

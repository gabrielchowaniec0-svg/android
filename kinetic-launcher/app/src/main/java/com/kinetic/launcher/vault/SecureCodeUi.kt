package com.kinetic.launcher.vault

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.SecretKey
import kotlin.math.roundToInt

private val Accent = Color(0xFF8FA6D8)
private val Subtle = Color(0xFF8C93A6)
private val Wrong = Color(0xFFFF453A)

const val PIN_LENGTH = 6

/**
 * Asks for the Kinetic code (6-digit PIN or pattern, whichever was set up) and hands
 * back the vault key when it's right. Wrong tries shake the dots; repeated misses
 * lock it for a while.
 */
@Composable
fun UnlockWithCode(title: String, subtitle: String, allowDecoy: Boolean = true, onUnlocked: (SecretKey) -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val kind = remember { VaultCrypto.kind(context) }
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(subtitle) }
    var resetTick by remember { mutableStateOf(0) }

    fun attempt(code: String) {
        if (checking) return
        val wait = VaultCrypto.lockedFor(context)
        if (wait > 0) {
            message = "Try again in ${(wait / 1000) + 1} s"
            resetTick++
            return
        }
        checking = true
        scope.launch {
            val key = withContext(Dispatchers.Default) { VaultCrypto.unlock(context, code, allowDecoy) }
            checking = false
            if (key != null) {
                onUnlocked(key)
            } else {
                val lock = VaultCrypto.lockedFor(context)
                message = if (lock > 0) "Too many tries — wait ${(lock / 1000) + 1} s" else "Wrong code"
                // Third wrong try in a row: quietly photograph whoever is trying.
                if (VaultCrypto.failedTries(context) == 3) IntruderSnap.capture(context, owner)
                resetTick++
                for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(55))
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (checking) "Checking…" else message,
            color = if (message.startsWith("Wrong") || message.startsWith("Too")) Wrong else Subtle,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
            if (kind == VaultCrypto.Kind.Pattern) {
                PatternPad(resetTick, onDone = { dots -> attempt(VaultCrypto.patternCode(dots)) })
            } else {
                PinPad(resetTick, onDone = { pin -> attempt(pin) })
            }
        }
    }
}

/** First-time setup or changing the code: choose PIN or pattern, enter it twice. */
@Composable
fun ChooseCode(onChosen: (code: String, kind: VaultCrypto.Kind) -> Unit) {
    var kind by remember { mutableStateOf(VaultCrypto.Kind.Pin) }
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("This code is only for Kinetic's secure things — not your lock screen.") }
    var resetTick by remember { mutableStateOf(0) }

    fun entered(code: String) {
        val f = first
        if (f == null) {
            first = code
            message = "Enter it again to confirm"
            resetTick++
        } else if (f == code) {
            onChosen(code, kind)
        } else {
            first = null
            message = "They didn't match — start again"
            resetTick++
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(if (first == null) "Create your Kinetic code" else "Confirm your code", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = Subtle, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(14.dp))
        if (first == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(VaultCrypto.Kind.Pin to "6-digit PIN", VaultCrypto.Kind.Pattern to "Pattern").forEach { (k, label) ->
                    val on = k == kind
                    Text(
                        label,
                        color = if (on) Color.Black else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) Accent else Color(0xFF232A3D))
                            .clickable {
                                kind = k
                                resetTick++
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (kind == VaultCrypto.Kind.Pattern) {
            PatternPad(resetTick, minDots = 4, onDone = { dots ->
                if (dots.size < 4) {
                    message = "Connect at least 4 dots"
                    resetTick++
                } else {
                    entered(VaultCrypto.patternCode(dots))
                }
            })
        } else {
            PinPad(resetTick, onDone = ::entered)
        }
    }
}

/** iOS-style round number keys with a row of dots. */
@Composable
fun PinPad(resetTick: Int, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(resetTick) { pin = "" }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PIN_LENGTH) { i ->
                Box(
                    Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(if (i < pin.length) Color.White else Color.Transparent)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(30.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(if (key.isEmpty() || key == "⌫") Color.Transparent else Color.White.copy(alpha = 0.12f))
                            .clickable(enabled = key.isNotEmpty()) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (key == "⌫") {
                                    pin = pin.dropLast(1)
                                } else if (pin.length < PIN_LENGTH) {
                                    pin += key
                                    if (pin.length == PIN_LENGTH) onDone(pin)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(key, color = Color.White, fontSize = if (key == "⌫") 24.sp else 32.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
    }
}

/** A 3 × 3 dot grid you draw a line through. */
@Composable
fun PatternPad(resetTick: Int, minDots: Int = 1, onDone: (List<Int>) -> Unit) {
    val dots = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(resetTick) {
        delay(if (dots.isEmpty()) 0 else 350)
        dots.clear()
    }
    Canvas(
        Modifier
            .size(270.dp)
            .pointerInput(resetTick) {
                val cell = size.width / 3f
                fun hit(p: Offset): Int? {
                    val col = (p.x / cell).toInt()
                    val row = (p.y / cell).toInt()
                    if (col !in 0..2 || row !in 0..2) return null
                    val c = Offset(col * cell + cell / 2, row * cell + cell / 2)
                    return if ((p - c).getDistance() < cell * 0.32f) row * 3 + col else null
                }
                detectDragGestures(
                    onDragStart = { p ->
                        dots.clear()
                        hit(p)?.let {
                            dots += it
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        finger = p
                    },
                    onDragEnd = {
                        finger = null
                        if (dots.size >= minDots) onDone(dots.toList()) else dots.clear()
                    },
                    onDragCancel = {
                        finger = null
                        dots.clear()
                    },
                ) { change, _ ->
                    val p = change.position
                    finger = p
                    hit(p)?.let { d ->
                        if (d !in dots) {
                            dots += d
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            }
    ) {
        val cell = size.width / 3f
        fun centre(d: Int) = Offset((d % 3) * cell + cell / 2, (d / 3) * cell + cell / 2)
        // Lines between chosen dots, then to the finger.
        for (i in 1 until dots.size) drawLine(Accent, centre(dots[i - 1]), centre(dots[i]), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        val f = finger
        if (f != null && dots.isNotEmpty()) drawLine(Accent.copy(alpha = 0.6f), centre(dots.last()), f, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        for (d in 0 until 9) {
            val on = d in dots
            drawCircle(if (on) Accent else Color.White.copy(alpha = 0.85f), radius = if (on) 11.dp.toPx() else 7.dp.toPx(), center = centre(d))
            if (on) drawCircle(Accent.copy(alpha = 0.25f), radius = 26.dp.toPx(), center = centre(d))
        }
    }
}

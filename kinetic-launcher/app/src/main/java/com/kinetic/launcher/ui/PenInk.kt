package com.kinetic.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * S Pen ink: write anywhere on the home screen with the pen, and what you wrote runs as
 * a Kinetic Command ("mom", "timer 10 min", "planes") or opens search. Fingers are
 * never affected, and a pen *tap* still opens apps normally. Only pen strokes become ink.
 *
 * Handwriting is read on the phone (ML Kit Digital Ink). The English model (~20 MB)
 * downloads once, the first time Kinetic starts with this on.
 */
@Stable
class InkState(private val scope: CoroutineScope, private val onText: (String) -> Unit) {
    /** Finished strokes (screen px). */
    val strokes = ArrayList<List<Offset>>()
    private var current: ArrayList<Offset>? = null
    private var ink = Ink.builder()
    private var stroke: Ink.Stroke.Builder? = null

    /** Bumped on every change so the canvas redraws. */
    var version by mutableIntStateOf(0)
        private set
    var fade by mutableFloatStateOf(1f)
        private set
    /** What the pen wrote, shown in a pill for a moment. */
    var pill by mutableStateOf<String?>(null)

    private var idle: Job? = null
    private var pillJob: Job? = null

    val active get() = strokes.isNotEmpty() || current != null

    fun begin(points: List<Pair<Offset, Long>>) {
        idle?.cancel()
        fade = 1f
        current = ArrayList(points.map { it.first })
        stroke = Ink.Stroke.builder().also { b -> points.forEach { (o, t) -> b.addPoint(Ink.Point.create(o.x, o.y, t)) } }
        version++
    }

    fun add(o: Offset, t: Long) {
        current?.add(o)
        stroke?.addPoint(Ink.Point.create(o.x, o.y, t))
        version++
    }

    fun end() {
        current?.let { strokes += it }
        current = null
        stroke?.let { ink.addStroke(it.build()) }
        stroke = null
        version++
        idle?.cancel()
        // A short pause after the last stroke means "done writing".
        idle = scope.launch {
            delay(850)
            val built = ink.build()
            ink = Ink.builder()
            val text = PenRecognizer.recognize(built)
            // Fade the ink out.
            val steps = 12
            for (i in 1..steps) {
                fade = 1f - i / steps.toFloat()
                delay(16)
            }
            strokes.clear()
            version++
            fade = 1f
            when {
                !text.isNullOrBlank() -> onText(text.trim())
                !PenRecognizer.modelReady -> say("Handwriting is still downloading. Try again in a minute")
                else -> say("Couldn't read that")
            }
        }
    }

    fun say(text: String, millis: Long = 1800) {
        pill = text
        pillJob?.cancel()
        pillJob = scope.launch {
            delay(millis)
            pill = null
        }
    }

    /** The stroke being drawn right now, if any. */
    fun currentStroke(): List<Offset>? = current
}

/** Watches for S Pen strokes (never fingers). A pen tap is left alone for whatever is under it. */
fun Modifier.penInk(enabled: Boolean, state: InkState): Modifier =
    if (!enabled) this else pointerInput(state) {
        val slop = 4.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Stylus) return@awaitEachGesture
            val start = down.position
            val early = arrayListOf(start to down.uptimeMillis)
            var inking = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!inking) {
                    early += c.position to c.uptimeMillis
                    if ((c.position - start).getDistance() > slop) {
                        inking = true
                        state.begin(early)
                    }
                } else {
                    // Use the in-between samples too for smooth handwriting.
                    c.historical.forEach { h -> state.add(h.position, h.uptimeMillis) }
                    state.add(c.position, c.uptimeMillis)
                }
                if (inking) ev.changes.forEach { it.consume() }
                if (!c.pressed) break
            }
            if (inking) state.end()
        }
    }

/** Draws the glowing ink and the "what you wrote" pill. */
@Composable
fun InkLayer(state: InkState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val redraw = state.version
            if (redraw < 0) return@Canvas
            val all = state.strokes + listOfNotNull(state.currentStroke())
            if (all.isEmpty()) return@Canvas
            val path = Path()
            all.forEach { s ->
                if (s.isEmpty()) return@forEach
                path.moveTo(s[0].x, s[0].y)
                for (i in 1 until s.size) {
                    // Smooth with midpoints.
                    val a = s[i - 1]
                    val b = s[i]
                    path.quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
                }
                path.lineTo(s.last().x, s.last().y)
            }
            val a = state.fade
            drawPath(path, Color(0xFF7FB2FF).copy(alpha = 0.22f * a), style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color(0xFFA9CCFF).copy(alpha = 0.45f * a), style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color.White.copy(alpha = a), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        AnimatedVisibility(
            visible = state.pill != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp),
        ) {
            Text(
                state.pill.orEmpty(),
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(22.dp))
                    .clickable { state.pill = null }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** On-device handwriting recognition (English). */
object PenRecognizer {
    private var recognizer: DigitalInkRecognizer? = null

    /** False until the handwriting model is on the phone. */
    @Volatile
    var modelReady = false
        private set
    private val model: DigitalInkRecognitionModel? by lazy {
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US") }.getOrNull()
            ?.let { DigitalInkRecognitionModel.builder(it).build() }
    }

    /** Downloads the model in the background if it isn't there yet. Safe to call often. */
    fun prepare() {
        val m = model ?: return
        RemoteModelManager.getInstance().download(m, DownloadConditions.Builder().build())
    }

    suspend fun recognize(ink: Ink): String? {
        val m = model ?: return null
        val mgr = RemoteModelManager.getInstance()
        val ready = suspendCancellableCoroutine { cont ->
            mgr.isModelDownloaded(m)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(false) }
        }
        modelReady = ready
        if (!ready) {
            prepare()
            return null
        }
        val r = recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()).also { recognizer = it }
        return suspendCancellableCoroutine { cont ->
            r.recognize(ink)
                .addOnSuccessListener { res -> cont.resume(res.candidates.firstOrNull()?.text) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
}

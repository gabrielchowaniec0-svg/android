package com.kinetic.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.launcher.pen.StickyNotes
import kotlinx.coroutines.launch

/** Sticky notes on the first home page (swipe one sideways to throw it away). */
@Composable
fun StickyNotesLayer(notes: List<StickyNotes.Note>, onRemove: (Long) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            notes.take(4).forEachIndexed { i, n -> key(n.id) { Sticky(n, if (i % 2 == 0) -2.5f else 2f, onRemove) } }
        }
    }
}

@Composable
private fun Sticky(n: StickyNotes.Note, tilt: Float, onRemove: (Long) -> Unit) {
    val dx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .width(132.dp)
            .height(104.dp)
            .graphicsLayer {
                translationX = dx.value
                rotationZ = tilt
                alpha = (1f - kotlin.math.abs(dx.value) / 600f).coerceIn(0f, 1f)
            }
            .shadow(6.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFFFE45C))
            .pointerInput(n.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (kotlin.math.abs(dx.value) > 120f) {
                                dx.animateTo(if (dx.value > 0) 900f else -900f)
                                onRemove(n.id)
                            } else {
                                dx.animateTo(0f)
                            }
                        }
                    },
                ) { c, d ->
                    c.consume()
                    scope.launch { dx.snapTo(dx.value + d) }
                }
            }
            .padding(10.dp),
    ) {
        if (n.ink.isNotEmpty()) {
            // Your own handwriting.
            Canvas(Modifier.fillMaxSize()) {
                val s = minOf(size.width, size.height)
                val path = Path()
                n.ink.forEach { stroke ->
                    stroke.firstOrNull()?.let { path.moveTo(it.x * s, it.y * s) }
                    stroke.drop(1).forEach { path.lineTo(it.x * s, it.y * s) }
                }
                drawPath(path, Color(0xFF2B2B2B), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        } else {
            Text(n.text, color = Color(0xFF2B2B2B), fontSize = 16.sp, fontFamily = FontFamily.Cursive, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

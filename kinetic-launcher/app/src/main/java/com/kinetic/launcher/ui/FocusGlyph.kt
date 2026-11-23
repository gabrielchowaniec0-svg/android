package com.kinetic.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.kinetic.launcher.FocusModes

/** Simple filled icons for Focus modes (drawn, so they're crisp at any size). */
@Composable
fun FocusGlyph(glyph: FocusModes.Glyph, color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val s = minOf(w, h)
    when (glyph) {
        FocusModes.Glyph.Moon -> {
            // A crescent: a circle with a bite taken out.
            val path = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w / 2, h / 2), s * 0.46f))
            }
            val bite = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w / 2 + s * 0.24f, h / 2 - s * 0.18f), s * 0.38f))
            }
            val crescent = Path().apply { op(path, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
            drawPath(crescent, color)
        }
        FocusModes.Glyph.Bed -> {
            drawRoundRect(color, Offset(s * 0.06f, h * 0.48f), Size(w - s * 0.12f, h * 0.24f), CornerRadius(s * 0.06f))
            drawRoundRect(color, Offset(s * 0.06f, h * 0.22f), Size(s * 0.12f, h * 0.66f), CornerRadius(s * 0.05f))
            drawRoundRect(color, Offset(s * 0.24f, h * 0.32f), Size(s * 0.24f, h * 0.16f), CornerRadius(s * 0.07f))
            drawRect(color, Offset(w - s * 0.16f, h * 0.48f), Size(s * 0.1f, h * 0.4f))
        }
        FocusModes.Glyph.Book -> {
            drawRoundRect(color, Offset(w * 0.08f, h * 0.18f), Size(w * 0.39f, h * 0.64f), CornerRadius(s * 0.06f))
            drawRoundRect(color, Offset(w * 0.53f, h * 0.18f), Size(w * 0.39f, h * 0.64f), CornerRadius(s * 0.06f))
        }
        FocusModes.Glyph.Gamepad -> {
            drawRoundRect(color, Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.44f), CornerRadius(s * 0.22f))
            val dark = Color.Black.copy(alpha = 0.55f)
            drawRect(dark, Offset(w * 0.2f, h * 0.46f), Size(w * 0.18f, h * 0.07f))
            drawRect(dark, Offset(w * 0.255f, h * 0.395f), Size(w * 0.07f, h * 0.18f))
            drawCircle(dark, s * 0.05f, Offset(w * 0.68f, h * 0.44f))
            drawCircle(dark, s * 0.05f, Offset(w * 0.78f, h * 0.55f))
        }
        FocusModes.Glyph.Person -> {
            drawCircle(color, s * 0.18f, Offset(w / 2, h * 0.32f))
            drawRoundRect(color, Offset(w * 0.2f, h * 0.56f), Size(w * 0.6f, h * 0.34f), CornerRadius(s * 0.2f))
        }
        FocusModes.Glyph.Briefcase -> {
            drawRoundRect(color, Offset(w * 0.08f, h * 0.3f), Size(w * 0.84f, h * 0.54f), CornerRadius(s * 0.08f))
            drawRoundRect(color, Offset(w * 0.34f, h * 0.16f), Size(w * 0.32f, h * 0.2f), CornerRadius(s * 0.05f), style = Stroke(s * 0.08f))
        }
        FocusModes.Glyph.Car -> {
            drawRoundRect(color, Offset(w * 0.06f, h * 0.42f), Size(w * 0.88f, h * 0.3f), CornerRadius(s * 0.1f))
            val roof = Path().apply {
                moveTo(w * 0.22f, h * 0.44f)
                lineTo(w * 0.32f, h * 0.22f)
                lineTo(w * 0.68f, h * 0.22f)
                lineTo(w * 0.78f, h * 0.44f)
                close()
            }
            drawPath(roof, color)
            drawCircle(Color.Black.copy(alpha = 0.55f), s * 0.08f, Offset(w * 0.27f, h * 0.72f))
            drawCircle(Color.Black.copy(alpha = 0.55f), s * 0.08f, Offset(w * 0.73f, h * 0.72f))
        }
        FocusModes.Glyph.Leaf -> {
            val leaf = Path().apply {
                moveTo(w * 0.15f, h * 0.85f)
                cubicTo(w * 0.1f, h * 0.3f, w * 0.5f, h * 0.1f, w * 0.88f, h * 0.12f)
                cubicTo(w * 0.9f, h * 0.55f, w * 0.6f, h * 0.9f, w * 0.15f, h * 0.85f)
                close()
            }
            drawPath(leaf, color)
            drawLine(Color.Black.copy(alpha = 0.4f), Offset(w * 0.2f, h * 0.8f), Offset(w * 0.7f, h * 0.3f), strokeWidth = s * 0.05f, cap = StrokeCap.Round)
        }
    }
}

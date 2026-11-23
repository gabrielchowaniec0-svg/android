package com.kinetic.core.apps

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The One UI–style icon shape: a superellipse ("squircle"). Used both to cut
 * every app icon to the same outline and for shadows/bevels in the UI, so
 * everything shares exactly one shape.
 */
object Squircle {
    /** Higher = squarer. ~4.5 is close to One UI's icon shape. */
    private const val EXPONENT = 4.5
    private const val STEPS = 96

    fun androidPath(width: Float, height: Float, out: android.graphics.Path = android.graphics.Path()): android.graphics.Path {
        out.reset()
        val a = width / 2f
        val b = height / 2f
        val e = 2.0 / EXPONENT
        for (i in 0..STEPS) {
            val t = 2.0 * Math.PI * i / STEPS
            val c = cos(t)
            val s = sin(t)
            val x = a + (a * sign(c) * abs(c).pow(e)).toFloat()
            val y = b + (b * sign(s) * abs(s).pow(e)).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }
}

/** Compose [Shape] for the squircle (shadows, clipping, bevel outlines). */
object SquircleShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Squircle.androidPath(size.width, size.height).asComposePath())
}

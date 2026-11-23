package com.kinetic.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/**
 * Where a widget's own card actually is inside its cell (many widgets, like the
 * weather card or the search bar, draw a card with a margin around it), and how
 * rounded its corners are. Pixel units, relative to the widget view.
 */
data class WidgetEdges(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val radius: Float,
    /** False for widgets that are just floating text/icons with no card behind them. */
    val isCard: Boolean,
)

object WidgetEdgeDetector {
    private const val SCALE = 4         // analyse at 1/4 size: fast and plenty accurate
    private const val OPAQUE = 128

    /** Renders the widget off-screen and finds its card. Null if it can't be drawn yet. */
    fun detect(view: View): WidgetEdges? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val bw = (w / SCALE).coerceAtLeast(1)
        val bh = (h / SCALE).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.scale(bw.toFloat() / w, bh.toFloat() / h)
            view.draw(canvas)
        } catch (e: Exception) {
            // e.g. a widget using hardware-only bitmaps; fall back to the whole cell
            bmp.recycle()
            return null
        }

        var minX = bw
        var minY = bh
        var maxX = -1
        var maxY = -1
        var opaqueCount = 0
        val row = IntArray(bw)
        for (y in 0 until bh) {
            bmp.getPixels(row, 0, bw, 0, y, bw, 1)
            for (x in 0 until bw) {
                if (Color.alpha(row[x]) > OPAQUE) {
                    opaqueCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) {
            bmp.recycle()
            return null
        }

        // A card fills (almost) all of its bounding box; loose text/icons don't.
        val boxArea = (maxX - minX + 1) * (maxY - minY + 1)
        val isCard = opaqueCount >= boxArea * 0.8f

        // Corner radius: walk in diagonally from the top-left corner until we hit the card.
        // A rounded corner of radius r leaves r·(1 − 1/√2) transparent along the diagonal.
        val limit = min(maxX - minX, maxY - minY) / 2
        var d = 0
        while (d < limit && Color.alpha(bmp.getPixel(minX + d, minY + d)) <= OPAQUE) d++
        bmp.recycle()

        val sx = w.toFloat() / bw
        val sy = h.toFloat() / bh
        return WidgetEdges(
            left = minX * sx,
            top = minY * sy,
            right = (maxX + 1) * sx,
            bottom = (maxY + 1) * sy,
            radius = d * 3.414f * sx,
            isCard = isCard,
        )
    }
}

/** A rounded rectangle exactly on the widget's own card edges (at least [minRadius] round). */
data class WidgetCardShape(val edges: WidgetEdges, val minRadius: Float) : Shape {
    fun radiusFor(width: Float, height: Float): Float {
        val w = min(edges.right, width) - max(edges.left, 0f)
        val h = min(edges.bottom, height) - max(edges.top, 0f)
        return max(edges.radius, minRadius).coerceAtMost(min(w, h) / 2f)
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = max(edges.left, 0f)
        val top = max(edges.top, 0f)
        val right = min(edges.right, size.width)
        val bottom = min(edges.bottom, size.height)
        val r = radiusFor(size.width, size.height)
        return Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius(r, r)))
    }
}

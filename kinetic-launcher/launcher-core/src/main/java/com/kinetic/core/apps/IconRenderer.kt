package com.kinetic.core.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

/**
 * Draws any app icon into the same squircle at the same size, so the grid looks
 * consistent like One UI:
 *  - adaptive icons: background + foreground layers, cut to the squircle
 *  - full-bleed square/rounded legacy icons (e.g. Netlify): scaled to fill, then cut
 *  - logo-only legacy icons with transparent edges: placed on a white squircle
 */
class IconRenderer(private val sizePx: Int) {

    private val clip = Squircle.androidPath(sizePx.toFloat(), sizePx.toFloat())
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(icon: Drawable): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.save()
        canvas.clipPath(clip)
        if (icon is AdaptiveIconDrawable) {
            drawAdaptive(canvas, icon)
        } else {
            drawLegacy(canvas, icon)
        }
        canvas.restore()
        return out
    }

    private fun drawAdaptive(canvas: Canvas, icon: AdaptiveIconDrawable) {
        // Layers are 108dp with the visible area being the middle 72dp (2/3), so
        // draw them 1.5× larger, centred, and let the squircle clip the bleed.
        val full = (sizePx * 1.5f).roundToInt()
        val offset = (sizePx - full) / 2
        val bounds = Rect(offset, offset, offset + full, offset + full)
        val background = icon.background
        if (background != null) {
            background.bounds = bounds
            background.draw(canvas)
        } else {
            canvas.drawColor(Color.WHITE)
        }
        icon.foreground?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
    }

    private fun drawLegacy(canvas: Canvas, icon: Drawable) {
        val src = icon.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        if (isFullBleed(src)) {
            // Slightly enlarge so the icon's own rounded corners fall outside our shape.
            val grow = sizePx * 0.04f
            canvas.drawBitmap(src, null, RectF(-grow, -grow, sizePx + grow, sizePx + grow), bitmapPaint)
        } else {
            canvas.drawColor(Color.WHITE)
            val inset = sizePx * 0.15f
            canvas.drawBitmap(src, null, RectF(inset, inset, sizePx - inset, sizePx - inset), bitmapPaint)
        }
    }

    /** True if the icon is opaque out to its edges (a square or rounded-square tile). */
    private fun isFullBleed(b: Bitmap): Boolean {
        val n = b.width
        val near = (n * 0.06f).roundToInt().coerceAtLeast(1)
        val mid = n / 2
        val samples = listOf(
            near to mid, n - 1 - near to mid, mid to near, mid to n - 1 - near,
            // diagonals near the corners: inside a rounded square, outside a circle
            near * 2 to near * 2, n - 1 - near * 2 to near * 2,
            near * 2 to n - 1 - near * 2, n - 1 - near * 2 to n - 1 - near * 2,
        )
        return samples.all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 200 }
    }
}

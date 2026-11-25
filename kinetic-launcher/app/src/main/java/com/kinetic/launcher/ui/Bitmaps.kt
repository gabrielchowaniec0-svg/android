package com.kinetic.launcher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Opens pictures at the size they're needed, never the full original: a
 * 200-megapixel photo from the S25 Ultra would need about 800 MB at full size.
 */
object Bitmaps {
    /** [open] is called twice (once to measure, once to read). */
    fun decode(target: Int, open: () -> InputStream?): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
            open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    } catch (_: Throwable) {
        // Includes running out of memory: show nothing rather than crash.
        null
    }
}

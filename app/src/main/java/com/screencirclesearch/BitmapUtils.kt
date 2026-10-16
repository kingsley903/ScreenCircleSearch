package com.screencirclesearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log

/**
 * Small bitmap helpers shared by the screenshot pipeline and the OCR stage.
 *
 * Everything in here is deliberately allocation friendly: screenshots taken on
 * a modern phone are 1080x2400 or larger, so we always work on a scaled down
 * copy and never keep more than one full size bitmap in memory at a time.
 */
internal object BitmapUtils {

    private const val TAG = "BitmapUtils"

    /**
     * Crops [source] to [rect] (bitmap pixel coordinates).
     *
     * The rectangle is clamped to the bitmap bounds, and a 1px inset is applied
     * so that rounding never produces an empty bitmap. Returns null if nothing
     * is left after clamping.
     */
    fun crop(source: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        if (right - left < 1 || bottom - top < 1) return null
        return try {
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "crop failed for $rect", e)
            null
        }
    }

    /**
     * Converts to greyscale. Tesseract's layout analysis is noticeably more
     * reliable on single channel images, and this also removes the colour
     * fringing that sub-pixel rendered text produces on screenshots.
     */
    fun toGrayscale(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Cheap "is this image just a black or white rectangle?" test.
     *
     * Screenshotting a secure window (password field, banking app, DRM video)
     * does not fail on Android - the system simply hands out black pixels. This
     * check lets us tell the user what is going on instead of running OCR on an
     * empty image and reporting "no text found".
     */
    fun looksBlank(source: Bitmap, sampleSize: Int = 48): Boolean {
        val w = minOf(sampleSize, source.width)
        val h = minOf(sampleSize, source.height)
        if (w < 2 || h < 2) return true
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()

        var sum = 0L
        var sumSq = 0L
        for (p in pixels) {
            val luma = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p))
            sum += luma.toLong()
            sumSq += (luma * luma).toLong()
        }
        val n = pixels.size
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        val stdDev = Math.sqrt(maxOf(variance, 0.0))

        // A completely flat image (no edges at all) is treated as blank, no
        // matter whether it is black or white.
        return stdDev < 2.0
    }

    /**
     * Mean luminance of an image, in 0..255. Used to decide whether the text is
     * light-on-dark (needs to be inverted before OCR).
     */
    fun meanLuma(source: Bitmap, sampleSize: Int = 64): Double {
        val w = minOf(sampleSize, source.width)
        val h = minOf(sampleSize, source.height)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()

        var sum = 0L
        for (p in pixels) {
            sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toLong()
        }
        return sum.toDouble() / pixels.size
    }
}

package com.screencirclesearch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.RectF
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.DisplayManager
import android.view.WindowManager
import android.hardware.display.VirtualDisplay
import com.screencirclesearch.BitmapUtils.looksBlank
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Takes a screenshot of the whole screen using [MediaProjection].
 *
 * This is the cross-version replacement for the API 30
 * [android.accessibilityservice.AccessibilityService.takeScreenshot] call:
 * MediaProjection has been public since API 21 (Android 5.0), so the app now
 * installs and runs on devices between API 21 and API 30+.
 *
 * The MediaProjection permission is granted **once** (system "Screen capture?"
 * dialog) and the resulting [MediaProjection] token is kept for reuse, so later
 * captures do not re-prompt the user. Each capture builds a throwaway
 * [VirtualDisplay] + [ImageReader], reads one frame and tears them down while the
 * [MediaProjection] itself stays alive.
 *
 * Usage:
 * 1. [requestCapture] is called on the foreground [Activity] with a listener.
 *    If the permission has not been granted yet, the activity-result flow is
 *    started via [launchIntent]; otherwise capture starts immediately.
 * 2. Forward the activity result to [handleActivityResult].
 * 3. A [VirtualDisplay] renders into an [ImageReader]; the first frame is read
 *    back as a software [Bitmap] on a background thread and handed to the
 *    listener on the main thread.
 * 4. The caller owns the bitmap and must recycle it. Call [release] on shutdown.
 */
class ScreenCaptureHelper(private val activity: Activity) {

    /** Result of a capture attempt. */
    sealed class Outcome {
        data class Success(val bitmap: Bitmap) : Outcome()
        data class Failure(val reason: Reason) : Outcome()
    }

    /** Why a screenshot could not be taken, mapped to a user facing message. */
    enum class Reason(val messageRes: Int) {
        UNAVAILABLE(R.string.error_capture_unavailable),
        INTERVAL_TOO_SHORT(R.string.error_capture_interval),
        SECURE_WINDOW(R.string.error_capture_secure),
        INCOMPATIBLE(R.string.error_capture_incompatible),
        DECODE_FAILED(R.string.error_capture_generic),
        BLANK(R.string.error_capture_blank),
    }

    private val context: Context = activity.applicationContext
    private val projectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    /** The persistent MediaProjection token, valid across repeated captures. */
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    /** Listener carried across the permission-dialog round trip. */
    private var pendingListener: ((Outcome) -> Unit)? = null

    private val minCaptureIntervalMs = 500L
    private var lastCaptureAt = 0L

    /** True once the MediaProjection permission has been granted this session. */
    fun isReady(): Boolean = mediaProjection != null

    /**
     * Starts a screenshot. If the MediaProjection permission has already been
     * granted, capture begins immediately; otherwise the system permission
     * dialog is launched via [launchIntent] and the result must be forwarded to
     * [handleActivityResult].
     *
     * [onResult] is invoked on the main thread.
     */
    fun requestCapture(
        onResult: (Outcome) -> Unit,
        launchIntent: (Intent) -> Unit = { intent ->
            activity.startActivityForResult(intent, REQUEST_CODE_CAPTURE)
        },
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastCaptureAt != 0L && now - lastCaptureAt < minCaptureIntervalMs) {
            deliver(onResult, Outcome.Failure(Reason.INTERVAL_TOO_SHORT))
            return
        }
        lastCaptureAt = now

        val projection = mediaProjection
        if (projection != null) {
            startCapture(projection, onResult)
            return
        }

        // Permission not granted yet: keep the listener and ask the user.
        pendingListener = onResult
        val intent = projectionManager.createScreenCaptureIntent()
        try {
            launchIntent(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no activity to start screen capture intent", e)
            pendingListener = null
            deliver(onResult, Outcome.Failure(Reason.UNAVAILABLE))
        }
    }

    /**
     * Forward the activity result from [Activity.onActivityResult] (or an
     * ActivityResultLauncher callback). Returns true when the request code matched.
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_CAPTURE) return false
        val cb = pendingListener
        pendingListener = null
        if (cb == null) return true
        if (resultCode != Activity.RESULT_OK || data == null) {
            deliver(cb, Outcome.Failure(Reason.UNAVAILABLE))
            return true
        }
        val projection = try {
            projectionManager.getMediaProjection(Activity.RESULT_OK, data)
        } catch (e: Exception) {
            Log.e(TAG, "could not obtain MediaProjection", e)
            deliver(cb, Outcome.Failure(Reason.UNAVAILABLE))
            return true
        }
        mediaProjection = projection
        startCapture(projection, cb)
        return true
    }

    private fun startCapture(projection: MediaProjection, onResult: (Outcome) -> Unit) {
        // Drop any previous in-flight capture before starting a new one.
        teardownCapture()

        val size = displaySize()
        if (size.x <= 0 || size.y <= 0) {
            deliver(onResult, Outcome.Failure(Reason.INCOMPATIBLE))
            return
        }

        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ImageFormat.RGBA_8888
        } else {
            ImageFormat.YUV_420_888
        }
        val densityDpi = context.resources.displayMetrics.densityDpi
        val reader = try {
            ImageReader.newInstance(size.x, size.y, format, /*maxImages=*/2)
        } catch (e: Exception) {
            Log.e(TAG, "could not create ImageReader", e)
            deliver(onResult, Outcome.Failure(Reason.INCOMPATIBLE))
            return
        }
        imageReader = reader
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, mainHandler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCircleSearch",
                size.x, size.y, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "could not create VirtualDisplay", e)
            // The projection is likely stale/revoked — forget it so the next
            // tap re-prompts the user instead of failing forever.
            mediaProjection = null
            teardownCapture()
            deliver(onResult, Outcome.Failure(Reason.INCOMPATIBLE))
            return
        }
        // The ImageReader listener owns the listener until a frame arrives.
        pendingListener = onResult
    }

    private fun onImageAvailable(reader: ImageReader) {
        val cb = pendingListener
        if (cb == null) return
        pendingListener = null
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        if (image == null) return
        // Decode the (potentially large) image off the main thread.
        bgExecutor.execute {
            var bitmap: Bitmap? = null
            var decodeFailed = false
            try {
                bitmap = toBitmap(image)
            } catch (e: Exception) {
                Log.e(TAG, "failed to decode image", e)
                decodeFailed = true
            } finally {
                try { image.close() } catch (e: Exception) { }
                // The VirtualDisplay/ImageReader are throwaway; only stop them
                // here. The MediaProjection itself is kept for reuse.
                teardownCapture()
            }

            val outcome = if (decodeFailed || bitmap == null) {
                Outcome.Failure(Reason.DECODE_FAILED)
            } else if (looksBlank(bitmap)) {
                bitmap.recycle()
                Outcome.Failure(Reason.BLANK)
            } else {
                Outcome.Success(bitmap)
            }
            mainHandler.post { deliver(cb, outcome) }
        }
    }

    private fun toBitmap(image: Image): Bitmap? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null
        return when (image.format) {
            ImageFormat.RGBA_8888 -> rgbaToBitmap(image, w, h)
            ImageFormat.YUV_420_888 -> yuvToBitmap(image, w, h)
            else -> yuvToBitmap(image, w, h)
        }
    }

    /** Converts a single-plane RGBA_8888 image into an ARGB_8888 bitmap. */
    private fun rgbaToBitmap(image: Image, w: Int, h: Int): Bitmap? {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                buffer.position(rowStart + x * pixelStride)
                // RGBA_8888 lays bytes out as R, G, B, A in memory.
                val r = buffer.get().toInt() and 0xff
                val g = buffer.get().toInt() and 0xff
                val b = buffer.get().toInt() and 0xff
                val a = buffer.get().toInt() and 0xff
                pixels[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /** Converts a YUV_420_888 image into an ARGB_8888 bitmap (API 21 fallback). */
    private fun yuvToBitmap(image: Image, w: Int, h: Int): Bitmap? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBytes = ByteArray(yPlane.buffer.remaining())
        val uBytes = ByteArray(uPlane.buffer.remaining())
        val vBytes = ByteArray(vPlane.buffer.remaining())
        if (yBytes.isEmpty() || uBytes.isEmpty() || vBytes.isEmpty()) return null
        yPlane.buffer.get(yBytes)
        uPlane.buffer.get(uBytes)
        vPlane.buffer.get(vBytes)

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            val yRow = row * yRowStride
            val uRow = (row / 2) * uRowStride
            val vRow = (row / 2) * vRowStride
            for (col in 0 until w) {
                val yVal = (yBytes[yRow + col * yPixelStride].toInt() and 0xff)
                val uVal = (uBytes[uRow + (col / 2) * uPixelStride].toInt() and 0xff) - 128
                val vVal = (vBytes[vRow + (col / 2) * vPixelStride].toInt() and 0xff) - 128

                // BT.601 full range. Shift operators bind looser than + in
                // Kotlin, so the per-channel term is wrapped before the shift.
                var r = yVal + ((91881 * vVal + 32768) shr 16)
                var g = yVal - ((22554 * uVal + 46839 * vVal + 32768) shr 16)
                var b = yVal + ((116130 * uVal + 32768) shr 16)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                pixels[row * w + col] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun displaySize(): Point {
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            size.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
        }
        return size
    }

    private fun deliver(onResult: (Outcome) -> Unit, outcome: Outcome) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onResult(outcome)
        } else {
            mainHandler.post { onResult(outcome) }
        }
    }

    private fun teardownCapture() {
        try { virtualDisplay?.release() } catch (e: Exception) { }
        imageReader?.let { try { it.close() } catch (e: Exception) { } }
        virtualDisplay = null
        imageReader = null
    }

    private fun releaseProjection() {
        teardownCapture()
        mediaProjection?.let { try { it.stop() } catch (e: Exception) { } }
        mediaProjection = null
    }

    /** Call when the host Activity is torn down. */
    fun release() {
        pendingListener = null
        releaseProjection()
        bgExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "ScreenCaptureHelper"
        const val REQUEST_CODE_CAPTURE = 1001

        fun minSelectionSize(bitmapWidth: Int, bitmapHeight: Int): Int =
            max(48, (minOf(bitmapWidth, bitmapHeight) * 0.06f).roundToInt())

        fun normalizeRect(left: Float, top: Float, right: Float, bottom: Float): RectF =
            RectF(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

        fun isTap(x1: Float, y1: Float, x2: Float, y2: Float, slopPx: Float = 24f): Boolean =
            abs(x1 - x2) <= slopPx && abs(y1 - y2) <= slopPx
    }
}

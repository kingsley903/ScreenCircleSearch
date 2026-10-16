package com.screencirclesearch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.screencirclesearch.BitmapUtils.looksBlank
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Thin wrapper around [android.accessibilityservice.AccessibilityService.takeScreenshot],
 * the only public API on Android 11 (API 30) that can capture another app's
 * screen.
 *
 * The flow is asynchronous: the system hands us a [HardwareBuffer] plus a
 * [android.graphics.ColorSpace] and we have to turn that into a regular
 * software [Bitmap] before it can be cropped, drawn or passed to Tesseract.
 */
class ScreenCaptureHelper(private val appContext: Context) {

    /** Result of a capture attempt. */
    sealed class Outcome {
        data class Success(val bitmap: Bitmap) : Outcome()

        data class Failure(val reason: Reason) : Outcome()
    }

    /** Why a screenshot could not be taken, mapped to a user facing message. */
    enum class Reason(val messageRes: Int) {
        /** AccessibilityService not connected, or canTakeScreenshot() == false. */
        UNAVAILABLE(R.string.error_capture_unavailable),

        /** The system refuses two requests that are too close together. */
        INTERVAL_TOO_SHORT(R.string.error_capture_interval),

        /** FLAG_SECURE window: Android blanks the content out. */
        SECURE_WINDOW(R.string.error_capture_secure),

        /** Device/OEM does not implement the API 30 screenshot call. */
        INCOMPATIBLE(R.string.error_capture_incompatible),

        /** Got a buffer back but could not decode it. */
        DECODE_FAILED(R.string.error_capture_generic),

        /** Screenshot succeeded but the result is a flat, empty image. */
        BLANK(R.string.error_capture_blank),
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    /**
     * AccessibilityService.takeScreenshot() fails with
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT when two requests are made
     * within a fraction of a second. We throttle to be safe - the user has to
     * tap the bubble, so this never blocks normal use.
     */
    private var lastCaptureAt = 0L

    /**
     * True when we can reasonably expect takeScreenshot() to succeed.
     *
     * There is deliberately no call to AccessibilityService.canTakeScreenshot()
     * here: that method exists in the framework but is not part of the public
     * API 30 SDK (it is marked @hide), so referencing it would not compile
     * against android-30. What we can check publicly is that our service is
     * actually listed as enabled - takeScreenshot() fails with
     * ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS otherwise. The
     * `android:canTakeScreenshot="true"` attribute in
     * res/xml/accessibility_service_config.xml is what declares that this
     * service may capture the screen at all.
     */
    fun canCapture(service: AccessibilityService?): Boolean =
        service != null && SettingsCompat.isAccessibilityServiceEnabled(appContext)

    /**
     * Requests a screenshot of the default display.
     *
     * [onResult] is always invoked on the main thread so the caller can touch
     * views directly. The bitmap is owned by the caller and must be recycled.
     */
    fun capture(
        service: AccessibilityService?,
        onResult: (Outcome) -> Unit,
    ) {
        if (!canCapture(service)) {
            deliver(onResult, Outcome.Failure(Reason.UNAVAILABLE))
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (lastCaptureAt != 0L && now - lastCaptureAt < MIN_CAPTURE_INTERVAL_MS) {
            deliver(onResult, Outcome.Failure(Reason.INTERVAL_TOO_SHORT))
            return
        }
        lastCaptureAt = now

        try {
            service!!.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = toSoftwareBitmap(screenshot)
                        if (bitmap == null) {
                            deliver(onResult, Outcome.Failure(Reason.DECODE_FAILED))
                            return
                        }
                        if (looksBlank(bitmap)) {
                            // Almost always FLAG_SECURE (password fields, DRM).
                            bitmap.recycle()
                            deliver(onResult, Outcome.Failure(Reason.BLANK))
                            return
                        }
                        deliver(onResult, Outcome.Success(bitmap))
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed with code $errorCode")
                        deliver(onResult, Outcome.Failure(mapErrorCode(errorCode)))
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "takeScreenshot threw", e)
            deliver(onResult, Outcome.Failure(Reason.DECODE_FAILED))
        }
    }

    /**
     * Converts the ScreenshotResult into a mutable ARGB_8888 bitmap.
     *
     * The result is a HardwareBuffer in a possibly wide colour space
     * (Display.P3 on some devices), which the CPU cannot read directly.
     * Bitmap.wrapHardwareBuffer() maps it, and copy() then produces a CPU side
     * bitmap we can crop, draw and finally hand to Tesseract.
     */
    @Suppress("DEPRECATION")
    private fun toSoftwareBitmap(
        screenshot: AccessibilityService.ScreenshotResult,
    ): Bitmap? {
        val buffer = try {
            // getHardwareBuffer() is the API 30 accessor. It is only deprecated
            // from API 33 on, where getHardwareBuffers() (a list) took over, but
            // the single-buffer variant still works and is what we need.
            screenshot.hardwareBuffer
        } catch (e: Throwable) {
            Log.e(TAG, "could not read HardwareBuffer", e)
            null
        } ?: return null

        return try {
            val colorSpace = try {
                screenshot.colorSpace
            } catch (e: Throwable) {
                null
            }

            // Bitmap.wrapHardwareBuffer() is the API 30 public entry point.
            // Bitmap.wrap(Buffer, ColorSpace) is @hide and cannot be used.
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
            if (wrapped == null) {
                Log.e(TAG, "wrapHardwareBuffer() returned null")
                return null
            }

            val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()

            if (software != null && software.width > 0 && software.height > 0) {
                software
            } else {
                software?.recycle()
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to decode HardwareBuffer", e)
            null
        } finally {
            // The buffer is a shared resource: always hand it back, otherwise
            // the compositor runs out of buffers.
            runCatching { buffer.close() }
        }
    }

    /**
     * Maps the platform error codes of takeScreenshot().
     *
     * These are the four constants that actually exist in the API 30 SDK. The
     * SECURE_WINDOW / INCOMPATIBLE reasons are not reported through onFailure
     * on Android 11 - a secure window comes back as flat black pixels and is
     * detected by the blank-image check instead.
     */
    private fun mapErrorCode(errorCode: Int): Reason = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            Reason.INTERVAL_TOO_SHORT

        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            Reason.UNAVAILABLE

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            Reason.INCOMPATIBLE

        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            Reason.DECODE_FAILED

        else -> Reason.DECODE_FAILED
    }

    private fun deliver(onResult: (Outcome) -> Unit, outcome: Outcome) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onResult(outcome)
        } else {
            mainHandler.post { onResult(outcome) }
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureHelper"
        private const val MIN_CAPTURE_INTERVAL_MS = 500L

        /**
         * Smallest sensible selection in bitmap pixels, scaled by screen size
         * so that the same threshold works on a 720p and a 1440p device.
         */
        fun minSelectionSize(bitmapWidth: Int, bitmapHeight: Int): Int =
            max(48, (minOf(bitmapWidth, bitmapHeight) * 0.06f).roundToInt())

        /** Utility used by the selection view to normalise a drag rectangle. */
        fun normalizeRect(left: Float, top: Float, right: Float, bottom: Float): RectF =
            RectF(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

        /** True when both coordinates are effectively the same point. */
        fun isTap(x1: Float, y1: Float, x2: Float, y2: Float, slopPx: Float = 24f): Boolean =
            abs(x1 - x2) <= slopPx && abs(y1 - y2) <= slopPx
    }
}

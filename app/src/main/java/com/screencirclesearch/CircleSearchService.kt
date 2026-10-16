package com.screencirclesearch

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The engine of the app.
 *
 * Why an AccessibilityService?
 *  - On Android 11 there is exactly one public API that can screenshot another
 *    app: [takeScreenshot], which lives on this class.
 *  - A connected service also keeps our process alive, which is what allows the
 *    floating button to stay on screen while the user is in another app.
 *
 * The service itself never inspects window content; it only reacts to the user
 * tapping our own button.
 *
 * Flow:
 *   tap bubble -> hide bubble -> screenshot -> selection overlay
 *              -> crop -> OCR (offline) -> result panel -> copy / web search
 */
class CircleSearchService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * AccessibilityService.getWindow() is @hide in the public SDK, so the
     * overlay windows are created through the regular system service instead.
     */
    private val overlayWindowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private lateinit var captureHelper: ScreenCaptureHelper
    private lateinit var ocrManager: OCRManager

    private var bubble: FloatingBubble? = null
    private var selection: SelectionOverlayController? = null
    private var resultPanel: ResultPanelOverlay? = null

    /** Set while a capture is in flight so overlapping taps are ignored. */
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")

        captureHelper = ScreenCaptureHelper(applicationContext)
        ocrManager = OCRManager(applicationContext)

        // Let MainActivity know the state changed.
        listeners.forEach { it.onServiceConnected(this) }

        if (SettingsCompat.canDrawOverlays(this)) {
            ensureBubbleAttached()
        } else {
            Toast.makeText(this, R.string.status_overlay_missing, Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Intentionally empty: no window content is read. The service exists
        // only as a screenshot provider and as a host for the overlay windows.
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onDestroy() {
        Log.i(TAG, "AccessibilityService destroyed")
        teardownOverlays()
        if (this::ocrManager.isInitialized) ocrManager.release()
        serviceScope.cancel()
        if (instance === this) instance = null
        listeners.forEach { it.onServiceDestroyed() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // API used by MainActivity
    // ---------------------------------------------------------------------

    /** Whether the user wants the floating button on screen. */
    var bubbleEnabled: Boolean = true
        private set

    fun setBubbleEnabled(enabled: Boolean) {
        bubbleEnabled = enabled
        if (enabled) {
            ensureBubbleAttached()?.setVisible(true)
        } else {
            bubble?.detach()
            bubble = null
        }
    }

    /** True when both required permissions are in place. */
    val isReady: Boolean
        get() = SettingsCompat.canDrawOverlays(this)

    /**
     * Lets MainActivity refresh its UI when the service connects or goes away.
     *
     * Declared in the class body rather than in the companion object so that
     * external code can write `CircleSearchService.ConnectionListener`.
     */
    interface ConnectionListener {
        fun onServiceConnected(service: CircleSearchService) {}
        fun onServiceDestroyed() {}
    }

    // ---------------------------------------------------------------------
    // Capture flow
    // ---------------------------------------------------------------------

    /**
     * Entry point for the bubble tap.
     *
     * The bubble is hidden before the screenshot is taken - otherwise the user
     * would be selecting our own button instead of the content underneath.
     */
    private fun startSelection() {
        if (busy) return
        if (!SettingsCompat.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.status_overlay_missing, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        bubble?.setVisible(false)

        captureHelper.capture(this) { outcome ->
            when (outcome) {
                is ScreenCaptureHelper.Outcome.Success -> showSelectionOverlay(outcome.bitmap)

                is ScreenCaptureHelper.Outcome.Failure -> {
                    busy = false
                    restoreBubble()
                    Toast.makeText(this, outcome.reason.messageRes, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showSelectionOverlay(screenshot: Bitmap) {
        val controller = SelectionOverlayController(this, overlayWindowManager)
        // The controller hands the bitmap over instead of recycling it.
        controller.onSelectionConfirmed = { rect, bitmap -> onSelectionConfirmed(bitmap, rect) }
        controller.onCancelled = {
            busy = false
            restoreBubble()
        }
        selection = controller
        controller.attach(screenshot)
    }

    /**
     * The user confirmed a region: crop it, run OCR and release the full
     * screenshot. Nothing is ever written to disk.
     */
    private fun onSelectionConfirmed(screenshot: Bitmap, rect: Rect) {
        val crop = BitmapUtils.crop(screenshot, rect)
        // The crop is an independent copy, so the full screenshot can go now.
        screenshot.recycle()
        selection = null

        if (crop == null) {
            busy = false
            restoreBubble()
            Toast.makeText(this, R.string.result_no_text, Toast.LENGTH_LONG).show()
            return
        }

        val panel = resultPanel ?: createResultPanel()
        panel.attach()
        panel.showLoading()

        serviceScope.launch {
            val result = ocrManager.recognize(crop)
            crop.recycle()

            val fallback = getString(
                if (result.error == OCRManager.Reason.ENGINE_NOT_READY) {
                    R.string.result_ocr_failed
                } else {
                    R.string.result_no_text
                }
            )
            // No-op if the user already closed the panel while OCR was running.
            panel.showResult(result, fallback)
        }
    }

    private fun createResultPanel(): ResultPanelOverlay {
        val panel = ResultPanelOverlay(this, overlayWindowManager)
        // "New selection" simply restarts the flow.
        panel.onNewSelection = { startSelection() }
        // Whenever the panel goes away - close button, tap outside, web search -
        // the bubble comes back and the flow is ready for the next selection.
        panel.onDismissed = {
            busy = false
            restoreBubble()
        }
        resultPanel = panel
        return panel
    }

    private fun ensureBubbleAttached(): FloatingBubble? {
        if (!SettingsCompat.canDrawOverlays(this)) return null
        bubble?.let { return it }

        val created = FloatingBubble(this, overlayWindowManager).apply {
            onSearchRequested = { startSelection() }
        }
        bubble = created
        created.attach()
        created.setVisible(bubbleEnabled)
        return created
    }

    private fun restoreBubble() {
        if (SettingsCompat.canDrawOverlays(this)) {
            ensureBubbleAttached()?.setVisible(bubbleEnabled)
        }
    }

    private fun teardownOverlays() {
        selection?.detach()
        selection = null
        resultPanel?.detach()
        resultPanel = null
        bubble?.detach()
        bubble = null
    }

    companion object {
        private const val TAG = "CircleSearchService"

        /**
         * The connected service instance. An Activity cannot bind to the
         * service (the system does that), so the instance is published here.
         */
        @Volatile
        var instance: CircleSearchService? = null
            private set

        private val listeners = LinkedHashSet<ConnectionListener>()

        @Synchronized
        fun addListener(listener: ConnectionListener) {
            listeners.add(listener)
            instance?.let { listener.onServiceConnected(it) }
        }

        @Synchronized
        fun removeListener(listener: ConnectionListener) {
            listeners.remove(listener)
        }
    }
}

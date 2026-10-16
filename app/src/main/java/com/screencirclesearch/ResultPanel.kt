package com.screencirclesearch

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.screencirclesearch.databinding.OverlayResultBinding

/**
 * The result window: a Material card drawn as a system overlay so the
 * recognised text shows up on top of the app the user was reading.
 *
 * Actions offered: copy to clipboard, open a web search, start a new selection
 * or simply dismiss.
 */
class ResultPanelOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    /** "New selection" - go straight back into the capture flow. */
    var onNewSelection: (() -> Unit)? = null

    /** Fired whenever the panel window leaves the screen, however it happened. */
    var onDismissed: (() -> Unit)? = null

    private var binding: OverlayResultBinding? = null
    private var attached = false
    private var recognisedText: String = ""

    fun attach() {
        if (attached) return

        val inflated = OverlayResultBinding.inflate(LayoutInflater.from(context))
        binding = inflated
        showLoading()

        inflated.resultSearch.setOnClickListener { search() }
        inflated.resultCopy.setOnClickListener { copy() }
        inflated.resultNewSelection.setOnClickListener {
            val callback = onNewSelection
            detach()
            callback?.invoke()
        }
        inflated.resultClose.setOnClickListener { detach() }
        // Tapping the dimmed background dismisses the panel.
        inflated.resultRoot.setOnClickListener { detach() }

        try {
            windowManager.addView(inflated.root, panelParams())
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "could not add the result panel", e)
            attached = false
            binding = null
            // Tell the caller the flow is over so it can recover.
            onDismissed?.invoke()
        }
    }

    /** Switches the panel into its "recognising..." state. */
    fun showLoading() {
        val view = binding ?: return
        view.resultProgress.visibility = View.VISIBLE
        view.resultConfidence.visibility = View.GONE
        view.resultText.setText(R.string.result_loading)
        view.resultSearch.isEnabled = false
        view.resultCopy.isEnabled = false
    }

    /**
     * Fills the panel with the OCR outcome. [message] is already resolved to a
     * user facing string so this class does not need to know about error types.
     */
    fun showResult(result: OCRManager.Result, fallbackMessage: String) {
        val view = binding ?: return
        view.resultProgress.visibility = View.GONE

        if (!result.isSuccess) {
            recognisedText = ""
            view.resultConfidence.visibility = View.GONE
            view.resultText.setText(fallbackMessage)
            view.resultSearch.isEnabled = false
            view.resultCopy.isEnabled = false
            return
        }

        if (result.query.isBlank()) {
            recognisedText = ""
            view.resultConfidence.visibility = View.GONE
            view.resultText.setText(R.string.result_no_text)
            view.resultSearch.isEnabled = false
            view.resultCopy.isEnabled = false
            return
        }

        recognisedText = result.text
        view.resultText.setText(result.text)
        view.resultConfidence.visibility = View.VISIBLE
        view.resultConfidence.text =
            context.getString(R.string.result_confidence, result.confidence)
        view.resultSearch.isEnabled = true
        view.resultCopy.isEnabled = true
    }

    /** Copies the recognised text to the clipboard. */
    fun copy() {
        val text = recognisedText.trim()
        if (text.isEmpty()) {
            toast(R.string.result_copied_nothing)
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            toast(R.string.result_copied_nothing)
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        toast(R.string.result_copied)
    }

    /**
     * Opens the default browser with a web search for the recognised text.
     * This is the only place where the app touches the network (via the
     * browser), which is why INTERNET is declared.
     */
    fun search() {
        val query = recognisedText.trim()
        if (query.isEmpty()) {
            toast(R.string.result_nothing_to_search)
            return
        }
        val url = SEARCH_PREFIX + Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            detach()
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_no_browser)
        }
    }

    /** Removes the window. Safe to call when it is not attached. */
    fun detach() {
        if (!attached) {
            binding = null
            recognisedText = ""
            return
        }
        binding?.root?.let { runCatching { windowManager.removeViewImmediate(it) } }
        attached = false
        binding = null
        recognisedText = ""
        onDismissed?.invoke()
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ResultPanel"
        private const val CLIP_LABEL = "ScreenCircleSearch"
        private const val SEARCH_PREFIX = "https://www.google.com/search?q="

        /**
         * The panel is a plain full screen overlay, not focusable. The card
         * itself is centred by the layout.
         */
        fun panelParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
    }
}

package com.screencirclesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The draggable floating button that lives on top of every app.
 *
 * It is a plain TYPE_APPLICATION_OVERLAY window - no foreground service and no
 * notification are involved. The AccessibilityService owns it so the button
 * disappears together with the service when the user turns it off in settings.
 */
class FloatingBubble(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    /** Invoked on a real tap (not on the end of a drag). */
    var onSearchRequested: (() -> Unit)? = null

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val sizePx = (BUBBLE_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var bubble: ImageView? = null
    private var attached = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    private val density = context.resources.displayMetrics.density

    /** Adds the window. Does nothing when the overlay permission is missing. */
    fun attach() {
        if (attached) return

        val view = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            setImageResource(R.drawable.ic_search_lens)
            // The vector is white; make sure it stays white on the blue bubble.
            imageTintList = ContextCompat.getColorStateList(context, R.color.bubble_icon)
            background = ContextCompat.getDrawable(context, R.drawable.bubble_background)
            foreground = ContextCompat.getDrawable(context, R.drawable.bubble_ripple)
            contentDescription = context.getString(R.string.bubble_description)
            elevation = dp(6f)
            isClickable = true
        }

        val params = bubbleParams()
        try {
            windowManager.addView(view, params)
            bubble = view
            attached = true
            restorePosition(view, params)
        } catch (e: Exception) {
            // Almost always: "Display over other apps" has not been granted.
            Log.w(TAG, "cannot show the floating bubble", e)
            attached = false
            bubble = null
        }
    }

    /** Shows or hides the button without destroying it. */
    fun setVisible(visible: Boolean) {
        val view = bubble ?: return
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    val isVisible: Boolean
        get() = bubble?.visibility == View.VISIBLE

    /** Removes the window. */
    fun detach() {
        if (!attached) return
        bubble?.let { runCatching { windowManager.removeViewImmediate(it) } }
        bubble = null
        attached = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchListener(view: ImageView, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val clamped = clamp(startX + dx.roundToInt(), startY + dy.roundToInt())
                        params.x = clamped.first
                        params.y = clamped.second
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Remember the new position for the next launch.
                        preferences.edit()
                            .putInt(KEY_X, params.x)
                            .putInt(KEY_Y, params.y)
                            .apply()
                    } else {
                        onSearchRequested?.invoke()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** Keeps the bubble inside the display. */
    private fun clamp(x: Int, y: Int): Pair<Int, Int> {
        val bounds = displayBounds()
        val maxX = (bounds.width() - sizePx).coerceAtLeast(0)
        val maxY = (bounds.height() - sizePx).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    private fun restorePosition(view: ImageView, params: WindowManager.LayoutParams) {
        val display = displayBounds()
        val fallbackY = (display.height() * DEFAULT_Y_FRACTION).roundToInt()

        if (!preferences.contains(KEY_X) || !preferences.contains(KEY_Y)) {
            params.x = dp(6f).roundToInt()
            params.y = fallbackY
        } else {
            val (x, y) = clamp(
                preferences.getInt(KEY_X, 0),
                preferences.getInt(KEY_Y, fallbackY),
            )
            params.x = x
            params.y = y
        }
        // The clamp may have moved the button, so push it back into the window.
        runCatching { windowManager.updateViewLayout(view, params) }
        installTouchListener(view, params)
    }

    /**
     * Real display bounds, including any area excluded from the app layout by
     * the status bar or a display cutout.
     *
     * currentWindowMetrics() is the API 30 replacement for the deprecated
     * getDefaultDisplay().getRealMetrics(). android.util.Point is avoided on
     * purpose: it is not part of the public API 30 stub, so the fallback below
     * would not compile.
     */
    private fun displayBounds(): Rect {
        val bounds = windowManager.currentWindowMetrics.bounds
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun dp(value: Float): Float = value * density

    companion object {
        private const val TAG = "FloatingBubble"
        private const val PREFS_NAME = "floating_bubble"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val BUBBLE_SIZE_DP = 54f
        private const val DEFAULT_Y_FRACTION = 0.42f

        fun bubbleParams(): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
    }
}

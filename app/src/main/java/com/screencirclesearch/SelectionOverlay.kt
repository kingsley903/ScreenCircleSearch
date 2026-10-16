package com.screencirclesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.screencirclesearch.databinding.OverlaySelectionBinding
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shape the user draws to mark the area to read. */
enum class SelectionShape {
    CIRCLE,
    RECTANGLE,
    ;

    fun toggled(): SelectionShape = if (this == CIRCLE) RECTANGLE else CIRCLE
}

/**
 * The interactive layer of the selection overlay.
 *
 * It paints a scrim over the screenshot with a hole punched out where the user
 * is selecting, and translates touch events from view coordinates into pixel
 * coordinates of the screenshot bitmap. Doing the mapping against the exact
 * bounds the [android.widget.ImageView] was laid out with means we never have
 * to care about status bar insets, display cutouts or screen density.
 */
class SelectionShapeView(context: Context) : View(context) {

    /** Where the screenshot is drawn inside this view. */
    private val contentBounds = RectF()

    private var bitmapWidth = 0
    private var bitmapHeight = 0

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.selection_scrim, context.theme)
    }

    /** Xfermode used to punch the selection hole out of the scrim. */
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = resources.getColor(R.color.selection_stroke, context.theme)
        strokeWidth = dp(3f)
    }

    private val strokeInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.2f)
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var anchorX = 0f
    private var anchorY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var dragging = false

    /** Current selection in *view* coordinates. */
    val selection = RectF()

    var shape: SelectionShape = SelectionShape.CIRCLE
        set(value) {
            field = value
            invalidate()
        }

    /** Smallest selection we accept, in view pixels. */
    private var minSizePx = dp(64f)

    /** Fired whenever the selection changes, so the caller can update its UI. */
    var onSelectionChanged: (() -> Unit)? = null

    /** Fired when a drag was too small to be useful. */
    var onSelectionRejected: (() -> Unit)? = null

    init {
        // PorterDuff.Mode.CLEAR only works on a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Tells the view where the screenshot ended up so it can convert touch
     * coordinates into bitmap pixels.
     */
    fun setContentBounds(bounds: RectF, width: Int, height: Int) {
        contentBounds.set(bounds)
        bitmapWidth = width
        bitmapHeight = height
        minSizePx = ScreenCaptureHelper.minSelectionSize(width, height) *
            (if (bounds.width() > 0f) bounds.width() / width else 1f)
        invalidate()
    }

    val hasValidSelection: Boolean
        get() = selection.width() >= minSizePx && selection.height() >= minSizePx

    /** Clears the current selection (used when switching shape). */
    fun clearSelection() {
        selection.setEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Scrim over everything.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        if (selection.isEmpty) return

        // 2. Punch the selection out of the scrim.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)
        if (shape == SelectionShape.CIRCLE) {
            canvas.drawCircle(
                selection.centerX(),
                selection.centerY(),
                selection.width() / 2f,
                clearPaint,
            )
        } else {
            canvas.drawRect(selection, clearPaint)
        }

        // 3. Re-draw the shape as a crisp outline on top of everything.
        if (shape == SelectionShape.CIRCLE) {
            val radius = selection.width() / 2f
            canvas.drawCircle(selection.centerX(), selection.centerY(), radius, strokePaint)
            canvas.drawCircle(
                selection.centerX(),
                selection.centerY(),
                radius - dp(1.5f),
                strokeInnerPaint,
            )
        } else {
            canvas.drawRect(selection, strokePaint)
            val inset = RectF(selection).apply { inset(dp(1.5f), dp(1.5f)) }
            canvas.drawRect(inset, strokeInnerPaint)
        }

        // 4. Corner handles - only shown once the drag is finished.
        if (!dragging && hasValidSelection) {
            val r = dp(4f)
            if (shape == SelectionShape.CIRCLE) {
                val cx = selection.centerX()
                val cy = selection.centerY()
                val rad = selection.width() / 2f
                canvas.drawCircle(cx, cy - rad, r, handlePaint)
                canvas.drawCircle(cx, cy + rad, r, handlePaint)
                canvas.drawCircle(cx - rad, cy, r, handlePaint)
                canvas.drawCircle(cx + rad, cy, r, handlePaint)
            } else {
                canvas.drawCircle(selection.left, selection.top, r, handlePaint)
                canvas.drawCircle(selection.right, selection.top, r, handlePaint)
                canvas.drawCircle(selection.left, selection.bottom, r, handlePaint)
                canvas.drawCircle(selection.right, selection.bottom, r, handlePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anchorX = event.x
                anchorY = event.y
                currentX = event.x
                currentY = event.y
                dragging = true
                selection.set(anchorX, anchorY, anchorX, anchorY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                currentX = event.x
                currentY = event.y
                updateSelection()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                // Final position, then either accept or reject it.
                updateSelection()
                if (!hasValidSelection) {
                    onSelectionRejected?.invoke()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSelection() {
        selection.set(
            ScreenCaptureHelper.normalizeRect(anchorX, anchorY, currentX, currentY)
        )
        // A circle is centred on the midpoint of the drag, so the bounding box
        // has to be square. This keeps "draw a circle" from turning into a very
        // flat ellipse when the user drags mostly horizontally.
        if (shape == SelectionShape.CIRCLE && dragging) {
            val cx = (anchorX + currentX) / 2f
            val cy = (anchorY + currentY) / 2f
            val radius = max(
                hypot(currentX - anchorX, currentY - anchorY) / 2f,
                minSizePx / 2f,
            )
            selection.set(cx - radius, cy - radius, cx + radius, cy + radius)
        }
        onSelectionChanged?.invoke()
        invalidate()
    }

    /**
     * Converts a selection rectangle from view coordinates into a pixel
     * rectangle in the screenshot bitmap. This is what gets cropped and handed
     * to the OCR engine.
     */
    fun toBitmapRect(viewRect: RectF): Rect {
        if (contentBounds.isEmpty || bitmapWidth == 0 || bitmapHeight == 0) return Rect()
        val scaleX = bitmapWidth / contentBounds.width()
        val scaleY = bitmapHeight / contentBounds.height()

        val left = ((viewRect.left - contentBounds.left) * scaleX)
        val top = ((viewRect.top - contentBounds.top) * scaleY)
        val right = ((viewRect.right - contentBounds.left) * scaleX)
        val bottom = ((viewRect.bottom - contentBounds.top) * scaleY)

        return Rect(
            max(0, left.roundToInt()).coerceAtMost(bitmapWidth - 1),
            max(0, top.roundToInt()).coerceAtMost(bitmapHeight - 1),
            min(bitmapWidth, right.roundToInt()).coerceAtLeast(1),
            min(bitmapHeight, bottom.roundToInt()).coerceAtLeast(1),
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/**
 * Owns the selection overlay window.
 *
 * The window is a TYPE_APPLICATION_OVERLAY, so it floats above every app. The
 * screenshot is taken *before* this window is added, which is why the user sees
 * the real content underneath instead of our own dim layer.
 */
class SelectionOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    /**
     * The user confirmed a selection.
     *
     * The rect is in screenshot pixels and the bitmap is the screenshot itself:
     * ownership of the bitmap is handed over to the callback, which must
     * recycle it when it is done. Nothing is recycled by the controller.
     */
    var onSelectionConfirmed: ((Rect, Bitmap) -> Unit)? = null

    /** The user backed out. */
    var onCancelled: (() -> Unit)? = null

    private var binding: OverlaySelectionBinding? = null
    private var attached = false
    private var screenshot: Bitmap? = null

    private val shapeView: SelectionShapeView
        get() = requireNotNull(binding).selectionShape

    /**
     * Shows the overlay for [bitmap].
     *
     * The controller owns the bitmap until the selection ends: on confirm it is
     * handed to [onSelectionConfirmed], on cancel (and on any early failure) it
     * is recycled by [detach].
     */
    fun attach(bitmap: Bitmap) {
        if (attached) return
        screenshot = bitmap

        val inflated = OverlaySelectionBinding.inflate(android.view.LayoutInflater.from(context))
        binding = inflated
        inflated.selectionScreenshot.setImageBitmap(bitmap)

        inflated.selectionCancel.setOnClickListener { cancel() }
        inflated.selectionConfirm.setOnClickListener { confirm() }
        inflated.selectionShapeToggle.setOnClickListener { toggleShape() }

        shapeView.shape = SelectionShape.CIRCLE
        shapeView.onSelectionChanged = { onSelectionChanged() }
        shapeView.onSelectionRejected = {
            inflated.selectionHint.setText(R.string.selection_hint_too_small)
        }

        // Extract stays disabled until there is something to extract.
        inflated.selectionConfirm.isEnabled = false
        inflated.selectionShapeToggle.setText(R.string.action_shape_rectangle)

        val root = inflated.root
        val params = overlayParams()

        try {
            windowManager.addView(root, params)
            attached = true
        } catch (e: Exception) {
            // Happens when the overlay permission was revoked while we were
            // capturing the screenshot.
            Log.e(TAG, "could not add the selection overlay", e)
            detach()
            onCancelled?.invoke()
            return
        }

        // Now that the view has a size we can compute where the screenshot is
        // drawn and hand those bounds to the touch layer.
        root.doOnLayout {
            applyContentBounds(root, bitmap)
        }
    }

    /** Computes the "fit center" rect of the screenshot and applies it. */
    private fun applyContentBounds(root: View, bitmap: Bitmap) {
        val inflated = binding ?: return
        val viewWidth = root.width
        val viewHeight = root.height
        if (viewWidth == 0 || viewHeight == 0) return

        val scale = min(
            viewWidth.toFloat() / bitmap.width,
            viewHeight.toFloat() / bitmap.height,
        )
        val width = (bitmap.width * scale).roundToInt()
        val height = (bitmap.height * scale).roundToInt()
        val left = ((viewWidth - width) / 2f).roundToInt()
        val top = ((viewHeight - height) / 2f).roundToInt()

        val layoutParams = FrameLayout.LayoutParams(width, height)
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.leftMargin = left
        layoutParams.topMargin = top
        inflated.selectionScreenshot.layoutParams = layoutParams

        shapeView.setContentBounds(
            RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()),
            bitmap.width,
            bitmap.height,
        )
    }

    private fun onSelectionChanged() {
        val inflated = binding ?: return
        val valid = shapeView.hasValidSelection
        if (inflated.selectionConfirm.isEnabled != valid) {
            inflated.selectionConfirm.isEnabled = valid
        }
        if (valid) {
            inflated.selectionHint.setText(R.string.selection_hint)
        }
    }

    private fun toggleShape() {
        val inflated = binding ?: return
        val next = shapeView.shape.toggled()
        shapeView.shape = next
        shapeView.clearSelection()
        inflated.selectionConfirm.isEnabled = false
        inflated.selectionShapeToggle.setText(
            if (next == SelectionShape.CIRCLE) R.string.action_shape_rectangle
            else R.string.action_shape_circle
        )
    }

    private fun confirm() {
        if (binding == null) return
        if (!shapeView.hasValidSelection) return

        // Snapshot everything we need before the view goes away.
        val rect = shapeView.toBitmapRect(RectF(shapeView.selection))
        val shot = screenshot ?: return
        val callback = onSelectionConfirmed

        // Hand the bitmap over without recycling it: the caller crops it and
        // then releases it.
        detach(recycleScreenshot = false)
        callback?.invoke(rect, shot)
    }

    private fun cancel() {
        val callback = onCancelled
        detach(recycleScreenshot = true)
        callback?.invoke()
    }

    /** Removes the window. Safe to call twice. */
    fun detach(recycleScreenshot: Boolean = true) {
        val root = binding?.root
        if (attached && root != null) {
            runCatching { windowManager.removeViewImmediate(root) }
        }
        attached = false
        binding = null
        if (recycleScreenshot) {
            screenshot?.recycle()
        }
        screenshot = null
    }

    companion object {
        private const val TAG = "SelectionOverlay"

        /**
         * Window parameters for a full screen, non focusable overlay. It is
         * deliberately NOT focusable: taking focus would dismiss the keyboard
         * of the app underneath and could dismiss our own windows.
         */
        fun overlayParams(): WindowManager.LayoutParams =
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
                    // Draw into the cutout area too, otherwise the top of the
                    // screenshot cannot be selected on notched devices.
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
    }
}

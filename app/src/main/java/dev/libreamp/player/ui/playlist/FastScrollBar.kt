package dev.libreamp.player.ui.playlist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.libreamp.player.R
import kotlin.math.roundToInt

/**
 * Draggable fast-scroll thumb, overlaid on the right edge of a RecyclerView.
 *
 * Replaces RecyclerView's built-in fast scroller, which is impractical to actually
 * grab: it only accepts a drag inside a strip as wide as the thumb drawable's
 * intrinsic width, and only while it is in its VISIBLE state — i.e. within ~1.5s of
 * the last scroll. This one stays visible whenever the list overflows, and takes a
 * touch anywhere in its (much wider) width within the thumb's vertical span.
 *
 * A touch that isn't on the thumb is deliberately *not* consumed, so it falls
 * through to the row underneath — the drag handles live in the same corner.
 */
class FastScrollBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val thumbWidth = THUMB_WIDTH_DP * density
    private val minThumbHeight = MIN_THUMB_HEIGHT_DP * density
    private val touchSlopY = TOUCH_SLOP_DP * density
    private val edgeInset = EDGE_INSET_DP * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.fast_scroll_track)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbColor = ContextCompat.getColor(context, R.color.fast_scroll_thumb)
    private val thumbColorActive = ContextCompat.getColor(context, R.color.fast_scroll_thumb_active)

    private var recycler: RecyclerView? = null
    private var dragging = false
    private var dragGrabOffset = 0f

    /** While dragging, the thumb follows the finger instead of the (lagging) scroll offset. */
    private var dragFraction: Float? = null

    fun attachTo(recyclerView: RecyclerView) {
        recycler = recyclerView
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = invalidate()
        })
    }

    override fun onDraw(canvas: Canvas) {
        val rv = recycler ?: return
        val metrics = metrics(rv) ?: return

        val left = width - thumbWidth - edgeInset
        val right = width - edgeInset
        val radius = thumbWidth / 2f
        canvas.drawRoundRect(left, 0f, right, height.toFloat(), radius, radius, trackPaint)

        thumbPaint.color = if (dragging) thumbColorActive else thumbColor
        val top = metrics.thumbTop
        canvas.drawRoundRect(left, top, right, top + metrics.thumbHeight, radius, radius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recycler ?: return false
        val metrics = metrics(rv) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val top = metrics.thumbTop
                if (event.y < top - touchSlopY || event.y > top + metrics.thumbHeight + touchSlopY) {
                    return false // not on the thumb: let the row underneath have it
                }
                dragging = true
                dragGrabOffset = (event.y - top).coerceIn(0f, metrics.thumbHeight)
                // ViewPager2 hosts this page; without this it steals the gesture.
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val maxTop = height - metrics.thumbHeight
                val top = (event.y - dragGrabOffset).coerceIn(0f, maxTop.coerceAtLeast(0f))
                val fraction = if (maxTop > 0f) top / maxTop else 0f
                dragFraction = fraction
                scrollToFraction(rv, fraction)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                dragFraction = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return dragging
    }

    /** Index-proportional jump, so a full-list drag costs no per-row scrolling work. */
    private fun scrollToFraction(rv: RecyclerView, fraction: Float) {
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0) return
        val target = ((count - 1) * fraction).roundToInt().coerceIn(0, count - 1)
        layoutManager.scrollToPositionWithOffset(target, 0)
    }

    private class Metrics(val thumbTop: Float, val thumbHeight: Float)

    /** Null when there is nothing to scroll, which is also when nothing should be drawn. */
    private fun metrics(rv: RecyclerView): Metrics? {
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        if (range <= extent || extent == 0 || height == 0) return null

        val thumbHeight = (height.toFloat() * extent / range).coerceAtLeast(minThumbHeight)
            .coerceAtMost(height.toFloat())
        val maxTop = height - thumbHeight
        val fraction = dragFraction
            ?: (rv.computeVerticalScrollOffset().toFloat() / (range - extent)).coerceIn(0f, 1f)
        return Metrics(maxTop * fraction, thumbHeight)
    }

    private companion object {
        const val THUMB_WIDTH_DP = 6f
        const val MIN_THUMB_HEIGHT_DP = 48f
        const val TOUCH_SLOP_DP = 12f
        const val EDGE_INSET_DP = 3f
    }
}

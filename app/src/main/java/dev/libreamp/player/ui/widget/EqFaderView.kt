package dev.libreamp.player.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.libreamp.player.R
import kotlin.math.roundToInt

/**
 * One equaliser band: a thin rail with a centre detent and a wide cap.
 *
 * A rotated SeekBar was the obvious alternative, but its thumb travel is
 * measured against the rotated bounds and its touch target follows the
 * pre-rotation geometry, which makes the eighteen of these impossible to hit
 * cleanly inside a horizontally scrolling row. Drawing it directly also lets
 * the cap span the full width the design asks for while the rail stays 3dp.
 */
class EqFaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Fires on every drag tick; the caller decides what to persist and push. */
    var onGainChanged: ((Float) -> Unit)? = null

    var gainDb: Float = 0f
        set(value) {
            val clamped = value.coerceIn(-RANGE_DB, RANGE_DB)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val railWidth = RAIL_WIDTH_DP * density
    private val capHeight = CAP_HEIGHT_DP * density
    private val capInset = CAP_INSET_DP * density

    private val railPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.border)
    }
    private val detentPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.eq_center_line)
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.accent_edge_dark)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        capPaint.shader = LinearGradient(
            0f, 0f, 0f, capHeight,
            ContextCompat.getColor(context, R.color.accent_eq_top),
            ContextCompat.getColor(context, R.color.accent_grad_bottom),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val centreX = width / 2f
        canvas.drawRect(
            centreX - railWidth / 2f, 0f, centreX + railWidth / 2f, height.toFloat(), railPaint
        )
        canvas.drawRect(0f, height / 2f, width.toFloat(), height / 2f + density, detentPaint)

        val top = capTopFor(gainDb)
        canvas.drawRect(capInset, top, width - capInset, top + capHeight, capPaint)
        canvas.drawRect(capInset, top, width - capInset, top + capHeight, capEdgePaint)
    }

    /** Cap travel is inset by its own height so it never overhangs the rail. */
    private fun capTopFor(db: Float): Float {
        val travel = height - capHeight
        val fraction = (RANGE_DB - db) / (RANGE_DB * 2f)
        return travel * fraction
    }

    private fun gainForY(y: Float): Float {
        val travel = height - capHeight
        if (travel <= 0f) return 0f
        val fraction = ((y - capHeight / 2f) / travel).coerceIn(0f, 1f)
        val db = RANGE_DB - fraction * RANGE_DB * 2f
        return (db / STEP_DB).roundToInt() * STEP_DB
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Inside a horizontal scroller inside a pager; both will happily
                // claim a vertical drag that belongs to this fader.
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                applyTouch(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun applyTouch(y: Float) {
        val db = gainForY(y)
        if (db == gainDb) return
        gainDb = db
        onGainChanged?.invoke(db)
    }

    private companion object {
        const val RANGE_DB = 12f
        const val STEP_DB = 0.5f
        const val RAIL_WIDTH_DP = 3f
        const val CAP_HEIGHT_DP = 9f
        const val CAP_INSET_DP = 1f
    }
}

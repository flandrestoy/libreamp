package dev.libreamp.player.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import dev.libreamp.player.R
import kotlin.math.hypot

/**
 * Stand-in for a missing cover: diagonal hatching with a centred mark.
 *
 * The hatch pitch is fixed in dp rather than scaled with the box, so the 42dp
 * playlist thumbnails and the full-width Now Playing panel read as the same
 * material instead of one looking like a zoom of the other.
 *
 * The mark is drawn as a path rather than set as text. The design uses a CJK
 * glyph, and a device without a CJK font would render tofu in a placeholder
 * whose whole job is to look deliberate.
 */
class HatchDrawable(context: Context, private val small: Boolean = false) : Drawable() {

    private val density = context.resources.displayMetrics.density

    private val basePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.art_hatch_dark)
    }
    private val stripePaint = Paint().apply {
        color = ContextCompat.getColor(
            context,
            if (small) R.color.art_hatch_light_small else R.color.art_hatch_light
        )
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        color = ContextCompat.getColor(
            context, if (small) R.color.art_glyph_small else R.color.art_glyph
        )
    }

    private val pitch = (if (small) STRIPE_SMALL_DP else STRIPE_LARGE_DP) * density
    private val mark = Path()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, basePaint)

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val reach = hypot(bounds.width().toFloat(), bounds.height().toFloat())

        canvas.save()
        canvas.clipRect(bounds)
        canvas.rotate(-45f, cx, cy)
        var x = cx - reach
        while (x < cx + reach) {
            canvas.drawRect(x, cy - reach, x + pitch, cy + reach, stripePaint)
            x += pitch * 2
        }
        canvas.restore()

        drawMark(canvas, cx, cy, minOf(bounds.width(), bounds.height()) * MARK_SCALE)
    }

    /**
     * Two strokes: a shoulder falling to the left, and a base sweeping back to
     * the right past it.
     */
    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        markPaint.strokeWidth = size * STROKE_RATIO
        val half = size / 2f
        mark.reset()
        mark.moveTo(cx + half * 0.62f, cy - half)
        mark.cubicTo(
            cx + half * 0.10f, cy - half * 0.30f,
            cx - half * 0.42f, cy + half * 0.18f,
            cx - half * 0.78f, cy + half * 0.52f
        )
        mark.moveTo(cx - half * 0.72f, cy + half * 0.30f)
        mark.cubicTo(
            cx - half * 0.10f, cy + half * 0.52f,
            cx + half * 0.42f, cy + half * 0.72f,
            cx + half * 0.80f, cy + half * 0.92f
        )
        canvas.drawPath(mark, markPaint)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        stripePaint.alpha = alpha
        markPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        stripePaint.colorFilter = colorFilter
        markPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val STRIPE_LARGE_DP = 8f
        const val STRIPE_SMALL_DP = 6f
        const val MARK_SCALE = 0.42f
        const val STROKE_RATIO = 0.13f
    }
}

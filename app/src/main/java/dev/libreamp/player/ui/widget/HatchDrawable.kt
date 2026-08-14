package dev.libreamp.player.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import dev.libreamp.player.R
import kotlin.math.hypot

/**
 * Stand-in for a missing cover: diagonal hatching with the centred 厶 mark.
 *
 * The hatch pitch is fixed in dp rather than scaled with the box, so the 42dp
 * playlist thumbnails and the full-width Now Playing panel read as the same
 * material instead of one looking like a zoom of the other.
 *
 * The mark comes from @drawable/ic_logo_mu, which carries the real Noto Sans JP
 * outline. Drawing it from a bundled font instead would work, but the same mark
 * is the launcher icon, where only a drawable will do.
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
    private val pitch = (if (small) STRIPE_SMALL_DP else STRIPE_LARGE_DP) * density

    private val mark = AppCompatResources.getDrawable(context, R.drawable.ic_logo_mu)?.mutate()
        ?.apply {
            setTint(
                ContextCompat.getColor(
                    context, if (small) R.color.art_glyph_small else R.color.art_glyph
                )
            )
        }

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

        val glyph = mark ?: return
        val size = (minOf(bounds.width(), bounds.height()) *
            (if (small) MARK_SCALE_SMALL else MARK_SCALE)).toInt()
        val half = size / 2
        glyph.setBounds(
            (cx - half).toInt(), (cy - half).toInt(),
            (cx - half).toInt() + size, (cy - half).toInt() + size
        )
        glyph.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        stripePaint.alpha = alpha
        mark?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        stripePaint.colorFilter = colorFilter
        mark?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val STRIPE_LARGE_DP = 8f
        const val STRIPE_SMALL_DP = 6f

        /** Thumbnails carry the mark proportionally larger, as the design does. */
        const val MARK_SCALE = 0.36f
        const val MARK_SCALE_SMALL = 0.46f
    }
}

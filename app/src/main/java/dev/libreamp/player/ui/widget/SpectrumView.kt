package dev.libreamp.player.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.libreamp.player.R
import dev.libreamp.player.playback.SpectrumAnalyzer

/**
 * The bar spectrum above the track title.
 *
 * Levels are pulled, not pushed: [provider] is polled once per drawn frame, so
 * the display runs at the display's refresh rate regardless of how often the
 * decode thread produces analysis frames, and a stalled or paused engine simply
 * stops answering and lets the bars fall.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Fills the array with 0..1 per band and returns whether it had anything. */
    var provider: ((FloatArray) -> Boolean)? = null

    private val bands = FloatArray(SpectrumAnalyzer.BAND_COUNT)
    private val levels = FloatArray(SpectrumAnalyzer.BAND_COUNT)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.border_soft)
    }

    private val density = resources.displayMetrics.density
    private val gap = BAR_GAP_DP * density
    private val rule = density

    private var lastFrameNanos = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = ContextCompat.getColor(context, R.color.spectrum_top)
        val bottom = ContextCompat.getColor(context, R.color.spectrum_bottom)
        barPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        advance()

        val plotHeight = height - rule - BAR_INSET_DP * density
        val count = levels.size
        val barWidth = (width - gap * (count - 1)) / count
        if (barWidth <= 0f) return

        for (i in 0 until count) {
            val barHeight = plotHeight * levels[i]
            if (barHeight <= 0f) continue
            val left = i * (barWidth + gap)
            canvas.drawRect(left, plotHeight - barHeight, left + barWidth, plotHeight, barPaint)
        }
        canvas.drawRect(0f, height - rule, width.toFloat(), height.toFloat(), rulePaint)

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    /**
     * Instant attack, timed decay: a bar jumps straight to a new peak but eases
     * back, which is what makes transients legible instead of a blur.
     */
    private fun advance() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        val live = provider?.invoke(bands) ?: false
        val fall = (DECAY_PER_SECOND * dt).coerceAtMost(1f)
        for (i in levels.indices) {
            val target = if (live) bands[i] else 0f
            levels[i] = if (target >= levels[i]) target else (levels[i] - fall).coerceAtLeast(target)
        }
    }

    private companion object {
        const val BAR_GAP_DP = 3f
        const val BAR_INSET_DP = 2f
        const val DECAY_PER_SECOND = 1.9f
    }
}

package dev.libreamp.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns the decoded PCM stream into the band magnitudes the Now Playing
 * spectrum draws.
 *
 * It is fed from the decode thread at the point the chunk is handed to the
 * AudioTrack, which is *ahead* of what the listener is hearing by however much
 * is still sitting in the track's buffer (~370ms at the sizes this app uses).
 * Displaying a frame as soon as it is computed would therefore run the bars
 * visibly ahead of the music. Instead every analysed frame is filed under the
 * playback-head position it will be audible at, and [bandsAt] hands back the
 * frame matching the head position *now* — the same trick
 * [PlaybackEngine.currentPositionUs] uses to keep the seek bar honest.
 *
 * The tap sits after the ffmpeg filter graph, so the display reflects the
 * equaliser and effects rather than the untouched file.
 */
class SpectrumAnalyzer(private val sampleRate: Int) {

    /** One analysed frame, valid from [frameStamp] onwards. */
    private class Slot {
        var frameStamp: Int = 0
        var filled: Boolean = false
        val bands = FloatArray(BAND_COUNT)
    }

    private val lock = Any()
    private val slots = Array(SLOT_COUNT) { Slot() }
    private var writeIndex = 0

    /**
     * Sliding mono window, so successive FFTs overlap by [FFT_SIZE] - [HOP].
     * A ring rather than a shifted array: at 4096 frames per chunk, moving the
     * whole window down by one on every sample would be millions of copies per
     * chunk on the decode thread.
     */
    private val window = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var windowFill = 0
    private var sinceLastFft = 0

    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE) { i ->
        0.5f - 0.5f * cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat()
    }
    private val scratch = FloatArray(BAND_COUNT)

    /** Inclusive bin range per band, log-spaced across the audible span. */
    private val bandStart = IntArray(BAND_COUNT)
    private val bandEnd = IntArray(BAND_COUNT)

    init {
        val nyquistBin = FFT_SIZE / 2
        val logMin = ln(MIN_HZ)
        val logMax = ln(MAX_HZ.coerceAtMost(sampleRate / 2f))
        for (band in 0 until BAND_COUNT) {
            val lowHz = exp(logMin + (logMax - logMin) * band / BAND_COUNT)
            val highHz = exp(logMin + (logMax - logMin) * (band + 1) / BAND_COUNT)
            val low = (lowHz * FFT_SIZE / sampleRate).toInt().coerceIn(1, nyquistBin - 1)
            // At the bottom of the range several bands land on one bin; widening
            // the last one instead would make those bands read identically.
            val high = (highHz * FFT_SIZE / sampleRate).toInt().coerceIn(low, nyquistBin - 1)
            bandStart[band] = low
            bandEnd[band] = high
        }
    }

    /**
     * Consumes one decoded chunk of interleaved 16-bit stereo.
     *
     * [frameStamp] is the playback-head position the chunk's first frame will be
     * heard at; reads are absolute so the caller's buffer position — which the
     * AudioTrack write consumes — is left alone.
     */
    fun submit(buffer: ByteBuffer, byteCount: Int, frameStamp: Int) {
        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        try {
            var offset = 0
            var frame = 0
            while (offset + BYTES_PER_FRAME <= byteCount) {
                val left = buffer.getShort(offset).toFloat()
                val right = buffer.getShort(offset + 2).toFloat()
                push((left + right) * 0.5f / Short.MAX_VALUE)
                offset += BYTES_PER_FRAME
                frame++

                if (sinceLastFft >= HOP && windowFill == FFT_SIZE) {
                    sinceLastFft = 0
                    // Attribute the frame to the middle of the window it was
                    // measured over, not its leading edge.
                    analyse(frameStamp + frame - FFT_SIZE / 2)
                }
            }
        } finally {
            buffer.order(order)
        }
    }

    private fun push(sample: Float) {
        window[ringPos] = sample
        ringPos = if (ringPos + 1 == FFT_SIZE) 0 else ringPos + 1
        if (windowFill < FFT_SIZE) windowFill++
        sinceLastFft++
    }

    private fun analyse(frameStamp: Int) {
        // Once full, ringPos is the oldest sample, so this walks the window in
        // chronological order and the Hann taper lands on the right end of it.
        for (i in 0 until FFT_SIZE) {
            val index = ringPos + i
            real[i] = window[if (index >= FFT_SIZE) index - FFT_SIZE else index] * hann[i]
            imag[i] = 0f
        }
        fft()

        for (band in 0 until BAND_COUNT) {
            var peak = 0f
            for (bin in bandStart[band]..bandEnd[band]) {
                val magnitude = hypot(real[bin], imag[bin])
                if (magnitude > peak) peak = magnitude
            }
            // Normalise out the window's coherent gain and the transform length so
            // the dB figure is referenced to full scale rather than to FFT_SIZE.
            val normalised = peak * 4f / FFT_SIZE
            val db = 20f * log10(normalised.coerceAtLeast(1e-7f))
            scratch[band] = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }

        synchronized(lock) {
            val slot = slots[writeIndex]
            System.arraycopy(scratch, 0, slot.bands, 0, BAND_COUNT)
            slot.frameStamp = frameStamp
            slot.filled = true
            writeIndex = (writeIndex + 1) % SLOT_COUNT
        }
    }

    /**
     * Copies the newest frame that is already audible at [headFrames] into [out].
     *
     * Comparisons go through Int subtraction so they stay correct across the
     * wrap of [android.media.AudioTrack.getPlaybackHeadPosition].
     */
    fun bandsAt(headFrames: Int, out: FloatArray): Boolean {
        synchronized(lock) {
            var best: Slot? = null
            var bestAge = Int.MAX_VALUE
            for (slot in slots) {
                if (!slot.filled) continue
                val age = headFrames - slot.frameStamp
                if (age >= 0 && age < bestAge) {
                    bestAge = age
                    best = slot
                }
            }
            val chosen = best ?: return false
            System.arraycopy(chosen.bands, 0, out, 0, BAND_COUNT)
            return true
        }
    }

    /** Called wherever the AudioTrack is flushed, since stamps restart with it. */
    fun reset() {
        synchronized(lock) {
            for (slot in slots) slot.filled = false
            writeIndex = 0
        }
        windowFill = 0
        ringPos = 0
        sinceLastFft = 0
    }

    /** In-place iterative radix-2 Cooley-Tukey over [real]/[imag]. */
    private fun fft() {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        var len = 2
        while (len <= FFT_SIZE) {
            val angle = -2.0 * Math.PI / len
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()
            var i = 0
            while (i < FFT_SIZE) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val vImag = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal
                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BAND_COUNT = 32

        /** 2048 at 44.1 kHz is ~21 Hz per bin: fine enough that only the bottom
         *  couple of bands share one, while still yielding ~43 frames/second. */
        private const val FFT_SIZE = 2048
        private const val HOP = 1024

        /** ~740ms of history, comfortably more than the AudioTrack buffer. */
        private const val SLOT_COUNT = 32

        private const val BYTES_PER_FRAME = 4 // 16-bit stereo
        private const val FLOOR_DB = -72f
        private const val CEIL_DB = -12f
        private const val MIN_HZ = 40f
        private const val MAX_HZ = 16000f
    }
}

package dev.libreamp.player.data.effects

import java.util.Locale
import kotlin.math.abs

/** The 18 fixed centre frequencies the eq UI exposes, one `anequalizer` band each. */
val EQ_FREQUENCIES = intArrayOf(
    65, 92, 131, 185, 262, 370, 523, 740, 1047,
    1480, 2093, 2960, 4186, 5920, 8372, 11840, 16744, 20000
)

const val EQ_BAND_COUNT = 18
const val EQ_MAX_DB = 12f
const val BASS_TREBLE_MAX_DB = 20f
const val MIN_SPEED = 0.25f
const val MAX_SPEED = 2.0f

/**
 * Instance name given to the `anequalizer` stage in [EffectsConfig.toFilterGraph],
 * so a single band's gain can be live-updated via `nativeSendFilterCommand` (see
 * `PlaybackEngine.applyEqBand`) instead of tearing down and rebuilding the whole
 * graph, which is audible as a click on every rebuild.
 */
const val EQ_FILTER_INSTANCE = "eq"

/**
 * Bandwidth (Hz) `anequalizer` gets for a band centred at [freqHz]. Used both when
 * the graph is first built and by every later runtime `change` command, since the
 * two drifting apart could make the live command behave unpredictably.
 */
fun eqBandWidth(freqHz: Int): Float = freqHz * EQ_BAND_WIDTH_RATIO

private const val EQ_BAND_WIDTH_RATIO = 0.5f

/**
 * The whole effects chain, in UI units (dB, playback rate, -1..+1 balance).
 *
 * [toFilterGraph] renders the parts that ffmpeg handles; [speed] and [balance] are
 * deliberately *not* in the graph — they are applied on the `AudioTrack` instead, so
 * that speed changes don't desynchronise the byte-count-based position estimate and
 * balance doesn't cost a graph rebuild on every drag.
 */
data class EffectsConfig(
    val enabled: Boolean = false,
    val bandsDb: List<Float> = List(EQ_BAND_COUNT) { 0f },
    val preset: String = EqPresets.FLAT,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val crossfeed: Boolean = false,
    val dynaudnorm: Boolean = false,
    val speed: Float = 1f,
    val balance: Float = 0f
) {

    fun bandDb(index: Int): Float = bandsDb.getOrElse(index) { 0f }

    fun withBand(index: Int, db: Float): EffectsConfig {
        val bands = MutableList(EQ_BAND_COUNT) { bandDb(it) }
        bands[index] = db
        return copy(bandsDb = bands, preset = EqPresets.CUSTOM)
    }

    val effectiveSpeed: Float get() = if (enabled) speed.coerceIn(MIN_SPEED, MAX_SPEED) else 1f

    /** Left/right AudioTrack gains for [balance]; a centred balance leaves both at 1. */
    val channelGains: Pair<Float, Float>
        get() {
            if (!enabled) return 1f to 1f
            val b = balance.coerceIn(-1f, 1f)
            return (if (b > 0) 1f - b else 1f) to (if (b < 0) 1f + b else 1f)
        }

    /** ffmpeg filter-graph description, or null when nothing needs filtering. */
    fun toFilterGraph(): String? {
        if (!enabled) return null
        val parts = mutableListOf<String>()

        // Always present, even flat: PlaybackEngine.applyEqBand needs the stage to
        // already exist in the running graph to live-update it band by band.
        parts += eqFilterGraphPart()
        if (abs(bassDb) >= DB_EPSILON) parts += String.format(Locale.US, "bass=g=%.1f", bassDb)
        if (abs(trebleDb) >= DB_EPSILON) parts += String.format(Locale.US, "treble=g=%.1f", trebleDb)
        if (crossfeed) parts += "crossfeed"
        if (dynaudnorm) parts += "dynaudnorm"

        return parts.joinToString(",")
    }

    /** One `c<channel> f=<hz> w=<hz> g=<db>` entry per band per channel, `|`-joined. */
    private fun eqFilterGraphPart(): String {
        val entries = (0 until OUT_CHANNELS).flatMap { channel ->
            (0 until EQ_BAND_COUNT).map { i ->
                val freq = EQ_FREQUENCIES[i]
                // anequalizer's `g` is already in dB, unlike superequalizer's linear multiplier.
                String.format(
                    Locale.US, "c%d f=%d w=%.1f g=%.2f", channel, freq, eqBandWidth(freq), bandDb(i)
                )
            }
        }
        return "anequalizer@$EQ_FILTER_INSTANCE=" + entries.joinToString("|")
    }

    private companion object {
        const val DB_EPSILON = 0.05f
        const val OUT_CHANNELS = 2
    }
}

object EqPresets {

    const val FLAT = "Flat"
    const val CUSTOM = "Custom"

    /** dB per band, low → high; all 18 bands, in [EQ_FREQUENCIES] order. */
    val CURVES: Map<String, List<Float>> = linkedMapOf(
        FLAT to List(EQ_BAND_COUNT) { 0f },
        "Rock" to listOf(5f, 5f, 4f, 3f, 1f, -1f, -2f, -2f, -1f, 0f, 1f, 2f, 3f, 4f, 4f, 4f, 3f, 3f),
        "Pop" to listOf(-1f, -1f, 0f, 1f, 3f, 4f, 4f, 4f, 3f, 2f, 1f, 0f, 0f, -1f, -1f, -1f, -1f, -1f),
        "Jazz" to listOf(3f, 3f, 2f, 2f, 1f, 0f, 0f, 1f, 2f, 2f, 1f, 0f, 1f, 2f, 2f, 3f, 3f, 3f),
        "Classical" to listOf(4f, 4f, 3f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -1f, -1f, -1f, 0f, 2f, 3f, 3f),
        "Bass boost" to listOf(9f, 8f, 7f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 5f, 6f, 7f, 8f, 8f, 8f),
        "Vocal" to listOf(-3f, -3f, -2f, 0f, 2f, 4f, 5f, 5f, 4f, 3f, 2f, 1f, 0f, -1f, -2f, -2f, -3f, -3f),
        "Loudness" to listOf(8f, 7f, 6f, 4f, 2f, 0f, -1f, -2f, -2f, -1f, 0f, 1f, 3f, 5f, 6f, 7f, 7f, 7f)
    )

    /** Preset names plus the "Custom" slot the UI switches to on any manual band edit. */
    val NAMES: List<String> = CURVES.keys.toList() + CUSTOM

    fun curveFor(name: String): List<Float>? = CURVES[name]
}

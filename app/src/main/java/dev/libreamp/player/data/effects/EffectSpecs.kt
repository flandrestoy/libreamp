package dev.libreamp.player.data.effects

import dev.libreamp.player.R
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** How a slider's UI value maps onto the value the ffmpeg option actually wants. */
enum class ParamScale {
    LINEAR,

    /** Slider is in dB, the filter option takes a linear amplitude multiplier. */
    DB_TO_AMPLITUDE
}

enum class ParamUnit(val decimals: Int, val suffix: String) {
    PLAIN(2, ""),
    DB(1, " dB"),
    MS(0, " ms"),
    HZ(2, " Hz"),
    RATIO(1, ":1")
}

/**
 * One tunable option of one filter.
 *
 * [min]/[max] must stay inside the range the filter's own `AVOption` table declares:
 * `av_opt_set` rejects an out-of-range value, and since these are passed as part of the
 * graph description string, one bad value fails the whole `avfilter_graph_parse_ptr`
 * and drops the entire chain, not just this effect.
 */
data class EffectParam(
    val key: String,
    val labelRes: Int,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: ParamUnit = ParamUnit.PLAIN,
    val scale: ParamScale = ParamScale.LINEAR,
    val steps: Int = 100
) {

    fun valueOf(progress: Int): Float = min + (max - min) * progress / steps

    fun progressOf(value: Float): Int =
        ((value.coerceIn(min, max) - min) / (max - min) * steps).roundToInt()

    fun format(value: Float): String =
        String.format(Locale.US, "%.${unit.decimals}f%s", value, unit.suffix)

    /**
     * The `key=value` fragment this param contributes to the filter-graph string.
     *
     * Always 4 decimals, including for the integer-typed options (dynaudnorm's
     * framelen), which ffmpeg parses through av_expr and rounds. Rounding to whole
     * milliseconds here instead would push sub-1ms minimums (alimiter's 0.1ms attack)
     * to 0 and out of range, failing the parse for the whole chain.
     */
    fun render(value: Float): String = String.format(
        Locale.US, "%s=%.4f", key, when (scale) {
            ParamScale.LINEAR -> value.coerceIn(min, max)
            ParamScale.DB_TO_AMPLITUDE -> 10f.pow(value.coerceIn(min, max) / 20f)
        }
    )
}

/** [id] doubles as the ffmpeg filter name and the persistence key. */
data class EffectSpec(
    val id: String,
    val labelRes: Int,
    val params: List<EffectParam>
) {

    fun paramKey(param: EffectParam): String = "$id.${param.key}"
}

/**
 * Every optional filter the effects screen exposes, in chain order (the equalizer is
 * not here — it is always first and is built separately, see [EffectsConfig]).
 *
 * Ranges are deliberately narrower than what each filter permits, trading unusable
 * extremes for slider resolution where it matters.
 */
val EFFECT_SPECS: List<EffectSpec> = listOf(
    EffectSpec(
        "crossfeed", R.string.effects_crossfeed, listOf(
            EffectParam("strength", R.string.param_strength, 0f, 1f, 0.2f),
            EffectParam("range", R.string.param_range, 0f, 1f, 0.5f)
        )
    ),
    EffectSpec(
        "dynaudnorm", R.string.effects_dynaudnorm, listOf(
            EffectParam("p", R.string.param_target_peak, 0.1f, 1f, 0.95f),
            EffectParam("m", R.string.param_max_gain, 1f, 30f, 10f, steps = 145),
            EffectParam("f", R.string.param_frame_length, 50f, 2000f, 500f, ParamUnit.MS, steps = 195)
        )
    ),
    EffectSpec(
        "acompressor", R.string.effects_compressor, listOf(
            EffectParam(
                "threshold", R.string.param_threshold, -60f, 0f, -18f,
                ParamUnit.DB, ParamScale.DB_TO_AMPLITUDE, 120
            ),
            EffectParam("ratio", R.string.param_ratio, 1f, 20f, 2f, ParamUnit.RATIO, steps = 190),
            EffectParam("attack", R.string.param_attack, 1f, 200f, 20f, ParamUnit.MS, steps = 199),
            EffectParam("release", R.string.param_release, 10f, 2000f, 250f, ParamUnit.MS, steps = 199),
            EffectParam(
                "makeup", R.string.param_makeup, 0f, 24f, 0f,
                ParamUnit.DB, ParamScale.DB_TO_AMPLITUDE, 96
            )
        )
    ),
    EffectSpec(
        "alimiter", R.string.effects_limiter, listOf(
            EffectParam(
                "limit", R.string.param_ceiling, -24f, 0f, 0f,
                ParamUnit.DB, ParamScale.DB_TO_AMPLITUDE, 96
            ),
            EffectParam("attack", R.string.param_attack, 0.1f, 80f, 5f, ParamUnit.MS, steps = 160),
            EffectParam("release", R.string.param_release, 1f, 1000f, 50f, ParamUnit.MS, steps = 200)
        )
    ),
    EffectSpec(
        "loudnorm", R.string.effects_loudnorm, listOf(
            EffectParam("I", R.string.param_target_loudness, -40f, -5f, -24f, ParamUnit.DB, steps = 140),
            EffectParam("LRA", R.string.param_loudness_range, 1f, 20f, 7f, ParamUnit.DB, steps = 190),
            EffectParam("TP", R.string.param_true_peak, -9f, 0f, -2f, ParamUnit.DB, steps = 90)
        )
    ),
    EffectSpec(
        "aecho", R.string.effects_echo, listOf(
            EffectParam("in_gain", R.string.param_input_gain, 0.1f, 1f, 0.6f),
            EffectParam("out_gain", R.string.param_output_gain, 0.1f, 1f, 0.3f),
            EffectParam("delays", R.string.param_delay, 10f, 2000f, 500f, ParamUnit.MS, steps = 199),
            // aecho rejects a decay outside (0, 1] outright, taking the whole graph with it.
            EffectParam("decays", R.string.param_decay, 0.05f, 1f, 0.5f)
        )
    ),
    EffectSpec(
        "stereotools", R.string.effects_stereo_tools, listOf(
            EffectParam("mlev", R.string.param_middle_level, 0.0625f, 4f, 1f),
            EffectParam("slev", R.string.param_side_level, 0.0625f, 4f, 1f),
            EffectParam("base", R.string.param_stereo_base, -1f, 1f, 0f),
            EffectParam("balance_out", R.string.param_balance, -1f, 1f, 0f)
        )
    ),
    EffectSpec(
        "extrastereo", R.string.effects_extra_stereo, listOf(
            EffectParam("m", R.string.param_width, 0f, 4f, 2.5f)
        )
    ),
    EffectSpec(
        "apulsator", R.string.effects_pulsator, listOf(
            EffectParam("hz", R.string.param_rate, 0.05f, 10f, 2f, ParamUnit.HZ, steps = 199),
            EffectParam("amount", R.string.param_depth, 0f, 1f, 1f),
            EffectParam("width", R.string.param_pulse_width, 0f, 2f, 1f)
        )
    )
)

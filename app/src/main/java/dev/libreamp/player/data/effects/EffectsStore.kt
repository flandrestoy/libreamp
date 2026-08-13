package dev.libreamp.player.data.effects

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide, SharedPreferences-backed holder for the effects chain, mirroring
 * [dev.libreamp.player.playback.PlaybackController]'s single-process singleton style:
 * the effects screen writes it, [dev.libreamp.player.playback.PlaybackEngine] reads it
 * when (re)building the filter graph after every `nativeOpen`.
 */
object EffectsStore {

    private const val PREFS_NAME = "effects"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BANDS = "bands_db"
    private const val KEY_PRESET = "preset"
    private const val KEY_CROSSFEED = "crossfeed"
    private const val KEY_DYNAUDNORM = "dynaudnorm"
    private const val KEY_COMPRESSOR = "compressor"
    private const val KEY_LIMITER = "limiter"
    private const val KEY_LOUDNORM = "loudnorm"
    private const val KEY_ECHO = "echo"
    private const val KEY_STEREO_TOOLS = "stereo_tools"
    private const val KEY_EXTRA_STEREO = "extra_stereo"
    private const val KEY_PULSATOR = "pulsator"
    private const val KEY_SPEED = "speed"
    private const val KEY_BALANCE = "balance"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(EffectsConfig())
    val config: StateFlow<EffectsConfig> = _config

    /** Idempotent; safe to call from every entry point that might touch effects first. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _config.value = load(p)
        }
    }

    fun update(config: EffectsConfig) {
        _config.value = config
        prefs?.let { save(it, config) }
    }

    private fun load(p: SharedPreferences): EffectsConfig {
        val defaults = EffectsConfig()
        val bands = p.getString(KEY_BANDS, null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EQ_BAND_COUNT }
            ?: defaults.bandsDb
        return EffectsConfig(
            enabled = p.getBoolean(KEY_ENABLED, defaults.enabled),
            bandsDb = bands,
            preset = p.getString(KEY_PRESET, defaults.preset) ?: defaults.preset,
            crossfeed = p.getBoolean(KEY_CROSSFEED, defaults.crossfeed),
            dynaudnorm = p.getBoolean(KEY_DYNAUDNORM, defaults.dynaudnorm),
            compressor = p.getBoolean(KEY_COMPRESSOR, defaults.compressor),
            limiter = p.getBoolean(KEY_LIMITER, defaults.limiter),
            loudnorm = p.getBoolean(KEY_LOUDNORM, defaults.loudnorm),
            echo = p.getBoolean(KEY_ECHO, defaults.echo),
            stereoTools = p.getBoolean(KEY_STEREO_TOOLS, defaults.stereoTools),
            extraStereo = p.getBoolean(KEY_EXTRA_STEREO, defaults.extraStereo),
            pulsator = p.getBoolean(KEY_PULSATOR, defaults.pulsator),
            speed = p.getFloat(KEY_SPEED, defaults.speed),
            balance = p.getFloat(KEY_BALANCE, defaults.balance)
        )
    }

    private fun save(p: SharedPreferences, c: EffectsConfig) {
        p.edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_BANDS, c.bandsDb.joinToString(","))
            .putString(KEY_PRESET, c.preset)
            .putBoolean(KEY_CROSSFEED, c.crossfeed)
            .putBoolean(KEY_DYNAUDNORM, c.dynaudnorm)
            .putBoolean(KEY_COMPRESSOR, c.compressor)
            .putBoolean(KEY_LIMITER, c.limiter)
            .putBoolean(KEY_LOUDNORM, c.loudnorm)
            .putBoolean(KEY_ECHO, c.echo)
            .putBoolean(KEY_STEREO_TOOLS, c.stereoTools)
            .putBoolean(KEY_EXTRA_STEREO, c.extraStereo)
            .putBoolean(KEY_PULSATOR, c.pulsator)
            .putFloat(KEY_SPEED, c.speed)
            .putFloat(KEY_BALANCE, c.balance)
            .apply()
    }
}

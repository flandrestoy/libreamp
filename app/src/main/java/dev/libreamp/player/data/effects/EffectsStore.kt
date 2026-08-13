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
    private const val KEY_ACTIVE = "active_effects"
    private const val KEY_PARAMS = "effect_params"
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
            activeEffects = p.getStringSet(KEY_ACTIVE, null)?.toSet() ?: defaults.activeEffects,
            params = loadParams(p.getString(KEY_PARAMS, null)),
            speed = p.getFloat(KEY_SPEED, defaults.speed),
            balance = p.getFloat(KEY_BALANCE, defaults.balance)
        )
    }

    private fun save(p: SharedPreferences, c: EffectsConfig) {
        p.edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_BANDS, c.bandsDb.joinToString(","))
            .putString(KEY_PRESET, c.preset)
            .putStringSet(KEY_ACTIVE, c.activeEffects)
            .putString(KEY_PARAMS, c.params.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putFloat(KEY_SPEED, c.speed)
            .putFloat(KEY_BALANCE, c.balance)
            .apply()
    }

    /** Anything unparseable is dropped rather than failing the load; the param falls back to its default. */
    private fun loadParams(stored: String?): Map<String, Float> {
        if (stored.isNullOrEmpty()) return emptyMap()
        return stored.split(';').mapNotNull { entry ->
            val key = entry.substringBefore('=', "")
            val value = entry.substringAfter('=', "").toFloatOrNull()
            if (key.isEmpty() || value == null) null else key to value
        }.toMap()
    }
}

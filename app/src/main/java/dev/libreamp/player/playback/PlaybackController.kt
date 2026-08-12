package dev.libreamp.player.playback

import android.content.Context

/**
 * Process-wide singleton so both the UI (fragments) and [PlaybackService] talk to
 * the same [PlaybackEngine] instance without needing a bound-service/IPC layer
 * (everything runs in a single process).
 */
object PlaybackController {
    @Volatile private var engineInstance: PlaybackEngine? = null

    fun get(context: Context): PlaybackEngine = engineInstance ?: synchronized(this) {
        engineInstance ?: PlaybackEngine(context.applicationContext).also { engineInstance = it }
    }
}

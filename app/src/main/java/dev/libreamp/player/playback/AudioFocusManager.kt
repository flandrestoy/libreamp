package dev.libreamp.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Full transient/duck/gain state machine (API 26+ AudioFocusRequest, matches minSdk).
 * LOSS -> stop; LOSS_TRANSIENT -> pause (remember it was auto-paused);
 * LOSS_TRANSIENT_CAN_DUCK -> lower volume, don't pause; GAIN -> resume only if the
 * pause was auto-triggered, never overriding a manual user pause.
 */
class AudioFocusManager(
    context: Context,
    private val engine: PlaybackEngine,
    private val onLossStop: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    fun requestFocus(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                onLossStop()
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.autoPauseForFocusLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.duck(DUCK_VOLUME)
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.duck(FULL_VOLUME)
                engine.resumeIfAutoPaused()
            }
        }
    }

    companion object {
        private const val DUCK_VOLUME = 0.2f
        private const val FULL_VOLUME = 1.0f
    }
}

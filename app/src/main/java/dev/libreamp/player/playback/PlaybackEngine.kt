package dev.libreamp.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.core.net.toUri
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.data.effects.EffectsConfig
import dev.libreamp.player.data.effects.EffectsStore
import dev.libreamp.player.native_bridge.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

/**
 * Owns a single dedicated decode thread, one long-lived [AudioTrack] for the whole
 * playback session (never recreated per track, for click-free transitions), and the
 * native FFmpeg handle. All native calls happen on [handlerThread] only.
 */
class PlaybackEngine(context: Context) {

    interface Listener {
        fun onTrackCompleted()
        fun onError(message: String)
    }

    var listener: Listener? = null

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    private val handlerThread = HandlerThread("LibreAmpDecode").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val targetSampleRate: Int = run {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 44100
    }
    private val bytesPerSecond = targetSampleRate * OUT_CHANNELS * BYTES_PER_SAMPLE

    private var audioTrack: AudioTrack? = null
    private var nativeHandle: Long = 0L
    private var basePositionUs: Long = 0L
    private var bytesWrittenSinceBase: Long = 0L
    private val decodeChunk: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES)

    @Volatile private var playing = false
    @Volatile private var pendingAutoPause = false // set by AudioFocusManager transient loss

    /** Effects chain to (re)install after every open; touched on [handler] only. */
    private var effects: EffectsConfig
    private var duckFactor = 1f
    private var appliedSpeed = 1f

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    init {
        EffectsStore.init(appContext)
        effects = EffectsStore.config.value
    }

    private fun ensureAudioTrack() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            targetSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(targetSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(CHUNK_BYTES) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        appliedSpeed = 1f // a fresh track always starts at normal rate
        applyTrackEffects()
    }

    /**
     * Pushes the non-ffmpeg half of the chain (speed, balance, ducking) onto the
     * AudioTrack. Speed lives here rather than in an `atempo` filter so that
     * [bytesWrittenSinceBase] keeps measuring *source* PCM and the position estimate
     * stays truthful at any rate.
     */
    @Suppress("DEPRECATION") // setStereoVolume: the only per-channel gain API there is
    private fun applyTrackEffects() {
        val track = audioTrack ?: return
        val (left, right) = effects.channelGains
        track.setStereoVolume(left * duckFactor, right * duckFactor)

        val speed = effects.effectiveSpeed
        if (speed == appliedSpeed) return
        try {
            track.playbackParams = PlaybackParams().setSpeed(speed)
            appliedSpeed = speed
        } catch (t: Throwable) {
            // Some devices reject rates outside their resampler's range; stay where we were.
        }
    }

    /** Installs [effects] on the open native handle, if any. Call on [handler]. */
    private fun applyFilterGraph() {
        if (nativeHandle == 0L) return
        NativeBridge.nativeSetFilterGraph(nativeHandle, effects.toFilterGraph())
    }

    fun applyEffects(config: EffectsConfig) {
        handler.post {
            effects = config
            applyTrackEffects()
            applyFilterGraph()
        }
    }

    fun audioSessionId(): Int = audioTrack?.audioSessionId ?: 0

    fun play(entry: PlaylistEntryEntity) {
        handler.post {
            closeCurrentLocked()
            val pfd = try {
                resolver.openFileDescriptor(entry.contentUri.toUri(), "r")
            } catch (t: Throwable) {
                null
            }
            if (pfd == null) {
                _state.value = _state.value.copy(error = "Cannot open ${entry.displayName}")
                listener?.onError("Cannot open ${entry.displayName}")
                return@post
            }
            val fd = pfd.detachFd()
            val handle = NativeBridge.nativeOpen(fd, entry.displayName, targetSampleRate)
            if (handle == 0L) {
                _state.value = _state.value.copy(error = "Cannot decode ${entry.displayName}")
                listener?.onError("Cannot decode ${entry.displayName}")
                return@post
            }
            nativeHandle = handle
            basePositionUs = 0L
            bytesWrittenSinceBase = 0L

            // The graph is per-PlayerContext, so it dies with the previous handle and
            // has to be reinstalled for every track.
            applyFilterGraph()

            ensureAudioTrack()
            audioTrack?.play()
            playing = true
            _state.value = _state.value.copy(
                entry = entry,
                isPlaying = true,
                positionUs = 0L,
                durationUs = NativeBridge.nativeGetDurationUs(handle),
                error = null
            )
            handler.post(decodeLoop)
        }
    }

    fun pause(userInitiated: Boolean = true) {
        handler.post {
            if (userInitiated) pendingAutoPause = false
            playing = false
            audioTrack?.pause()
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    fun resume() {
        handler.post {
            if (nativeHandle == 0L) return@post
            pendingAutoPause = false
            playing = true
            audioTrack?.play()
            _state.value = _state.value.copy(isPlaying = true)
            handler.post(decodeLoop)
        }
    }

    /** Called by AudioFocusManager on transient loss; distinct from a manual pause. */
    fun autoPauseForFocusLoss() {
        handler.post {
            if (playing) {
                pendingAutoPause = true
                playing = false
                audioTrack?.pause()
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    fun resumeIfAutoPaused() {
        handler.post {
            if (pendingAutoPause) {
                pendingAutoPause = false
                playing = true
                audioTrack?.play()
                _state.value = _state.value.copy(isPlaying = true)
                handler.post(decodeLoop)
            }
        }
    }

    /** Focus-loss ducking; folded together with the balance gains rather than replacing them. */
    fun duck(volume: Float) {
        handler.post {
            duckFactor = volume.coerceIn(0f, 1f)
            applyTrackEffects()
        }
    }

    fun seekTo(positionUs: Long) {
        handler.post {
            if (nativeHandle == 0L) return@post
            val wasPlaying = playing
            audioTrack?.pause()
            val ok = NativeBridge.nativeSeekUs(nativeHandle, positionUs)
            audioTrack?.flush()
            if (ok) {
                basePositionUs = positionUs
                bytesWrittenSinceBase = 0L
            }
            if (wasPlaying) {
                audioTrack?.play()
                handler.post(decodeLoop)
            }
            _state.value = _state.value.copy(positionUs = currentPositionUs())
        }
    }

    fun toggleShuffle() {
        _state.value = _state.value.copy(shuffle = !_state.value.shuffle)
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.value = _state.value.copy(repeatMode = next)
    }

    fun stop() {
        handler.post {
            playing = false
            closeCurrentLocked()
            audioTrack?.pause()
            audioTrack?.flush()
            _state.value = _state.value.copy(isPlaying = false, entry = null)
        }
    }

    fun release() {
        handler.post {
            closeCurrentLocked()
            audioTrack?.release()
            audioTrack = null
        }
        handlerThread.quitSafely()
    }

    private fun currentPositionUs(): Long =
        basePositionUs + (bytesWrittenSinceBase * 1_000_000L / bytesPerSecond)

    private fun closeCurrentLocked() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeClose(nativeHandle)
            nativeHandle = 0L
        }
    }

    private val decodeLoop = object : Runnable {
        override fun run() {
            if (!playing || nativeHandle == 0L) return
            decodeChunk.clear()
            val n = NativeBridge.nativeReadPcmChunk(nativeHandle, decodeChunk, decodeChunk.capacity())
            when {
                n == -1 -> {
                    playing = false
                    _state.value = _state.value.copy(isPlaying = false)
                    listener?.onTrackCompleted()
                    return
                }
                n == -2 -> {
                    playing = false
                    _state.value = _state.value.copy(isPlaying = false, error = "Decode error")
                    listener?.onError("Decode error")
                    return
                }
                n > 0 -> {
                    decodeChunk.limit(n)
                    decodeChunk.position(0)
                    audioTrack?.write(decodeChunk, n, AudioTrack.WRITE_BLOCKING)
                    bytesWrittenSinceBase += n
                    _state.value = _state.value.copy(positionUs = currentPositionUs())
                }
            }
            handler.post(this)
        }
    }

    companion object {
        private const val OUT_CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
        private const val CHUNK_BYTES = 16384
    }
}

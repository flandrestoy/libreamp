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
import dev.libreamp.player.data.effects.EQ_BAND_COUNT
import dev.libreamp.player.data.effects.EQ_FILTER_TARGET
import dev.libreamp.player.data.effects.EQ_FREQUENCIES
import dev.libreamp.player.data.effects.EffectsConfig
import dev.libreamp.player.data.effects.EffectsStore
import dev.libreamp.player.data.effects.eqBandWidth
import dev.libreamp.player.native_bridge.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.Locale

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

    /** Read from the UI thread by [readSpectrum]; written on [handler]. */
    @Volatile private var audioTrack: AudioTrack? = null
    private var nativeHandle: Long = 0L
    private var basePositionUs: Long = 0L

    private val spectrum = SpectrumAnalyzer(targetSampleRate)

    /**
     * Frames handed to the AudioTrack since the last flush, which is the same
     * origin [AudioTrack.getPlaybackHeadPosition] counts from - so a chunk's
     * value here is the head position it becomes audible at. Wraps as an Int
     * exactly like the head position does.
     */
    private var writtenFrames: Int = 0

    /**
     * [AudioTrack.getPlaybackHeadPosition] at the moment [basePositionUs] was last set,
     * so the delta between it and the live head position is frames *actually rendered*
     * since then. Using bytes written to the track instead would run ahead by however
     * much is still sitting in the track's buffer, unplayed - most visible on pause,
     * where a byte-based estimate freezes at a point that hasn't been heard yet.
     */
    private var baseHeadFrames: Int = 0
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
     * [AudioTrack.getPlaybackHeadPosition] keeps counting *source* frames and the
     * position estimate stays truthful at any rate.
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

    /**
     * Live-tweaks one eq band's gain on the already-running `anequalizer` stage
     * instead of going through [applyEffects], which rebuilds the whole graph (and
     * with it the filter's FFT/biquad state) on every call - audible as a click on
     * every drag tick. Requires effects to already be enabled for the open handle,
     * since that's what puts the eq stage in the graph in the first place; see
     * [EffectsConfig.toFilterGraph], which keeps it there even when flat just so
     * this has something to update.
     */
    fun applyEqBand(index: Int, db: Float) {
        handler.post {
            if (nativeHandle == 0L || !effects.enabled) return@post
            val freq = EQ_FREQUENCIES[index]
            val width = eqBandWidth(freq)
            for (channel in 0 until STEREO_CHANNELS) {
                val filterIndex = channel * EQ_BAND_COUNT + index
                val arg = String.format(
                    Locale.US, "%d|f=%d|w=%.1f|g=%.2f", filterIndex, freq, width, db
                )
                NativeBridge.nativeSendFilterCommand(nativeHandle, EQ_FILTER_TARGET, "change", arg)
            }
        }
    }

    fun audioSessionId(): Int = audioTrack?.audioSessionId ?: 0

    /**
     * [flushBuffered] discards whatever of the previous track is still sitting in the
     * AudioTrack's buffer. Right for a track the user picked - they want it *now*, not
     * after the ~370ms already queued - and wrong for an auto-advance, where that
     * buffer is the tail of the track that just finished decoding and has yet to be
     * heard.
     */
    fun play(entry: PlaylistEntryEntity, flushBuffered: Boolean = true) {
        handler.post {
            // Before anything else: whatever the user asked for, the old track stops
            // being decoded here, not one chunk from now.
            handler.removeCallbacks(decodeLoop)
            if (flushBuffered) {
                audioTrack?.pause()
                audioTrack?.flush() // also resets getPlaybackHeadPosition() to 0
                resetSpectrumOrigin()
            }
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

            // The graph is per-PlayerContext, so it dies with the previous handle and
            // has to be reinstalled for every track.
            applyFilterGraph()

            ensureAudioTrack()
            baseHeadFrames = audioTrack?.playbackHeadPosition ?: 0
            audioTrack?.play()
            playing = true
            _state.value = _state.value.copy(
                entry = entry,
                isPlaying = true,
                positionUs = 0L,
                durationUs = NativeBridge.nativeGetDurationUs(handle),
                error = null
            )
            restartDecodeLoop()
        }
    }

    fun pause(userInitiated: Boolean = true) {
        handler.post {
            if (userInitiated) pendingAutoPause = false
            playing = false
            audioTrack?.pause()
            _state.value = _state.value.copy(isPlaying = false, positionUs = currentPositionUs())
        }
    }

    fun resume() {
        handler.post {
            if (nativeHandle == 0L) return@post
            pendingAutoPause = false
            playing = true
            audioTrack?.play()
            _state.value = _state.value.copy(isPlaying = true)
            restartDecodeLoop()
        }
    }

    /** Called by AudioFocusManager on transient loss; distinct from a manual pause. */
    fun autoPauseForFocusLoss() {
        handler.post {
            if (playing) {
                pendingAutoPause = true
                playing = false
                audioTrack?.pause()
                _state.value = _state.value.copy(isPlaying = false, positionUs = currentPositionUs())
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
                restartDecodeLoop()
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
            audioTrack?.flush() // also resets getPlaybackHeadPosition() to 0
            resetSpectrumOrigin()
            baseHeadFrames = 0
            if (ok) {
                basePositionUs = positionUs
            }
            if (wasPlaying) {
                audioTrack?.play()
                restartDecodeLoop()
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
            resetSpectrumOrigin()
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

    /**
     * Fills [out] with the band levels for what is audible right now, returning
     * false when there is nothing playing to describe.
     *
     * Safe from the UI thread: it only reads the AudioTrack's head position and
     * the analyzer's own synchronized ring.
     */
    fun readSpectrum(out: FloatArray): Boolean {
        if (!playing) return false
        val track = audioTrack ?: return false
        return spectrum.bandsAt(track.playbackHeadPosition, out)
    }

    /** Call wherever the AudioTrack is flushed: its frame origin moves with it. */
    private fun resetSpectrumOrigin() {
        writtenFrames = 0
        spectrum.reset()
    }

    private fun currentPositionUs(): Long {
        val track = audioTrack ?: return basePositionUs
        // Plain Int subtraction wraps correctly modulo 2^32 even if the head position
        // has overflowed since baseHeadFrames was captured, as long as the true delta
        // fits in 31 bits (~13 hours at 44.1 kHz) - ample for one track's playback.
        val deltaFrames = (track.playbackHeadPosition - baseHeadFrames).toLong()
        return basePositionUs + (deltaFrames * 1_000_000L / targetSampleRate)
    }

    private fun closeCurrentLocked() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeClose(nativeHandle)
            nativeHandle = 0L
        }
    }

    /**
     * Posts a decode-loop iteration, dropping any already queued.
     *
     * [decodeLoop] reposts itself, so a copy left in the queue is not a stray message
     * but a whole second chain cycling forever - and the same Runnable can sit in the
     * queue any number of times. Each copy holds the decode thread for one blocking
     * [AudioTrack.write] (~93ms at [CHUNK_BYTES]/44.1kHz), and work posted afterwards
     * queues behind all of them, so chains accumulated over a run of track switches
     * turn into a lag between picking a track and hearing it that grows by ~93ms each
     * time. Call on [handler], which is also the only thread [decodeLoop] runs on, so
     * there is never an iteration in flight to race with the removal.
     */
    private fun restartDecodeLoop() {
        handler.removeCallbacks(decodeLoop)
        handler.post(decodeLoop)
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
                    // Analysed before the write, which consumes the buffer, and
                    // stamped with the head position this chunk will play at.
                    spectrum.submit(decodeChunk, n, writtenFrames)
                    decodeChunk.limit(n)
                    decodeChunk.position(0)
                    audioTrack?.write(decodeChunk, n, AudioTrack.WRITE_BLOCKING)
                    writtenFrames += n / BYTES_PER_FRAME
                    _state.value = _state.value.copy(positionUs = currentPositionUs())
                }
            }
            handler.post(this)
        }
    }

    companion object {
        private const val CHUNK_BYTES = 16384
        private const val STEREO_CHANNELS = 2
        private const val BYTES_PER_FRAME = 4 // 16-bit stereo
    }
}

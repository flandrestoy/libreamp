package dev.libreamp.player.native_bridge

import java.nio.ByteBuffer

/**
 * Thin JNI surface over the statically-linked FFmpeg build (libffmpeg_player.so).
 * FFmpeg does all demuxing/decoding here — no MediaCodec/MediaExtractor involved.
 *
 * Threading contract: every native call for a given [handle] (including seeks)
 * MUST be issued from the same thread (see PlaybackEngine's decode thread). The
 * native side keeps no locks.
 */
object NativeBridge {

    init {
        System.loadLibrary("ffmpeg_player")
    }

    /** Returns 0 on failure, otherwise an opaque native handle. */
    external fun nativeOpen(fd: Int, displayName: String, targetSampleRate: Int): Long

    external fun nativeGetDurationUs(handle: Long): Long

    /**
     * Decodes+resamples into [byteBuffer] (must be direct, capacity >= [capacity]),
     * returning bytes written, or -1 at true end-of-stream, or -2 on error.
     */
    external fun nativeReadPcmChunk(handle: Long, byteBuffer: ByteBuffer, capacity: Int): Int

    external fun nativeSeekUs(handle: Long, positionUs: Long): Boolean

    /** Flattened key/value pairs: [k0, v0, k1, v1, ...]. */
    external fun nativeGetTags(handle: Long): Array<String>

    /** Raw compressed (JPEG/PNG) embedded cover art bytes, or null if none present. */
    external fun nativeGetEmbeddedArt(handle: Long): ByteArray?

    external fun nativeClose(handle: Long)

    external fun nativeGetFfmpegConfig(): String

    fun tagsToMap(flat: Array<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            map[flat[i].lowercase()] = flat[i + 1]
            i += 2
        }
        return map
    }
}

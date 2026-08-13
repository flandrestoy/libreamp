package dev.libreamp.player.native_bridge

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

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

    /**
     * Flattened key/value pairs: [k0, v0, k1, v1, ...], as **raw bytes** in whatever
     * encoding the container held. Decode with [tagsToMap]; see [decodeTag] for why
     * these do not cross the JNI boundary as strings.
     */
    external fun nativeGetTags(handle: Long): Array<ByteArray>

    /** Raw compressed (JPEG/PNG) embedded cover art bytes, or null if none present. */
    external fun nativeGetEmbeddedArt(handle: Long): ByteArray?

    external fun nativeClose(handle: Long)

    /**
     * Installs an ffmpeg filter-graph string (e.g. "superequalizer=1b=6:2b=4",
     * "bass=g=5,crossfeed=strength=0.4"), or clears the chain when [filterDesc]
     * is null. Returns false if the chain could not be built, in which case
     * playback continues unfiltered.
     */
    external fun nativeSetFilterGraph(handle: Long, filterDesc: String?): Boolean

    /** Live-tweaks one parameter of a running filter without rebuilding the graph. */
    external fun nativeSendFilterCommand(
        handle: Long, target: String, cmd: String, arg: String?
    ): Boolean

    external fun nativeGetFfmpegConfig(): String

    fun tagsToMap(flat: Array<ByteArray>): Map<String, String> {
        val map = LinkedHashMap<String, String>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            map[decodeTag(flat[i]).lowercase()] = decodeTag(flat[i + 1])
            i += 2
        }
        return map
    }

    /**
     * Decodes one raw tag value.
     *
     * FFmpeg only guarantees UTF-8 for tags whose container declares an encoding
     * (ID3v2 frames, Vorbis comments); ID3v1 fields have no encoding field at all
     * and are copied out verbatim in whatever 8-bit codepage the tagger used. Such
     * bytes cannot be turned into a jstring natively — `NewStringUTF` demands
     * modified UTF-8 and aborts the whole process under CheckJNI when it does not
     * get it — so the native side hands over bytes and the guess happens here.
     *
     * Strict UTF-8 first (never mangles correctly-tagged files), then a legacy
     * codepage picked from the byte pattern.
     */
    private fun decodeTag(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        decodeStrictUtf8(bytes)?.let { return it }
        return String(bytes, legacyCharsetFor(bytes))
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (t: CharacterCodingException) {
        null
    }

    /**
     * Cyrillic text in CP1251 is *runs* of high bytes (every letter is one), while
     * Western-European text in CP1252 has isolated high bytes sprinkled through
     * ASCII (accents). That difference is what this checks; a wrong guess only
     * misrenders the tag, where the previous behaviour killed the process.
     */
    private fun legacyCharsetFor(bytes: ByteArray): Charset {
        var run = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val cyrillic = v >= 0xC0 || v == 0xA8 || v == 0xB8 || v == 0xAF || v == 0xBF
            if (cyrillic) {
                run++
                if (run >= 3) return CP1251
            } else {
                run = 0
            }
        }
        return CP1252
    }

    private fun charsetOrLatin1(name: String): Charset =
        try {
            Charset.forName(name)
        } catch (t: Exception) {
            Charsets.ISO_8859_1
        }

    private val CP1251: Charset by lazy { charsetOrLatin1("windows-1251") }
    private val CP1252: Charset by lazy { charsetOrLatin1("windows-1252") }
}

package dev.libreamp.player.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.libreamp.player.data.db.MediaType
import dev.libreamp.player.data.db.PickedFile
import dev.libreamp.player.native_bridge.NativeBridge
import java.io.File
import java.util.UUID

fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
    return uri.lastPathSegment ?: uri.toString()
}

/**
 * Probes a freshly-picked file using the same FFmpeg native bridge used for
 * playback (not MediaMetadataRetriever/MediaStore) to read duration + tags,
 * so metadata extraction and playback share one demux/decode code path.
 */
object MediaProbe {

    fun probe(context: Context, uri: Uri): PickedFile? {
        val displayName = queryDisplayName(context, uri)
        val mime = context.contentResolver.getType(uri) ?: ""
        val mediaType = if (mime.startsWith("video/")) MediaType.VIDEO else MediaType.AUDIO

        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (t: Throwable) {
            null
        } ?: return null

        val fd = pfd.detachFd()
        val handle = NativeBridge.nativeOpen(fd, displayName, PROBE_SAMPLE_RATE)
        if (handle == 0L) return null

        return try {
            val durationUs = NativeBridge.nativeGetDurationUs(handle)
            val tags = NativeBridge.tagsToMap(NativeBridge.nativeGetTags(handle))
            val artPath = if (mediaType == MediaType.AUDIO) {
                NativeBridge.nativeGetEmbeddedArt(handle)?.let { cacheArt(context, it) }
            } else {
                null
            }
            PickedFile(
                uri = uri,
                displayName = displayName,
                mediaType = mediaType,
                title = tags["title"],
                artist = tags["artist"],
                album = tags["album"],
                durationMs = if (durationUs > 0) durationUs / 1000 else 0L,
                artPath = artPath
            )
        } finally {
            NativeBridge.nativeClose(handle)
        }
    }

    /** Writes probed cover art bytes to app-private storage; returns the file's absolute path. */
    private fun cacheArt(context: Context, bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "album_art").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.art")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    private const val PROBE_SAMPLE_RATE = 44100
}

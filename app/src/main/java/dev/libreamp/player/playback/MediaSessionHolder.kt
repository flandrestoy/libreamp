package dev.libreamp.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.SystemClock
import dev.libreamp.player.data.db.PlaylistEntryEntity

/**
 * Wraps the plain platform android.media.session.MediaSession (no androidx.media
 * compat artifact — targetSdk 31 lets us use the raw platform APIs directly).
 * No manifest MEDIA_BUTTON receiver is needed at minSdk 26: keeping the session
 * active is enough for hardware/Bluetooth button events to route to [callback].
 */
class MediaSessionHolder(context: Context, callback: MediaSession.Callback) {

    val session: MediaSession = MediaSession(context, "LibreAmpSession").apply {
        setCallback(callback)
        isActive = true
    }

    val sessionToken: MediaSession.Token get() = session.sessionToken

    private var cachedArtPath: String? = null
    private var cachedArtBitmap: Bitmap? = null

    fun updateState(state: PlaybackState) {
        val actions = PlatformPlaybackState.ACTION_PLAY or
                PlatformPlaybackState.ACTION_PAUSE or
                PlatformPlaybackState.ACTION_PLAY_PAUSE or
                PlatformPlaybackState.ACTION_SKIP_TO_NEXT or
                PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlatformPlaybackState.ACTION_SEEK_TO

        val platformState = if (state.isPlaying) {
            PlatformPlaybackState.STATE_PLAYING
        } else {
            PlatformPlaybackState.STATE_PAUSED
        }

        session.setPlaybackState(
            PlatformPlaybackState.Builder()
                .setActions(actions)
                .setState(platformState, state.positionUs / 1000, 1.0f, SystemClock.elapsedRealtime())
                .build()
        )

        val entry: PlaylistEntryEntity? = state.entry
        val artPath = entry?.artPath
        if (artPath != cachedArtPath) {
            cachedArtPath = artPath
            cachedArtBitmap = artPath?.let { BitmapFactory.decodeFile(it) }
        }
        val artBitmap = cachedArtBitmap
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, entry?.title ?: entry?.displayName ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, entry?.artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, entry?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationUs / 1000)
                .apply { if (artBitmap != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap) }
                .build()
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}

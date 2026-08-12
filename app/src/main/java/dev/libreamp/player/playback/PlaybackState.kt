package dev.libreamp.player.playback

import dev.libreamp.player.data.db.PlaylistEntryEntity

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val entry: PlaylistEntryEntity? = null,
    val isPlaying: Boolean = false,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val error: String? = null
)

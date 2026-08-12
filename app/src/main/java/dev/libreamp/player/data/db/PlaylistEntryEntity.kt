package dev.libreamp.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType { AUDIO, VIDEO }

/**
 * A single playlist row. [contentUri] comes from Storage Access Framework
 * (ACTION_OPEN_DOCUMENT) — never MediaStore.
 */
@Entity(tableName = "playlist_entries")
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentUri: String,
    val displayName: String,
    val mediaType: MediaType,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val manualOrderIndex: Long,
    val dateAddedMs: Long,
    val accessRevoked: Boolean = false,
    /** Path to a cached copy of the embedded cover art extracted at probe time, if any. */
    val artPath: String? = null
)

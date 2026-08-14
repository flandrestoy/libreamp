package dev.libreamp.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType { AUDIO, VIDEO }

/**
 * A single playlist row. [contentUri] is either a Storage Access Framework
 * content:// Uri or a plain file:// Uri from the in-app filesystem picker —
 * never MediaStore.
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
    /**
     * Position **within its container** — among top-level items when [groupId] is null
     * (sharing a number line with [PlaylistGroupEntity.orderIndex]), among its siblings
     * inside the group otherwise. Never compare indices across containers.
     */
    val manualOrderIndex: Long,
    /** Owning group, or null for a loose track sitting at the top level. */
    val groupId: Long? = null,
    val dateAddedMs: Long,
    val lastModifiedMs: Long = 0L,
    val accessRevoked: Boolean = false,
    /** Path to a cached copy of the embedded cover art extracted at probe time, if any. */
    val artPath: String? = null
)

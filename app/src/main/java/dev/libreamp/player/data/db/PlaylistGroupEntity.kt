package dev.libreamp.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-owned group: an object with identity, not a rendering of a run of matching
 * metadata. That is the whole point — [label] is free text the user can edit and it is
 * never re-derived from the tracks, so a group survives sorting, renaming and having
 * its contents replaced wholesale.
 *
 * [orderIndex] lives in the same number line as a *top-level* entry's
 * [PlaylistEntryEntity.manualOrderIndex], which is what lets groups and loose tracks
 * interleave freely. [collapsed] is view state only: it never affects the play queue.
 */
@Entity(tableName = "playlist_groups")
data class PlaylistGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val orderIndex: Long,
    val collapsed: Boolean = false
)

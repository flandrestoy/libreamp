package dev.libreamp.player.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistEntryDao {

    /**
     * Ordered by manualOrderIndex, which is only meaningful *within* a container — so
     * this is a correctly ordered list of every container's contents interleaved, and
     * only becomes a playlist once [buildTree] has split it by [PlaylistEntryEntity.groupId].
     */
    @Query("SELECT * FROM playlist_entries ORDER BY manualOrderIndex ASC")
    fun observeAll(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY manualOrderIndex ASC")
    suspend fun getAll(): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries WHERE groupId = :groupId ORDER BY manualOrderIndex ASC")
    suspend fun getByGroup(groupId: Long): List<PlaylistEntryEntity>

    /** Top-level tail only: new files append after the last top-level item, never inside a group. */
    @Query("SELECT COALESCE(MAX(manualOrderIndex), 0) FROM playlist_entries WHERE groupId IS NULL")
    suspend fun maxTopLevelOrderIndex(): Long

    @Insert
    suspend fun insertAll(entries: List<PlaylistEntryEntity>): List<Long>

    @Update
    suspend fun update(entry: PlaylistEntryEntity)

    @Update
    suspend fun updateAll(entries: List<PlaylistEntryEntity>)

    @Delete
    suspend fun deleteAll(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM playlist_entries WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntryEntity?
}

package dev.libreamp.player.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistEntryDao {

    @Query("SELECT * FROM playlist_entries ORDER BY manualOrderIndex ASC")
    fun observeAll(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries ORDER BY manualOrderIndex ASC")
    suspend fun getAll(): List<PlaylistEntryEntity>

    @Query("SELECT COALESCE(MAX(manualOrderIndex), 0) FROM playlist_entries")
    suspend fun maxManualOrderIndex(): Long

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

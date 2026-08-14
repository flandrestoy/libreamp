package dev.libreamp.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistGroupDao {

    @Query("SELECT * FROM playlist_groups ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<PlaylistGroupEntity>>

    @Query("SELECT * FROM playlist_groups ORDER BY orderIndex ASC")
    suspend fun getAll(): List<PlaylistGroupEntity>

    @Query("SELECT * FROM playlist_groups WHERE id = :id")
    suspend fun getById(id: Long): PlaylistGroupEntity?

    @Insert
    suspend fun insert(group: PlaylistGroupEntity): Long

    @Update
    suspend fun update(group: PlaylistGroupEntity)

    @Update
    suspend fun updateAll(groups: List<PlaylistGroupEntity>)

    @Query("DELETE FROM playlist_groups WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

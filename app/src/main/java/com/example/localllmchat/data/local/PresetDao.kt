package com.example.localllmchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY lastUsedAt DESC")
    fun getAllPresets(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun getPresetById(id: Long): PresetEntity?

    @Insert
    suspend fun insert(preset: PresetEntity): Long

    @Update
    suspend fun update(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE presets SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsedAt(id: Long, timestamp: Long = System.currentTimeMillis())
}

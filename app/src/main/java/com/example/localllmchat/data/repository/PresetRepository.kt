package com.example.localllmchat.data.repository

import com.example.localllmchat.data.local.PresetDao
import com.example.localllmchat.data.local.PresetEntity
import kotlinx.coroutines.flow.Flow

class PresetRepository(private val presetDao: PresetDao) {

    fun getAllPresets(): Flow<List<PresetEntity>> = presetDao.getAllPresets()

    suspend fun getPresetById(id: Long): PresetEntity? = presetDao.getPresetById(id)

    suspend fun createPreset(name: String, emoji: String, description: String, systemPrompt: String): Long {
        return presetDao.insert(
            PresetEntity(name = name, emoji = emoji, description = description, systemPrompt = systemPrompt)
        )
    }

    suspend fun updatePreset(preset: PresetEntity) = presetDao.update(preset)

    suspend fun deletePreset(id: Long) = presetDao.deleteById(id)

    suspend fun updateLastUsedAt(id: Long) = presetDao.updateLastUsedAt(id)
}

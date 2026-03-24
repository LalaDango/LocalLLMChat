package com.example.localllmchat.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val emoji: String,
    val description: String = "",
    val systemPrompt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

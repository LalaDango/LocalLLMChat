package com.example.localllmchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageImageDao {
    @Insert
    suspend fun insertAll(images: List<MessageImageEntity>)

    @Query("SELECT * FROM message_images WHERE messageId IN (:messageIds) ORDER BY messageId, sortOrder")
    suspend fun getForMessages(messageIds: List<Long>): List<MessageImageEntity>
}

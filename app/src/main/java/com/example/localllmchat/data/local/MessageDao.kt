package com.example.localllmchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesForConversationSync(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long)

    @Query("UPDATE messages SET summaryText = :summaryText, isSummarized = 1 WHERE id = :messageId")
    suspend fun updateSummary(messageId: Long, summaryText: String)

    @Query("UPDATE messages SET isExcluded = :isExcluded WHERE id = :messageId")
    suspend fun updateExcluded(messageId: Long, isExcluded: Boolean)
}

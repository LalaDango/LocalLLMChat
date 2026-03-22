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

    @Query("UPDATE messages SET summaryText = :summaryText, isSummarized = 1, summarizeConfigJson = :configJson WHERE id = :messageId")
    suspend fun updateSummaryWithConfig(messageId: Long, summaryText: String, configJson: String)

    @Query("UPDATE messages SET isExcluded = :isExcluded WHERE id = :messageId")
    suspend fun updateExcluded(messageId: Long, isExcluded: Boolean)

    @Query("UPDATE messages SET translatedText = :translatedText WHERE id = :messageId")
    suspend fun updateTranslation(messageId: Long, translatedText: String)

    // Branch feature queries
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE parentMessageId = :parentId ORDER BY siblingIndex ASC")
    suspend fun getChildMessages(parentId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE parentMessageId IS NULL AND conversationId = :conversationId ORDER BY siblingIndex ASC")
    suspend fun getRootMessages(conversationId: Long): List<MessageEntity>

    @Query("UPDATE messages SET activeChildId = :activeChildId WHERE id = :messageId")
    suspend fun updateActiveChildId(messageId: Long, activeChildId: Long?)

    @Query("UPDATE messages SET parentMessageId = :parentId, siblingIndex = :siblingIndex WHERE id = :messageId")
    suspend fun updateParentAndIndex(messageId: Long, parentId: Long?, siblingIndex: Int)

    @Query("SELECT COUNT(*) FROM messages WHERE parentMessageId = :parentId")
    suspend fun getSiblingCount(parentId: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE parentMessageId IS NULL AND conversationId = :conversationId")
    suspend fun getRootSiblingCount(conversationId: Long): Int
}

package com.example.localllmchat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// 画像 base64 の永続化用。messages テーブルに持たせると getMessagesForConversation の
// Flow 全体が肥大化し CursorWindow ~2MB 制限に抵触するため別テーブルにする
@Entity(
    tableName = "message_images",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class MessageImageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageId: Long,
    val fileName: String,
    val mimeType: String,
    val base64Data: String,
    val sortOrder: Int = 0
)

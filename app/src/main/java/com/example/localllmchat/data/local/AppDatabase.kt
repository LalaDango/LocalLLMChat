package com.example.localllmchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN totalTokens INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN decodingSpeedTps REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN prefillSpeedTps REAL DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN summaryText TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN isSummarized INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN isExcluded INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN translatedText TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN toolCallsJson TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN parentMessageId INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE messages ADD COLUMN siblingIndex INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN activeChildId INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE conversations ADD COLUMN activeRootMessageId INTEGER DEFAULT NULL")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_parentMessageId ON messages(parentMessageId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "localllmchat.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.example.localllmchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, PresetEntity::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun presetDao(): PresetDao

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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN summarizeConfigJson TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        emoji TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        systemPrompt TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        lastUsedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                database.execSQL("ALTER TABLE conversations ADD COLUMN presetId INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE conversations ADD COLUMN presetEmoji TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE conversations ADD COLUMN presetName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE conversations ADD COLUMN systemPrompt TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "localllmchat.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

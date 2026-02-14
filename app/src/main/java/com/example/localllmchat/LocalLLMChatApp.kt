package com.example.localllmchat

import android.app.Application
import com.example.localllmchat.data.local.AppDatabase
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository

class LocalLLMChatApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var chatRepository: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        chatRepository = ChatRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            settingsRepository = settingsRepository
        )
    }
}

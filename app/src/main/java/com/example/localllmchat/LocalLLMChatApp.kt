package com.example.localllmchat

import android.app.Application
import com.example.localllmchat.data.local.AppDatabase
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.PresetRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.data.tool.AskUserQuestionTool
import com.example.localllmchat.data.tool.DateTimeTool
import com.example.localllmchat.data.tool.ToolRegistry

class LocalLLMChatApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var presetRepository: PresetRepository
        private set

    lateinit var toolRegistry: ToolRegistry
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        presetRepository = PresetRepository(database.presetDao())
        toolRegistry = ToolRegistry().apply {
            register("get_datetime", DateTimeTool())
            register("ask_user_question", AskUserQuestionTool())
        }
        chatRepository = ChatRepository(
            database = database,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            messageImageDao = database.messageImageDao(),
            settingsRepository = settingsRepository,
            toolRegistry = toolRegistry
        )
    }
}

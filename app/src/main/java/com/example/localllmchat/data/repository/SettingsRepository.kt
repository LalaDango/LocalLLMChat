package com.example.localllmchat.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val BASE_URL_KEY = stringPreferencesKey("base_url")
        private val MODEL_NAME_KEY = stringPreferencesKey("model_name")
        private val CONTEXT_WINDOW_SIZE_KEY = intPreferencesKey("context_window_size")
        private val SYSTEM_PROMPT_KEY = stringPreferencesKey("system_prompt")
        private val DISABLED_TOOLS_KEY = stringSetPreferencesKey("disabled_tools")
        private val MAX_ATTACHMENT_TEXT_KB_KEY = intPreferencesKey("max_attachment_text_kb")

        const val DEFAULT_BASE_URL = "http://localhost:8080"
        const val DEFAULT_MODEL_NAME = "gemma4-it:e4b"
        const val DEFAULT_CONTEXT_WINDOW_SIZE = 32768
        const val DEFAULT_SYSTEM_PROMPT = ""
        const val DEFAULT_MAX_ATTACHMENT_TEXT_KB = 60
    }

    val baseUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[BASE_URL_KEY] ?: DEFAULT_BASE_URL
    }

    val modelName: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MODEL_NAME_KEY] ?: DEFAULT_MODEL_NAME
    }

    val contextWindowSize: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[CONTEXT_WINDOW_SIZE_KEY] ?: DEFAULT_CONTEXT_WINDOW_SIZE
    }

    val systemPrompt: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SYSTEM_PROMPT_KEY] ?: DEFAULT_SYSTEM_PROMPT
    }

    val disabledTools: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[DISABLED_TOOLS_KEY] ?: emptySet()
    }

    val maxAttachmentTextKb: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[MAX_ATTACHMENT_TEXT_KB_KEY] ?: DEFAULT_MAX_ATTACHMENT_TEXT_KB
    }

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[BASE_URL_KEY] = url
        }
    }

    suspend fun saveModelName(model: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_NAME_KEY] = model
        }
    }

    suspend fun saveContextWindowSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[CONTEXT_WINDOW_SIZE_KEY] = size
        }
    }

    suspend fun saveDisabledTools(names: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[DISABLED_TOOLS_KEY] = names
        }
    }

    suspend fun saveSettings(baseUrl: String, modelName: String, contextWindowSize: Int, systemPrompt: String, maxAttachmentTextKb: Int) {
        context.dataStore.edit { preferences ->
            preferences[BASE_URL_KEY] = baseUrl
            preferences[MODEL_NAME_KEY] = modelName
            preferences[CONTEXT_WINDOW_SIZE_KEY] = contextWindowSize
            preferences[SYSTEM_PROMPT_KEY] = systemPrompt
            preferences[MAX_ATTACHMENT_TEXT_KB_KEY] = maxAttachmentTextKb
        }
    }
}

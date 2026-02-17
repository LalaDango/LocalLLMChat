package com.example.localllmchat.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        private val LEAP_VISION_ENABLED_KEY = booleanPreferencesKey("leap_vision_enabled")

        const val DEFAULT_BASE_URL = "http://localhost:8080"
        const val DEFAULT_MODEL_NAME = "lfm2.5-tk:1.2b"
        const val DEFAULT_CONTEXT_WINDOW_SIZE = 32768
        const val DEFAULT_SYSTEM_PROMPT = ""
        const val DEFAULT_LEAP_VISION_ENABLED = false
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

    val leapVisionEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LEAP_VISION_ENABLED_KEY] ?: DEFAULT_LEAP_VISION_ENABLED
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

    suspend fun saveLeapVisionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LEAP_VISION_ENABLED_KEY] = enabled
        }
    }

    suspend fun saveSettings(baseUrl: String, modelName: String, contextWindowSize: Int, systemPrompt: String) {
        context.dataStore.edit { preferences ->
            preferences[BASE_URL_KEY] = baseUrl
            preferences[MODEL_NAME_KEY] = modelName
            preferences[CONTEXT_WINDOW_SIZE_KEY] = contextWindowSize
            preferences[SYSTEM_PROMPT_KEY] = systemPrompt
        }
    }
}

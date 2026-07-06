package com.example.localllmchat.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localllmchat.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = SettingsRepository.DEFAULT_BASE_URL,
    val modelName: String = SettingsRepository.DEFAULT_MODEL_NAME,
    val contextWindowSize: Int = SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE,
    val systemPrompt: String = SettingsRepository.DEFAULT_SYSTEM_PROMPT,
    val maxAttachmentTextKb: Int = SettingsRepository.DEFAULT_MAX_ATTACHMENT_TEXT_KB,
    val temperature: Double = SettingsRepository.DEFAULT_TEMPERATURE,
    val maxCompletionTokens: Int = SettingsRepository.DEFAULT_MAX_COMPLETION_TOKENS,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.baseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(baseUrl = url)
            }
        }
        viewModelScope.launch {
            settingsRepository.modelName.collect { model ->
                _uiState.value = _uiState.value.copy(modelName = model)
            }
        }
        viewModelScope.launch {
            settingsRepository.contextWindowSize.collect { size ->
                _uiState.value = _uiState.value.copy(contextWindowSize = size)
            }
        }
        viewModelScope.launch {
            settingsRepository.systemPrompt.collect { prompt ->
                _uiState.value = _uiState.value.copy(systemPrompt = prompt)
            }
        }
        viewModelScope.launch {
            settingsRepository.maxAttachmentTextKb.collect { kb ->
                _uiState.value = _uiState.value.copy(maxAttachmentTextKb = kb)
            }
        }
        viewModelScope.launch {
            settingsRepository.temperature.collect { temp ->
                _uiState.value = _uiState.value.copy(temperature = temp)
            }
        }
        viewModelScope.launch {
            settingsRepository.maxCompletionTokens.collect { tokens ->
                _uiState.value = _uiState.value.copy(maxCompletionTokens = tokens)
            }
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url, saveSuccess = false)
    }

    fun updateModelName(model: String) {
        _uiState.value = _uiState.value.copy(modelName = model, saveSuccess = false)
    }

    fun updateContextWindowSize(size: String) {
        val intSize = size.toIntOrNull() ?: SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE
        _uiState.value = _uiState.value.copy(contextWindowSize = intSize, saveSuccess = false)
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = prompt, saveSuccess = false)
    }

    fun updateMaxAttachmentTextKb(value: String) {
        val kb = (value.toIntOrNull() ?: SettingsRepository.DEFAULT_MAX_ATTACHMENT_TEXT_KB)
            .coerceAtLeast(1)
        _uiState.value = _uiState.value.copy(maxAttachmentTextKb = kb, saveSuccess = false)
    }

    fun updateTemperature(value: Double) {
        _uiState.value = _uiState.value.copy(temperature = value, saveSuccess = false)
    }

    fun updateMaxCompletionTokens(value: String) {
        val tokens = (value.toIntOrNull() ?: SettingsRepository.DEFAULT_MAX_COMPLETION_TOKENS)
            .coerceAtLeast(1)
        _uiState.value = _uiState.value.copy(maxCompletionTokens = tokens, saveSuccess = false)
    }

    fun saveSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            settingsRepository.saveSettings(
                baseUrl = _uiState.value.baseUrl,
                modelName = _uiState.value.modelName,
                contextWindowSize = _uiState.value.contextWindowSize,
                systemPrompt = _uiState.value.systemPrompt,
                maxAttachmentTextKb = _uiState.value.maxAttachmentTextKb,
                temperature = _uiState.value.temperature,
                maxCompletionTokens = _uiState.value.maxCompletionTokens
            )
            _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
        }
    }

    class Factory(private val settingsRepository: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepository) as T
        }
    }
}

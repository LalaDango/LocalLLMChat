package com.example.localllmchat.ui.screen.conversationlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.PresetEntity
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.PresetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConversationListUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val presets: List<PresetEntity> = emptyList(),
    val isLoading: Boolean = true
)

class ConversationListViewModel(
    private val chatRepository: ChatRepository,
    private val presetRepository: PresetRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    init {
        loadConversations()
        loadPresets()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            chatRepository.getAllConversations().collect { conversations ->
                _uiState.value = _uiState.value.copy(
                    conversations = conversations,
                    isLoading = false
                )
            }
        }
    }

    private fun loadPresets() {
        viewModelScope.launch {
            presetRepository.getAllPresets().collect { presets ->
                _uiState.value = _uiState.value.copy(presets = presets)
            }
        }
    }

    fun createNewConversation(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val conversationId = chatRepository.createConversation("New Chat")
            onCreated(conversationId)
        }
    }

    fun createConversationFromPreset(preset: PresetEntity, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            presetRepository.updateLastUsedAt(preset.id)
            val conversationId = chatRepository.createConversationWithPreset(
                title = "${preset.emoji} New Chat",
                preset = preset
            )
            onCreated(conversationId)
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversationId)
        }
    }

    class Factory(
        private val chatRepository: ChatRepository,
        private val presetRepository: PresetRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConversationListViewModel(chatRepository, presetRepository) as T
        }
    }
}

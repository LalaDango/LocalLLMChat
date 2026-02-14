package com.example.localllmchat.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.util.ProcessedAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionTokenCount: Int = 0,
    val conversationTotalTokens: Int = 0, // cumulative tokens across all turns in this chat
    val contextWindowSize: Int = SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE,
    val attachment: ProcessedAttachment? = null,
    val attachmentWarning: String? = null,
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val summarizingMessageId: Long? = null,
    val summarizeToast: String? = null
)

class ChatViewModel(
    private val conversationId: Long,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadConversation()
        loadMessages()
        loadContextWindowSize()
    }

    private fun loadConversation() {
        viewModelScope.launch {
            val conversation = chatRepository.getConversationById(conversationId)
            _uiState.value = _uiState.value.copy(conversation = conversation)
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            chatRepository.getMessagesForConversation(conversationId).collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
                updateSessionTokenCount(messages)
            }
        }
    }

    private fun loadContextWindowSize() {
        viewModelScope.launch {
            settingsRepository.contextWindowSize.collect { size ->
                _uiState.value = _uiState.value.copy(contextWindowSize = size)
            }
        }
    }

    private fun updateSessionTokenCount(messages: List<MessageEntity>) {
        val activeMessages = messages.filter { !it.isExcluded }
        val lastAssistantMessage = activeMessages.lastOrNull { it.role == "assistant" }
        val lastTurnTokens = lastAssistantMessage?.totalTokens ?: 0
        // Sum total_tokens from all non-excluded assistant messages for cumulative count
        val cumulativeTokens = activeMessages.filter { it.role == "assistant" }.sumOf { it.totalTokens ?: 0 }
        _uiState.value = _uiState.value.copy(
            sessionTokenCount = lastTurnTokens,
            conversationTotalTokens = cumulativeTokens
        )
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun setAttachment(attachment: ProcessedAttachment) {
        val warning = if (attachment is ProcessedAttachment.TextAttachment && attachment.wasTruncated) {
            "ファイルが24KBを超えています。先頭部分のみ送信します。"
        } else null
        _uiState.value = _uiState.value.copy(attachment = attachment, attachmentWarning = warning)
    }

    fun clearAttachment() {
        _uiState.value = _uiState.value.copy(attachment = null, attachmentWarning = null)
    }

    fun sendMessage() {
        val message = _uiState.value.inputText.trim()
        val attachment = _uiState.value.attachment
        if (message.isEmpty() && attachment == null) return

        _uiState.value = _uiState.value.copy(
            inputText = "",
            attachment = null,
            attachmentWarning = null,
            isLoading = true,
            error = null,
            streamingContent = "",
            streamingReasoning = ""
        )

        viewModelScope.launch {
            val result = chatRepository.sendMessage(
                conversationId = conversationId,
                userMessage = message,
                attachment = attachment,
                onStreamUpdate = { content, reasoning ->
                    _uiState.value = _uiState.value.copy(
                        streamingContent = content,
                        streamingReasoning = reasoning
                    )
                }
            )
            result.fold(
                onSuccess = {
                    loadConversation()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = ""
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        error = e.message ?: "An error occurred"
                    )
                }
            )
        }
    }

    fun summarizeMessage(messageId: Long, content: String) {
        if (_uiState.value.summarizingMessageId != null) return
        _uiState.value = _uiState.value.copy(summarizingMessageId = messageId)
        viewModelScope.launch {
            val result = chatRepository.summarizeMessage(messageId, content)
            result.fold(
                onSuccess = { summarizeResult ->
                    val reduced = summarizeResult.originalTokens - summarizeResult.summaryTokens
                    val newTotal = (_uiState.value.conversationTotalTokens - reduced).coerceAtLeast(0)
                    val newSession = (_uiState.value.sessionTokenCount - reduced).coerceAtLeast(0)
                    val toast = "要約完了！ ${summarizeResult.originalTokens} → ${summarizeResult.summaryTokens} トークン"
                    _uiState.value = _uiState.value.copy(
                        summarizingMessageId = null,
                        conversationTotalTokens = newTotal,
                        sessionTokenCount = newSession,
                        summarizeToast = toast
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        summarizingMessageId = null,
                        error = "要約に失敗しました: ${e.message}"
                    )
                }
            )
        }
    }

    fun toggleExcludeMessage(messageId: Long, currentlyExcluded: Boolean) {
        viewModelScope.launch {
            chatRepository.excludeMessage(messageId, !currentlyExcluded)
        }
    }

    fun clearSummarizeToast() {
        _uiState.value = _uiState.value.copy(summarizeToast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    class Factory(
        private val conversationId: Long,
        private val chatRepository: ChatRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(conversationId, chatRepository, settingsRepository) as T
        }
    }
}

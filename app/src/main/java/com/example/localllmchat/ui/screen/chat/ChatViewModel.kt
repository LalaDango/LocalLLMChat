package com.example.localllmchat.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Base64
import com.example.localllmchat.data.leap.LeapModelManager
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.util.ProcessedAttachment
import kotlinx.coroutines.flow.first
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
    val summarizeToast: String? = null,
    val leapVisionStatus: String? = null // "画像を読み取り中..." etc.
)

class ChatViewModel(
    private val conversationId: Long,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val leapModelManager: LeapModelManager
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
            // Check if LEAP Vision should process the image first
            var leapImageDescription: String? = null
            if (attachment is ProcessedAttachment.ImageAttachment) {
                val leapEnabled = settingsRepository.leapVisionEnabled.first()
                if (leapEnabled && leapModelManager.isReady()) {
                    _uiState.value = _uiState.value.copy(leapVisionStatus = "画像を読み取り中...")
                    val jpegBytes = Base64.decode(attachment.base64Data, Base64.NO_WRAP)
                    val prompt = if (message.isNotBlank()) message else "この画像の内容を詳しく説明してください。テキストがあれば書き起こしてください。"
                    val result = leapModelManager.describeImage(jpegBytes, prompt)
                    result.fold(
                        onSuccess = { description ->
                            leapImageDescription = description
                            _uiState.value = _uiState.value.copy(leapVisionStatus = null)
                        },
                        onFailure = { e ->
                            // LEAP failed, fall back to direct multimodal
                            _uiState.value = _uiState.value.copy(
                                leapVisionStatus = null,
                                error = "LEAP Vision失敗 (フォールバック): ${e.message}"
                            )
                        }
                    )
                }
            }

            val result = chatRepository.sendMessage(
                conversationId = conversationId,
                userMessage = message,
                attachment = attachment,
                leapImageDescription = leapImageDescription,
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
        private val settingsRepository: SettingsRepository,
        private val leapModelManager: LeapModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(conversationId, chatRepository, settingsRepository, leapModelManager) as T
        }
    }
}

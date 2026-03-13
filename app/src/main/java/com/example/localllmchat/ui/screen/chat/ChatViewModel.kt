package com.example.localllmchat.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.util.ProcessedAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AskUserDialogState(
    val question: String,
    val options: List<String>,
    val deferred: CompletableDeferred<String>
)

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
    val translatingMessageId: Long? = null,
    val toolExecutionStatus: String? = null,
    val askUserDialog: AskUserDialogState? = null,
    val availableTools: List<String> = emptyList(),
    val toolDescriptions: Map<String, String> = emptyMap(),
    val disabledTools: Set<String> = emptySet(),
    val modelSupportsTools: Boolean = false,
    val siblingInfoMap: Map<Long, ChatRepository.SiblingInfo> = emptyMap(),
    val editingMessageId: Long? = null,
    val editingText: String = ""
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
        loadToolState()
    }

    private fun loadConversation() {
        viewModelScope.launch {
            val conversation = chatRepository.getConversationById(conversationId)
            _uiState.value = _uiState.value.copy(conversation = conversation)
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            chatRepository.getActivePathFlow(conversationId).collect { result ->
                _uiState.value = _uiState.value.copy(
                    messages = result.messages,
                    siblingInfoMap = result.siblingInfoMap
                )
                updateSessionTokenCount(result.messages)
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

    private fun loadToolState() {
        val availableTools = chatRepository.getAvailableToolNames()
        val toolDescriptions = chatRepository.getToolDescriptions()
        _uiState.value = _uiState.value.copy(
            availableTools = availableTools,
            toolDescriptions = toolDescriptions
        )
        viewModelScope.launch {
            settingsRepository.disabledTools.collect { disabled ->
                _uiState.value = _uiState.value.copy(disabledTools = disabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.modelName.collect { modelName ->
                _uiState.value = _uiState.value.copy(
                    modelSupportsTools = chatRepository.supportsToolCalling(modelName)
                )
            }
        }
    }

    fun toggleTool(name: String) {
        viewModelScope.launch {
            val current = _uiState.value.disabledTools
            val updated = if (name in current) current - name else current + name
            settingsRepository.saveDisabledTools(updated)
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
                },
                onToolStatus = { status ->
                    _uiState.value = _uiState.value.copy(
                        toolExecutionStatus = status,
                        // Clear streaming content: Step 1 messages are now saved to DB
                        // and will appear via Flow; Step 3 will fill new streaming content
                        streamingContent = "",
                        streamingReasoning = ""
                    )
                },
                onAskUser = { question, options ->
                    val deferred = CompletableDeferred<String>()
                    _uiState.value = _uiState.value.copy(
                        askUserDialog = AskUserDialogState(question, options, deferred)
                    )
                    deferred
                }
            )
            result.fold(
                onSuccess = {
                    loadConversation()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        toolExecutionStatus = null
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        toolExecutionStatus = null,
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

    fun translateMessage(messageId: Long, content: String) {
        if (_uiState.value.translatingMessageId != null) return
        _uiState.value = _uiState.value.copy(translatingMessageId = messageId)
        viewModelScope.launch {
            val result = chatRepository.translateMessage(messageId, content)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(translatingMessageId = null)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        translatingMessageId = null,
                        error = "翻訳に失敗しました: ${e.message}"
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

    fun toggleExcludeToolGroup(messageIds: List<Long>, currentlyExcluded: Boolean) {
        viewModelScope.launch {
            messageIds.forEach { id ->
                chatRepository.excludeMessage(id, !currentlyExcluded)
            }
        }
    }

    fun clearSummarizeToast() {
        _uiState.value = _uiState.value.copy(summarizeToast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun answerAskUserQuestion(answer: String) {
        val dialog = _uiState.value.askUserDialog ?: return
        dialog.deferred.complete(answer)
        _uiState.value = _uiState.value.copy(askUserDialog = null)
    }

    fun cancelAskUserQuestion() {
        val dialog = _uiState.value.askUserDialog ?: return
        dialog.deferred.complete("User cancelled")
        _uiState.value = _uiState.value.copy(askUserDialog = null)
    }

    // ── Branch feature methods ──

    fun regenerateResponse() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            streamingContent = "",
            streamingReasoning = ""
        )
        viewModelScope.launch {
            val result = chatRepository.regenerateLastResponse(
                conversationId = conversationId,
                onStreamUpdate = { content, reasoning ->
                    _uiState.value = _uiState.value.copy(
                        streamingContent = content,
                        streamingReasoning = reasoning
                    )
                },
                onToolStatus = { status ->
                    _uiState.value = _uiState.value.copy(
                        toolExecutionStatus = status,
                        streamingContent = "",
                        streamingReasoning = ""
                    )
                },
                onAskUser = { question, options ->
                    val deferred = CompletableDeferred<String>()
                    _uiState.value = _uiState.value.copy(
                        askUserDialog = AskUserDialogState(question, options, deferred)
                    )
                    deferred
                }
            )
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        toolExecutionStatus = null
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        toolExecutionStatus = null,
                        error = e.message ?: "An error occurred"
                    )
                }
            )
        }
    }

    fun editMessage(messageId: Long) {
        val msg = _uiState.value.messages.find { it.id == messageId } ?: return
        _uiState.value = _uiState.value.copy(
            editingMessageId = messageId,
            editingText = msg.content
        )
    }

    fun updateEditText(text: String) {
        _uiState.value = _uiState.value.copy(editingText = text)
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(editingMessageId = null, editingText = "")
    }

    fun submitEdit() {
        val messageId = _uiState.value.editingMessageId ?: return
        val newContent = _uiState.value.editingText.trim()
        if (newContent.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            editingMessageId = null,
            editingText = "",
            isLoading = true,
            error = null,
            streamingContent = "",
            streamingReasoning = ""
        )

        viewModelScope.launch {
            val result = chatRepository.editAndResend(
                conversationId = conversationId,
                originalMessageId = messageId,
                newContent = newContent,
                onStreamUpdate = { content, reasoning ->
                    _uiState.value = _uiState.value.copy(
                        streamingContent = content,
                        streamingReasoning = reasoning
                    )
                },
                onToolStatus = { status ->
                    _uiState.value = _uiState.value.copy(
                        toolExecutionStatus = status,
                        streamingContent = "",
                        streamingReasoning = ""
                    )
                },
                onAskUser = { question, options ->
                    val deferred = CompletableDeferred<String>()
                    _uiState.value = _uiState.value.copy(
                        askUserDialog = AskUserDialogState(question, options, deferred)
                    )
                    deferred
                }
            )
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        toolExecutionStatus = null
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        streamingContent = "",
                        streamingReasoning = "",
                        toolExecutionStatus = null,
                        error = e.message ?: "An error occurred"
                    )
                }
            )
        }
    }

    fun switchBranch(targetMessageId: Long) {
        viewModelScope.launch {
            chatRepository.switchBranch(conversationId, targetMessageId)
        }
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

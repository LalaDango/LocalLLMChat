package com.example.localllmchat.ui.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.model.SummarizeConfig
import com.example.localllmchat.data.repository.ChatRepository
import com.example.localllmchat.data.repository.SettingsRepository
import com.example.localllmchat.util.ProcessedAttachment
import com.google.gson.Gson
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

data class CapacityWarning(
    val projected: Int,
    val capacity: Int
)

data class ChatUiState(
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionTokenCount: Int = 0,
    val conversationTotalTokens: Int = 0, // measured active_kv_tokens, or estimated cumulative tokens as fallback
    val contextWindowSize: Int = SettingsRepository.DEFAULT_CONTEXT_WINDOW_SIZE,
    val maxAttachmentTextKb: Int = SettingsRepository.DEFAULT_MAX_ATTACHMENT_TEXT_KB,
    val measuredKvCapacity: Int? = null, // max_kv_token_capacity from FLM (overrides contextWindowSize when present)
    val isFullPrefill: Boolean = false, // last turn was a full prefill (cache miss)
    val capacityWarning: CapacityWarning? = null,
    val textAttachment: ProcessedAttachment.TextAttachment? = null,
    val imageAttachments: List<ProcessedAttachment.ImageAttachment> = emptyList(),
    val attachmentWarning: String? = null,
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val showSummarizeDialog: Boolean = false,
    val summarizeTargetMessageId: Long? = null,
    val summarizeTargetContent: String? = null,
    val summarizePreview: ChatRepository.SummarizeResult? = null,
    val isSummarizePreviewLoading: Boolean = false,
    val summarizeInitialConfig: SummarizeConfig? = null,
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
        viewModelScope.launch {
            settingsRepository.maxAttachmentTextKb.collect { kb ->
                _uiState.value = _uiState.value.copy(maxAttachmentTextKb = kb)
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

        // Prefer measured KV values (FLM v0.9.41+ stream final chunk).
        // Exclusion flags are ignored here: activeKvTokens records the server-side KV
        // state at generation time, regardless of what is excluded now.
        val kvMessages = messages.filter { it.role == "assistant" && it.activeKvTokens != null }
        val latestKvMessage = kvMessages.lastOrNull()
        if (latestKvMessage != null) {
            val activeKv = latestKvMessage.activeKvTokens!!
            val prevKv = kvMessages.getOrNull(kvMessages.size - 2)?.activeKvTokens
            // Full prefill (cache miss) detection: on a hit prompt_tokens covers only the
            // newly added tokens, so prompt + completion + prevKv ≈ activeKv. On a miss the
            // whole history is re-prefilled (prompt_tokens ≈ activeKv - completion), leaving
            // a surplus of roughly prevKv. Small histories (< 100 tokens) are skipped: a full
            // prefill there is cheap and the margin is too noisy to judge.
            val prompt = latestKvMessage.promptTokens
            val completion = latestKvMessage.completionTokens ?: 0
            val isFullPrefill = prevKv != null && prevKv >= 100 && prompt != null &&
                latestKvMessage === messages.lastOrNull { it.role == "assistant" } &&
                (prompt + completion + prevKv - activeKv) >= prevKv * 0.5
            _uiState.value = _uiState.value.copy(
                sessionTokenCount = lastTurnTokens,
                conversationTotalTokens = activeKv,
                measuredKvCapacity = latestKvMessage.maxKvTokenCapacity,
                isFullPrefill = isFullPrefill
            )
            return
        }

        // Fallback (old data / servers without KV fields): estimate from cumulative totals
        var cumulativeTokens = activeMessages.filter { it.role == "assistant" }.sumOf { it.totalTokens ?: 0 }
        // Subtract token reductions from summarized messages
        activeMessages.filter { it.isSummarized && it.summarizeConfigJson != null }.forEach { msg ->
            try {
                val config = Gson().fromJson(msg.summarizeConfigJson, SummarizeConfig::class.java)
                val orig = config.originalTokens
                val summary = config.summaryTokens
                if (orig != null && summary != null) {
                    cumulativeTokens -= (orig - summary)
                }
            } catch (_: Exception) { }
        }
        _uiState.value = _uiState.value.copy(
            sessionTokenCount = lastTurnTokens,
            conversationTotalTokens = cumulativeTokens.coerceAtLeast(0),
            measuredKvCapacity = null,
            isFullPrefill = false
        )
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun addAttachment(attachment: ProcessedAttachment) {
        when (attachment) {
            is ProcessedAttachment.TextAttachment -> {
                val warning = if (attachment.wasTruncated)
                    "ファイルが${_uiState.value.maxAttachmentTextKb}KBを超えています。先頭部分のみ送信します。" else null
                _uiState.value = _uiState.value.copy(
                    textAttachment = attachment, attachmentWarning = warning
                )
            }
            is ProcessedAttachment.ImageAttachment -> {
                val current = _uiState.value.imageAttachments
                if (current.size >= 5) {
                    _uiState.value = _uiState.value.copy(
                        attachmentWarning = "画像は最大5枚までです"
                    )
                    return
                }
                _uiState.value = _uiState.value.copy(
                    imageAttachments = current + attachment,
                    attachmentWarning = null
                )
            }
        }
    }

    fun clearAllAttachments() {
        _uiState.value = _uiState.value.copy(
            textAttachment = null, imageAttachments = emptyList(), attachmentWarning = null
        )
    }

    fun removeTextAttachment() {
        _uiState.value = _uiState.value.copy(textAttachment = null)
    }

    fun removeImageAttachment(index: Int) {
        val current = _uiState.value.imageAttachments.toMutableList()
        if (index in current.indices) current.removeAt(index)
        _uiState.value = _uiState.value.copy(imageAttachments = current)
    }

    fun sendMessage() {
        val message = _uiState.value.inputText.trim()
        val textAttachment = _uiState.value.textAttachment
        val imageAttachments = _uiState.value.imageAttachments
        if (message.isEmpty() && textAttachment == null && imageAttachments.isEmpty()) return

        // 容量超過ガード: 実測 KV 容量があるときのみ、予測トークン量が上限に達しそうなら送信を止める
        // （容量超過の prefill 強行は FLM の checkpoint を全滅させ復旧不能になるため）
        val state = _uiState.value
        val capacity = state.measuredKvCapacity
        if (capacity != null) {
            val estimatedInput =
                ((message.length + (textAttachment?.content?.length ?: 0)) * 0.9).toInt() +
                    imageAttachments.sumOf { it.base64Data.length * 3 / 8 } // base64長×3/4=バイト数、その1/2
            val projected = state.conversationTotalTokens + estimatedInput + MAX_COMPLETION_TOKENS
            if (projected >= capacity) {
                // 入力・添付はクリアしない（要約・除外で履歴を減らした後に再送できるように残す）
                _uiState.value = state.copy(capacityWarning = CapacityWarning(projected, capacity))
                return
            }
        }

        _uiState.value = _uiState.value.copy(
            inputText = "",
            textAttachment = null,
            imageAttachments = emptyList(),
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
                textAttachment = textAttachment,
                imageAttachments = imageAttachments,
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

    fun openSummarizeDialog(messageId: Long, content: String) {
        _uiState.value = _uiState.value.copy(
            showSummarizeDialog = true,
            summarizeTargetMessageId = messageId,
            summarizeTargetContent = content,
            summarizePreview = null,
            isSummarizePreviewLoading = false,
            summarizeInitialConfig = null
        )
    }

    fun openResummarizeDialog(messageId: Long, content: String, configJson: String?) {
        val prevConfig = configJson?.let {
            try { Gson().fromJson(it, SummarizeConfig::class.java) } catch (_: Exception) { null }
        }
        _uiState.value = _uiState.value.copy(
            showSummarizeDialog = true,
            summarizeTargetMessageId = messageId,
            summarizeTargetContent = content,
            summarizePreview = null,
            isSummarizePreviewLoading = false,
            summarizeInitialConfig = prevConfig
        )
    }

    fun closeSummarizeDialog() {
        _uiState.value = _uiState.value.copy(
            showSummarizeDialog = false,
            summarizeTargetMessageId = null,
            summarizeTargetContent = null,
            summarizePreview = null,
            isSummarizePreviewLoading = false,
            summarizeInitialConfig = null
        )
    }

    fun generateSummarizePreview(config: SummarizeConfig) {
        val content = _uiState.value.summarizeTargetContent ?: return
        if (_uiState.value.isSummarizePreviewLoading) return

        _uiState.value = _uiState.value.copy(
            isSummarizePreviewLoading = true,
            summarizePreview = null
        )

        viewModelScope.launch {
            val result = chatRepository.generateSummary(content, config)
            result.fold(
                onSuccess = { preview ->
                    _uiState.value = _uiState.value.copy(
                        isSummarizePreviewLoading = false,
                        summarizePreview = preview
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isSummarizePreviewLoading = false,
                        error = "要約プレビューに失敗しました: ${e.message}"
                    )
                }
            )
        }
    }

    fun confirmSummarizePreview() {
        val messageId = _uiState.value.summarizeTargetMessageId ?: return
        val preview = _uiState.value.summarizePreview ?: return

        viewModelScope.launch {
            chatRepository.saveSummary(messageId, preview.summaryText, preview.config)

            val toast = "要約完了！ ${preview.originalTokens} → ${preview.summaryTokens} トークン（次の応答は履歴の再読み込みで時間がかかります）"
            _uiState.value = _uiState.value.copy(
                summarizeToast = toast
            )
            closeSummarizeDialog()
            // Token counts will be recalculated via updateSessionTokenCount when Room Flow emits
        }
    }

    fun translateMessage(messageId: Long, content: String) {
        if (_uiState.value.translatingMessageId != null) return
        _uiState.value = _uiState.value.copy(translatingMessageId = messageId)
        viewModelScope.launch {
            val result = chatRepository.translateMessage(messageId, content)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        translatingMessageId = null,
                        summarizeToast = "翻訳完了。次の応答は履歴の再読み込みで時間がかかることがあります"
                    )
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
            _uiState.value = _uiState.value.copy(summarizeToast = HISTORY_RELOAD_HINT)
        }
    }

    fun toggleExcludeToolGroup(messageIds: List<Long>, currentlyExcluded: Boolean) {
        viewModelScope.launch {
            messageIds.forEach { id ->
                chatRepository.excludeMessage(id, !currentlyExcluded)
            }
            _uiState.value = _uiState.value.copy(summarizeToast = HISTORY_RELOAD_HINT)
        }
    }

    fun clearSummarizeToast() {
        _uiState.value = _uiState.value.copy(summarizeToast = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun dismissCapacityWarning() {
        _uiState.value = _uiState.value.copy(capacityWarning = null)
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
            _uiState.value = _uiState.value.copy(summarizeToast = HISTORY_RELOAD_HINT)
        }
    }

    companion object {
        // ChatRequest.maxTokens (8192) と同値。応答生成分を projected に含めるための保守値
        private const val MAX_COMPLETION_TOKENS = 8192

        // 履歴を書き換える操作は FLM checkpoint 照合を外し、次ターンが全量 prefill になるため
        private const val HISTORY_RELOAD_HINT = "※次の応答は履歴の再読み込みで時間がかかることがあります"
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

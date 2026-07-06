package com.example.localllmchat.data.repository

import com.example.localllmchat.data.local.ConversationDao
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.PresetEntity
import com.example.localllmchat.data.local.MessageDao
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.local.MessageImageDao
import com.example.localllmchat.data.local.MessageImageEntity
import com.example.localllmchat.data.remote.AccumulatedToolCall
import com.example.localllmchat.data.remote.ApiChatMessage
import com.example.localllmchat.data.remote.ApiChatRequest
import com.example.localllmchat.data.remote.ApiClient
import com.example.localllmchat.data.remote.ChatApi
import com.example.localllmchat.data.remote.ChatResponse
import com.example.localllmchat.data.remote.ContentPart
import com.example.localllmchat.data.remote.ImageUrl
import com.example.localllmchat.data.remote.MessageContent
import com.example.localllmchat.data.tool.AskUserQuestionTool
import kotlinx.coroutines.CompletableDeferred
import com.example.localllmchat.data.remote.ToolCall
import com.example.localllmchat.data.remote.ToolCallFunction
import com.example.localllmchat.data.remote.ToolDefinition
import com.example.localllmchat.data.remote.UsageResponse
import com.example.localllmchat.data.tool.ToolRegistry
import com.example.localllmchat.util.ProcessedAttachment
import com.example.localllmchat.data.model.LengthPreset
import com.example.localllmchat.data.model.SummarizeConfig
import com.example.localllmchat.data.remote.ChatMessage
import com.example.localllmchat.data.remote.ChatRequest
import android.util.Log
import androidx.room.withTransaction
import com.example.localllmchat.data.local.AppDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val messageImageDao: MessageImageDao,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry
) {
    private val gson = Gson()

    companion object {
        // ツール実行ループの上限（1ユーザー発言あたり）。超過分の tool_calls はテキスト扱いで打ち切り
        private const val MAX_TOOL_ROUNDS = 3

        // 小型モデル（gemma4-it:e4b等）はツール結果を無視して質問を繰り返すことがあるため、
        // tools送信時のみ system prompt にガイダンスを追記する
        private val TOOL_GUIDANCE_PROMPT = """
            # ツール使用ルール
            - ツール呼び出し後、role が tool のメッセージで実行結果が返ってくる。次の応答は必ずその結果の内容を踏まえて書くこと。
            - ask_user_question の結果の answer にはユーザーの回答が入っている（"User selected: " は選択肢の選択、"User's custom answer: " は自由記述、"User cancelled" はキャンセル）。回答を受け取ったら同じ質問を本文で繰り返さず、その回答に対する応答（正誤判定・次の処理など）を返すこと。
            - get_datetime の結果の datetime/date/time が現在日時。日時に関する質問にはこの値を使って答えること。
        """.trimIndent()
    }

    fun getAllConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.getAllConversations()
    }

    suspend fun getConversationById(id: Long): ConversationEntity? {
        return conversationDao.getConversationById(id)
    }

    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    suspend fun createConversation(title: String): Long {
        val conversation = ConversationEntity(title = title)
        return conversationDao.insert(conversation)
    }

    suspend fun createConversationWithPreset(title: String, preset: PresetEntity): Long {
        val conversation = ConversationEntity(
            title = title,
            presetId = preset.id,
            presetEmoji = preset.emoji,
            presetName = preset.name,
            systemPrompt = preset.systemPrompt
        )
        return conversationDao.insert(conversation)
    }

    private suspend fun resolveSystemPrompt(conversationId: Long): String {
        val conversation = conversationDao.getConversationById(conversationId)
        return conversation?.systemPrompt
            ?: settingsRepository.systemPrompt.first()
    }

    suspend fun updateConversationTitle(id: Long, title: String) {
        val conversation = conversationDao.getConversationById(id)
        if (conversation != null) {
            conversationDao.update(
                conversation.copy(
                    title = title,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteConversation(id: Long) {
        conversationDao.deleteById(id)
    }

    suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        parentMessageId: Long? = null,
        siblingIndex: Int = 0,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        totalTokens: Int? = null,
        decodingSpeedTps: Double? = null,
        prefillSpeedTps: Double? = null,
        activeKvTokens: Int? = null,
        maxKvTokenCapacity: Int? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            parentMessageId = parentMessageId,
            siblingIndex = siblingIndex,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            decodingSpeedTps = decodingSpeedTps,
            prefillSpeedTps = prefillSpeedTps,
            activeKvTokens = activeKvTokens,
            maxKvTokenCapacity = maxKvTokenCapacity,
            toolCallsJson = toolCallsJson,
            toolCallId = toolCallId
        )
        val messageId = database.withTransaction {
            val newId = messageDao.insert(message)
            if (parentMessageId != null) {
                messageDao.updateActiveChildId(parentMessageId, newId)
            } else {
                conversationDao.updateActiveRootMessageId(conversationId, newId)
            }
            newId
        }

        val conversation = conversationDao.getConversationById(conversationId)
        if (conversation != null) {
            conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
        }

        return messageId
    }

    suspend fun sendMessage(
        conversationId: Long,
        userMessage: String,
        textAttachment: ProcessedAttachment.TextAttachment? = null,
        imageAttachments: List<ProcessedAttachment.ImageAttachment> = emptyList(),
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null,
        onToolStatus: ((status: String?) -> Unit)? = null,
        onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null
    ): Result<String> {
        return try {
            // Build DB content based on attachment type
            val dbContent = buildString {
                if (textAttachment != null) {
                    append("<documents>\n<document filename=\"${textAttachment.fileName}\" type=\"${textAttachment.mimeType}\">\n${textAttachment.content}\n</document>\n</documents>")
                    if (userMessage.isNotBlank()) append("\n\n")
                }
                if (userMessage.isNotBlank()) append(userMessage)
                if (imageAttachments.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    val names = imageAttachments.joinToString(", ") { it.fileName }
                    append("[画像添付: $names]")
                }
            }

            // Determine parent: last message in the current active path
            val activePath = getActivePathMessages(conversationId)
            val parentId = activePath.lastOrNull()?.id

            val userMsgId = addMessage(conversationId, "user", dbContent, parentMessageId = parentId)

            // 画像を DB に永続化し、以降のターンでも buildApiMessages が base64 を再送できるようにする
            // （FLM checkpoint 照合を画像込みトークン列で一致させるため）
            if (imageAttachments.isNotEmpty()) {
                messageImageDao.insertAll(
                    imageAttachments.mapIndexed { index, img ->
                        MessageImageEntity(
                            messageId = userMsgId,
                            fileName = img.fileName,
                            mimeType = img.mimeType,
                            base64Data = img.base64Data,
                            sortOrder = index
                        )
                    }
                )
            }

            generateResponse(
                conversationId = conversationId,
                parentMessageId = userMsgId,
                onStreamUpdate = onStreamUpdate,
                onToolStatus = onToolStatus,
                onAskUser = onAskUser
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Shared method: stream API response, handle tool calls, save result messages
    private suspend fun generateResponse(
        conversationId: Long,
        parentMessageId: Long,
        siblingIndex: Int = 0,
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null,
        onToolStatus: ((status: String?) -> Unit)? = null,
        onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null
    ): Result<String> {
        val baseUrl = settingsRepository.baseUrl.first()
        val modelName = settingsRepository.modelName.first()
        val systemPrompt = resolveSystemPrompt(conversationId)
        val disabledTools = settingsRepository.disabledTools.first()
        val toolDefinitions = toolRegistry.getDefinitions(modelName, disabledTools)
        val temperature = settingsRepository.temperature.first()
        val maxCompletionTokens = settingsRepository.maxCompletionTokens.first()

        val api = ApiClient.getChatApi(baseUrl)

        val toolsEnabled = toolDefinitions != null
        val effectiveSystemPrompt = when {
            !toolsEnabled -> systemPrompt
            systemPrompt.isBlank() -> TOOL_GUIDANCE_PROMPT
            else -> systemPrompt + "\n\n" + TOOL_GUIDANCE_PROMPT
        }
        val step1Messages = buildApiMessages(conversationId, effectiveSystemPrompt, toolsEnabled, upToMessageId = parentMessageId)
        val step1Request = ApiChatRequest(
            model = modelName,
            messages = step1Messages,
            tools = toolDefinitions,
            stream = true,
            temperature = temperature,
            maxTokens = maxCompletionTokens
        )

        var result = streamApiCall(api, step1Request, onStreamUpdate)

        // ツール実行ループ: 応答に tool_calls が含まれる限り「保存→実行→再送信」を繰り返す。
        // MAX_TOOL_ROUNDS 超過分の tool_calls は実行せずテキスト扱いで打ち切り（暴走防止）
        var rounds = 0
        var currentParentId = parentMessageId
        var currentSiblingIndex = siblingIndex

        val askTool = toolRegistry.getTool<AskUserQuestionTool>("ask_user_question")
        try {
            askTool?.onAskUser = onAskUser

            while (result.toolCallMap.isNotEmpty() && rounds < MAX_TOOL_ROUNDS) {
                rounds++
                val completedToolCalls = result.toolCallMap.values.map { acc ->
                    val toolCallId = if (acc.id.isNullOrBlank() || acc.id!!.contains("("))
                        "call_${java.util.UUID.randomUUID()}"
                    else acc.id!!
                    ToolCall(
                        id = toolCallId,
                        type = "function",
                        function = ToolCallFunction(name = acc.name, arguments = acc.arguments.toString())
                    )
                }
                val toolCallsJson = gson.toJson(completedToolCalls)

                val assistantContent = buildRawMessage(result)
                var lastMsgId = addMessage(
                    conversationId = conversationId,
                    role = "assistant",
                    content = assistantContent.ifEmpty { "" },
                    parentMessageId = currentParentId,
                    siblingIndex = currentSiblingIndex,
                    toolCallsJson = toolCallsJson,
                    promptTokens = result.usage?.promptTokens,
                    completionTokens = result.usage?.completionTokens,
                    totalTokens = result.usage?.totalTokens,
                    decodingSpeedTps = result.usage?.decodingSpeedTps,
                    prefillSpeedTps = result.usage?.prefillSpeedTps,
                    activeKvTokens = result.usage?.activeKvTokens,
                    maxKvTokenCapacity = result.usage?.maxKvTokenCapacity
                )

                onToolStatus?.invoke("ツール実行中...")

                for (tc in completedToolCalls) {
                    val toolName = tc.function?.name ?: continue
                    val toolArgs = tc.function.arguments ?: "{}"
                    val toolResult = toolRegistry.execute(toolName, toolArgs)
                    lastMsgId = addMessage(
                        conversationId = conversationId,
                        role = "tool",
                        content = toolResult,
                        parentMessageId = lastMsgId,
                        toolCallId = tc.id
                    )
                }

                onToolStatus?.invoke(null)

                // 2周目以降の assistant は tool メッセージの子（ブランチの siblingIndex は初回のみ有効）
                currentParentId = lastMsgId
                currentSiblingIndex = 0

                val nextMessages = buildApiMessages(conversationId, effectiveSystemPrompt)
                val nextRequest = ApiChatRequest(
                    model = modelName,
                    messages = nextMessages,
                    tools = toolDefinitions,
                    stream = true,
                    temperature = temperature,
                    maxTokens = maxCompletionTokens
                )
                result = streamApiCall(api, nextRequest, onStreamUpdate)
            }
        } finally {
            askTool?.onAskUser = null
        }

        val rawMessage = buildRawMessage(result)
        var assistantMessage = cleanupIncompleteThinkTags(rawMessage)

        if (result.toolCallMap.isNotEmpty()) {
            Log.w("ChatRepository", "Tool round limit ($MAX_TOOL_ROUNDS) reached; ${result.toolCallMap.size} tool call(s) left unexecuted")
            // tool_calls のみで本文が空だと空バブルになるため、打ち切り理由を表示する
            if (assistantMessage.isBlank()) {
                assistantMessage = "（ツール実行回数が上限（${MAX_TOOL_ROUNDS}回）に達したため、応答を打ち切りました）"
            }
        }

        addMessage(
            conversationId = conversationId,
            role = "assistant",
            content = assistantMessage,
            parentMessageId = currentParentId,
            siblingIndex = currentSiblingIndex,
            promptTokens = result.usage?.promptTokens,
            completionTokens = result.usage?.completionTokens,
            totalTokens = result.usage?.totalTokens,
            decodingSpeedTps = result.usage?.decodingSpeedTps,
            prefillSpeedTps = result.usage?.prefillSpeedTps,
            activeKvTokens = result.usage?.activeKvTokens,
            maxKvTokenCapacity = result.usage?.maxKvTokenCapacity
        )

        updateConversationTitleIfNeeded(conversationId)
        return Result.success(assistantMessage)
    }

    data class SiblingInfo(
        val currentIndex: Int,
        val totalSiblings: Int,
        val siblingIds: List<Long>
    )

    data class ActivePathResult(
        val messages: List<MessageEntity>,
        val siblingInfoMap: Map<Long, SiblingInfo>
    )

    private data class StreamResult(
        val contentBuilder: StringBuilder,
        val reasoningBuilder: StringBuilder,
        val toolCallMap: Map<Int, AccumulatedToolCall>,
        val finishReason: String?,
        val usage: UsageResponse?
    )

    private suspend fun streamApiCall(
        api: ChatApi,
        request: ApiChatRequest,
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)?
    ): StreamResult {
        val responseBody = api.chatStreamMultimodal(request = request)
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val toolCallMap = mutableMapOf<Int, AccumulatedToolCall>()
        var usage: UsageResponse? = null
        var finishReason: String? = null

        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
            var line: String?
            var lastEmitTime = 0L
            val throttleMs = 32L // ~30fps
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim() ?: continue
                if (!trimmed.startsWith("data: ")) continue
                val jsonStr = trimmed.removePrefix("data: ")
                if (jsonStr == "[DONE]") break
                try {
                    val chunk = gson.fromJson(jsonStr, ChatResponse::class.java)
                    val delta = chunk.choices.firstOrNull()?.delta
                    val chunkFinishReason = chunk.choices.firstOrNull()?.finishReason
                    if (chunkFinishReason != null) {
                        finishReason = chunkFinishReason
                    }
                    if (delta?.reasoningContent != null) {
                        reasoningBuilder.append(delta.reasoningContent)
                    }
                    if (delta?.content != null) {
                        contentBuilder.append(delta.content)
                    }
                    // Accumulate tool_calls from streaming deltas
                    if (delta?.toolCalls != null) {
                        for (tc in delta.toolCalls) {
                            val idx = tc.index ?: 0
                            val acc = toolCallMap.getOrPut(idx) { AccumulatedToolCall() }
                            if (tc.id != null) acc.id = tc.id
                            if (tc.function?.name != null) acc.name = tc.function.name
                            if (tc.function?.arguments != null) acc.arguments.append(tc.function.arguments)
                        }
                    }
                    if (chunk.usage != null) {
                        usage = chunk.usage
                    }
                    // Emit streaming update with throttle
                    if (onStreamUpdate != null && (delta?.content != null || delta?.reasoningContent != null)) {
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= throttleMs) {
                            lastEmitTime = now
                            onStreamUpdate(
                                contentBuilder.toString(),
                                reasoningBuilder.toString()
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ChatRepository", "SSE chunk parse error: ${e.message}, json: $jsonStr")
                }
            }
            reader.close()
            // Final emit to ensure last chunk is not lost due to throttle
            onStreamUpdate?.invoke(
                contentBuilder.toString(),
                reasoningBuilder.toString()
            )
        }

        Log.d("ChatRepository", "Stream complete: finishReason=$finishReason, toolCalls=${toolCallMap.size}, content=${contentBuilder.length}chars, reasoning=${reasoningBuilder.length}chars")
        if (toolCallMap.isNotEmpty()) {
            toolCallMap.forEach { (idx, acc) ->
                Log.d("ChatRepository", "  toolCall[$idx]: id=${acc.id}, name=${acc.name}, args=${acc.arguments}")
            }
        }

        return StreamResult(contentBuilder, reasoningBuilder, toolCallMap, finishReason, usage)
    }

    // ── Branch feature: active path resolution ──

    fun getActivePathFlow(conversationId: Long): Flow<ActivePathResult> {
        return messageDao.getMessagesForConversation(conversationId).map { allMessages ->
            if (allMessages.isEmpty()) return@map ActivePathResult(emptyList(), emptyMap())

            val conversation = conversationDao.getConversationById(conversationId)

            // Lazy migration for pre-branch conversations
            if (conversation?.activeRootMessageId == null && allMessages.isNotEmpty()) {
                val needsMigration = allMessages.none { it.parentMessageId != null || it.activeChildId != null }
                if (needsMigration) {
                    migrateConversationIfNeeded(conversationId, allMessages)
                    val freshMessages = messageDao.getMessagesForConversationSync(conversationId)
                    val freshConversation = conversationDao.getConversationById(conversationId)
                    return@map computeActivePathInternal(freshConversation, freshMessages)
                }
            }

            computeActivePathInternal(conversation, allMessages)
        }
    }

    suspend fun getActivePathMessages(conversationId: Long): List<MessageEntity> {
        val allMessages = messageDao.getMessagesForConversationSync(conversationId)
        if (allMessages.isEmpty()) return emptyList()

        val conversation = conversationDao.getConversationById(conversationId)

        if (conversation?.activeRootMessageId == null && allMessages.isNotEmpty()) {
            val needsMigration = allMessages.none { it.parentMessageId != null || it.activeChildId != null }
            if (needsMigration) {
                migrateConversationIfNeeded(conversationId, allMessages)
                val freshMessages = messageDao.getMessagesForConversationSync(conversationId)
                val freshConversation = conversationDao.getConversationById(conversationId)
                return computeActivePathInternal(freshConversation, freshMessages).messages
            }
        }

        return computeActivePathInternal(conversation, allMessages).messages
    }

    private fun computeActivePathInternal(
        conversation: ConversationEntity?,
        allMessages: List<MessageEntity>
    ): ActivePathResult {
        if (allMessages.isEmpty()) return ActivePathResult(emptyList(), emptyMap())

        val msgMap = allMessages.associateBy { it.id }

        // Find root message
        val rootMsg = conversation?.activeRootMessageId?.let { msgMap[it] }
            ?: allMessages.filter { it.parentMessageId == null }.minByOrNull { it.createdAt }
            ?: return ActivePathResult(emptyList(), emptyMap())

        // Walk the activeChildId chain
        val path = mutableListOf<MessageEntity>()
        var current: MessageEntity? = rootMsg
        val visited = mutableSetOf<Long>() // guard against cycles
        while (current != null) {
            if (!visited.add(current.id)) break
            path.add(current)
            val childId = current.activeChildId ?: break
            current = msgMap[childId]
        }

        // Compute sibling info for each message in the path
        val childrenByParent = allMessages.filter { it.parentMessageId != null }.groupBy { it.parentMessageId }
        val rootMessages = allMessages.filter { it.parentMessageId == null }.sortedBy { it.siblingIndex }

        val siblingInfoMap = mutableMapOf<Long, SiblingInfo>()
        for (msg in path) {
            val siblings = if (msg.parentMessageId == null) {
                rootMessages
            } else {
                childrenByParent[msg.parentMessageId]?.sortedBy { it.siblingIndex } ?: listOf(msg)
            }

            if (siblings.size > 1) {
                val currentIdx = siblings.indexOfFirst { it.id == msg.id }
                siblingInfoMap[msg.id] = SiblingInfo(
                    currentIndex = if (currentIdx >= 0) currentIdx else 0,
                    totalSiblings = siblings.size,
                    siblingIds = siblings.map { it.id }
                )
            }
        }

        // Propagate siblingInfo from tool_calls ancestor to final response
        // So the BranchNavigator appears on the response MessageBubble, not ToolCallBubble
        for (i in path.indices) {
            val msg = path[i]
            if (msg.role == "assistant" && msg.toolCallsJson == null && msg.id !in siblingInfoMap) {
                var j = i - 1
                while (j >= 0 && path[j].role == "tool") j--
                if (j >= 0 && path[j].role == "assistant" && path[j].toolCallsJson != null) {
                    val ancestorInfo = siblingInfoMap[path[j].id]
                    if (ancestorInfo != null) {
                        siblingInfoMap[msg.id] = ancestorInfo
                    }
                }
            }
        }

        return ActivePathResult(path, siblingInfoMap)
    }

    private suspend fun migrateConversationIfNeeded(conversationId: Long, allMessages: List<MessageEntity>) {
        database.withTransaction {
            val sorted = allMessages.sortedBy { it.createdAt }
            for (i in sorted.indices) {
                val parentId = if (i > 0) sorted[i - 1].id else null
                val activeChild = if (i < sorted.lastIndex) sorted[i + 1].id else null
                messageDao.updateParentAndIndex(sorted[i].id, parentId, 0)
                messageDao.updateActiveChildId(sorted[i].id, activeChild)
            }
            if (sorted.isNotEmpty()) {
                conversationDao.updateActiveRootMessageId(conversationId, sorted.first().id)
            }
        }
    }

    suspend fun regenerateLastResponse(
        conversationId: Long,
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null,
        onToolStatus: ((status: String?) -> Unit)? = null,
        onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null
    ): Result<String> {
        return try {
            val activePath = getActivePathMessages(conversationId)
            // Find the last assistant message and its parent (user message)
            val lastAssistant = activePath.lastOrNull { it.role == "assistant" }
                ?: return Result.failure(Exception("No assistant message to regenerate"))

            // Walk up the parent chain to find the user message
            // (skip tool chain: assistant(tool_calls) → tool → ... → assistant(final))
            var parentId = lastAssistant.parentMessageId
                ?: return Result.failure(Exception("Assistant message has no parent"))
            var parentMsg = messageDao.getMessageById(parentId)
            while (parentMsg != null && parentMsg.role != "user") {
                parentId = parentMsg.parentMessageId ?: break
                parentMsg = messageDao.getMessageById(parentId)
            }
            // Fallback if we couldn't find a user message (shouldn't happen normally)
            if (parentMsg?.role != "user") {
                parentId = lastAssistant.parentMessageId!!
            }

            // Calculate siblingIndex for the new assistant response
            val siblingIndex = messageDao.getSiblingCount(parentId)

            generateResponse(
                conversationId = conversationId,
                parentMessageId = parentId,
                siblingIndex = siblingIndex,
                onStreamUpdate = onStreamUpdate,
                onToolStatus = onToolStatus,
                onAskUser = onAskUser
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editAndResend(
        conversationId: Long,
        originalMessageId: Long,
        newContent: String,
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null,
        onToolStatus: ((status: String?) -> Unit)? = null,
        onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null
    ): Result<String> {
        return try {
            val originalMsg = messageDao.getMessageById(originalMessageId)
                ?: return Result.failure(Exception("Original message not found"))

            // New user message is a sibling of the original
            val grandparentId = originalMsg.parentMessageId
            val siblingIndex = if (grandparentId != null) {
                messageDao.getSiblingCount(grandparentId)
            } else {
                messageDao.getRootSiblingCount(originalMsg.conversationId)
            }

            val userMsgId = addMessage(
                conversationId = conversationId,
                role = "user",
                content = newContent,
                parentMessageId = grandparentId,
                siblingIndex = siblingIndex
            )

            // If editing a root message, activeRootMessageId is already updated by addMessage()

            generateResponse(
                conversationId = conversationId,
                parentMessageId = userMsgId,
                onStreamUpdate = onStreamUpdate,
                onToolStatus = onToolStatus,
                onAskUser = onAskUser
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun switchBranch(conversationId: Long, targetMessageId: Long) {
        val targetMsg = messageDao.getMessageById(targetMessageId) ?: return
        if (targetMsg.parentMessageId == null) {
            // Root switch
            conversationDao.updateActiveRootMessageId(conversationId, targetMessageId)
            // Touch messages table to trigger Flow re-emission
            messageDao.updateActiveChildId(targetMessageId, targetMsg.activeChildId)
        } else {
            messageDao.updateActiveChildId(targetMsg.parentMessageId, targetMessageId)
        }
    }

    // U+3000（全角スペース）は FLM checkpoint 照合のキャッシュミス主因の1つ。
    // 送信時のみ正規化する（DB・表示は変更しない）。一貫適用する限り履歴整合は崩れない
    private fun String.normalizeForApi(): String = replace('　', ' ')

    private suspend fun buildApiMessages(
        conversationId: Long,
        systemPrompt: String,
        toolsEnabled: Boolean = true,
        upToMessageId: Long? = null
    ): List<ApiChatMessage> {
        var activePathMessages = getActivePathMessages(conversationId)
        if (upToMessageId != null) {
            val idx = activePathMessages.indexOfFirst { it.id == upToMessageId }
            if (idx >= 0) {
                activePathMessages = activePathMessages.subList(0, idx + 1)
            }
        }
        val messages = activePathMessages.filter { msg ->
            !msg.isExcluded &&
            (toolsEnabled || (msg.role != "tool" && msg.toolCallsJson == null))
        }

        // 履歴中の全 user メッセージの画像を DB から復元し、毎ターン base64 を再送する。
        // 初回送信と再送でトークン列をバイト一致させ、FLM checkpoint 照合を保つため
        val userMessageIds = messages.filter { it.role == "user" }.map { it.id }
        val imagesByMessageId = if (userMessageIds.isNotEmpty()) {
            messageImageDao.getForMessages(userMessageIds).groupBy { it.messageId }
        } else {
            emptyMap()
        }

        val apiMessages = messages.map { msg ->
            when {
                // Tool result message
                msg.role == "tool" -> {
                    ApiChatMessage(
                        role = "tool",
                        content = MessageContent.Text(msg.content.normalizeForApi()),
                        toolCallId = msg.toolCallId
                    )
                }
                // Assistant message with tool_calls
                msg.role == "assistant" && msg.toolCallsJson != null -> {
                    val toolCalls: List<ToolCall> = gson.fromJson(
                        msg.toolCallsJson,
                        object : TypeToken<List<ToolCall>>() {}.type
                    )
                    ApiChatMessage(
                        role = "assistant",
                        content = if (msg.content.isNotEmpty()) MessageContent.Text(msg.content.normalizeForApi()) else null,
                        toolCalls = toolCalls
                    )
                }
                // Normal assistant message
                msg.role == "assistant" -> {
                    val sourceText = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                    // <think> 除去 + trim は生成実物とのズレを生むため、thinking を出すモデル（qwen系）では
                    // ターン毎にキャッシュミス1回分の宿命。e4b（thinking なし運用）では実害なし
                    val content = sourceText
                        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                        .replace(Regex("^[\\s\\S]*?</think>"), "")
                        .trim()
                    ApiChatMessage(role = msg.role, content = MessageContent.Text(content.normalizeForApi()))
                }
                // User message: 画像は DB から復元して毎ターン再送（最後の user に限らず全 user 共通）
                else -> {
                    val sourceText = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                    val images = imagesByMessageId[msg.id].orEmpty()
                    if (images.isNotEmpty()) {
                        val parts = mutableListOf<ContentPart>()
                        // 画像のみ送信時はプレースホルダ単体で先頭に \n\n が付かないため optional にする
                        val cleanedText = sourceText.replace(Regex("(\\n\\n)?\\[画像添付: [^\\]]+\\]$"), "")
                        if (cleanedText.isNotBlank()) {
                            parts.add(ContentPart.TextPart(cleanedText.normalizeForApi()))
                        }
                        images.sortedBy { it.sortOrder }.forEach { img ->
                            parts.add(ContentPart.ImageUrlPart(
                                ImageUrl("data:${img.mimeType};base64,${img.base64Data}")
                            ))
                        }
                        ApiChatMessage(role = msg.role, content = MessageContent.Parts(parts))
                    } else {
                        ApiChatMessage(role = msg.role, content = MessageContent.Text(sourceText.normalizeForApi()))
                    }
                }
            }
        }

        return if (systemPrompt.isNotBlank()) {
            listOf(ApiChatMessage(role = "system", content = MessageContent.Text(systemPrompt.normalizeForApi()))) + apiMessages
        } else {
            apiMessages
        }
    }

    private fun buildRawMessage(result: StreamResult): String {
        return buildString {
            if (result.reasoningBuilder.isNotEmpty()) {
                append("<think>")
                append(result.reasoningBuilder)
                append("</think>")
            }
            if (result.contentBuilder.isNotEmpty()) {
                append(result.contentBuilder)
            } else if (result.reasoningBuilder.isEmpty() && result.toolCallMap.isEmpty()) {
                append("No response received")
            }
        }
    }

    private suspend fun updateConversationTitleIfNeeded(conversationId: Long) {
        val allMessages = messageDao.getMessagesForConversationSync(conversationId)
        val userMessages = allMessages.filter { it.role == "user" }
        val firstUserMessage = userMessages.firstOrNull()?.content
        if (firstUserMessage != null && userMessages.size <= 1) {
            val title = firstUserMessage.take(30) + if (firstUserMessage.length > 30) "..." else ""
            updateConversationTitle(conversationId, title)
        }
    }

    suspend fun excludeMessage(messageId: Long, isExcluded: Boolean) {
        messageDao.updateExcluded(messageId, isExcluded)
    }

    data class SummarizeResult(
        val summaryText: String,
        val originalTokens: Int,
        val summaryTokens: Int,
        val config: SummarizeConfig
    )

    suspend fun generateSummary(content: String, config: SummarizeConfig): Result<SummarizeResult> {
        return try {
            val baseUrl = settingsRepository.baseUrl.first()
            val modelName = settingsRepository.modelName.first()
            val api = ApiClient.getChatApi(baseUrl)

            val systemPrompt = buildSummarizePrompt(config)
            val request = ChatRequest(
                model = modelName,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = content)
                ),
                stream = false,
                maxTokens = config.lengthPreset.maxTokens
            )

            val response = withTimeout(180_000) {
                withContext(Dispatchers.IO) {
                    api.chat(request = request)
                }
            }

            val rawText = response.choices.firstOrNull()?.message?.content ?: ""
            val cleanedText = rawText
                .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                .replace(Regex("^[\\s\\S]*?</think>"), "")
                .trim()

            if (cleanedText.isBlank()) {
                return Result.failure(Exception("要約結果が空です"))
            }

            val usage = response.usage
            val originalTokens = ((usage?.promptTokens ?: 0) - 50).coerceAtLeast(0)
            val summaryTokens = usage?.completionTokens ?: 0

            val resultConfig = config.copy(
                originalTokens = originalTokens,
                summaryTokens = summaryTokens
            )
            Result.success(SummarizeResult(cleanedText, originalTokens, summaryTokens, resultConfig))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveSummary(messageId: Long, summaryText: String, config: SummarizeConfig) {
        val configJson = Gson().toJson(config)
        messageDao.updateSummaryWithConfig(messageId, summaryText, configJson)
    }

    private fun buildSummarizePrompt(config: SummarizeConfig): String {
        return buildString {
            append("以下のテキストを日本語で要約してください。")
            append("出力は必ず${config.lengthPreset.minTokens}トークン以上、")
            append("${config.lengthPreset.targetRange}トークン程度の長さを目安にしてください。")
            append("短すぎる要約は禁止です。")
            append("重要な情報、具体的な数値、固有名詞、制約条件をできるだけ保持してください。")
            append("情報を省略しすぎず、内容が薄くならないようにしてください。")
            if (config.lengthPreset == LengthPreset.DETAILED) {
                append("概要・主要な特徴・制約・利用方法の4要素を必ず含めてください。")
            }
            if (config.priorityTopics.isNotBlank()) {
                append("特に「${config.priorityTopics}」に関する情報を優先的に残してください。")
            }
            if (config.excludeTopics.isNotBlank()) {
                append("「${config.excludeTopics}」に関する内容は省略して構いません。")
            }
            append("余計な補足やアドバイスは含めず、要約文のみ返してください。")
        }
    }

    suspend fun translateMessage(messageId: Long, content: String): Result<String> {
        return try {
            val baseUrl = settingsRepository.baseUrl.first()
            val modelName = settingsRepository.modelName.first()
            val api = ApiClient.getChatApi(baseUrl)

            // Strip <think>...</think> tags from content before translating
            val cleanedContent = content
                .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                .replace(Regex("^[\\s\\S]*?</think>"), "")
                .trim()

            val translationPrompt = buildString {
                append("You are a professional English (en) to Japanese (ja) translator. ")
                append("Your goal is to accurately convey the meaning and nuances of the original English text ")
                append("while adhering to Japanese grammar, vocabulary, and cultural sensitivities.\n")
                append("Produce only the Japanese translation, without any additional explanations or commentary. ")
                append("Please translate the following English text into Japanese:\n")
                append("\n")
                append("\n")
                append(cleanedContent)
            }

            val request = ChatRequest(
                model = modelName,
                messages = listOf(
                    ChatMessage(role = "user", content = translationPrompt)
                ),
                stream = false,
                temperature = 0.1,
                maxTokens = 8192
            )

            val response = withTimeout(180_000) {
                withContext(Dispatchers.IO) {
                    api.chat(request = request)
                }
            }

            // Chat models with thinking (e.g. qwen) may prepend <think> to the translation
            val translatedText = (response.choices.firstOrNull()?.message?.content ?: "")
                .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                .replace(Regex("^[\\s\\S]*?</think>"), "")
                .trim()

            if (translatedText.isBlank()) {
                return Result.failure(Exception("翻訳結果が空です"))
            }

            messageDao.updateTranslation(messageId, translatedText)
            Result.success(translatedText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanupIncompleteThinkTags(text: String): String {
        var result = text
        // If </think> exists but no <think>, prepend <think> for proper parsing
        if (result.contains("</think>") && !result.contains("<think>")) {
            result = "<think>$result"
        }
        // Remove trailing <think> without closing </think> tag
        return result
            .replace(Regex("<think>(?![\\s\\S]*</think>)[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun getAvailableToolNames(): List<String> = toolRegistry.getAvailableToolNames()

    fun getToolDescriptions(): Map<String, String> = toolRegistry.getToolDescriptions()

    fun supportsToolCalling(modelName: String): Boolean = toolRegistry.supportsToolCalling(modelName)
}

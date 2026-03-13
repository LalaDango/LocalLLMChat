package com.example.localllmchat.data.repository

import com.example.localllmchat.data.local.ConversationDao
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.MessageDao
import com.example.localllmchat.data.local.MessageEntity
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
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry
) {
    private val gson = Gson()

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
        attachment: ProcessedAttachment? = null,
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null,
        onToolStatus: ((status: String?) -> Unit)? = null,
        onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null
    ): Result<String> {
        return try {
            // Build DB content based on attachment type
            val dbContent = when (attachment) {
                is ProcessedAttachment.TextAttachment -> {
                    val doc = "<documents>\n<document filename=\"${attachment.fileName}\" type=\"${attachment.mimeType}\">\n${attachment.content}\n</document>\n</documents>"
                    if (userMessage.isNotBlank()) "$doc\n\n$userMessage" else doc
                }
                is ProcessedAttachment.ImageAttachment -> {
                    "$userMessage\n\n[画像添付: ${attachment.fileName}]"
                }
                null -> userMessage
            }

            // Determine parent: last message in the current active path
            val activePath = getActivePathMessages(conversationId)
            val parentId = activePath.lastOrNull()?.id

            val userMsgId = addMessage(conversationId, "user", dbContent, parentMessageId = parentId)

            generateResponse(
                conversationId = conversationId,
                parentMessageId = userMsgId,
                attachment = attachment,
                userMessage = userMessage,
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
        attachment: ProcessedAttachment? = null,
        userMessage: String = "",
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null,
        onToolStatus: ((status: String?) -> Unit)? = null,
        onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null
    ): Result<String> {
        val baseUrl = settingsRepository.baseUrl.first()
        val modelName = settingsRepository.modelName.first()
        val systemPrompt = settingsRepository.systemPrompt.first()
        val disabledTools = settingsRepository.disabledTools.first()
        val toolDefinitions = toolRegistry.getDefinitions(modelName, disabledTools)

        val api = ApiClient.getChatApi(baseUrl)

        val toolsEnabled = toolDefinitions != null
        val step1Messages = buildApiMessages(conversationId, attachment, userMessage, systemPrompt, toolsEnabled, upToMessageId = parentMessageId)
        val step1Request = ApiChatRequest(
            model = modelName,
            messages = step1Messages,
            tools = toolDefinitions,
            stream = true
        )

        val step1Result = streamApiCall(api, step1Request, onStreamUpdate)

        if (step1Result.toolCallMap.isNotEmpty()) {
            val completedToolCalls = step1Result.toolCallMap.values.map { acc ->
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

            val assistantContent = buildRawMessage(step1Result)
            var lastMsgId = addMessage(
                conversationId = conversationId,
                role = "assistant",
                content = assistantContent.ifEmpty { "" },
                parentMessageId = parentMessageId,
                siblingIndex = siblingIndex,
                toolCallsJson = toolCallsJson,
                promptTokens = step1Result.usage?.promptTokens,
                completionTokens = step1Result.usage?.completionTokens,
                totalTokens = step1Result.usage?.totalTokens,
                decodingSpeedTps = step1Result.usage?.decodingSpeedTps,
                prefillSpeedTps = step1Result.usage?.prefillSpeedTps
            )

            val askTool = toolRegistry.getTool<AskUserQuestionTool>("ask_user_question")
            try {
                askTool?.onAskUser = onAskUser
                onToolStatus?.invoke("ツール実行中...")

                for (tc in completedToolCalls) {
                    val toolName = tc.function?.name ?: continue
                    val toolArgs = tc.function.arguments ?: "{}"
                    val result = toolRegistry.execute(toolName, toolArgs)
                    lastMsgId = addMessage(
                        conversationId = conversationId,
                        role = "tool",
                        content = result,
                        parentMessageId = lastMsgId,
                        toolCallId = tc.id
                    )
                }
            } finally {
                askTool?.onAskUser = null
            }

            onToolStatus?.invoke(null)

            val step3Messages = buildApiMessages(conversationId, null, "", systemPrompt)
            val step3Request = ApiChatRequest(
                model = modelName,
                messages = step3Messages,
                tools = toolDefinitions,
                stream = true
            )

            val step3Result = streamApiCall(api, step3Request, onStreamUpdate)
            val rawMessage = buildRawMessage(step3Result)
            val assistantMessage = cleanupIncompleteThinkTags(rawMessage)

            addMessage(
                conversationId = conversationId,
                role = "assistant",
                content = assistantMessage,
                parentMessageId = lastMsgId,
                promptTokens = step3Result.usage?.promptTokens,
                completionTokens = step3Result.usage?.completionTokens,
                totalTokens = step3Result.usage?.totalTokens,
                decodingSpeedTps = step3Result.usage?.decodingSpeedTps,
                prefillSpeedTps = step3Result.usage?.prefillSpeedTps
            )

            updateConversationTitleIfNeeded(conversationId)
            return Result.success(assistantMessage)
        } else {
            val rawMessage = buildRawMessage(step1Result)
            val assistantMessage = cleanupIncompleteThinkTags(rawMessage)

            addMessage(
                conversationId = conversationId,
                role = "assistant",
                content = assistantMessage,
                parentMessageId = parentMessageId,
                siblingIndex = siblingIndex,
                promptTokens = step1Result.usage?.promptTokens,
                completionTokens = step1Result.usage?.completionTokens,
                totalTokens = step1Result.usage?.totalTokens,
                decodingSpeedTps = step1Result.usage?.decodingSpeedTps,
                prefillSpeedTps = step1Result.usage?.prefillSpeedTps
            )

            updateConversationTitleIfNeeded(conversationId)
            return Result.success(assistantMessage)
        }
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

    private suspend fun buildApiMessages(
        conversationId: Long,
        attachment: ProcessedAttachment?,
        userMessage: String,
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

        val apiMessages = messages.mapIndexed { index, msg ->
            val isLastUserMessage = index == messages.lastIndex && msg.role == "user"

            when {
                // Tool result message
                msg.role == "tool" -> {
                    ApiChatMessage(
                        role = "tool",
                        content = MessageContent.Text(msg.content),
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
                        content = if (msg.content.isNotEmpty()) MessageContent.Text(msg.content) else null,
                        toolCalls = toolCalls
                    )
                }
                // Normal assistant message
                msg.role == "assistant" -> {
                    val sourceText = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                    val content = sourceText
                        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                        .replace(Regex("^[\\s\\S]*?</think>"), "")
                        .trim()
                    ApiChatMessage(role = msg.role, content = MessageContent.Text(content))
                }
                // Last user message with image attachment
                isLastUserMessage && attachment is ProcessedAttachment.ImageAttachment -> {
                    val parts = mutableListOf<ContentPart>()
                    if (userMessage.isNotBlank()) {
                        parts.add(ContentPart.TextPart(userMessage))
                    }
                    parts.add(
                        ContentPart.ImageUrlPart(
                            ImageUrl("data:${attachment.mimeType};base64,${attachment.base64Data}")
                        )
                    )
                    ApiChatMessage(role = msg.role, content = MessageContent.Parts(parts))
                }
                // Normal user message
                else -> {
                    val sourceText = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                    ApiChatMessage(role = msg.role, content = MessageContent.Text(sourceText))
                }
            }
        }

        return if (systemPrompt.isNotBlank()) {
            listOf(ApiChatMessage(role = "system", content = MessageContent.Text(systemPrompt))) + apiMessages
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
        val summaryTokens: Int
    )

    suspend fun summarizeMessage(messageId: Long, content: String): Result<SummarizeResult> {
        return try {
            val baseUrl = settingsRepository.baseUrl.first()
            val modelName = settingsRepository.modelName.first()
            val api = ApiClient.getChatApi(baseUrl)

            val systemPrompt = "以下のテキストを日本語で100〜200トークン程度に要約してください。重要な情報や結論を保持しつつ、トークン数を削減してください。要約文のみ返してください。"
            val request = ChatRequest(
                model = modelName,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = content)
                ),
                stream = false,
                maxTokens = 512
            )

            val response = withTimeout(180_000) {
                withContext(Dispatchers.IO) {
                    api.chat(request = request)
                }
            }

            val rawText = response.choices.firstOrNull()?.message?.content ?: ""
            // Remove <think>...</think> tags if present
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

            messageDao.updateSummary(messageId, cleanedText)
            Result.success(SummarizeResult(cleanedText, originalTokens, summaryTokens))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateMessage(messageId: Long, content: String): Result<String> {
        return try {
            val baseUrl = settingsRepository.baseUrl.first()
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
                model = "translategemma:4b",
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

            val translatedText = response.choices.firstOrNull()?.message?.content?.trim() ?: ""

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

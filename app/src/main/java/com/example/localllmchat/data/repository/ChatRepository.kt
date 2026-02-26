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
import com.example.localllmchat.data.remote.ToolCall
import com.example.localllmchat.data.remote.ToolCallFunction
import com.example.localllmchat.data.remote.ToolDefinition
import com.example.localllmchat.data.remote.UsageResponse
import com.example.localllmchat.data.tool.ToolRegistry
import com.example.localllmchat.util.ProcessedAttachment
import com.example.localllmchat.data.remote.ChatMessage
import com.example.localllmchat.data.remote.ChatRequest
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(
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
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            decodingSpeedTps = decodingSpeedTps,
            prefillSpeedTps = prefillSpeedTps,
            toolCallsJson = toolCallsJson,
            toolCallId = toolCallId
        )
        val messageId = messageDao.insert(message)

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
        onToolStatus: ((status: String?) -> Unit)? = null
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
            addMessage(conversationId, "user", dbContent)

            val baseUrl = settingsRepository.baseUrl.first()
            val modelName = settingsRepository.modelName.first()
            val systemPrompt = settingsRepository.systemPrompt.first()
            val disabledTools = settingsRepository.disabledTools.first()
            val toolDefinitions = toolRegistry.getDefinitions(modelName, disabledTools)

            val api = ApiClient.getChatApi(baseUrl)

            // Build API messages from DB and send Step 1
            val toolsEnabled = toolDefinitions != null
            val step1Messages = buildApiMessages(conversationId, attachment, userMessage, systemPrompt, toolsEnabled)
            val step1Request = ApiChatRequest(
                model = modelName,
                messages = step1Messages,
                tools = toolDefinitions,
                stream = true
            )

            val step1Result = streamApiCall(
                api = api,
                request = step1Request,
                onStreamUpdate = onStreamUpdate
            )

            // Check if model requested tool calls
            if (step1Result.toolCallMap.isNotEmpty()) {
                // Build completed tool calls
                val completedToolCalls = step1Result.toolCallMap.values.map { acc ->
                    // Generate a proper UUID if server returns invalid id (e.g. "generate_id()")
                    val toolCallId = if (acc.id.isNullOrBlank() || acc.id!!.contains("("))
                        "call_${java.util.UUID.randomUUID()}"
                    else acc.id!!
                    ToolCall(
                        id = toolCallId,
                        type = "function",
                        function = ToolCallFunction(
                            name = acc.name,
                            arguments = acc.arguments.toString()
                        )
                    )
                }
                val toolCallsJson = gson.toJson(completedToolCalls)

                // Save assistant message with tool_calls to DB (include reasoning from Step 1)
                val assistantContent = buildRawMessage(step1Result)
                addMessage(
                    conversationId = conversationId,
                    role = "assistant",
                    content = assistantContent.ifEmpty { "" },
                    toolCallsJson = toolCallsJson,
                    promptTokens = step1Result.usage?.promptTokens,
                    completionTokens = step1Result.usage?.completionTokens,
                    totalTokens = step1Result.usage?.totalTokens,
                    decodingSpeedTps = step1Result.usage?.decodingSpeedTps,
                    prefillSpeedTps = step1Result.usage?.prefillSpeedTps
                )

                // Execute each tool and save results to DB
                for (tc in completedToolCalls) {
                    val toolName = tc.function?.name ?: continue
                    val toolArgs = tc.function.arguments ?: "{}"
                    val result = toolRegistry.execute(toolName, toolArgs)
                    addMessage(
                        conversationId = conversationId,
                        role = "tool",
                        content = result,
                        toolCallId = tc.id
                    )
                }

                // Notify UI AFTER messages are saved to DB
                // This clears streaming content; saved messages appear via Flow
                onToolStatus?.invoke("ツール実行中...")

                // Clear tool status before Step 3 streaming starts
                onToolStatus?.invoke(null)

                // Step 3: Rebuild full history from DB (now includes tool messages) and send again
                val step3Messages = buildApiMessages(conversationId, null, "", systemPrompt)
                val step3Request = ApiChatRequest(
                    model = modelName,
                    messages = step3Messages,
                    tools = toolDefinitions,
                    stream = true
                )

                val step3Result = streamApiCall(
                    api = api,
                    request = step3Request,
                    onStreamUpdate = onStreamUpdate
                )

                // Save final response
                val rawMessage = buildRawMessage(step3Result)
                val assistantMessage = cleanupIncompleteThinkTags(rawMessage)

                addMessage(
                    conversationId = conversationId,
                    role = "assistant",
                    content = assistantMessage,
                    promptTokens = step3Result.usage?.promptTokens,
                    completionTokens = step3Result.usage?.completionTokens,
                    totalTokens = step3Result.usage?.totalTokens,
                    decodingSpeedTps = step3Result.usage?.decodingSpeedTps,
                    prefillSpeedTps = step3Result.usage?.prefillSpeedTps
                )

                updateConversationTitleIfNeeded(conversationId)
                Result.success(assistantMessage)
            } else {
                // Normal text response (no tool calls)
                val rawMessage = buildRawMessage(step1Result)
                val assistantMessage = cleanupIncompleteThinkTags(rawMessage)

                addMessage(
                    conversationId = conversationId,
                    role = "assistant",
                    content = assistantMessage,
                    promptTokens = step1Result.usage?.promptTokens,
                    completionTokens = step1Result.usage?.completionTokens,
                    totalTokens = step1Result.usage?.totalTokens,
                    decodingSpeedTps = step1Result.usage?.decodingSpeedTps,
                    prefillSpeedTps = step1Result.usage?.prefillSpeedTps
                )

                updateConversationTitleIfNeeded(conversationId)
                Result.success(assistantMessage)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    private suspend fun buildApiMessages(
        conversationId: Long,
        attachment: ProcessedAttachment?,
        userMessage: String,
        systemPrompt: String,
        toolsEnabled: Boolean = true
    ): List<ApiChatMessage> {
        val allMessages = messageDao.getMessagesForConversationSync(conversationId)
        val messages = allMessages.filter { msg ->
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

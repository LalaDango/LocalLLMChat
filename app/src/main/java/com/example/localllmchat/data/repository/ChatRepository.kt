package com.example.localllmchat.data.repository

import com.example.localllmchat.data.local.ConversationDao
import com.example.localllmchat.data.local.ConversationEntity
import com.example.localllmchat.data.local.MessageDao
import com.example.localllmchat.data.local.MessageEntity
import com.example.localllmchat.data.remote.ApiChatMessage
import com.example.localllmchat.data.remote.ApiChatRequest
import com.example.localllmchat.data.remote.ApiClient
import com.example.localllmchat.data.remote.ChatResponse
import com.example.localllmchat.data.remote.ContentPart
import com.example.localllmchat.data.remote.ImageUrl
import com.example.localllmchat.data.remote.MessageContent
import com.example.localllmchat.data.remote.UsageResponse
import com.example.localllmchat.util.ProcessedAttachment
import com.example.localllmchat.data.remote.ChatMessage
import com.example.localllmchat.data.remote.ChatRequest
import com.google.gson.Gson
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
    private val settingsRepository: SettingsRepository
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
        prefillSpeedTps: Double? = null
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            decodingSpeedTps = decodingSpeedTps,
            prefillSpeedTps = prefillSpeedTps
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
        onStreamUpdate: ((content: String, reasoning: String) -> Unit)? = null
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

            val allMessages = messageDao.getMessagesForConversationSync(conversationId)
            val messages = allMessages.filter { !it.isExcluded }

            // Build API messages: all history as text, current message may be multimodal
            // Use summaryText for summarized messages to reduce token consumption
            val apiMessages = messages.mapIndexed { index, msg ->
                val isLastMessage = index == messages.lastIndex
                if (msg.role == "assistant") {
                    val sourceText = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                    val content = sourceText
                        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                        .replace(Regex("^[\\s\\S]*?</think>"), "")
                        .trim()
                    ApiChatMessage(role = msg.role, content = MessageContent.Text(content))
                } else if (isLastMessage && attachment is ProcessedAttachment.ImageAttachment) {
                    // Current user message with image: use multimodal format
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
                } else {
                    val sourceText = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                    ApiChatMessage(role = msg.role, content = MessageContent.Text(sourceText))
                }
            }

            val baseUrl = settingsRepository.baseUrl.first()
            val modelName = settingsRepository.modelName.first()
            val systemPrompt = settingsRepository.systemPrompt.first()

            val finalMessages = if (systemPrompt.isNotBlank()) {
                listOf(ApiChatMessage(role = "system", content = MessageContent.Text(systemPrompt))) + apiMessages
            } else {
                apiMessages
            }

            val api = ApiClient.getChatApi(baseUrl)
            val request = ApiChatRequest(model = modelName, messages = finalMessages, stream = true)

            // Use streaming mode
            val responseBody = api.chatStreamMultimodal(request = request)
            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            var usage: UsageResponse? = null

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
                        if (delta?.reasoningContent != null) {
                            reasoningBuilder.append(delta.reasoningContent)
                        }
                        if (delta?.content != null) {
                            contentBuilder.append(delta.content)
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
                    } catch (_: Exception) {
                        // Skip malformed chunks
                    }
                }
                reader.close()
                // Final emit to ensure last chunk is not lost due to throttle
                onStreamUpdate?.invoke(
                    contentBuilder.toString(),
                    reasoningBuilder.toString()
                )
            }

            // Build full message with think tags wrapping reasoning content
            val rawMessage = buildString {
                if (reasoningBuilder.isNotEmpty()) {
                    append("<think>")
                    append(reasoningBuilder)
                    append("</think>")
                }
                if (contentBuilder.isNotEmpty()) {
                    append(contentBuilder)
                } else if (reasoningBuilder.isEmpty()) {
                    append("No response received")
                }
            }

            // Clean up incomplete think tags (trailing <think> without closing tag)
            val assistantMessage = cleanupIncompleteThinkTags(rawMessage)

            addMessage(
                conversationId = conversationId,
                role = "assistant",
                content = assistantMessage,
                promptTokens = usage?.promptTokens,
                completionTokens = usage?.completionTokens,
                totalTokens = usage?.totalTokens,
                decodingSpeedTps = usage?.decodingSpeedTps,
                prefillSpeedTps = usage?.prefillSpeedTps
            )

            val firstUserMessage = allMessages.firstOrNull { it.role == "user" }?.content
            if (firstUserMessage != null && allMessages.size <= 2) {
                val title = firstUserMessage.take(30) + if (firstUserMessage.length > 30) "..." else ""
                updateConversationTitle(conversationId, title)
            }

            Result.success(assistantMessage)
        } catch (e: Exception) {
            Result.failure(e)
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
}

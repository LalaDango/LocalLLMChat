package com.example.localllmchat.data.remote

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

// Polymorphic content for API messages
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Parts(val parts: List<ContentPart>) : MessageContent()
}

sealed class ContentPart {
    data class TextPart(val text: String) : ContentPart()
    data class ImageUrlPart(val imageUrl: ImageUrl) : ContentPart()
}

data class ImageUrl(val url: String)

// API-only message class (separate from DB ChatMessage)
data class ApiChatMessage(
    val role: String,
    val content: MessageContent? = null,
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

// API request using ApiChatMessage
data class ApiChatRequest(
    @SerializedName("model")
    val model: String,
    @SerializedName("messages")
    val messages: List<ApiChatMessage>,
    @SerializedName("tools")
    val tools: List<ToolDefinition>? = null,
    @SerializedName("stream")
    val stream: Boolean = false,
    @SerializedName("temperature")
    val temperature: Double = 0.45,
    @SerializedName("top_p")
    val topP: Double = 0.9,
    @SerializedName("max_tokens")
    val maxTokens: Int = 8192,
    @SerializedName("top_k")
    val topK: Int = 40,
    @SerializedName("repeat_penalty")
    val repeatPenalty: Double = 1.1,
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Double = 0.2,
    @SerializedName("presence_penalty")
    val presencePenalty: Double = 0.0
)

// Custom Gson serializer for ApiChatMessage
class ApiChatMessageSerializer : JsonSerializer<ApiChatMessage> {
    override fun serialize(
        src: ApiChatMessage,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val obj = JsonObject()
        obj.addProperty("role", src.role)

        when (val content = src.content) {
            is MessageContent.Text -> {
                obj.addProperty("content", content.text)
            }
            is MessageContent.Parts -> {
                val array = JsonArray()
                content.parts.forEach { part ->
                    when (part) {
                        is ContentPart.TextPart -> {
                            val partObj = JsonObject()
                            partObj.addProperty("type", "text")
                            partObj.addProperty("text", part.text)
                            array.add(partObj)
                        }
                        is ContentPart.ImageUrlPart -> {
                            val partObj = JsonObject()
                            partObj.addProperty("type", "image_url")
                            val urlObj = JsonObject()
                            urlObj.addProperty("url", part.imageUrl.url)
                            partObj.add("image_url", urlObj)
                            array.add(partObj)
                        }
                    }
                }
                obj.add("content", array)
            }
            null -> {
                obj.add("content", JsonNull.INSTANCE)
            }
        }

        if (src.reasoningContent != null) {
            obj.addProperty("reasoning_content", src.reasoningContent)
        }

        if (src.toolCalls != null) {
            val toolCallsArray = JsonArray()
            src.toolCalls.forEach { tc ->
                val tcObj = JsonObject()
                if (tc.id != null) tcObj.addProperty("id", tc.id)
                if (tc.type != null) tcObj.addProperty("type", tc.type)
                if (tc.function != null) {
                    val fnObj = JsonObject()
                    if (tc.function.name != null) fnObj.addProperty("name", tc.function.name)
                    if (tc.function.arguments != null) fnObj.addProperty("arguments", tc.function.arguments)
                    tcObj.add("function", fnObj)
                }
                toolCallsArray.add(tcObj)
            }
            obj.add("tool_calls", toolCallsArray)
        }

        if (src.toolCallId != null) {
            obj.addProperty("tool_call_id", src.toolCallId)
        }

        return obj
    }
}

package com.example.localllmchat.data.remote

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("model")
    val model: String,
    @SerializedName("messages")
    val messages: List<ChatMessage>,
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

data class ChatMessage(
    @SerializedName("role")
    val role: String,
    @SerializedName("content")
    val content: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null
)

package com.example.localllmchat.data.remote

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("object")
    val objectType: String? = null,
    @SerializedName("created")
    val created: Long? = null,
    @SerializedName("model")
    val model: String? = null,
    @SerializedName("choices")
    val choices: List<Choice>,
    @SerializedName("usage")
    val usage: UsageResponse? = null
)

data class Choice(
    @SerializedName("index")
    val index: Int? = null,
    @SerializedName("message")
    val message: ChatMessage? = null,
    @SerializedName("delta")
    val delta: ChatMessage? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

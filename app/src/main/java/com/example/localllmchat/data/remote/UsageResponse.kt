package com.example.localllmchat.data.remote

import com.google.gson.annotations.SerializedName

data class UsageResponse(
    @SerializedName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerializedName("completion_tokens")
    val completionTokens: Int? = null,
    @SerializedName("total_tokens")
    val totalTokens: Int? = null,
    @SerializedName("decoding_speed_tps")
    val decodingSpeedTps: Double? = null,
    @SerializedName("prefill_speed_tps")
    val prefillSpeedTps: Double? = null
)

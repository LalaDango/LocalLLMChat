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
    val prefillSpeedTps: Double? = null,
    // FastFlowLM v0.9.41+: KV 実測値（stream の最終チャンクのみ）
    @SerializedName("active_kv_tokens")
    val activeKvTokens: Int? = null,
    @SerializedName("max_kv_token_capacity")
    val maxKvTokenCapacity: Int? = null
)

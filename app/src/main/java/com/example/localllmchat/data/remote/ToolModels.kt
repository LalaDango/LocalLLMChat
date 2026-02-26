package com.example.localllmchat.data.remote

import com.google.gson.annotations.SerializedName

data class ToolDefinition(
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: FunctionDefinition
)

data class FunctionDefinition(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: Map<String, Any>? = null
)

data class ToolCall(
    @SerializedName("index") val index: Int? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("function") val function: ToolCallFunction? = null
)

data class ToolCallFunction(
    @SerializedName("name") val name: String? = null,
    @SerializedName("arguments") val arguments: String? = null
)

data class AccumulatedToolCall(
    var id: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder()
)

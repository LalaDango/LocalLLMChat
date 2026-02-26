package com.example.localllmchat.data.tool

import com.example.localllmchat.data.remote.ToolDefinition

interface ToolHandler {
    val definition: ToolDefinition
    suspend fun execute(arguments: String): String
}

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolHandler>()

    fun register(name: String, handler: ToolHandler) {
        tools[name] = handler
    }

    fun getDefinitions(modelName: String, disabledTools: Set<String> = emptySet()): List<ToolDefinition>? {
        if (!supportsToolCalling(modelName)) return null
        val defs = tools.entries
            .filter { it.key !in disabledTools }
            .map { it.value.definition }
        return defs.ifEmpty { null }
    }

    fun getAvailableToolNames(): List<String> = tools.keys.toList()

    fun getToolDescriptions(): Map<String, String> =
        tools.mapValues { it.value.definition.function.description }

    suspend fun execute(name: String, arguments: String): String {
        val handler = tools[name]
            ?: return """{"error": "Unknown tool: $name"}"""
        return try {
            handler.execute(arguments)
        } catch (e: Exception) {
            """{"error": "${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun supportsToolCalling(modelName: String): Boolean {
        val lower = modelName.lowercase()
        return lower.contains("qwen3") ||
                lower.contains("qwen2.5vl") ||
                lower.contains("lfm2.5")
    }
}

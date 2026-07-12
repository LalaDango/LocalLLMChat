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

    fun <T : ToolHandler> getTool(name: String): T? {
        @Suppress("UNCHECKED_CAST")
        return tools[name] as? T
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
                lower.contains("lfm2.5") ||
                lower.contains("nanbeige") ||
                lower.contains("gemma4")
    }

    // gemma系は FLM 経由だと role:"tool" がモデルに届かない（テンプレート実装が落とす）ため、
    // 送信時に tool→user 変換 + tool_calls のテキスト畳み込みが必要
    fun requiresToolRoleConversion(modelName: String): Boolean =
        modelName.lowercase().contains("gemma4")

    // gemma4系は生 OpenAI 形式＋v3プロファイルで運用する（FLM v0.9.45 のツールターン2バグ対策）。
    // 受信側: 捏造断片 strip＋max_tokens キャップ（v3要件④）、送信側: 分割 echo（同②・バグB対策）。
    // 詳細: .claude/skills/local-ai-env-ref/references/known-issues.md「v0.9.45のツールターン応答2バグ」
    fun usesV3ToolProfile(modelName: String): Boolean =
        modelName.lowercase().contains("gemma4")
}

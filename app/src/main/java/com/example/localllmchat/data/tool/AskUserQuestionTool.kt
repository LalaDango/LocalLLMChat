package com.example.localllmchat.data.tool

import com.example.localllmchat.data.remote.FunctionDefinition
import com.example.localllmchat.data.remote.ToolDefinition
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred

class AskUserQuestionTool : ToolHandler {
    private val gson = Gson()

    /**
     * Callback set from ChatRepository before tool execution.
     * Called during execute() to show UI dialog and wait for user response.
     * Returns a CompletableDeferred that completes when the user answers.
     */
    var onAskUser: ((question: String, options: List<String>) -> CompletableDeferred<String>)? = null

    override val definition = ToolDefinition(
        function = FunctionDefinition(
            name = "ask_user_question",
            description = "Ask the user a multiple-choice question and wait for their answer. Use this when you need clarification or a decision from the user to proceed. The tool result's 'answer' field contains the user's actual answer (e.g. \"User selected: <option>\"). After receiving it, do not repeat the question; respond to the user's answer (e.g. judge correctness, then continue).",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "question" to mapOf(
                        "type" to "string",
                        "description" to "The question to ask the user"
                    ),
                    "options" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "List of options for the user to choose from"
                    )
                ),
                "required" to listOf("question", "options")
            )
        )
    )

    /**
     * When all options are short labels (<=3 chars, e.g. "A","B","C","D" or "1","2","3"),
     * try to extract the full option text from the question body.
     * On success, removes the entire option block (from the first label to end) from question.
     */
    private fun expandShortOptions(
        question: String,
        options: List<String>
    ): Pair<String, List<String>> {
        if (options.any { it.length > 3 }) return question to options

        val expanded = mutableListOf<String>()
        val escapedLabels = options.map { Regex.escape(it) }

        // Extract body for each option label
        for ((i, option) in options.withIndex()) {
            val escaped = escapedLabels[i]
            // Lookahead: stop at the next option label or end of string
            val stopAlternatives = escapedLabels
                .filterIndexed { j, _ -> j != i }
                .joinToString("|") { """\s*$it\s*[).:\-]""" }
            val stopPattern = if (stopAlternatives.isNotEmpty()) {
                """(?=\n(?:$stopAlternatives)|\z)"""
            } else {
                """\z"""
            }
            val pattern = Regex(
                """(?:^|\n)\s*${escaped}\s*[).:\-]\s*(.+?)$stopPattern""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val match = pattern.find(question)
            if (match != null) {
                expanded.add("${option}) ${match.groupValues[1].trim()}")
            } else {
                return question to options
            }
        }

        // Remove entire block from first label occurrence to end of string
        val firstLabel = escapedLabels[0]
        val blockStart = Regex("""(?:^|\n)\s*${firstLabel}\s*[).:\-]""").find(question)
            ?: return question to options
        // Also remove leading intro lines like "正解を選びなさい：" right before the block
        var cutPos = blockStart.range.first
        val beforeBlock = question.substring(0, cutPos)
        // Trim trailing blank/intro lines (e.g. "正解を選びなさい：\n")
        val trimmed = beforeBlock.trimEnd()
        // If the last non-blank line looks like an intro (ends with：or : ), remove it too
        val lines = trimmed.split("\n")
        val cleanedLines = if (lines.isNotEmpty() &&
            lines.last().trim().let { it.endsWith("：") || it.endsWith(":") }
        ) {
            lines.dropLast(1)
        } else {
            lines
        }

        return cleanedLines.joinToString("\n").trim() to expanded
    }

    override suspend fun execute(arguments: String): String {
        val args = try {
            gson.fromJson(arguments, JsonObject::class.java)
        } catch (_: Exception) {
            return gson.toJson(mapOf("error" to "Invalid JSON arguments"))
        }

        val question = args?.get("question")?.asString
            ?: return gson.toJson(mapOf("error" to "Missing 'question' parameter"))

        val optionsArray = args.getAsJsonArray("options")
            ?: return gson.toJson(mapOf("error" to "Missing 'options' parameter"))

        val rawOptions = optionsArray.map { it.asString }
        if (rawOptions.isEmpty()) {
            return gson.toJson(mapOf("error" to "Options must not be empty"))
        }

        // Expand short labels (e.g. "A","B","C") by extracting full text from question
        val (displayQuestion, displayOptions) = expandShortOptions(question, rawOptions)

        val handler = onAskUser
            ?: return gson.toJson(mapOf("error" to "No UI handler available"))

        val deferred = handler(displayQuestion, displayOptions)
        val answer = deferred.await()

        val result = JsonObject()
        result.addProperty("answer", answer)
        result.addProperty("cancelled", answer == "User cancelled")
        return gson.toJson(result)
    }
}

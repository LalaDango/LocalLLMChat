package com.example.localllmchat.data.model

enum class LengthPreset(
    val label: String,
    val targetRange: String,
    val minTokens: Int,
    val maxTokens: Int
) {
    SHORT("短い", "200〜400", 100, 512),
    STANDARD("標準", "300〜600", 200, 1024),
    DETAILED("詳細", "800〜1500", 400, 2048)
}

data class SummarizeConfig(
    val lengthPreset: LengthPreset = LengthPreset.STANDARD,
    val priorityTopics: String = "",
    val excludeTopics: String = "",
    val originalTokens: Int? = null,
    val summaryTokens: Int? = null
)

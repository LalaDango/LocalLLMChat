package com.example.localllmchat.util

/**
 * 捏造断片 strip（v3要件④）の純関数部。
 *
 * gemma4-it:e4b × FLM v0.9.45 で、生成が <|tool_call> 終端で止まらず架空の tool_response
 * （"User selected: ..." 等）→自作判定→次問まで書き続ける暴走の断片が、stream の content
 * デルタに漏れる（2026-07-12 ハーネス[B]実測）。最初のマーカー位置から末尾までを切除する。
 *
 * 適用のゲート（gemma4系×ツール有効ターン限定）と、DB 保存前の不可逆切除に伴う発動ログは
 * 呼び出し側（ChatRepository.stripFabricatedToolFragments）が担う。
 * このオブジェクトは Android 非依存（JVM ユニットテスト対象: ToolFragmentSanitizerTest。
 * テストケースはハーネス実測の断片実物をそのまま使用している）。
 */
object ToolFragmentSanitizer {

    /** 切除結果。removed が null なら発動なし（cleaned は元テキストと同一インスタンス） */
    data class Result(val cleaned: String, val removed: String?)

    // 疑似タグは正規コンテンツに出現しないため無条件マーカー。
    // <|" は疑似クオートトークン <|"|> の先頭、<tool_call\| は閉じタグ形 <tool_call|>
    // （いずれも 2026-07-12 ハーネス[B]で実測した漏れ断片に含まれる実物シグネチャ）
    private val fabricatedTagRegex =
        Regex("""<\|tool_response|<\|tool_call|<\|"|</tool_|<tool_response>|<tool_call\|""")

    // 実測シグネチャは `response:ask_user_question{...}` 形（response: の直後にツール名が来る）。
    // JSON 直開きの `response:{` / `response:"` 形も対象。"response:" は一般語で誤爆リスクが
    // あるため、行頭アンカー＋同一応答に tool_calls がある場合（hasToolCalls）のみ適用する
    private val fabricatedResponseLineRegex =
        Regex("""^response:\s*(?:[{"]|\w+\s*\{)""", RegexOption.MULTILINE)

    fun strip(text: String, hasToolCalls: Boolean): Result {
        var cutIndex = fabricatedTagRegex.find(text)?.range?.first ?: -1
        if (hasToolCalls) {
            val respIndex = fabricatedResponseLineRegex.find(text)?.range?.first ?: -1
            if (respIndex >= 0 && (cutIndex < 0 || respIndex < cutIndex)) cutIndex = respIndex
        }
        if (cutIndex < 0) return Result(text, null)
        return Result(text.substring(0, cutIndex).trimEnd(), text.substring(cutIndex))
    }
}

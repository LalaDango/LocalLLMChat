package com.example.localllmchat.util

/**
 * 送信前のトークン数見積り（容量ガード用）。必ず実測より多め（安全側）に出るよう較正している。
 *
 * 係数は FLM 同梱 tokenizer.json（Gemma4 12B/E4B・Qwen3.5 9B）での実測から決定（2026-10-06）:
 * - 通常の ASCII（英数字・記号・空白）0.45 / それ以外（日本語等）0.9 トークン/UTF-16 単位
 * - 64 文字以上連続する base64 系の英数字（data URI の画像・ハッシュ等）は 0.8。
 *   base64 は実測 0.70〜0.74 トークン/文字で、通常 ASCII の係数だと約半分に過小見積りしてしまう
 *   （画像の base64 はほぼランダム＝最悪ケースなので、実測値に 5〜10% の余裕を足した）
 * 実測比（見積り÷実トークン）: HTML 1.2、圧縮 JS 1.05〜1.1、日本語 md 1.2〜1.35、
 * base64 1.09〜1.15、Kotlin コード 1.9。旧係数（一律 0.9）は HTML で 1.9〜2.3 倍に過大だった。
 */
object TokenEstimator {
    private const val ASCII_TOKENS_PER_CHAR = 0.45
    private const val NON_ASCII_TOKENS_PER_CHAR = 0.9
    private const val BASE64_TOKENS_PER_CHAR = 0.8
    private const val BASE64_MIN_RUN = 64

    fun estimate(text: String): Int {
        var ascii = 0
        var base64 = 0
        var run = 0
        for (c in text) {
            if (isBase64Char(c)) {
                run++
                continue
            }
            if (run >= BASE64_MIN_RUN) base64 += run else ascii += run
            run = 0
            if (c.code < 128) ascii++
        }
        if (run >= BASE64_MIN_RUN) base64 += run else ascii += run
        val nonAscii = text.length - ascii - base64
        return (ascii * ASCII_TOKENS_PER_CHAR +
            base64 * BASE64_TOKENS_PER_CHAR +
            nonAscii * NON_ASCII_TOKENS_PER_CHAR).toInt()
    }

    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c == '-' || c == '_'
}

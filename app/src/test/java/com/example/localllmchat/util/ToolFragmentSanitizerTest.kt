package com.example.localllmchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 捏造断片 strip の両方向テスト（取り逃し側／誤爆側）。
 *
 * wildFragment は 2026-07-12 ハーネス[B] quiz-生形式v3 #4 で観測した野生捏造断片の実物
 * （results_20260712_193004.jsonl trial 4 の content 全文・一字一句そのまま）。
 * 野生捏造は狙って再現できないため、この実物文字列が回帰の基準になる。
 */
class ToolFragmentSanitizerTest {

    private val wildFragment =
        """response:ask_user_question{answer:<|"|>User selected: Positional Encoding<|"|>,cancelled:false}<tool_call|>""" +
        """response:ask_user_question{answer:<|"|>User selected: Self-Attention<|"|>,cancelled:false}<tool_call|>""" +
        """response:ask_user_question{answer:<|"|>User selected: Feed-Forward Network<|"|>,cancelled:false}<tool_call|>"""

    // ── 取り逃し側（切除されるべきもの） ──

    @Test
    fun wildFragment_isFullyStripped() {
        val r = ToolFragmentSanitizer.strip(wildFragment, hasToolCalls = true)
        assertEquals("", r.cleaned)
        assertEquals(wildFragment, r.removed)
    }

    @Test
    fun judgmentFollowedByWildFragment_keepsJudgmentOnly() {
        // アプリで想定される形: 判定文（正規）のあとに捏造断片が続く
        val judgment = "「Multi-Head Attention」が正解です！\n\n次の問題です。"
        val r = ToolFragmentSanitizer.strip(judgment + "\n" + wildFragment, hasToolCalls = true)
        assertEquals(judgment, r.cleaned)
        assertEquals(wildFragment, r.removed)
    }

    @Test
    fun pseudoTag_isStrippedRegardlessOfToolCalls() {
        val r = ToolFragmentSanitizer.strip("説明です。\n<|tool_response>捏造の続き", hasToolCalls = false)
        assertEquals("説明です。", r.cleaned)
    }

    @Test
    fun closingTagForm_isStripped() {
        // 実測断片に含まれる閉じタグ形 <tool_call|>（16e2b98 時点では取り逃していた形）
        val r = ToolFragmentSanitizer.strip("本文。\n<tool_call|>残渣", hasToolCalls = false)
        assertEquals("本文。", r.cleaned)
    }

    @Test
    fun pseudoQuoteToken_isStripped() {
        // 疑似クオートトークン <|"|>（実測断片の構成要素）
        val r = ToolFragmentSanitizer.strip("""本文。<|"|>junk""", hasToolCalls = false)
        assertEquals("本文。", r.cleaned)
    }

    // ── 誤爆側（切除されてはいけないもの） ──

    @Test
    fun japaneseProseWithResponseColon_isNotStripped() {
        val text = "HTTP の用語では、\nresponse: はサーバーの応答を指します。"
        val r = ToolFragmentSanitizer.strip(text, hasToolCalls = true)
        assertSame(text, r.cleaned)
        assertNull(r.removed)
    }

    @Test
    fun englishProseWithResponseColon_isNotStripped() {
        val text = "Check the log.\nresponse: the server replied with status 200."
        val r = ToolFragmentSanitizer.strip(text, hasToolCalls = true)
        assertNull(r.removed)
    }

    @Test
    fun responseColonMidLine_isNotStripped() {
        // 行頭アンカーの確認: 行中の response:{ は対象外
        val text = "サンプル: response:{...} という形式が返ります。"
        val r = ToolFragmentSanitizer.strip(text, hasToolCalls = true)
        assertNull(r.removed)
    }

    @Test
    fun responseForm_isNotStrippedWithoutToolCalls() {
        // response: 系マーカーは tool_calls 同伴時のみ（疑似タグを含まない形で確認）
        val text = "response:ask_user_question{answer:x}"
        val r = ToolFragmentSanitizer.strip(text, hasToolCalls = false)
        assertNull(r.removed)
    }

    @Test
    fun plainText_isUntouched() {
        val text = "現在時刻は2026年7月7日（火）の21時30分です。"
        val r = ToolFragmentSanitizer.strip(text, hasToolCalls = true)
        assertSame(text, r.cleaned)
        assertNull(r.removed)
    }
}

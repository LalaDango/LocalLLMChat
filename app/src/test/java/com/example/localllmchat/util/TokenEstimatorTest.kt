package com.example.localllmchat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun ascii_usesLowRate() {
        // "ab " × 100 = 300 文字（単語は短いので base64 扱いにならない）→ 300 × 0.45
        assertEquals(135, TokenEstimator.estimate("ab ".repeat(100)))
    }

    @Test
    fun japanese_usesHighRate() {
        assertEquals(9, TokenEstimator.estimate("あ".repeat(10)))
    }

    @Test
    fun mixed_sumsBothRates() {
        // ASCII 10 × 0.45 + 日本語 10 × 0.9 = 13.5
        assertEquals(13, TokenEstimator.estimate("<div>hello" + "こんにちは世界です。"))
    }

    @Test
    fun surrogatePair_countsAsTwoUnits() {
        // 絵文字は UTF-16 で 2 単位 → 2 × 0.9 = 1.8
        assertEquals(1, TokenEstimator.estimate("😊"))
    }

    @Test
    fun empty_isZero() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun longBase64Run_usesBase64Rate() {
        // data URI: 前置き 22 文字は通常 ASCII、100 文字の連続英数字は base64 扱い
        val dataUri = "data:image/png;base64," + "iVBORw0KGgo".repeat(10).take(100)
        // "data" "image" "png" "base64" は短い run → ASCII。計 22 × 0.45 + 100 × 0.8 = 89.9
        assertEquals(89, TokenEstimator.estimate(dataUri))
    }

    @Test
    fun runShorterThanThreshold_staysAscii() {
        assertEquals((63 * 0.45).toInt(), TokenEstimator.estimate("a".repeat(63)))
        assertEquals((64 * 0.8).toInt(), TokenEstimator.estimate("a".repeat(64)))
    }
}

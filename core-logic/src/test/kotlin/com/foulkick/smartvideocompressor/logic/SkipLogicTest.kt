package com.foulkick.smartvideocompressor.logic

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkipLogicTest {

    @Test
    fun `rule A skips an already-small well-compressed 1080p source`() {
        // target would be 5,000,000 (1080p30 floor); 1.3x = 6,500,000. Source at 4 Mbps is below that.
        val decision = SkipLogic.evaluatePreCompression(
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceBitrateBps = 4_000_000,
            calculatedTargetBitrateBps = 5_000_000,
        )
        assertTrue(decision is SkipLogic.PreCompressionDecision.SkipAlreadyCompressed)
    }

    @Test
    fun `rule A does not skip a 1080p source with bitrate above the 1-3x threshold`() {
        val decision = SkipLogic.evaluatePreCompression(
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceBitrateBps = 20_000_000,
            calculatedTargetBitrateBps = 5_000_000,
        )
        assertTrue(decision is SkipLogic.PreCompressionDecision.Proceed)
    }

    @Test
    fun `rule B never skips a 4K HEVC source merely for already being HEVC`() {
        // Representative case: 4K HEVC at ~61 Mbps must remain eligible for compression.
        val decision = SkipLogic.evaluatePreCompression(
            sourceWidth = 3840,
            sourceHeight = 2160,
            sourceBitrateBps = 61_000_000,
            calculatedTargetBitrateBps = 8_709_120,
        )
        assertTrue(decision is SkipLogic.PreCompressionDecision.Proceed)
    }

    @Test
    fun `rule B proceeds even for a very low bitrate 4K source`() {
        val decision = SkipLogic.evaluatePreCompression(
            sourceWidth = 3840,
            sourceHeight = 2160,
            sourceBitrateBps = 1_000_000,
            calculatedTargetBitrateBps = 8_709_120,
        )
        assertTrue(decision is SkipLogic.PreCompressionDecision.Proceed)
    }

    @Test
    fun `rule C discards output that is not smaller than source`() {
        val decision = SkipLogic.evaluatePostCompression(sourceSizeBytes = 1_000_000, outputSizeBytes = 1_000_001)
        assertTrue(decision is SkipLogic.PostCompressionDecision.DiscardNotSmaller)
    }

    @Test
    fun `rule C discards output that is exactly equal to source size`() {
        val decision = SkipLogic.evaluatePostCompression(sourceSizeBytes = 1_000_000, outputSizeBytes = 1_000_000)
        assertTrue(decision is SkipLogic.PostCompressionDecision.DiscardNotSmaller)
    }

    @Test
    fun `rule C keeps output that is smaller than source`() {
        val decision = SkipLogic.evaluatePostCompression(sourceSizeBytes = 1_000_000, outputSizeBytes = 500_000)
        assertTrue(decision is SkipLogic.PostCompressionDecision.Keep)
    }
}

package com.foulkick.smartvideocompressor.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BitrateCalculatorTest {

    @Test
    fun `1080p60 raw calculation matches spec example of about 8-7 Mbps`() {
        val result = BitrateCalculator.calculateTargetBitrate(1920, 1080, 60.0)
        assertEquals(8_709_120L, result.rawCalculatedBps)
        assertEquals(8_709_120, result.targetBitrateBps)
    }

    @Test
    fun `1080p30 raw calculation is below the 1080p floor and gets clamped up to 5 Mbps`() {
        val result = BitrateCalculator.calculateTargetBitrate(1920, 1080, 30.0)
        assertEquals(4_354_560L, result.rawCalculatedBps)
        assertEquals(5_000_000, result.targetBitrateBps)
        assertEquals(5_000_000, result.minBitrateBps)
    }

    @Test
    fun `1080p maximum bitrate is capped at 12 Mbps`() {
        // A pathologically high effective fps should still be clamped to the 1080p ceiling.
        val result = BitrateCalculator.calculateTargetBitrate(1920, 1080, 200.0)
        assertEquals(12_000_000, result.targetBitrateBps)
        assertEquals(12_000_000, result.maxBitrateBps)
    }

    @Test
    fun `lower resolutions use a proportionally lower minimum, not the 1080p floor`() {
        val result = BitrateCalculator.calculateTargetBitrate(1280, 720, 30.0)
        // 720p has ~0.444x the pixels of 1080p -> min should scale down proportionally.
        assert(result.minBitrateBps < BitrateCalculator.REFERENCE_MIN_BITRATE_BPS) {
            "720p min (${result.minBitrateBps}) should be below the 1080p floor"
        }
        assert(result.minBitrateBps > 0)
    }

    @Test
    fun `portrait 1080p (1080x1920) uses the same pixel-area based bounds as landscape`() {
        val landscape = BitrateCalculator.calculateTargetBitrate(1920, 1080, 30.0)
        val portrait = BitrateCalculator.calculateTargetBitrate(1080, 1920, 30.0)
        assertEquals(landscape.targetBitrateBps, portrait.targetBitrateBps)
        assertEquals(landscape.minBitrateBps, portrait.minBitrateBps)
        assertEquals(landscape.maxBitrateBps, portrait.maxBitrateBps)
    }

    @Test
    fun `TARGET_BPP constant is 0-07`() {
        assertEquals(0.07, BitrateCalculator.TARGET_BPP, 0.0001)
    }
}

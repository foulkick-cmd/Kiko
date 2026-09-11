package com.foulkick.smartvideocompressor.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EffectiveFpsCalculatorTest {

    @Test
    fun `VFR source uses frameCount over duration`() {
        // Representative test video: 2:57 = 177s, nominal 60fps but VFR.
        // Suppose actual decodable frames = 8496 -> 8496/177 = 48.0
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = 8496,
            durationSeconds = 177.0,
            nominalFrameRate = 60.0,
        )
        assertEquals(48.0, fps, 0.01)
    }

    @Test
    fun `does not assume nominal 60fps represents true frame density`() {
        // A VFR clip tagged "60fps" but whose actual decoded frames average far fewer per second.
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = 1770, // half the frames of a true 60fps 59s clip
            durationSeconds = 59.0,
            nominalFrameRate = 60.0,
        )
        assert(fps < 60.0) { "expected computed density below nominal, was $fps" }
        assertEquals(30.0, fps, 0.01) // 1770/59 = 30 exactly -> sanity check value
    }

    @Test
    fun `falls back to nominal frame rate when frame count unavailable`() {
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = null,
            durationSeconds = 10.0,
            nominalFrameRate = 30.0,
        )
        assertEquals(30.0, fps, 0.01)
    }

    @Test
    fun `falls back to nominal frame rate when frame count is zero or negative`() {
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = 0,
            durationSeconds = 10.0,
            nominalFrameRate = 25.0,
        )
        assertEquals(25.0, fps, 0.01)
    }

    @Test
    fun `falls back to nominal frame rate when duration unavailable`() {
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = 100,
            durationSeconds = null,
            nominalFrameRate = 29.97,
        )
        assertEquals(29.97, fps, 0.01)
    }

    @Test
    fun `clamps below minimum to 24fps`() {
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = 100,
            durationSeconds = 20.0, // 5fps
            nominalFrameRate = null,
        )
        assertEquals(24.0, fps, 0.01)
    }

    @Test
    fun `clamps above maximum to 60fps`() {
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = 12000,
            durationSeconds = 100.0, // 120fps
            nominalFrameRate = null,
        )
        assertEquals(60.0, fps, 0.01)
    }

    @Test
    fun `uses conservative default when no metadata is reliable at all`() {
        val fps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = null,
            durationSeconds = null,
            nominalFrameRate = null,
        )
        assertEquals(EffectiveFpsCalculator.MIN_FPS, fps, 0.01)
    }
}

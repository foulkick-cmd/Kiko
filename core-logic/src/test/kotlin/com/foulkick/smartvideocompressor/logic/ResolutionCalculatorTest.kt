package com.foulkick.smartvideocompressor.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResolutionCalculatorTest {

    @Test
    fun `4K landscape downscales to 1920x1080`() {
        val r = ResolutionCalculator.calculateOutputResolution(3840, 2160)
        assertEquals(ResolutionCalculator.Resolution(1920, 1080), r)
    }

    @Test
    fun `4K portrait downscales to 1080x1920`() {
        val r = ResolutionCalculator.calculateOutputResolution(2160, 3840)
        assertEquals(ResolutionCalculator.Resolution(1080, 1920), r)
    }

    @Test
    fun `ultrawide 4K scales so neither dimension exceeds bounds`() {
        val r = ResolutionCalculator.calculateOutputResolution(3840, 1600)
        // scale = min(1920/3840, 1080/1600) = min(0.5, 0.675) = 0.5
        assertEquals(1920, r.width)
        assertEquals(800, r.height)
        assert(r.width <= 1920)
        assert(r.height <= 1080)
    }

    @Test
    fun `1080p source is not upscaled and not changed`() {
        val r = ResolutionCalculator.calculateOutputResolution(1920, 1080)
        assertEquals(ResolutionCalculator.Resolution(1920, 1080), r)
    }

    @Test
    fun `720p source is not upscaled`() {
        val r = ResolutionCalculator.calculateOutputResolution(1280, 720)
        assertEquals(ResolutionCalculator.Resolution(1280, 720), r)
    }

    @Test
    fun `portrait 720p source is not upscaled`() {
        val r = ResolutionCalculator.calculateOutputResolution(720, 1280)
        assertEquals(ResolutionCalculator.Resolution(720, 1280), r)
    }

    @Test
    fun `aspect ratio is preserved within rounding to even pixels`() {
        val source = 3840.0 / 2160.0
        val r = ResolutionCalculator.calculateOutputResolution(3840, 2160)
        val output = r.width.toDouble() / r.height.toDouble()
        assert(Math.abs(source - output) < 0.01)
    }

    @Test
    fun `output dimensions are always even`() {
        val r = ResolutionCalculator.calculateOutputResolution(3841, 2161)
        assertEquals(0, r.width % 2)
        assertEquals(0, r.height % 2)
    }
}

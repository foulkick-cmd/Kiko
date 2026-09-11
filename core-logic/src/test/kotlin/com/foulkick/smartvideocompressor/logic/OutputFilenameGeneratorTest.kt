package com.foulkick.smartvideocompressor.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutputFilenameGeneratorTest {

    @Test
    fun `no collision uses the plain suffixed name`() {
        val name = OutputFilenameGenerator.generate("20240813_151522.mp4") { false }
        assertEquals("20240813_151522_1080.mp4", name)
    }

    @Test
    fun `first collision appends _1`() {
        val existing = setOf("20240813_151522_1080.mp4")
        val name = OutputFilenameGenerator.generate("20240813_151522.mp4") { it in existing }
        assertEquals("20240813_151522_1080_1.mp4", name)
    }

    @Test
    fun `multiple collisions increment until a free name is found`() {
        val existing = setOf(
            "20240813_151522_1080.mp4",
            "20240813_151522_1080_1.mp4",
            "20240813_151522_1080_2.mp4",
        )
        val name = OutputFilenameGenerator.generate("20240813_151522.mp4") { it in existing }
        assertEquals("20240813_151522_1080_3.mp4", name)
    }

    @Test
    fun `handles source name without extension`() {
        val name = OutputFilenameGenerator.generate("MyVideo") { false }
        assertEquals("MyVideo_1080.mp4", name)
    }

    @Test
    fun `never returns a name marked as existing`() {
        val existing = mutableSetOf("a_1080.mp4", "a_1080_1.mp4")
        val name = OutputFilenameGenerator.generate("a.mp4") { it in existing }
        assert(name !in existing)
    }
}

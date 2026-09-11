package com.foulkick.smartvideocompressor.logic

import kotlin.math.roundToInt

/**
 * Single source of truth for the app's bitrate strategy (VBR).
 *
 * targetBitrate = outputWidth * outputHeight * effectiveFps * TARGET_BPP
 *
 * Tune [TARGET_BPP] here only -- do not duplicate this constant elsewhere.
 */
object BitrateCalculator {

    /** Target bits-per-pixel-per-frame. Tune after real-world quality/size testing. */
    const val TARGET_BPP = 0.07

    private const val REFERENCE_WIDTH = 1920
    private const val REFERENCE_HEIGHT = 1080
    private const val REFERENCE_PIXELS = REFERENCE_WIDTH * REFERENCE_HEIGHT

    /** 1080p bitrate floor/ceiling; other resolutions scale proportionally by pixel count. */
    const val REFERENCE_MIN_BITRATE_BPS = 5_000_000
    const val REFERENCE_MAX_BITRATE_BPS = 12_000_000

    /** Absolute floor so tiny outputs never get an unreasonably small bitrate. */
    private const val ABSOLUTE_MIN_BITRATE_BPS = 700_000

    data class BitrateResult(
        val targetBitrateBps: Int,
        val minBitrateBps: Int,
        val maxBitrateBps: Int,
        val rawCalculatedBps: Long,
    )

    fun calculateTargetBitrate(outputWidth: Int, outputHeight: Int, effectiveFps: Double): BitrateResult {
        require(outputWidth > 0 && outputHeight > 0) { "Output dimensions must be positive" }
        require(effectiveFps > 0.0) { "effectiveFps must be positive" }

        val pixels = outputWidth.toLong() * outputHeight.toLong()
        val raw = (pixels * effectiveFps * TARGET_BPP).roundToInt().toLong()

        val resolutionScale = pixels.toDouble() / REFERENCE_PIXELS.toDouble()
        val minBitrate = maxOf(
            ABSOLUTE_MIN_BITRATE_BPS,
            (REFERENCE_MIN_BITRATE_BPS * resolutionScale).roundToInt(),
        )
        val maxBitrate = maxOf(
            minBitrate,
            (REFERENCE_MAX_BITRATE_BPS * resolutionScale).roundToInt(),
        )

        val target = raw.coerceIn(minBitrate.toLong(), maxBitrate.toLong()).toInt()

        return BitrateResult(
            targetBitrateBps = target,
            minBitrateBps = minBitrate,
            maxBitrateBps = maxBitrate,
            rawCalculatedBps = raw,
        )
    }
}

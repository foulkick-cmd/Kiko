package com.foulkick.smartvideocompressor.logic

/**
 * Determines the effective frame rate to use for bitrate calculation.
 *
 * For variable-frame-rate (VFR) sources, the nominal/container frame rate
 * (e.g. a "60 fps" tag) does not reflect the actual frame density of the
 * footage. We prefer frameCount / durationSeconds, which reflects how many
 * frames actually have to be encoded per second on average.
 */
object EffectiveFpsCalculator {

    const val MIN_FPS = 24.0
    const val MAX_FPS = 60.0

    /**
     * @param frameCount total decodable video frame count from
     *   MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, or null/invalid if unavailable.
     * @param durationSeconds container duration in seconds, must be > 0 to be usable.
     * @param nominalFrameRate best available fallback frame-rate metadata (e.g.
     *   MediaMetadataRetriever's frame rate, or the track format's frame rate), or null.
     */
    fun calculateEffectiveFps(
        frameCount: Int?,
        durationSeconds: Double?,
        nominalFrameRate: Double?,
    ): Double {
        val fromFrameCount = computeFromFrameCount(frameCount, durationSeconds)
        if (fromFrameCount != null) {
            return clamp(fromFrameCount)
        }

        val fallback = nominalFrameRate
        if (fallback != null && fallback.isFinite() && fallback > 0.0) {
            return clamp(fallback)
        }

        // No reliable metadata at all: use a safe, conservative default rather than failing.
        return MIN_FPS
    }

    private fun computeFromFrameCount(frameCount: Int?, durationSeconds: Double?): Double? {
        if (frameCount == null || frameCount <= 0) return null
        if (durationSeconds == null || !durationSeconds.isFinite() || durationSeconds <= 0.0) return null
        val fps = frameCount / durationSeconds
        if (!fps.isFinite() || fps <= 0.0) return null
        return fps
    }

    fun clamp(fps: Double): Double = fps.coerceIn(MIN_FPS, MAX_FPS)
}

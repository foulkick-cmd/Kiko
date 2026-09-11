package com.foulkick.smartvideocompressor.logic

/**
 * Computes the output resolution for compression: fit within 1920x1080
 * (in whichever orientation the source has), preserving aspect ratio,
 * never upscaling, and always producing even dimensions (required by
 * most hardware HEVC encoders, including the S23 Ultra's).
 */
object ResolutionCalculator {

    const val MAX_LONG_SIDE = 1920
    const val MAX_SHORT_SIDE = 1080

    data class Resolution(val width: Int, val height: Int)

    /**
     * [sourceWidth]/[sourceHeight] must already be the *display-oriented*
     * dimensions (i.e. rotation from track metadata already applied by the
     * caller), so a portrait 3840x2160-rotated-90 source is passed in as
     * 2160x3840.
     */
    fun calculateOutputResolution(sourceWidth: Int, sourceHeight: Int): Resolution {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }

        val isLandscapeOrSquare = sourceWidth >= sourceHeight
        val maxWidth = if (isLandscapeOrSquare) MAX_LONG_SIDE else MAX_SHORT_SIDE
        val maxHeight = if (isLandscapeOrSquare) MAX_SHORT_SIDE else MAX_LONG_SIDE

        // Never upscale: if source already fits within the bounds, keep it as-is.
        if (sourceWidth <= maxWidth && sourceHeight <= maxHeight) {
            return Resolution(evenFloor(sourceWidth), evenFloor(sourceHeight))
        }

        val widthScale = maxWidth.toDouble() / sourceWidth.toDouble()
        val heightScale = maxHeight.toDouble() / sourceHeight.toDouble()
        val scale = minOf(widthScale, heightScale)

        val targetWidth = evenFloor((sourceWidth * scale).toInt().coerceAtLeast(2))
        val targetHeight = evenFloor((sourceHeight * scale).toInt().coerceAtLeast(2))

        return Resolution(targetWidth, targetHeight)
    }

    /** Hardware encoders require even width/height; round down to the nearest even number. */
    private fun evenFloor(value: Int): Int = if (value % 2 == 0) value else value - 1
}

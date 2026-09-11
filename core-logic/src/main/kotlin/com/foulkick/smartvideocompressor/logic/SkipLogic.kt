package com.foulkick.smartvideocompressor.logic

/**
 * Deterministic "smart skip" rules. See spec section 12.
 *
 * Rule A is evaluated BEFORE compression (saves the work entirely).
 * Rule C is evaluated AFTER compression (post-hoc safety net).
 * Rule B is not a positive check -- it is the absence of a skip: sources
 * above 1080p are never skipped by Rule A regardless of their existing codec.
 */
object SkipLogic {

    /** Rule A: skip only if source already fits at/under 1080p AND is already efficiently encoded. */
    private const val ALREADY_COMPRESSED_MULTIPLIER = 1.3

    sealed class PreCompressionDecision {
        data object Proceed : PreCompressionDecision()
        data class SkipAlreadyCompressed(val reason: String = "Already sufficiently compressed") :
            PreCompressionDecision()
    }

    sealed class PostCompressionDecision {
        data object Keep : PostCompressionDecision()
        data class DiscardNotSmaller(val reason: String = "Skipped — output was not smaller") :
            PostCompressionDecision()
    }

    /**
     * Rule A / Rule B.
     *
     * @param sourceWidth/[sourceHeight] display-oriented source dimensions.
     * @param sourceBitrateBps measured or estimated source video bitrate.
     * @param calculatedTargetBitrateBps the target bitrate that would be used if we did compress
     *   (from [BitrateCalculator], computed against the *source* resolution capped at 1080p).
     */
    fun evaluatePreCompression(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceBitrateBps: Long,
        calculatedTargetBitrateBps: Long,
    ): PreCompressionDecision {
        val longSide = maxOf(sourceWidth, sourceHeight)
        val shortSide = minOf(sourceWidth, sourceHeight)
        val isAlreadyAtOrUnder1080p =
            longSide <= ResolutionCalculator.MAX_LONG_SIDE && shortSide <= ResolutionCalculator.MAX_SHORT_SIDE

        if (!isAlreadyAtOrUnder1080p) {
            // Rule B: always eligible for compression regardless of existing codec.
            return PreCompressionDecision.Proceed
        }

        val threshold = (calculatedTargetBitrateBps * ALREADY_COMPRESSED_MULTIPLIER)
        return if (sourceBitrateBps < threshold) {
            PreCompressionDecision.SkipAlreadyCompressed()
        } else {
            PreCompressionDecision.Proceed
        }
    }

    /** Rule C. */
    fun evaluatePostCompression(sourceSizeBytes: Long, outputSizeBytes: Long): PostCompressionDecision {
        return if (outputSizeBytes >= sourceSizeBytes) {
            PostCompressionDecision.DiscardNotSmaller()
        } else {
            PostCompressionDecision.Keep
        }
    }
}

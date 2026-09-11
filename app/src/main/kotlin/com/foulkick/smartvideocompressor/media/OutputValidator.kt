package com.foulkick.smartvideocompressor.media

import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Validates a freshly-produced output file per spec section 17. */
class OutputValidator {

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    private val extractorProbe = FileProbe()

    fun validate(
        outputFile: File,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedDurationSeconds: Double,
    ): ValidationResult {
        if (!outputFile.exists()) return ValidationResult.Invalid("Output file does not exist")
        if (!outputFile.canRead() || outputFile.length() <= 0L) {
            return ValidationResult.Invalid("Output file is not readable or is empty")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(outputFile.absolutePath)

            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (mime != "yes") return ValidationResult.Invalid("No video stream found in output")

            val codedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return ValidationResult.Invalid("Output has no readable width")
            val codedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return ValidationResult.Invalid("Output has no readable height")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            // METADATA_KEY_VIDEO_WIDTH/HEIGHT report the *coded* (pre-rotation) frame, same as for
            // source probing -- rotate the same way SourceProbe.displayWidth/Height does before
            // comparing, so a 90/270-rotated portrait output isn't reported as a dimension mismatch
            // just because its coded frame is stored sideways.
            val displayWidth = if (rotation % 180 == 0) codedWidth else codedHeight
            val displayHeight = if (rotation % 180 == 0) codedHeight else codedWidth

            if (displayWidth != expectedWidth || displayHeight != expectedHeight) {
                return ValidationResult.Invalid(
                    "Output dimensions ($displayWidth x $displayHeight) do not match expected ($expectedWidth x $expectedHeight)",
                )
            }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return ValidationResult.Invalid("Output has no readable duration")
            val durationSeconds = durationMs / 1000.0
            val diffRatio = if (expectedDurationSeconds > 0.0) {
                kotlin.math.abs(durationSeconds - expectedDurationSeconds) / expectedDurationSeconds
            } else {
                0.0
            }
            if (diffRatio > MAX_DURATION_DIFF_RATIO) {
                return ValidationResult.Invalid(
                    "Output duration (${"%.1f".format(durationSeconds)}s) differs from source " +
                        "(${"%.1f".format(expectedDurationSeconds)}s) by more than ${(MAX_DURATION_DIFF_RATIO * 100).toInt()}%",
                )
            }

            val codecMime = extractorProbe.videoMime(outputFile)
            if (codecMime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                return ValidationResult.Invalid("Output codec is not HEVC (found: ${codecMime ?: "unknown"})")
            }

            return ValidationResult.Valid
        } catch (e: Exception) {
            return ValidationResult.Invalid("Output could not be parsed: ${e.message}")
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val MAX_DURATION_DIFF_RATIO = 0.02
    }
}

private class FileProbe {
    fun videoMime(file: File): String? {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.getString(MediaFormat.KEY_MIME)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}

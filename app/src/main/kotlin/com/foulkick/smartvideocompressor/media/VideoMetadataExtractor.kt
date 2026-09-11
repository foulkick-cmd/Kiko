package com.foulkick.smartvideocompressor.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.foulkick.smartvideocompressor.model.SourceProbe

/**
 * Probes a source video's real characteristics using MediaMetadataRetriever/MediaExtractor,
 * so compression decisions (resolution target, bitrate, skip logic, HDR handling) are based on
 * the actual file rather than assumptions.
 */
class VideoMetadataExtractor(private val context: Context) {

    class UnreadableSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun probe(uri: Uri): SourceProbe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val width = retriever.extractMetadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?: throw UnreadableSourceException("No video width metadata")
            val height = retriever.extractMetadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?: throw UnreadableSourceException("No video height metadata")
            val rotation = retriever.extractMetadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            val durationMs = retriever.extractMetadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: throw UnreadableSourceException("No duration metadata")
            val durationSeconds = durationMs / 1000.0
            if (durationSeconds <= 0.0) throw UnreadableSourceException("Invalid duration")

            val frameCount = retriever.extractMetadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.takeIf { it > 0 }

            val trackInfo = probeVideoTrack(uri)

            // MediaExtractor's track format often omits KEY_FRAME_RATE for camera-recorded
            // (especially VFR) MP4s; METADATA_KEY_CAPTURE_FRAMERATE is a second, independent
            // source of the nominal capture rate before we give up and let
            // EffectiveFpsCalculator fall back to its own conservative default.
            val nominalFrameRate = trackInfo.frameRate
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }

            val bitrate = trackInfo.bitrateBps ?: estimateBitrateFromFileSize(uri, durationSeconds)

            return SourceProbe(
                width = width,
                height = height,
                rotationDegrees = rotation,
                durationSeconds = durationSeconds,
                bitrateBps = bitrate,
                frameCount = frameCount,
                nominalFrameRate = nominalFrameRate,
                isHevc = trackInfo.isHevc,
                isHdr = trackInfo.isHdr,
                hasAudio = trackInfo.hasAudio,
            )
        } catch (e: UnreadableSourceException) {
            throw e
        } catch (e: Exception) {
            throw UnreadableSourceException("Could not read source video: ${e.message}", e)
        } finally {
            retriever.release()
        }
    }

    fun sizeBytes(uri: Uri): Long {
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    private data class TrackInfo(
        val isHevc: Boolean,
        val isHdr: Boolean,
        val hasAudio: Boolean,
        val bitrateBps: Long?,
        val frameRate: Double?,
    )

    private fun probeVideoTrack(uri: Uri): TrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var isHevc = false
            var isHdr = false
            var hasAudio = false
            var bitrateBps: Long? = null
            var frameRate: Double? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> {
                        isHevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
                        isHdr = isHdrTransfer(format)
                        bitrateBps = format.getIntSafe(MediaFormat.KEY_BIT_RATE)?.toLong()
                        frameRate = format.getIntSafe(MediaFormat.KEY_FRAME_RATE)?.toDouble()
                    }
                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
            return TrackInfo(isHevc, isHdr, hasAudio, bitrateBps, frameRate)
        } finally {
            extractor.release()
        }
    }

    private fun isHdrTransfer(format: MediaFormat): Boolean {
        val transfer = format.getIntSafe(MediaFormat.KEY_COLOR_TRANSFER) ?: return false
        return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
    }

    private fun estimateBitrateFromFileSize(uri: Uri, durationSeconds: Double): Long {
        val size = sizeBytes(uri)
        if (size <= 0L || durationSeconds <= 0.0) return 0L
        return ((size * 8) / durationSeconds).toLong()
    }
}

private fun MediaFormat.getIntSafe(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private fun MediaMetadataRetriever.extractMetadataInt(key: Int): Int? =
    extractMetadata(key)?.toIntOrNull()

private fun MediaMetadataRetriever.extractMetadataLong(key: Int): Long? =
    extractMetadata(key)?.toLongOrNull()

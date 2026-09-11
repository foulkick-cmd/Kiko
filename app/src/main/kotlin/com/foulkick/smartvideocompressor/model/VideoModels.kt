package com.foulkick.smartvideocompressor.model

import android.net.Uri

/** A video selected by the user via the Photo Picker, before any processing. */
data class SelectedVideo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

enum class CompressionStatus {
    QUEUED,
    PROBING,
    COMPRESSING,
    VALIDATING,
    DONE,
    SKIPPED,
    FAILED,
    CANCELLED,
}

/** Live/final state for one item in the batch, as shown in the UI and the notification. */
data class VideoJobState(
    val video: SelectedVideo,
    val status: CompressionStatus = CompressionStatus.QUEUED,
    val progressPercent: Int = 0,
    val elapsedMillis: Long = 0L,
    val outputSizeBytes: Long? = null,
    val outputDisplayName: String? = null,
    val message: String? = null,
) {
    val mbSaved: Double?
        get() = outputSizeBytes?.let { (video.sizeBytes - it) / (1024.0 * 1024.0) }

    val percentSaved: Double?
        get() = outputSizeBytes?.let {
            if (video.sizeBytes <= 0L) 0.0 else (1.0 - it.toDouble() / video.sizeBytes) * 100.0
        }
}

/** Source properties probed from the container/track before deciding how to compress. */
data class SourceProbe(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationSeconds: Double,
    val bitrateBps: Long,
    val frameCount: Int?,
    val nominalFrameRate: Double?,
    val isHevc: Boolean,
    val isHdr: Boolean,
    val hasAudio: Boolean,
) {
    /** Display-oriented dimensions, i.e. what the video actually looks like when played back. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width
}

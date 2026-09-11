package com.foulkick.smartvideocompressor.compression

import android.content.Context
import android.net.Uri
import com.foulkick.smartvideocompressor.logic.SkipLogic
import com.foulkick.smartvideocompressor.media.OutputPublisher
import com.foulkick.smartvideocompressor.media.OutputValidator
import com.foulkick.smartvideocompressor.media.SourceLocationResolver
import com.foulkick.smartvideocompressor.media.VideoCompressor
import com.foulkick.smartvideocompressor.media.VideoMetadataExtractor
import com.foulkick.smartvideocompressor.model.CompressionStatus
import com.foulkick.smartvideocompressor.model.SelectedVideo
import com.foulkick.smartvideocompressor.model.VideoJobState
import java.io.File
import java.util.UUID

/** Runs the full per-video pipeline: probe -> skip check -> compress -> validate -> publish. */
class CompressionEngine(context: Context) {

    private val appContext = context.applicationContext
    private val metadataExtractor = VideoMetadataExtractor(appContext)
    private val compressor = VideoCompressor(appContext)
    private val validator = OutputValidator()
    private val publisher = OutputPublisher(appContext)
    private val locationResolver = SourceLocationResolver(appContext)

    suspend fun process(
        video: SelectedVideo,
        limitTo30Fps: Boolean,
        onUpdate: (VideoJobState) -> Unit,
        isCancelled: () -> Boolean,
    ): VideoJobState {
        var state = VideoJobState(video = video, status = CompressionStatus.PROBING)
        onUpdate(state)

        val probe = try {
            metadataExtractor.probe(video.uri)
        } catch (e: VideoMetadataExtractor.UnreadableSourceException) {
            return state.copy(status = CompressionStatus.FAILED, message = e.message ?: "Invalid source file")
                .also(onUpdate)
        }

        val plan = compressor.buildPlan(probe)
        val preDecision = SkipLogic.evaluatePreCompression(
            sourceWidth = probe.displayWidth,
            sourceHeight = probe.displayHeight,
            sourceBitrateBps = probe.bitrateBps,
            calculatedTargetBitrateBps = plan.targetBitrateBps.toLong(),
        )
        if (preDecision is SkipLogic.PreCompressionDecision.SkipAlreadyCompressed) {
            return state.copy(status = CompressionStatus.SKIPPED, message = preDecision.reason).also(onUpdate)
        }

        if (isCancelled()) return state.copy(status = CompressionStatus.CANCELLED).also(onUpdate)

        state = state.copy(status = CompressionStatus.COMPRESSING)
        onUpdate(state)

        val tempOutput = File(appContext.cacheDir, "compress_${UUID.randomUUID()}.mp4")
        val outcome = try {
            compressor.compress(
                sourceUri = video.uri,
                outputFile = tempOutput,
                probe = probe,
                plan = plan,
                limitTo30Fps = limitTo30Fps,
                onProgress = { percent ->
                    onUpdate(state.copy(progressPercent = percent))
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            tempOutput.delete()
            return state.copy(status = CompressionStatus.CANCELLED).also(onUpdate)
        }

        return when (outcome) {
            is VideoCompressor.Outcome.Failure -> {
                tempOutput.delete()
                state.copy(status = CompressionStatus.FAILED, message = outcome.reason).also(onUpdate)
            }
            is VideoCompressor.Outcome.Success -> {
                finishSuccessfulExport(state, video, probe, plan, tempOutput, onUpdate)
            }
        }
    }

    private fun finishSuccessfulExport(
        state: VideoJobState,
        video: SelectedVideo,
        probe: com.foulkick.smartvideocompressor.model.SourceProbe,
        plan: VideoCompressor.Plan,
        tempOutput: File,
        onUpdate: (VideoJobState) -> Unit,
    ): VideoJobState {
        val postDecision = SkipLogic.evaluatePostCompression(video.sizeBytes, tempOutput.length())
        if (postDecision is SkipLogic.PostCompressionDecision.DiscardNotSmaller) {
            tempOutput.delete()
            return state.copy(status = CompressionStatus.SKIPPED, message = postDecision.reason).also(onUpdate)
        }

        val validation = validator.validate(
            outputFile = tempOutput,
            expectedWidth = plan.outputWidth,
            expectedHeight = plan.outputHeight,
            expectedDurationSeconds = probe.durationSeconds,
        )
        if (validation is OutputValidator.ValidationResult.Invalid) {
            tempOutput.delete()
            return state.copy(status = CompressionStatus.FAILED, message = "Output validation failed: ${validation.reason}")
                .also(onUpdate)
        }

        val location = locationResolver.resolve(video.uri)
        val originalName = location.displayName ?: video.displayName
        val published = try {
            publisher.publish(
                tempFile = tempOutput,
                originalDisplayName = originalName,
                sourceRelativePath = location.relativePath,
                sourceDateTakenMs = location.dateTakenMs,
            )
        } catch (e: Exception) {
            tempOutput.delete()
            return state.copy(status = CompressionStatus.FAILED, message = "Could not save output: ${e.message}")
                .also(onUpdate)
        } finally {
            tempOutput.delete()
        }

        return state.copy(
            status = CompressionStatus.DONE,
            progressPercent = 100,
            outputSizeBytes = published.sizeBytes,
            outputDisplayName = published.displayName,
        ).also(onUpdate)
    }

    fun probeSizeBytes(uri: Uri): Long = metadataExtractor.sizeBytes(uri)
}

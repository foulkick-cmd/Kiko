package com.foulkick.smartvideocompressor.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.foulkick.smartvideocompressor.logic.BitrateCalculator
import com.foulkick.smartvideocompressor.logic.EffectiveFpsCalculator
import com.foulkick.smartvideocompressor.logic.ResolutionCalculator
import com.foulkick.smartvideocompressor.model.SourceProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps AndroidX Media3 Transformer to drive the device's hardware HEVC encoder.
 * No software encoding path, no FFmpeg: Transformer's DefaultEncoderFactory always
 * prefers a MediaCodec hardware encoder on the S23 Ultra for H.265 output.
 */
class VideoCompressor(private val context: Context) {

    sealed class Outcome {
        data class Success(val outputFile: File) : Outcome()
        data class Failure(val reason: String) : Outcome()
    }

    data class Plan(
        val outputWidth: Int,
        val outputHeight: Int,
        val effectiveFps: Double,
        val targetBitrateBps: Int,
    )

    fun buildPlan(probe: SourceProbe): Plan {
        val resolution = ResolutionCalculator.calculateOutputResolution(probe.displayWidth, probe.displayHeight)
        val effectiveFps = EffectiveFpsCalculator.calculateEffectiveFps(
            frameCount = probe.frameCount,
            durationSeconds = probe.durationSeconds,
            nominalFrameRate = probe.nominalFrameRate,
        )
        val bitrate = BitrateCalculator.calculateTargetBitrate(resolution.width, resolution.height, effectiveFps)
        return Plan(resolution.width, resolution.height, effectiveFps, bitrate.targetBitrateBps)
    }

    /**
     * @param limitTo30Fps user setting; only actually drops frames if the source's effective
     *   fps exceeds 30 -- never upsamples a slower source.
     */
    suspend fun compress(
        sourceUri: Uri,
        outputFile: File,
        probe: SourceProbe,
        plan: Plan,
        limitTo30Fps: Boolean,
        onProgress: (Int) -> Unit,
    ): Outcome = coroutineScope {
        val videoEffects = buildList {
            add(
                Presentation.createForWidthAndHeight(
                    plan.outputWidth,
                    plan.outputHeight,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
            if (limitTo30Fps && plan.effectiveFps > 30.0) {
                add(FrameDropEffect.createDefaultFrameDropEffect(30f))
            }
        }

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ videoEffects))
            .build()

        // Preserve HDR characteristics when the source is HDR; if the device/pipeline cannot
        // honor this, export fails with an ExportException rather than silently tone-mapping.
        val composition = Composition.Builder(EditedMediaItemSequence(listOf(editedMediaItem)))
            .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(plan.targetBitrateBps)
                    .setBitrateMode(VideoEncoderSettings.BITRATE_MODE_VBR)
                    .build(),
            )
            // If the hardware encoder rejects the exact requested config, fall back to the
            // closest supported configuration instead of failing the whole export.
            .setEnableFallback(true)
            .build()

        outputFile.parentFile?.mkdirs()

        try {
            runExport(composition, encoderFactory, outputFile, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            outputFile.delete()
            Outcome.Failure(describeFailure(e))
        }
    }

    private suspend fun runExport(
        composition: Composition,
        encoderFactory: DefaultEncoderFactory,
        outputFile: File,
        onProgress: (Int) -> Unit,
    ): Outcome = coroutineScope {
        val result = CompletableDeferred<Outcome>()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                result.complete(Outcome.Success(outputFile))
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                outputFile.delete()
                result.complete(Outcome.Failure(describeFailure(exportException)))
            }
        }

        val transformer = withContext(Dispatchers.Main) {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setEncoderFactory(encoderFactory)
                .addListener(listener)
                .build()
                .also { it.start(composition, outputFile.absolutePath) }
        }

        val progressJob = launch(Dispatchers.Main) {
            val progressHolder = ProgressHolder()
            while (isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress)
                }
                delay(500)
            }
        }

        try {
            result.await()
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { transformer.cancel() }
            outputFile.delete()
            throw e
        } finally {
            progressJob.cancel()
        }
    }

    private fun describeFailure(e: Throwable): String =
        "Transformation failed: ${e.message ?: e::class.simpleName ?: "unknown error"}"
}

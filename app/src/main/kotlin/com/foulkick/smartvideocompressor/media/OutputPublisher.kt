package com.foulkick.smartvideocompressor.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.foulkick.smartvideocompressor.logic.OutputFilenameGenerator
import java.io.File

/**
 * Publishes a validated, app-cache-local compressed file into MediaStore, in the same
 * folder (RELATIVE_PATH) as the original when that can be resolved, using the standard
 * scoped-storage insert flow (Transformer itself can only write to a direct filesystem
 * path, which under scoped storage means our own cache directory -- this class is the
 * step that makes the result visible in the user's Gallery/Movies location).
 */
class OutputPublisher(private val context: Context) {

    data class Published(val uri: Uri, val displayName: String, val sizeBytes: Long)

    /**
     * @param sourceRelativePath e.g. "Movies/" or "DCIM/Camera/" as resolved from the source's
     *   own MediaStore row; null if it could not be resolved (falls back to Movies/).
     */
    fun publish(
        tempFile: File,
        originalDisplayName: String,
        sourceRelativePath: String?,
        sourceDateTakenMs: Long?,
    ): Published {
        val relativePath = sourceRelativePath ?: (Environment_MOVIES_RELATIVE_PATH)
        val finalName = OutputFilenameGenerator.generate(originalDisplayName) { candidate ->
            existsInMediaStore(candidate, relativePath)
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            if (sourceDateTakenMs != null) {
                put(MediaStore.Video.Media.DATE_TAKEN, sourceDateTakenMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: error("MediaStore refused to create an entry for the output video")

        resolver.openOutputStream(itemUri)?.use { out ->
            tempFile.inputStream().use { input -> input.copyTo(out) }
        } ?: error("Could not open an output stream for the new MediaStore entry")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(itemUri, doneValues, null, null)
        }

        val finalSize = resolver.openAssetFileDescriptor(itemUri, "r")?.use { it.length } ?: tempFile.length()
        return Published(itemUri, finalName, finalSize)
    }

    fun deletePublished(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun existsInMediaStore(displayName: String, relativePath: String): Boolean {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        context.contentResolver.query(collection, projection, selection, arrayOf(displayName, relativePath), null)
            ?.use { cursor -> return cursor.count > 0 }
        return false
    }

    companion object {
        private const val Environment_MOVIES_RELATIVE_PATH = "Movies/"
    }
}

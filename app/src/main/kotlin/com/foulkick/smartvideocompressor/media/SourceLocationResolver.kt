package com.foulkick.smartvideocompressor.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Resolves a Photo-Picker-selected video's real MediaStore row (folder, date taken) so the
 * compressed output can be saved next to the original. This only works when the app holds
 * READ_MEDIA_VIDEO: without it, Android intentionally restricts Photo Picker URIs to a minimal
 * column set for privacy, and we fall back to a default Movies/ location.
 */
class SourceLocationResolver(private val context: Context) {

    data class SourceLocation(val relativePath: String?, val dateTakenMs: Long?, val displayName: String?)

    fun resolve(uri: Uri): SourceLocation {
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return SourceLocation(null, null, null)
                val relativePathIdx = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                val dateTakenIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                SourceLocation(
                    relativePath = if (relativePathIdx >= 0) cursor.getString(relativePathIdx) else null,
                    dateTakenMs = if (dateTakenIdx >= 0 && !cursor.isNull(dateTakenIdx)) cursor.getLong(dateTakenIdx) else null,
                    displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null,
                )
            } ?: SourceLocation(null, null, null)
        }.getOrDefault(SourceLocation(null, null, null))
    }

    fun displayName(uri: Uri): String {
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        val name = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        return name ?: (uri.lastPathSegment ?: "video.mp4")
    }
}

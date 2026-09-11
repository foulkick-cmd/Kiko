package com.foulkick.smartvideocompressor

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.foulkick.smartvideocompressor.compression.CompressionService
import com.foulkick.smartvideocompressor.model.SelectedVideo
import com.foulkick.smartvideocompressor.ui.CompressorScreen
import com.foulkick.smartvideocompressor.ui.CompressorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CompressorViewModel by viewModels()

    private var compressionService: CompressionService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as CompressionService.LocalBinder
            compressionService = binder.getService()
            isBound = true
            viewModel.observeJobs(binder.getService().jobs)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            compressionService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashReporter.collectReport(this)?.let { viewModel.setLastCrash(it) }

        setContent {
            val pickVideosLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_VIDEOS),
            ) { uris ->
                if (uris.isNotEmpty()) {
                    viewModel.setSelectedVideos(uris.map { toSelectedVideo(it) })
                }
            }

            // The Photo Picker only surfaces MediaStore-indexed Gallery items; a video sitting in
            // Downloads, a WhatsApp folder, or anywhere else the Gallery doesn't index is invisible
            // to it. This uses the system file picker (Storage Access Framework) instead, which can
            // reach any folder/provider without a broad storage permission.
            val browseFilesLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments(),
            ) { uris ->
                if (uris.isNotEmpty()) {
                    uris.forEach { uri ->
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    viewModel.setSelectedVideos(uris.map { toSelectedVideo(it) })
                }
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { /* no-op: notification is a nice-to-have, not required for compression to run */ }

            val mediaPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { /* granted or not, CompressionEngine falls back to a default save location */ }

            CompressorScreen(
                viewModel = viewModel,
                onPickVideos = {
                    pickVideosLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onBrowseFiles = { browseFilesLauncher.launch(arrayOf("video/*")) },
                onRequestPermissions = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        mediaPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                    }
                },
                onStartCompression = { videos, limitTo30Fps ->
                    startCompression(videos, limitTo30Fps)
                },
                onCancelCompression = { compressionService?.cancel() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, CompressionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun startCompression(videos: List<SelectedVideo>, limitTo30Fps: Boolean) {
        viewModel.setRunning(true)
        // The service reads the batch from the intent's own extras in onStartCommand and starts
        // its foreground notification synchronously there -- it never waits on a service-bound
        // call to begin, which would risk missing Android 14's foreground-service start deadline.
        val intent = CompressionService.buildStartIntent(this, videos.map { it.uri }, limitTo30Fps)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun toSelectedVideo(uri: Uri): SelectedVideo {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "video.mp4"
        return SelectedVideo(uri = uri, displayName = displayName, sizeBytes = querySize(uri))
    }

    // OpenableColumns (not MediaStore.*) is the universal projection every content provider that
    // supports openInputStream/openFileDescriptor is required to honor -- MediaStore's own
    // DISPLAY_NAME/SIZE columns resolve to the exact same underlying keys, so this one query path
    // works identically for Photo Picker (MediaStore) and file-picker (arbitrary DocumentsProvider)
    // results.
    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        val projection = arrayOf(OpenableColumns.SIZE)
        val fromQuery = contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
            } else {
                null
            }
        }
        return fromQuery ?: (contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L)
    }

    companion object {
        private const val MAX_VIDEOS = 50
    }
}

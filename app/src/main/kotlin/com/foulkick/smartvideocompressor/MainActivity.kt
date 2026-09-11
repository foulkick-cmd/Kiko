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
import android.provider.MediaStore
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
                    val videos = uris.map { uri ->
                        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "video.mp4"
                        val size = querySize(uri)
                        SelectedVideo(uri = uri, displayName = displayName, sizeBytes = size)
                    }
                    viewModel.setSelectedVideos(videos)
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

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        return contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }

    companion object {
        private const val MAX_VIDEOS = 50
    }
}

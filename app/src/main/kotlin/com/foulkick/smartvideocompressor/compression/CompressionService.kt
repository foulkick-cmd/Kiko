package com.foulkick.smartvideocompressor.compression

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.foulkick.smartvideocompressor.MainActivity
import com.foulkick.smartvideocompressor.R
import com.foulkick.smartvideocompressor.SmartVideoCompressorApp
import com.foulkick.smartvideocompressor.model.CompressionStatus
import com.foulkick.smartvideocompressor.model.VideoJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the compression batch sequentially (one video at a time, by design -- see spec section
 * 15) as a foreground service of type mediaProcessing, so it survives the screen turning off
 * or the app going to background, with a persistent progress notification.
 *
 * The batch is started from [onStartCommand] (via [ACTION_START]'s intent extras), not from a
 * bound-service call: Android 14 enforces that a service started with
 * Context.startForegroundService() must call startForeground() almost immediately, and that
 * call can only happen reliably from onStartCommand -- waiting for a client to bind first (which
 * can race, especially on a cold start) risks a ForegroundServiceDidNotStartInTimeException.
 * Binding ([onBind]) is only used to observe [jobs] and to issue [cancel].
 */
class CompressionService : LifecycleService() {

    private val binder = LocalBinder()
    private lateinit var engine: CompressionEngine

    private val _jobs = MutableStateFlow<List<VideoJobState>>(emptyList())
    val jobs: StateFlow<List<VideoJobState>> = _jobs.asStateFlow()

    @Volatile private var cancelRequested: Boolean = false
    private var batchJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): CompressionService = this@CompressionService
    }

    override fun onCreate() {
        super.onCreate()
        engine = CompressionEngine(this)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL -> cancel()
            ACTION_START -> {
                val uris = intent.getParcelableArrayListExtraCompat<Uri>(EXTRA_URIS)
                val limitTo30Fps = intent.getBooleanExtra(EXTRA_LIMIT_30FPS, false)
                if (uris != null && uris.isNotEmpty()) {
                    start(uris, limitTo30Fps)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Must be called synchronously from [onStartCommand] -- see class doc. */
    private fun start(uris: List<Uri>, limitTo30Fps: Boolean) {
        if (batchJob?.isActive == true) return
        cancelRequested = false

        // Promote to foreground immediately, before any suspending work, so the OS-mandated
        // start deadline is always met regardless of how long metadata resolution takes.
        //
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING only exists on Android 15+ (API 35). Passing
        // it on Android 14 (API 34) -- the S23 Ultra's spec target -- makes startForeground()
        // throw, which crashed the app the moment "Compress" was tapped. Fall back to dataSync
        // there; both types are declared in the manifest.
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Call the platform API directly, NOT ServiceCompat.startForeground: androidx.core 1.13's
        // shim masks the type against a pre-Android-15 allowlist, which strips mediaProcessing
        // down to "none" -- and Android 15/16 then reject the start with
        // InvalidForegroundServiceTypeException ("Starting FGS with type none ... prohibited").
        // Confirmed from a real crash report on a realme RMX5090 / Android 16. The 3-arg
        // startForeground exists since API 29, so it is always available at minSdk 33.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(currentFileName = null, currentPercent = 0, overallDone = 0, overallTotal = uris.size),
            foregroundServiceType,
        )

        batchJob = lifecycleScope.launch {
            val videos = engine.resolveSelectedVideos(uris)
            _jobs.value = videos.map { VideoJobState(video = it) }

            for ((index, video) in videos.withIndex()) {
                if (cancelRequested) {
                    markRemaining(from = index, status = CompressionStatus.CANCELLED)
                    break
                }
                val startTime = System.currentTimeMillis()
                val onUpdate: (VideoJobState) -> Unit = { updated ->
                    val withElapsed = updated.copy(elapsedMillis = System.currentTimeMillis() - startTime)
                    updateJob(index, withElapsed)
                    updateNotification(withElapsed, index, videos.size)
                }
                // lifecycleScope has no exception handler: anything the engine doesn't catch would
                // otherwise take down the whole process instead of failing just this one file.
                try {
                    engine.process(
                        video = video,
                        limitTo30Fps = limitTo30Fps,
                        onUpdate = onUpdate,
                        isCancelled = { cancelRequested },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Unexpected error while processing ${video.displayName}", e)
                    onUpdate(
                        VideoJobState(
                            video = video,
                            status = CompressionStatus.FAILED,
                            message = "Unexpected error: ${e::class.simpleName}: ${e.message}",
                        ),
                    )
                }
            }
            ServiceCompat.stopForeground(this@CompressionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun cancel() {
        cancelRequested = true
        batchJob?.cancel()
    }

    private fun updateJob(index: Int, state: VideoJobState) {
        _jobs.update { current ->
            current.toMutableList().also { it[index] = state }
        }
    }

    private fun markRemaining(from: Int, status: CompressionStatus) {
        _jobs.update { current ->
            current.mapIndexed { i, job -> if (i >= from) job.copy(status = status) else job }
        }
    }

    private fun updateNotification(current: VideoJobState, index: Int, total: Int) {
        val notification = buildNotification(
            currentFileName = current.video.displayName,
            currentPercent = current.progressPercent,
            overallDone = index,
            overallTotal = total,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        currentFileName: String?,
        currentPercent: Int,
        overallDone: Int,
        overallTotal: Int,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SmartVideoCompressorApp.COMPRESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(currentFileName ?: getString(R.string.app_name))
            .setContentText("Video ${overallDone + 1} of $overallTotal — $currentPercent%")
            .setProgress(100, currentPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    companion object {
        private const val TAG = "CompressionService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.foulkick.smartvideocompressor.action.START"
        const val ACTION_CANCEL = "com.foulkick.smartvideocompressor.action.CANCEL"
        const val EXTRA_URIS = "com.foulkick.smartvideocompressor.extra.URIS"
        const val EXTRA_LIMIT_30FPS = "com.foulkick.smartvideocompressor.extra.LIMIT_30FPS"

        fun buildStartIntent(context: Context, uris: List<Uri>, limitTo30Fps: Boolean): Intent {
            return Intent(context, CompressionService::class.java)
                .setAction(ACTION_START)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putExtra(EXTRA_LIMIT_30FPS, limitTo30Fps)
        }
    }
}

// minSdk is 33 (Tiramisu), so the typed overload is always available -- no legacy branch needed.
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<T>? =
    getParcelableArrayListExtra(name, T::class.java)

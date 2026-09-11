package com.foulkick.smartvideocompressor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.foulkick.smartvideocompressor.BuildConfig
import com.foulkick.smartvideocompressor.model.CompressionStatus
import com.foulkick.smartvideocompressor.model.SelectedVideo
import com.foulkick.smartvideocompressor.model.VideoJobState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorScreen(
    viewModel: CompressorViewModel,
    onPickVideos: () -> Unit,
    onBrowseFiles: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartCompression: (List<SelectedVideo>, Boolean) -> Unit,
    onCancelCompression: () -> Unit,
) {
    val selectedVideos by viewModel.selectedVideos.collectAsState()
    val limitTo30Fps by viewModel.limitTo30Fps.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val lastCrash by viewModel.lastCrash.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    LaunchedEffect(Unit) { onRequestPermissions() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Smart Video Compressor · build ${BuildConfig.GIT_SHA}") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            lastCrash?.let { report ->
                LastCrashCard(report = report, onDismiss = { viewModel.setLastCrash(null) })
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Limit to 30 FPS", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = limitTo30Fps, onCheckedChange = { viewModel.setLimitTo30Fps(it) }, enabled = !isRunning)
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickVideos, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                    Text("Select Videos")
                }
                Button(onClick = onBrowseFiles, enabled = !isRunning, modifier = Modifier.weight(1f)) {
                    Text("Browse Files")
                }
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))

            val orderedSelection = remember(selectedVideos, sortOrder) { sortVideos(selectedVideos, sortOrder) }

            if (selectedVideos.isNotEmpty() && jobs.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${selectedVideos.size} video(s) selected", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { viewModel.cycleSortOrder() }) {
                        Text(
                            when (sortOrder) {
                                SortOrder.SELECTION -> "Sort: default"
                                SortOrder.SIZE_DESCENDING -> "Sort: largest first"
                                SortOrder.SIZE_ASCENDING -> "Sort: smallest first"
                            },
                        )
                    }
                }
                Button(
                    onClick = { onStartCompression(orderedSelection, limitTo30Fps) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Compress")
                }
            }

            if (isRunning) {
                Button(onClick = onCancelCompression, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }

            val displayList = if (jobs.isNotEmpty()) jobs else orderedSelection.map { VideoJobState(video = it) }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayList) { job -> VideoJobRow(job) }
            }
        }
    }
}

@Composable
private fun VideoJobRow(job: VideoJobState) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(job.video.displayName, style = MaterialTheme.typography.titleMedium)
            Text("Original: ${formatMb(job.video.sizeBytes)}", style = MaterialTheme.typography.bodySmall)

            when (job.status) {
                CompressionStatus.QUEUED -> Text("Queued")
                CompressionStatus.PROBING -> Text("Reading video info…")
                CompressionStatus.COMPRESSING -> {
                    Text("Compressing… ${job.progressPercent}% · ${formatElapsed(job.elapsedMillis)}")
                    LinearProgressIndicator(
                        progress = { job.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CompressionStatus.VALIDATING -> Text("Validating output… · ${formatElapsed(job.elapsedMillis)}")
                CompressionStatus.DONE -> {
                    val original = formatMb(job.video.sizeBytes)
                    val output = job.outputSizeBytes?.let { formatMb(it) } ?: "?"
                    val percent = job.percentSaved?.let { "%.1f".format(it) } ?: "?"
                    Text("$original → $output")
                    Text("$percent% saved · ${formatElapsed(job.elapsedMillis)}")
                }
                CompressionStatus.SKIPPED -> Text("Skipped — ${job.message}")
                CompressionStatus.FAILED -> Text("Failed — ${job.message}")
                CompressionStatus.CANCELLED -> Text("Cancelled")
            }
        }
    }
}

/** Shows the previous run's uncaught-exception report so it can be copied out without adb. */
@Composable
private fun LastCrashCard(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("The app crashed last time. Copy this report and send it:", style = MaterialTheme.typography.titleMedium)
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
                Button(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun sortVideos(videos: List<SelectedVideo>, order: SortOrder): List<SelectedVideo> = when (order) {
    SortOrder.SELECTION -> videos
    SortOrder.SIZE_DESCENDING -> videos.sortedByDescending { it.sizeBytes }
    SortOrder.SIZE_ASCENDING -> videos.sortedBy { it.sizeBytes }
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

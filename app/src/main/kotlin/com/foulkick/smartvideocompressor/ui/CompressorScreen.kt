package com.foulkick.smartvideocompressor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foulkick.smartvideocompressor.model.CompressionStatus
import com.foulkick.smartvideocompressor.model.SelectedVideo
import com.foulkick.smartvideocompressor.model.VideoJobState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorScreen(
    viewModel: CompressorViewModel,
    onPickVideos: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartCompression: (List<SelectedVideo>, Boolean) -> Unit,
    onCancelCompression: () -> Unit,
) {
    val selectedVideos by viewModel.selectedVideos.collectAsState()
    val limitTo30Fps by viewModel.limitTo30Fps.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    LaunchedEffect(Unit) { onRequestPermissions() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Smart Video Compressor") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Limit to 30 FPS", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = limitTo30Fps, onCheckedChange = { viewModel.setLimitTo30Fps(it) }, enabled = !isRunning)
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = onPickVideos, enabled = !isRunning, modifier = Modifier.fillMaxWidth()) {
                Text("Select Videos")
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))

            if (selectedVideos.isNotEmpty() && jobs.isEmpty()) {
                Text("${selectedVideos.size} video(s) selected", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { onStartCompression(selectedVideos, limitTo30Fps) },
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

            val displayList = if (jobs.isNotEmpty()) jobs else selectedVideos.map { VideoJobState(video = it) }

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

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

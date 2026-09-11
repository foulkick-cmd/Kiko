package com.foulkick.smartvideocompressor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foulkick.smartvideocompressor.model.SelectedVideo
import com.foulkick.smartvideocompressor.model.VideoJobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompressorViewModel : ViewModel() {

    private val _selectedVideos = MutableStateFlow<List<SelectedVideo>>(emptyList())
    val selectedVideos: StateFlow<List<SelectedVideo>> = _selectedVideos.asStateFlow()

    private val _limitTo30Fps = MutableStateFlow(false)
    val limitTo30Fps: StateFlow<Boolean> = _limitTo30Fps.asStateFlow()

    private val _jobs = MutableStateFlow<List<VideoJobState>>(emptyList())
    val jobs: StateFlow<List<VideoJobState>> = _jobs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun setSelectedVideos(videos: List<SelectedVideo>) {
        _selectedVideos.value = videos
    }

    fun setLimitTo30Fps(enabled: Boolean) {
        _limitTo30Fps.value = enabled
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun observeJobs(flow: kotlinx.coroutines.flow.StateFlow<List<VideoJobState>>) {
        viewModelScope.launch {
            flow.collect { updated ->
                _jobs.value = updated
                if (updated.isNotEmpty() && updated.all { it.status.isTerminal() }) {
                    _isRunning.value = false
                }
            }
        }
    }

    private fun com.foulkick.smartvideocompressor.model.CompressionStatus.isTerminal(): Boolean {
        return this == com.foulkick.smartvideocompressor.model.CompressionStatus.DONE ||
            this == com.foulkick.smartvideocompressor.model.CompressionStatus.SKIPPED ||
            this == com.foulkick.smartvideocompressor.model.CompressionStatus.FAILED ||
            this == com.foulkick.smartvideocompressor.model.CompressionStatus.CANCELLED
    }

    fun reset() {
        _selectedVideos.value = emptyList()
        _jobs.value = emptyList()
        _isRunning.value = false
    }
}

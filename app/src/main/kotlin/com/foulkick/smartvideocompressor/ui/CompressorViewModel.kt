package com.foulkick.smartvideocompressor.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foulkick.smartvideocompressor.model.CompressionStatus
import com.foulkick.smartvideocompressor.model.SelectedVideo
import com.foulkick.smartvideocompressor.model.VideoJobState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SortOrder { SELECTION, SIZE_DESCENDING, SIZE_ASCENDING }

class CompressorViewModel : ViewModel() {

    private val _selectedVideos = MutableStateFlow<List<SelectedVideo>>(emptyList())
    val selectedVideos: StateFlow<List<SelectedVideo>> = _selectedVideos.asStateFlow()

    private val _limitTo30Fps = MutableStateFlow(false)
    val limitTo30Fps: StateFlow<Boolean> = _limitTo30Fps.asStateFlow()

    private val _jobs = MutableStateFlow<List<VideoJobState>>(emptyList())
    val jobs: StateFlow<List<VideoJobState>> = _jobs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.SELECTION)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    // Original-file deletion is tracked separately from the service's job flow (not folded into
    // VideoJobState) so it can't be clobbered by a later, unrelated emission from the service.
    private val _deletedOriginals = MutableStateFlow<Set<Uri>>(emptySet())
    val deletedOriginals: StateFlow<Set<Uri>> = _deletedOriginals.asStateFlow()

    fun markOriginalDeleted(uri: Uri) {
        _deletedOriginals.value = _deletedOriginals.value + uri
    }

    private var jobsObserver: Job? = null

    fun setLastCrash(report: String?) {
        _lastCrash.value = report
    }

    fun setSelectedVideos(videos: List<SelectedVideo>) {
        _selectedVideos.value = videos
        // Selecting a new batch always starts a fresh run: drop any results/rows left over
        // from a previous batch, otherwise the "Compress" button never reappears (jobs.isEmpty()
        // in CompressorScreen gates it) and old rows are shown alongside the new selection.
        _jobs.value = emptyList()
    }

    /** Cycles: order picked -> largest first -> smallest first -> back to order picked. */
    fun cycleSortOrder() {
        _sortOrder.value = when (_sortOrder.value) {
            SortOrder.SELECTION -> SortOrder.SIZE_DESCENDING
            SortOrder.SIZE_DESCENDING -> SortOrder.SIZE_ASCENDING
            SortOrder.SIZE_ASCENDING -> SortOrder.SELECTION
        }
    }

    fun setLimitTo30Fps(enabled: Boolean) {
        _limitTo30Fps.value = enabled
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    /**
     * Subscribes to the bound service's job flow. Safe to call more than once (e.g. the
     * Activity re-binds on every onStart): a prior subscription is cancelled first so we never
     * accumulate duplicate collectors on the same underlying flow.
     *
     * Ignores empty snapshots: the service calls stopSelf() once a batch finishes, so returning
     * to the app later re-binds to a brand-new service instance whose job list starts empty --
     * without this guard that empty snapshot overwrote the just-finished results, and the UI fell
     * back to re-deriving all-QUEUED rows from selectedVideos, making it look like the whole batch
     * had been silently reset. The only place jobs should ever go back to empty is a fresh
     * selection (setSelectedVideos), which already clears it explicitly.
     */
    fun observeJobs(flow: StateFlow<List<VideoJobState>>) {
        jobsObserver?.cancel()
        jobsObserver = viewModelScope.launch {
            flow.collect { updated ->
                if (updated.isEmpty()) return@collect
                _jobs.value = updated
                if (updated.all { it.status.isTerminal() }) {
                    _isRunning.value = false
                }
            }
        }
    }

    private fun CompressionStatus.isTerminal(): Boolean = this == CompressionStatus.DONE ||
        this == CompressionStatus.SKIPPED ||
        this == CompressionStatus.FAILED ||
        this == CompressionStatus.CANCELLED

    fun reset() {
        _selectedVideos.value = emptyList()
        _jobs.value = emptyList()
        _isRunning.value = false
    }
}

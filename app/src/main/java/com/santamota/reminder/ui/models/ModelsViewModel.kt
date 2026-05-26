package com.santamota.reminder.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santamota.reminder.data.models.ModelDownloader
import com.santamota.reminder.data.models.ModelRowState
import com.santamota.reminder.data.models.ModelSpec
import com.santamota.reminder.data.models.ModelsRepository
import com.santamota.reminder.ml.MediaPipeGemmaAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val repo: ModelsRepository,
    private val downloader: ModelDownloader,
    private val llm: MediaPipeGemmaAdapter,
) : ViewModel() {

    val rows: StateFlow<List<ModelRowState>> = repo.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onTap(row: ModelRowState) {
        if (row.isDownloaded) {
            if (!row.isActive) activate(row.spec)
        }
        // Not-downloaded rows raise the confirm dialog in the UI; activation
        // and download both go through dedicated calls.
    }

    fun startDownload(spec: ModelSpec) {
        viewModelScope.launch { downloader.download(spec) }
    }

    private fun activate(spec: ModelSpec) {
        viewModelScope.launch {
            repo.setActive(spec.id)
            // Force the adapter to reload from the new path on next call.
            llm.reload()
        }
    }
}

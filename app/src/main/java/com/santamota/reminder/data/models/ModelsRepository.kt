package com.santamota.reminder.data.models

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single source of truth for what models exist on this device, which one is
 * currently active, and any in-progress download state.
 *
 * The UI ([com.santamota.reminder.ui.models.ModelsViewModel]) observes
 * `state` to render rows; the LLM adapter
 * ([com.santamota.reminder.ml.MediaPipeGemmaAdapter]) reads the active
 * model id to know which `.task` file to load.
 */
class ModelsRepository(
    private val appContext: Context,
    private val dataStore: DataStore<Preferences>,
) {

    private val modelsDir: File = File(appContext.filesDir, "models").also { it.mkdirs() }

    private val activeKey = stringPreferencesKey("active_model_id")

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    val activeModelId: Flow<String?> = dataStore.data.map { it[activeKey] }

    /**
     * The full reconciled UI state: each registered model plus whether it's
     * downloaded, downloading, or absent, and whether it's the active one.
     */
    val state: Flow<List<ModelRowState>> =
        combine(activeModelId, _downloads) { active, downloads ->
            ModelRegistry.all.map { spec ->
                val file = fileFor(spec)
                val sizeOnDisk = if (file.exists()) file.length() else 0L
                val downloadState = downloads[spec.id] ?: DownloadState.Idle
                ModelRowState(
                    spec = spec,
                    isDownloaded = sizeOnDisk > 0L &&
                        sizeOnDisk >= spec.approxSizeMb * 1024L * 1024L * 0.9,
                    isActive = active == spec.id,
                    download = downloadState,
                )
            }
        }

    fun fileFor(spec: ModelSpec): File = File(modelsDir, spec.fileName)

    /**
     * The path the LLM adapter should currently load. Returns null if the
     * active model file is missing — caller falls back to rule parser.
     */
    suspend fun activeModelPath(): String? {
        val activeId = dataStore.data.first()[activeKey] ?: ModelRegistry.DEFAULT_MODEL_ID
        val spec = ModelRegistry.byId(activeId) ?: return null
        val f = fileFor(spec)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    /**
     * Best-effort synchronous lookup used by MediaPipeGemmaAdapter's path
     * resolver, which is called from `generate()` on a background dispatcher.
     *
     * We cache the active-model id reactively from a coroutine on init and
     * read from a plain `@Volatile var`. That avoids the runBlocking +
     * never-completing `DataStore.data.collect` trap that froze the app: the
     * `data` flow is hot and never terminates, so a collect-once-and-return
     * inside runBlocking hangs forever.
     */
    @Volatile private var cachedActiveId: String? = ModelRegistry.DEFAULT_MODEL_ID

    init {
        // Hot subscription kept alive by a global scope tied to the
        // application process. The repository is a singleton anyway.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            dataStore.data.collect { prefs ->
                cachedActiveId = prefs[activeKey] ?: ModelRegistry.DEFAULT_MODEL_ID
            }
        }
    }

    fun activeModelPathBlocking(): String? {
        val id = cachedActiveId ?: ModelRegistry.DEFAULT_MODEL_ID
        val spec = ModelRegistry.byId(id) ?: return null
        val f = fileFor(spec)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    suspend fun setActive(modelId: String) {
        dataStore.edit { it[activeKey] = modelId }
    }

    /**
     * Internal: progress reporting from [ModelDownloader]. Public so the
     * downloader can update without exposing the MutableStateFlow.
     */
    internal fun reportProgress(modelId: String, state: DownloadState) {
        _downloads.value = _downloads.value.toMutableMap().also { it[modelId] = state }
    }
}

/**
 * UI-facing per-row state. The Models screen renders this directly.
 */
data class ModelRowState(
    val spec: ModelSpec,
    val isDownloaded: Boolean,
    val isActive: Boolean,
    val download: DownloadState,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState {
        val percent: Int get() =
            if (totalBytes > 0) ((bytesDownloaded.toDouble() / totalBytes) * 100).toInt() else 0
    }
    data class Failed(val message: String) : DownloadState
}

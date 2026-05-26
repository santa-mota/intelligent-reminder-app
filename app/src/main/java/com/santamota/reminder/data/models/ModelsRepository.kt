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
 *
 * **Auto-selection invariant.** "Active" should never silently point at a
 * model the user hasn't downloaded. If the user-set active id is missing or
 * stale, we resolve to the first downloaded file we find, scanning the
 * registry in order. That keeps the LLM-first path live even when the user
 * downloads a non-default model and doesn't think to tap it afterward —
 * which was the bug behind reports of "downloaded model isn't being used."
 */
class ModelsRepository(
    private val appContext: Context,
    private val dataStore: DataStore<Preferences>,
) {

    private val modelsDir: File = File(appContext.filesDir, "models").also { it.mkdirs() }

    private val activeKey = stringPreferencesKey("active_model_id")

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads

    /** Raw user preference (may be null). Internal — callers should use [effectiveActiveId]. */
    private val rawActiveId: Flow<String?> = dataStore.data.map { it[activeKey] }

    /**
     * The model id that is *actually* in use, falling back through:
     *   1. the user's explicit selection (if its file exists)
     *   2. the first downloaded file in registry order
     *   3. null (no model usable → engine degrades to rule parser)
     *
     * Both the UI tick and the LLM adapter use this same resolution, so the
     * checkmark you see in the Models tab always matches the model actually
     * loaded for inference.
     */
    val effectiveActiveId: Flow<String?> = rawActiveId.map { resolveEffectiveId(it) }

    /**
     * The full reconciled UI state: each registered model plus whether it's
     * downloaded, downloading, or active.
     */
    val state: Flow<List<ModelRowState>> =
        combine(effectiveActiveId, _downloads) { active, downloads ->
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
     * Returns the model id we should treat as active, accounting for missing
     * files. See class doc for the fallback order.
     */
    private fun resolveEffectiveId(rawId: String?): String? {
        // 1. User's explicit pick wins if its file is present.
        val pickedSpec = rawId?.let(ModelRegistry::byId)
        if (pickedSpec != null && fileFor(pickedSpec).existsNonEmpty()) {
            return pickedSpec.id
        }
        // 2. First downloaded file in registry order.
        val firstDownloaded = ModelRegistry.all.firstOrNull { fileFor(it).existsNonEmpty() }
        return firstDownloaded?.id
    }

    private fun File.existsNonEmpty(): Boolean = exists() && length() > 0L

    /**
     * Suspending lookup of the active model's file path. Returns null when no
     * downloaded model is usable.
     */
    suspend fun activeModelPath(): String? {
        val id = resolveEffectiveId(dataStore.data.first()[activeKey]) ?: return null
        val spec = ModelRegistry.byId(id) ?: return null
        val f = fileFor(spec)
        return if (f.existsNonEmpty()) f.absolutePath else null
    }

    /**
     * Best-effort synchronous lookup used by MediaPipeGemmaAdapter's path
     * resolver, which is called from `generate()` on a background dispatcher.
     *
     * Uses a [@Volatile] cache populated by a coroutine subscription to avoid
     * `runBlocking { DataStore.data.collect { } }`, which deadlocks because
     * `data` is a hot flow that never terminates.
     */
    @Volatile private var cachedActiveId: String? = null

    init {
        // Hot subscription kept alive by a global scope tied to the
        // application process. The repository is a singleton anyway.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            dataStore.data.collect { prefs ->
                // Cache the *effective* id, not the raw one — that way the
                // sync resolver tracks the same fallback semantics as the
                // Flow-based one used by the UI.
                cachedActiveId = resolveEffectiveId(prefs[activeKey])
            }
        }
    }

    fun activeModelPathBlocking(): String? {
        // Recompute on every call so newly-downloaded files become visible
        // immediately, without waiting for the DataStore subscription to fire.
        val id = resolveEffectiveId(cachedActiveId) ?: return null
        val spec = ModelRegistry.byId(id) ?: return null
        val f = fileFor(spec)
        return if (f.existsNonEmpty()) f.absolutePath else null
    }

    suspend fun setActive(modelId: String) {
        dataStore.edit { it[activeKey] = modelId }
    }

    /**
     * Called by [ModelDownloader] when a download completes. If the user
     * doesn't already have an active model, auto-promote this one so they
     * don't have to make a separate "activate" tap.
     */
    suspend fun setActiveIfNone(modelId: String) {
        val current = dataStore.data.first()[activeKey]
        val currentValid = current?.let(ModelRegistry::byId)?.let { fileFor(it).existsNonEmpty() }
            ?: false
        if (!currentValid) {
            dataStore.edit { it[activeKey] = modelId }
        }
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

package app.universal.patch.manager.ui.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.network.downloader.DownloaderCatalogItem
import app.universal.patch.manager.network.downloader.DownloaderPluginSourceState
import app.universal.patch.manager.network.runtime.RuntimeDownloadProgress
import app.universal.patch.manager.util.PM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keeps imports and catalog selection alive when the dialog closes or rotates. */
class DownloaderCatalogViewModel(
    private val repository: DownloaderPluginRepository,
    val pm: PM
) : ViewModel() {
    val sources = repository.sourceStates
    var catalog by mutableStateOf(emptyList<DownloaderCatalogItem>())
        private set
    var selectedIds by mutableStateOf(emptySet<String>())
        private set
    var loading by mutableStateOf(false)
        private set
    var warning by mutableStateOf<String?>(null)
        private set
    var importError by mutableStateOf<String?>(null)
        private set
    var currentDownload by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<RuntimeDownloadProgress?>(null)
        private set
    var step by mutableStateOf(0)
        private set
    var stepCount by mutableStateOf(0)
        private set
    private var initializedSelection = false
    private val context = pm.application

    init { refresh() }

    fun select(id: String, selected: Boolean) {
        if (currentDownload != null || catalog.none { it.id == id }) return
        selectedIds = if (selected) selectedIds + id else selectedIds - id
    }

    fun sourceFor(item: DownloaderCatalogItem, sources: Map<String, DownloaderPluginSourceState>) =
        repository.catalogSource(item, sources)

    fun isDownloaded(item: DownloaderCatalogItem, sources: Map<String, DownloaderPluginSourceState>): Boolean {
        val source = sourceFor(item, sources) ?: return false
        // The import catalog adds missing downloaders; updates keep the source's
        // chosen release channel and are handled by downloader settings.
        return source.state !is DownloaderPluginSourceState.State.Missing &&
            source.state !is DownloaderPluginSourceState.State.Failed
    }

    suspend fun apkFor(source: DownloaderPluginSourceState?) =
        source?.let { repository.getManagedSourceApk(it.entry.id) }

    fun refresh() = viewModelScope.launch {
        if (loading || currentDownload != null) return@launch
        loading = true
        warning = null
        try {
            repository.reload()
            val unavailableProviders = mutableSetOf<String>()
            val refreshed = repository.catalogAssets { unavailableProviders.add(it) }.distinctBy { it.id }
            // A temporary provider failure must not remove its choices or selections.
            val previousOrder = catalog.map { it.id }
            val retained = catalog.filter { it.repoUrl in unavailableProviders }
            catalog = (refreshed + retained).distinctBy { it.id }.sortedBy {
                previousOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }
            if (unavailableProviders.isNotEmpty()) {
                warning = context.getString(R.string.downloader_catalog_partial)
            }
            selectedIds = selectedIds.intersect(catalog.map { it.id }.toSet())
            if (!initializedSelection && selectedIds.isEmpty()) {
                selectedIds = catalog.filter { sourceFor(it, sources.value) != null }.map { it.id }.toSet()
            }
            initializedSelection = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            warning = context.getString(R.string.downloader_catalog_load_failed)
        } finally {
            loading = false
        }
    }

    fun downloadSelected() = viewModelScope.launch {
        if (loading || currentDownload != null || selectedIds.isEmpty()) return@launch
        val batch = catalog.filter { it.id in selectedIds && !isDownloaded(it, sources.value) }
        if (batch.isEmpty()) return@launch
        currentDownload = context.getString(R.string.runtime_progress_preparing)
        importError = null
        stepCount = batch.size
        var failed = false
        try {
            for ((index, item) in batch.withIndex()) {
                step = index
                currentDownload = item.name
                progress = null
                try {
                    val onProgress: (RuntimeDownloadProgress) -> Unit = {
                        progress = it.copy(catalogId = item.id, name = item.name)
                    }
                    val existing = sourceFor(item, sources.value)
                    if (existing != null) {
                        // Repair a missing/failed source using its own release preferences.
                        repository.updateSource(existing.entry.id, onProgress)
                    } else {
                        repository.importSourcesFromUrl(item.downloadUrl, onProgress)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
            }
            if (failed) importError = context.getString(R.string.downloader_catalog_import_failed)
        } finally {
            progress = null
            currentDownload = null
        }
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        if (currentDownload != null) return@launch
        currentDownload = context.getString(R.string.downloader_catalog_importing_file)
        importError = null
        stepCount = 0
        progress = null
        try {
            repository.importSourceFile(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            importError = context.getString(R.string.downloader_file_import_failed)
        } finally {
            currentDownload = null
        }
    }
}

package app.universal.patch.manager.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.repository.PatcherRuntimePluginRepository
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import app.universal.patch.manager.network.runtime.isInternalSupport
import app.universal.patch.manager.network.runtime.isInternalRuntimePlugin
import kotlinx.coroutines.withContext
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.uninstaller.UninstallFailure

class PatcherRuntimePluginsViewModel(
    private val runtimePluginRepository: PatcherRuntimePluginRepository,
    val pm: PM,
    private val catalogRepository: app.universal.patch.manager.domain.repository.RuntimeCatalogRepository,
    private val prefs: app.universal.patch.manager.domain.manager.PreferencesManager
) : ViewModel() {
    sealed interface RemoteSourceBusyState {
        data object Importing : RemoteSourceBusyState
        data class Updating(val id: String) : RemoteSourceBusyState
    }

    var catalog by mutableStateOf(catalogRepository.cached())
        private set
    var catalogReleases by mutableStateOf(emptyMap<String, app.universal.patch.manager.domain.repository.CatalogRuntimeRelease>())
        private set
    var catalogErrors by mutableStateOf(emptyMap<String, String>())
        private set
    var catalogWarning by mutableStateOf<String?>(null)
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var selectedCatalogIds by mutableStateOf(catalog.engineSelection(prefs.runtimeSetupSelection.getBlocking()))
        private set
    var currentDownload by mutableStateOf<String?>(null)
        private set
    var downloadError by mutableStateOf<String?>(null)
        private set
    private var initializedSelection = false


    fun selectCatalogRuntime(id: String, selected: Boolean) {
        if (catalog.plugins.none { it.id == id && it.isEngine }) return
        selectedCatalogIds = if (selected) selectedCatalogIds + id else selectedCatalogIds - id
        viewModelScope.launch { prefs.runtimeSetupSelection.update(selectedCatalogIds) }
    }

    fun refreshCatalog() = viewModelScope.launch {
        if (catalogLoading) return@launch
        catalogLoading = true
        catalogWarning = null
        try {
            runtimePluginRepository.migrateOfficialSources()
            try {
                catalog = catalogRepository.refresh()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                catalogWarning = error.simpleMessage() ?: "Could not refresh the runtime catalog"
            }
            val validIds = catalog.plugins.filter { it.isEngine }.map { it.id }.toSet()
            selectedCatalogIds = catalog.engineSelection(selectedCatalogIds)
            if (!initializedSelection && selectedCatalogIds.isEmpty()) {
                val loaded = runtimePluginRepository.loadedRuntimeSnapshot.keys
                selectedCatalogIds = catalog.plugins.filter { it.isEngine && it.runtimeKind in loaded }.map { it.id }.toSet()
                if (selectedCatalogIds.isEmpty() && !prefs.firstLaunch.get()) {
                    selectedCatalogIds = setOf("morphe", "reseam").intersect(validIds)
                }
            }
            initializedSelection = true
            prefs.runtimeSetupSelection.update(selectedCatalogIds)
            val releases = mutableMapOf<String, app.universal.patch.manager.domain.repository.CatalogRuntimeRelease>()
            val errors = mutableMapOf<String, String>()
            for (item in catalog.plugins) {
                try {
                    releases[item.id] = catalogRepository.resolve(item)
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    errors[item.id] = error.simpleMessage().orEmpty()
                }
            }
            catalogReleases = releases
            catalogErrors = errors
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            catalogWarning = error.simpleMessage().orEmpty()
        } finally { catalogLoading = false }
        if (prefs.runtimeSetupDownloading.get()) downloadSelected()
    }

    fun downloadSelected() = viewModelScope.launch {
        if (currentDownload != null || selectedCatalogIds.isEmpty()) return@launch
        currentDownload = appContext.getString(R.string.runtime_progress_preparing)
        downloadError = null
        try {
            prefs.runtimeSetupSelection.update(selectedCatalogIds)
            prefs.runtimeSetupDownloading.update(true)
            runtimePluginRepository.importCatalogRuntimes(selectedCatalogIds) { currentDownload = it }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            downloadError = error.simpleMessage()
        } finally {
            currentDownload = null
            if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != false) prefs.runtimeSetupDownloading.update(false)
        }
    }

    fun completeRuntimeSetup() = viewModelScope.launch {
        try {
            runtimePluginRepository.migrateOfficialSources()
            val loaded = runtimePluginRepository.catalogRuntimeSnapshot
            if (app.universal.patch.manager.network.runtime.canCompleteRuntimeSetup(catalog, selectedCatalogIds, loaded.keys)) {
                prefs.runtimeSetupVersion.update(app.universal.patch.manager.network.runtime.RUNTIME_SETUP_VERSION)
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            downloadError = error.simpleMessage()
        }
    }

    fun importRuntimeFile(uri: android.net.Uri) = viewModelScope.launch {
        if (currentDownload != null) return@launch
        currentDownload = appContext.getString(R.string.runtime_progress_importing)
        downloadError = null
        var temporary: java.io.File? = null
        try {
            val file = java.io.File.createTempFile("runtime-import-", ".apk", appContext.cacheDir)
            temporary = file
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: error("Cannot open runtime file")
            }
            val id = runtimePluginRepository.importCatalogFile(file)
            if (catalog.plugins.firstOrNull { it.id == id }?.isEngine == true) {
                selectCatalogRuntime(id, true)
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            downloadError = error.simpleMessage()
        } finally {
            temporary?.delete()
            currentDownload = null
        }
    }

    val runtimePluginStates = runtimePluginRepository.pluginStates.map { states ->
        states.filter { (packageName, state) -> !isInternalRuntimePlugin(packageName, state) }
    }
    val runtimePluginSourceStates = runtimePluginRepository.sourceStates.map { states ->
        states.filterValues { !it.isInternalSupport }
    }
    val downloadProgress = runtimePluginRepository.downloadProgress
    val downloadedCatalogRuntimes = runtimePluginRepository.downloadedCatalogRuntimes
    val catalogRuntimes = runtimePluginRepository.catalogRuntimes
    val loadedRuntimes = runtimePluginRepository.loadedRuntimes
    val newPluginPackageNames = runtimePluginRepository.newPluginPackageNames

    var isRefreshingPlugins by mutableStateOf(false)
        private set
    var remoteSourceBusyState by mutableStateOf<RemoteSourceBusyState?>(null)
        private set

    private val appContext = pm.application

    init { refreshCatalog() }

    fun refreshPlugins() = viewModelScope.launch {
        reloadPlugins()
    }

    fun acknowledgeNewPlugins() = viewModelScope.launch {
        runtimePluginRepository.acknowledgeAllNewPlugins()
    }

    fun importPluginSource(url: String) = viewModelScope.launch {
        remoteSourceBusyState = RemoteSourceBusyState.Importing
        runCatching {
            runtimePluginRepository.importSourcesFromUrl(url)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_import_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }.also {
            remoteSourceBusyState = null
        }
    }

    fun updatePluginSource(id: String) = viewModelScope.launch {
        remoteSourceBusyState = RemoteSourceBusyState.Updating(id)
        runCatching {
            runtimePluginRepository.updateSource(id)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_update_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }.also {
            remoteSourceBusyState = null
        }
    }

    fun removePluginSource(id: String) = viewModelScope.launch {
        runtimePluginRepository.removeSource(id)
    }

    fun trustPluginSource(id: String) = viewModelScope.launch {
        runCatching {
            runtimePluginRepository.trustSource(id)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_import_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }
    }

    fun revokePluginSourceTrust(id: String) = viewModelScope.launch {
        runtimePluginRepository.revokeTrustForSource(id)
    }

    fun setPluginSourceAutoUpdate(id: String, enabled: Boolean) = viewModelScope.launch {
        runtimePluginRepository.setSourceAutoUpdate(id, enabled)
    }

    fun setPluginSourceLatest(id: String, enabled: Boolean) = viewModelScope.launch {
        runtimePluginRepository.setSourceLatest(id, enabled)
    }

    fun setPluginSourcePrerelease(id: String, enabled: Boolean) = viewModelScope.launch {
        runtimePluginRepository.setSourcePrerelease(id, enabled)
    }

    fun trustPlugin(packageName: String) = viewModelScope.launch {
        runCatching {
            runtimePluginRepository.trustPackage(packageName)
        }.onFailure {
            appContext.toast(
                appContext.getString(
                    R.string.patcher_runtime_import_failed,
                    it.simpleMessage().orEmpty()
                )
            )
        }
    }

    fun revokePluginTrust(packageName: String) = viewModelScope.launch {
        runtimePluginRepository.revokeTrustForPackage(packageName)
    }

    fun uninstallPlugin(packageName: String) = viewModelScope.launch {
        if (isInternalRuntimePlugin(packageName, runtimePluginRepository.pluginStates.value[packageName])) return@launch
        val result = withContext(Dispatchers.IO) {
            pm.uninstallPackage(packageName)
        }
        when (result) {
            Session.State.Succeeded -> {
                runtimePluginRepository.removePlugin(packageName)
                reloadPlugins()
                appContext.toast(
                    appContext.getString(
                        R.string.patcher_runtime_uninstall_success,
                        packageName
                    )
                )
            }

            is Session.State.Failed<UninstallFailure> -> {
                if (result.failure is UninstallFailure.Aborted) return@launch
                val message = result.failure.message
                appContext.toast(
                    message ?: appContext.getString(
                        R.string.patcher_runtime_uninstall_failed,
                        packageName
                    )
                )
            }
        }
    }

    private suspend fun reloadPlugins() {
        isRefreshingPlugins = true
        try {
            runtimePluginRepository.reload()
            runtimePluginRepository.updateCheck()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to refresh patcher runtime plugins", t)
        } finally {
            isRefreshingPlugins = false
        }
    }

    companion object {
        private val TAG = PatcherRuntimePluginsViewModel::class.java.simpleName
            ?: "PatcherRuntimePluginsViewModel"
    }
}

package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.util.catalogErrorMessage
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.BundleDiscoveryStateStore
import app.universal.patch.manager.domain.bundles.DiscoveryScrollPosition
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.network.api.CatalogFilters
import app.universal.patch.manager.network.dto.PackageNode
import app.universal.patch.manager.network.dto.SourceNode
import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.network.dto.ExternalBundlePatch
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.network.utils.getOrThrow
import app.universal.patch.manager.util.DownloadProgressNotifier
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.toast
import io.ktor.client.request.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.io.File
import java.util.Locale
import java.nio.file.Path

class BundleDiscoveryViewModel(
    private val api: ExternalBundlesApi,
    private val patchBundleRepository: PatchBundleRepository,
    private val app: Application,
    private val http: HttpService,
    private val json: Json,
    private val downloadProgressNotifier: DownloadProgressNotifier,
    private val discoveryStateStore: BundleDiscoveryStateStore,
) : ViewModel() {
    val browsingState = discoveryStateStore.state

    fun setDiscoveryTab(appsTab: Boolean) = discoveryStateStore.update { it.copy(appsTab = appsTab) }
    fun setDiscoveryFilters(appsTab: Boolean, filters: CatalogFilters) =
        discoveryStateStore.update { it.withFilters(appsTab, filters) }
    fun updateDiscoveryFilters(appsTab: Boolean, update: (CatalogFilters) -> CatalogFilters) =
        discoveryStateStore.update { it.withFilters(appsTab, update(it.filtersFor(appsTab))) }
    fun toggleDiscoveryFiltersExpanded() =
        discoveryStateStore.update { it.copy(filtersExpanded = !it.filtersExpanded) }
    fun saveDiscoveryScroll(appsTab: Boolean, filters: CatalogFilters, index: Int, offset: Int) =
        discoveryStateStore.update { it.withScroll(appsTab, filters, DiscoveryScrollPosition(index, offset)) }
    fun showBundlesForPackage(packageName: String) =
        discoveryStateStore.update { it.showBundlesForPackage(packageName) }

    var bundles: List<ExternalBundleSnapshot>? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    var bundleSearchQuery: String by mutableStateOf("")
    var packageSearchQuery: String by mutableStateOf("")
    var isAppsTab by mutableStateOf(false)
        private set
    var apps: List<PackageNode>? by mutableStateOf(null)
        private set
    var sourceOptions: List<SourceNode> by mutableStateOf(emptyList())
        private set
    var versions: List<String> by mutableStateOf(emptyList())
        private set
    var filters by mutableStateOf(CatalogFilters())
        private set
    var isSourcesLoading by mutableStateOf(false)
        private set
    var sourcesError: String? by mutableStateOf(null)
        private set
    var appMetadata: Map<String, PackageNode> by mutableStateOf(emptyMap())
        private set
    private val requestedAppNames = mutableSetOf<String>()
    private val appMetadataCheckedAt = mutableMapOf<String, Long>()
    private var sourcesLoadedAt = 0L
    private var sourcesJob: Job? = null
    private var versionJob: Job? = null
    private var versionsSource = ""
    private var pageJob: Job? = null

    fun configure(next: CatalogFilters, appsTab: Boolean) {
        if (next == filters && appsTab == isAppsTab && currentQueryKey.isNotEmpty()) return
        filters = next
        isAppsTab = appsTab
        refreshDebounced()
    }

    fun loadSources(force: Boolean = false) {
        if (sourcesJob?.isActive == true) return
        if (!force && sourcesLoadedAt > 0 &&
            System.currentTimeMillis() - sourcesLoadedAt in 0 until CACHE_TTL_MS) return
        isSourcesLoading = true
        sourcesError = null
        sourcesJob = viewModelScope.launch {
            try {
                sourceOptions = api.getSources(force).getOrThrow()
                sourcesLoadedAt = System.currentTimeMillis()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                sourcesError = app.getString(R.string.catalog_sources_error)
            } finally {
                isSourcesLoading = false
            }
        }
    }

    fun loadAppMetadata(names: List<String>) {
        val now = System.currentTimeMillis()
        val missing = names.filter {
            it.isNotBlank() && it !in requestedAppNames &&
                now - (appMetadataCheckedAt[it] ?: 0L) !in 0 until CACHE_TTL_MS
        }.distinct()
        if (missing.isEmpty()) return
        requestedAppNames += missing
        viewModelScope.launch {
            try {
                val rows = api.getAppMetadata(missing).getOrThrow()
                appMetadata = appMetadata + rows.mapNotNull { row -> row.name?.let { it to row } }
                missing.forEach { appMetadataCheckedAt[it] = System.currentTimeMillis() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                // Keep the package name visible when optional metadata is unavailable.
            } finally {
                requestedAppNames.removeAll(missing.toSet())
            }
        }
    }

    fun clearVersions() {
        versionJob?.cancel()
        versionsSource = ""
        versions = emptyList()
    }

    fun loadVersions(sourceUrl: String) {
        if (versionsSource == sourceUrl && (versions.isNotEmpty() || versionJob?.isActive == true)) return
        versionJob?.cancel()
        versionsSource = sourceUrl
        versions = emptyList()
        if (sourceUrl.isBlank()) return
        versionJob = viewModelScope.launch {
            versions = api.getBundleHistory("", "", limit = 1000, sourceUrl = sourceUrl,
                includeReleaseNotes = false)
                .getOrNull().orEmpty().map { it.version }.distinct()
        }
    }

    private val patchesByBundle = mutableStateMapOf<BundleInstanceKey, List<ExternalBundlePatch>>()
    private val patchesLoading = mutableStateMapOf<BundleInstanceKey, Boolean>()
    private val patchesError = mutableStateMapOf<BundleInstanceKey, String?>()
    private val bundleCache = mutableMapOf<String, BundleCacheEntry>()
    private val bundleExports = mutableStateMapOf<Int, BundleExportProgress>()
    private val cacheDir = File(app.cacheDir, "bundle_discovery").also { it.mkdirs() }
    private var refreshJob: Job? = null
    private var currentQueryKey: String = ""
    private var currentApiHost: String? = null
    private var nextOffset: Int = 0
    private var refreshToken: Int = 0
    private var bundleDatasetGeneration: Int = 0
    private var isFirstPageRefreshInProgress: Boolean = false
    private var importProgressSnapshot by mutableStateOf<Map<String, PatchBundleRepository.DiscoveryImportProgress>>(emptyMap())
    private var queuedImportSnapshot by mutableStateOf<Set<String>>(emptySet())
    private val localQueuedKeys = mutableStateMapOf<String, Boolean>()
    var isLoadingMore: Boolean by mutableStateOf(false)
        private set
    var canLoadMore: Boolean by mutableStateOf(true)
        private set
    var isSearchingMore: Boolean by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            patchBundleRepository.discoveryImportProgress.collect { progress ->
                importProgressSnapshot = progress
            }
        }
        viewModelScope.launch {
            patchBundleRepository.discoveryImportQueued.collect { queued ->
                queuedImportSnapshot = queued
            }
        }
    }

    private fun refreshDebounced() {
        refreshJob?.cancel()
        pageJob?.cancel()
        refreshToken++
        isFirstPageRefreshInProgress = false
        isLoadingMore = false
        // Hide the previous filter's results while the user's input settles.
        bundles = null
        apps = null
        isLoading = true
        refreshJob = viewModelScope.launch {
            delay(450)
            refreshJob = null
            refresh()
        }
    }

    fun refresh(force: Boolean = false) {
        refreshJob?.cancel()
        pageJob?.cancel()
        isFirstPageRefreshInProgress = false
        val requestedFilters = filters
        val appsTab = isAppsTab
        val key = "$appsTab|${json.encodeToString(requestedFilters)}"
        currentQueryKey = key
        val token = ++refreshToken
        val cached = bundleCache[key] ?: withDiskCache(key)
        bundles = cached?.bundles
        apps = cached?.apps
        nextOffset = cached?.nextOffset ?: 0
        canLoadMore = cached?.canLoadMore ?: true
        currentApiHost = ExternalBundlesEndpoints.REGISTRY_HOST
        errorMessage = null
        isLoadingMore = false
        if (!force && cached != null && System.currentTimeMillis() - cached.loadedAt in 0 until CACHE_TTL_MS) {
            isLoading = false
            return
        }
        isFirstPageRefreshInProgress = true
        isLoading = cached == null
        pageJob = viewModelScope.launch {
            try {
                val pageBundles = if (!appsTab) api.getBundles(filters = requestedFilters,
                    limit = PAGE_SIZE, forceRefresh = force).getOrThrow().bundles else null
                val pageApps = if (appsTab) api.getApps(requestedFilters,
                    forceRefresh = force).getOrThrow() else null
                if (token != refreshToken) return@launch
                if (pageBundles == null && pageApps == null) {
                    errorMessage = app.getString(R.string.patch_bundle_discovery_error)
                    canLoadMore = cached?.canLoadMore ?: false
                    return@launch
                }
                invalidatePatchState()
                val size = pageBundles?.size ?: pageApps.orEmpty().size
                val entry = BundleCacheEntry(
                    bundles = pageBundles.orEmpty(), apps = pageApps.orEmpty(),
                    loadedAt = System.currentTimeMillis(), nextOffset = size,
                    canLoadMore = size >= PAGE_SIZE, forceNextPages = force,
                    schemaVersion = BUNDLE_CACHE_SCHEMA_VERSION
                )
                bundleCache[key] = entry
                bundles = entry.bundles
                apps = entry.apps
                nextOffset = size
                canLoadMore = entry.canLoadMore
                withContext(Dispatchers.IO) { persistDiskCache(key, entry) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == refreshToken) {
                    errorMessage = app.getString(R.string.patch_bundle_discovery_error) +
                        error.message?.let { "\n" + app.catalogErrorMessage(it) }.orEmpty()
                    canLoadMore = cached?.canLoadMore ?: false
                }
            } finally {
                if (token == refreshToken) {
                    isLoading = false
                    isFirstPageRefreshInProgress = false
                }
            }
        }
    }

    private fun withDiskCache(key: String) = loadDiskCache(key)?.also { bundleCache[key] = it }

    fun loadMore() {
        if (isLoading || isFirstPageRefreshInProgress || !canLoadMore || isLoadingMore) return
        pageJob = viewModelScope.launch { loadNextPageInternal() }
    }

    fun importBundle(
        bundle: ExternalBundleSnapshot,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        preferLatestAcrossChannels: Boolean = false
    ) {
        viewModelScope.launch {
            val key = patchBundleRepository.discoveryImportKey(bundle, preferLatestAcrossChannels)
            val result = patchBundleRepository.enqueueDiscoveryImport(
                bundle = bundle,
                searchUpdate = searchUpdate,
                autoUpdate = autoUpdate,
                preferLatestAcrossChannels = preferLatestAcrossChannels
            )
            if (result != PatchBundleRepository.DiscoveryImportEnqueueResult.Duplicate) {
                localQueuedKeys[key] = true
            }
            if (result == PatchBundleRepository.DiscoveryImportEnqueueResult.Queued) {
                app.toast(app.getString(R.string.patch_bundle_import_queued))
            }
        }
    }

    fun exportBundle(bundle: ExternalBundleSnapshot, target: Path) {
        viewModelScope.launch {
            val bundleId = bundle.bundleId
            val url = bundle.downloadUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (url.isNullOrBlank()) {
                app.toast(app.getString(R.string.patch_bundle_discovery_error))
                return@launch
            }
            bundleExports[bundleId] = BundleExportProgress(0L, null)
            val progressNotification =
                downloadProgressNotifier.begin(target.fileName.toString())
            try {
                withContext(Dispatchers.IO) {
                    target.parent?.toFile()?.mkdirs()
                    http.downloadToFile(
                        saveLocation = target.toFile(),
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            progressNotification.update(bytesRead, bytesTotal)
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                }
                progressNotification.complete()
                app.toast(app.getString(R.string.patch_bundle_export_success, target.fileName.toString()))
            } catch (e: CancellationException) {
                progressNotification.cancel()
                throw e
            } catch (e: Exception) {
                progressNotification.fail()
                app.toast(app.getString(R.string.patch_bundle_export_fail, e.simpleMessage()))
            } finally {
                bundleExports.remove(bundleId)
            }
        }
    }

    fun exportBundle(bundle: ExternalBundleSnapshot, target: Uri?) {
        if (target == null) return
        viewModelScope.launch {
            val bundleId = bundle.bundleId
            val url = bundle.downloadUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (url.isNullOrBlank()) {
                app.toast(app.getString(R.string.patch_bundle_discovery_error))
                return@launch
            }
            bundleExports[bundleId] = BundleExportProgress(0L, null)
            val tempFile = File.createTempFile("bundle-export-$bundleId-", ".tmp", cacheDir)
            val successName = bundle.repoName.ifBlank { "bundle" }
            val progressNotification = downloadProgressNotifier.begin(successName)
            try {
                withContext(Dispatchers.IO) {
                    http.downloadToFile(
                        saveLocation = tempFile,
                        builder = { url(url) },
                        onProgress = { bytesRead, bytesTotal ->
                            progressNotification.update(bytesRead, bytesTotal)
                            viewModelScope.launch(Dispatchers.Main) {
                                bundleExports[bundleId] = BundleExportProgress(bytesRead, bytesTotal)
                            }
                        }
                    )
                    app.contentResolver.openOutputStream(target)?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: error(app.getString(R.string.catalog_export_stream_error))
                }
                progressNotification.complete()
                app.toast(app.getString(R.string.patch_bundle_export_success, successName))
            } catch (e: CancellationException) {
                progressNotification.cancel()
                throw e
            } catch (e: Exception) {
                progressNotification.fail()
                app.toast(app.getString(R.string.patch_bundle_export_fail, e.simpleMessage()))
            } finally {
                bundleExports.remove(bundleId)
                tempFile.delete()
            }
        }
    }

    fun bundleEndpoints(bundle: ExternalBundleSnapshot): Set<String> {
        val endpoints = mutableSetOf<String>()
        ExternalBundlesEndpoints.latestBundleUrl(ExternalBundlesEndpoints.REGISTRY_HOST,
            bundleSourceUrl(bundle), null)?.let(endpoints::add)
        ExternalBundlesEndpoints.latestBundleUrl(ExternalBundlesEndpoints.REGISTRY_HOST,
            bundleSourceUrl(bundle), bundle.isPrerelease)?.let(endpoints::add)
        bundle.downloadUrl?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = false, prerelease = null)?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = true, prerelease = null)?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = false)?.let { endpoints.add(it) }
        externalBundleEndpoint(bundle, useDev = true)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = false, prerelease = null)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = true, prerelease = null)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = false)?.let { endpoints.add(it) }
        legacyV2Endpoint(bundle, useDev = true)?.let { endpoints.add(it) }
        legacyEndpoint(bundle.bundleId, bundleApiHost(bundle)).let(endpoints::add)
        return endpoints
    }

    fun matchesLocalPatchSource(bundle: ExternalBundleSnapshot, source: RemotePatchBundle): Boolean {
        if (source.endpoint !in bundleEndpoints(bundle)) return false

        val installedVersion = source.installedVersionSignature?.trim().orEmpty()
        val bundleVersion = bundle.version.trim()
        val versionVerified = installedVersion.isNotBlank() && bundleVersion.isNotBlank()
        if (versionVerified) {
            if (installedVersion != bundleVersion) return false
        }

        val rawExpectedFileHash = bundle.fileHash?.trim().orEmpty()
        var fileHashVerified = false
        if (rawExpectedFileHash.isNotBlank()) {
            val expectedSha256 = normalizeSha256FileHash(rawExpectedFileHash) ?: return false
            val installedSha256 = patchBundleRepository.installedBundleSha256(source.uid)
                ?.trim()
                ?.lowercase(Locale.US)
            if (installedSha256 != expectedSha256) return false
            fileHashVerified = true
        }

        if (versionVerified || fileHashVerified) {
            if (versionVerified && !fileHashVerified) {
                val sourceHost = runCatching { URI(source.endpoint).host }
                    .getOrNull()
                    ?.trim()
                    ?.lowercase(Locale.US)
                if (
                    sourceHost != null &&
                    ExternalBundlesEndpoints.isExternalBundlesHost(sourceHost) &&
                    !sourceHost.equals(bundleApiHost(bundle), ignoreCase = true)
                ) {
                    return false
                }
            }
            return true
        }

        // An any-channel endpoint can resolve to either release or prerelease. Without a
        // verified installed version or artifact hash there is no safe way to know which
        // discovery card its local patches belong to, so do not reuse them across channels.
        return !ANY_CHANNEL_QUERY.containsMatchIn(source.endpoint)
    }

    suspend fun releaseHistory(sourceUrl: String, after: String?, force: Boolean) =
        api.getReleaseHistory(sourceUrl, after, force).getOrThrow()

    suspend fun historicalRelease(id: String, generation: String) =
        api.getHistoricalRelease(id, generation).getOrThrow()

    suspend fun historicalBundles(release: app.universal.patch.manager.network.dto.HistoricalRelease, generation: String) =
        api.getHistoricalBundles(release, generation).getOrThrow()

    fun discoverySiteUrl(): String = ExternalBundlesEndpoints.REGISTRY_SITE

    fun remoteBundleUrl(bundle: ExternalBundleSnapshot): String? {
        return ExternalBundlesEndpoints.latestBundleUrl(
            host = bundleApiHost(bundle),
            sourceUrl = bundleSourceUrl(bundle),
            prerelease = bundle.isPrerelease
        )
    }

    private fun bundleApiHost(bundle: ExternalBundleSnapshot): String {
        val apiHost = bundle.apiHost.trim().lowercase(Locale.US)
        return apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)

            ?: ExternalBundlesEndpoints.REGISTRY_HOST
    }

    private fun legacyEndpoint(bundleId: Int, apiHost: String): String =
        "https://$apiHost/bundles/id?id=$bundleId"

    private fun externalBundleEndpoint(
        bundle: ExternalBundleSnapshot,
        useDev: Boolean,
        prerelease: Boolean? = bundle.isPrerelease
    ): String? {
        val host = if (useDev) {
            ExternalBundlesEndpoints.DEV_HOST
        } else {
            ExternalBundlesEndpoints.STABLE_HOST
        }
        return ExternalBundlesEndpoints.latestBundleUrl(host, bundleSourceUrl(bundle), prerelease)
    }

    private fun legacyV2Endpoint(
        bundle: ExternalBundleSnapshot,
        useDev: Boolean,
        prerelease: Boolean? = bundle.isPrerelease
    ): String? {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        if (owner.isBlank() || repo.isBlank()) return null
        val host = if (useDev) ExternalBundlesEndpoints.DEV_HOST else ExternalBundlesEndpoints.STABLE_HOST
        val channel = when (prerelease) {
            null -> "any"
            true -> "prerelease"
            false -> "stable"
        }
        return "https://$host/api/v2/bundle/$owner/$repo/latest?channel=$channel"
    }

    private fun bundleSourceUrl(bundle: ExternalBundleSnapshot): String =
        bundle.sourceUrl.trim().takeIf { it.isNotBlank() }
            ?: if (bundle.ownerName.isNotBlank() && bundle.repoName.isNotBlank()) {
                "https://github.com/${bundle.ownerName.trim()}/${bundle.repoName.trim()}"
            } else {
                ""
            }

    fun loadPatches(bundle: ExternalBundleSnapshot) {
        val key = patchStateKey(bundle)
        if (patchesByBundle.containsKey(key) || patchesLoading[key] == true) return
        val generation = bundleDatasetGeneration
        viewModelScope.launch {
            if (generation != bundleDatasetGeneration) return@launch
            patchesLoading[key] = true
            patchesError[key] = null
            try {
                val patches = withContext(Dispatchers.IO) {
                    api.getBundlePatches(bundle).getOrThrow()
                }
                if (generation != bundleDatasetGeneration) return@launch
                patchesByBundle[key] = patches
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (generation == bundleDatasetGeneration) {
                    patchesError[key] = app.catalogErrorMessage(error.message)
                }
            } finally {
                if (generation == bundleDatasetGeneration) {
                    patchesLoading[key] = false
                }
            }
        }
    }

    fun getPatches(bundle: ExternalBundleSnapshot): List<ExternalBundlePatch>? =
        patchesByBundle[patchStateKey(bundle)]

    fun isPatchesLoading(bundle: ExternalBundleSnapshot): Boolean =
        patchesLoading[patchStateKey(bundle)] == true

    fun getPatchesError(bundle: ExternalBundleSnapshot): String? =
        patchesError[patchStateKey(bundle)]

    fun getExportProgress(bundleId: Int): BundleExportProgress? = bundleExports[bundleId]

    fun getImportProgress(
        bundle: ExternalBundleSnapshot,
        isImported: Boolean
    ): PatchBundleRepository.DiscoveryImportProgress? {
        val keys = buildList {
            add(patchBundleRepository.discoveryImportKey(bundle))
            add(patchBundleRepository.discoveryImportKey(bundle, preferLatestAcrossChannels = true))
        }.distinct()

        val progressKey = keys.firstOrNull { importProgressSnapshot.containsKey(it) }
        val progress = progressKey?.let(importProgressSnapshot::get)
        if (progress != null) {
            progressKey?.let(localQueuedKeys::remove)
            return progress
        }

        val queuedKey = keys.firstOrNull { queuedImportSnapshot.contains(it) }
        val queuedFromRepo = queuedKey != null
        if (queuedFromRepo) {
            queuedKey?.let(localQueuedKeys::remove)
            return PatchBundleRepository.DiscoveryImportProgress(
                bytesRead = 0L,
                bytesTotal = null,
                status = PatchBundleRepository.DiscoveryImportStatus.Queued
            )
        }

        if (keys.any(localQueuedKeys::containsKey)) {
            return PatchBundleRepository.DiscoveryImportProgress(
                bytesRead = 0L,
                bytesTotal = null,
                status = PatchBundleRepository.DiscoveryImportStatus.Queued
            )
        }

        if (isImported) {
            keys.forEach(localQueuedKeys::remove)
        }
        return null
    }

    private suspend fun loadNextPageInternal(): Boolean {
        if (isFirstPageRefreshInProgress || !canLoadMore || isLoadingMore) return false
        val key = currentQueryKey
        val token = refreshToken
        val offset = nextOffset
        val requestedFilters = filters
        val appsTab = isAppsTab
        val force = bundleCache[key]?.forceNextPages == true
        isLoadingMore = true
        return try {
            val pageBundles = if (!appsTab) api.getBundles(filters = requestedFilters,
                limit = PAGE_SIZE, offset = offset, forceRefresh = force).getOrNull()?.bundles else null
            val pageApps = if (appsTab) api.getApps(requestedFilters, offset,
                forceRefresh = force).getOrNull() else null
            if (token != refreshToken || key != currentQueryKey) return false
            if (pageBundles == null && pageApps == null) {
                errorMessage = app.getString(R.string.patch_bundle_discovery_error)
                canLoadMore = false
                return false
            }
            val size = pageBundles?.size ?: pageApps.orEmpty().size
            val entry = BundleCacheEntry(
                bundles = (bundles.orEmpty() + pageBundles.orEmpty()).distinctBy { it.bundleId },
                apps = (apps.orEmpty() + pageApps.orEmpty()).distinctBy { it.name },
                loadedAt = bundleCache[key]?.loadedAt ?: System.currentTimeMillis(),
                nextOffset = offset + size, canLoadMore = size >= PAGE_SIZE,
                forceNextPages = force, schemaVersion = BUNDLE_CACHE_SCHEMA_VERSION
            )
            bundleCache[key] = entry
            bundles = entry.bundles
            apps = entry.apps
            nextOffset = entry.nextOffset
            canLoadMore = entry.canLoadMore
            withContext(Dispatchers.IO) { persistDiskCache(key, entry) }
            size > 0
        } finally {
            if (token == refreshToken) isLoadingMore = false
        }
    }

    private fun patchStateKey(bundle: ExternalBundleSnapshot) = BundleInstanceKey(
        apiHost = bundle.apiHost.trim().lowercase(Locale.US),
        bundleId = bundle.bundleId,
        fileHash = bundle.fileHash?.takeUnless(String::isBlank),
        patchCount = bundle.patchCount,
        sourceUrl = bundle.sourceUrl,
        version = bundle.version,
        bundleType = bundle.bundleType,
        downloadUrl = bundle.downloadUrl,
        isPrerelease = bundle.isPrerelease,
        historicalGeneration = bundle.historicalGeneration
    )

    private fun invalidatePatchState() {
        bundleDatasetGeneration++
        patchesByBundle.clear()
        patchesLoading.clear()
        patchesError.clear()
    }

    suspend fun fetchLatestBundle(
        bundle: ExternalBundleSnapshot,
        prerelease: Boolean
    ): ExternalBundleSnapshot? = withContext(Dispatchers.IO) {
        api.getLatestBundleForSource(bundleSourceUrl(bundle), prerelease).getOrNull()
    }

    @Serializable
    private data class BundleCacheEntry(
        val bundles: List<ExternalBundleSnapshot> = emptyList(),
        val apps: List<PackageNode> = emptyList(),
        val loadedAt: Long = 0,
        val nextOffset: Int = 0,
        val canLoadMore: Boolean = true,
        val forceNextPages: Boolean = false,
        val schemaVersion: Int = 0
    )

    data class BundleExportProgress(val bytesRead: Long, val bytesTotal: Long?)

    private data class BundleInstanceKey(
        val apiHost: String,
        val bundleId: Int,
        val fileHash: String?,
        val patchCount: Int,
        val sourceUrl: String,
        val version: String,
        val bundleType: String,
        val downloadUrl: String?,
        val isPrerelease: Boolean,
        val historicalGeneration: String?
    )

    private fun cacheFileForKey(key: String): File {
        val normalized = key.trim()
        val suffix = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "bundles_$suffix.json")
    }

    private fun loadDiskCache(key: String): BundleCacheEntry? {
        val file = cacheFileForKey(key)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<BundleCacheEntry>(file.readText())
        }.getOrNull()?.takeIf { entry ->
            entry.schemaVersion == BUNDLE_CACHE_SCHEMA_VERSION
        }
    }

    private fun persistDiskCache(key: String, entry: BundleCacheEntry) {
        runCatching {
            val file = cacheFileForKey(key)
            file.writeText(json.encodeToString(entry))
            cacheDir.listFiles { f -> f.name.startsWith("bundles_") }
                ?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
        }.onFailure { error ->
            if (error is SerializationException) return@onFailure
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val BUNDLE_CACHE_SCHEMA_VERSION = 5
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        val ANY_CHANNEL_QUERY = Regex("(?:[?&])channel=any(?:&|$)", RegexOption.IGNORE_CASE)
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

        fun normalizeSha256FileHash(raw: String): String? {
            val trimmed = raw.trim()
            val digest = when {
                trimmed.startsWith("sha256:", ignoreCase = true) -> trimmed.substringAfter(':')
                ':' !in trimmed -> trimmed
                else -> return null
            }
            return digest.takeIf(SHA256_HEX::matches)?.lowercase(Locale.US)
        }
    }
}

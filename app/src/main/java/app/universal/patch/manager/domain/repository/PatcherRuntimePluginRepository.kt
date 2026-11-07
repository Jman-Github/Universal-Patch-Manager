package app.universal.patch.manager.domain.repository

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import app.universal.patch.manager.R
import app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.api.successOrThrow
import app.universal.patch.manager.network.dto.GitHubAsset
import app.universal.patch.manager.network.dto.GitHubRelease
import app.universal.patch.manager.network.runtime.LoadedPatcherRuntimePlugin
import app.universal.patch.manager.network.runtime.PatcherRuntimeKind
import app.universal.patch.manager.network.runtime.PatcherRuntimePluginSourceEntry
import app.universal.patch.manager.network.runtime.PatcherRuntimePluginSourceState
import app.universal.patch.manager.network.runtime.PatcherRuntimePluginState
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.util.PendingRuntimeUpdate
import app.universal.patch.manager.util.RuntimeUpdateNotifications
import app.universal.patch.manager.util.DownloadProgressNotifier
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.tag
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import app.universal.patch.manager.network.runtime.rebindImportedRuntimeSources
import app.universal.patch.manager.network.runtime.importedRuntimeReleaseTag
import app.universal.patch.manager.worker.RuntimeRestoreWorker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.security.MessageDigest
import app.universal.patch.manager.network.runtime.toRuntimeMainName
import com.android.apksig.ApkVerifier
import app.universal.patch.manager.network.runtime.isInternalSupport
import app.universal.patch.manager.network.runtime.isInternalRuntimePlugin
import app.universal.patch.manager.network.runtime.withInternalSupportPolicy
import app.universal.patch.manager.network.runtime.catalogRuntimePlugins
import app.universal.patch.manager.network.runtime.runtimeSetupRequired as requiresRuntimeSetup
import app.universal.patch.manager.network.runtime.validateRuntimePluginPayload
import ru.solrudev.ackpine.session.Session
import app.universal.patch.manager.network.runtime.RuntimeCatalog
import app.universal.patch.manager.network.runtime.RuntimeDownloadProgress
import app.universal.patch.manager.network.runtime.RuntimeDownloadStage
import app.universal.patch.manager.network.runtime.RUNTIME_HOST_PROTOCOL

class PatcherRuntimePluginRepository(
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val app: Application,
    private val api: UpmAPI,
    private val httpService: HttpService,
    private val networkInfo: NetworkInfo,
    private val downloadProgressNotifier: DownloadProgressNotifier,
    private val catalog: RuntimeCatalogRepository
) {
    private val managedSourceRoot = app.getDir("managed_patcher_runtime_plugins", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val reloadMutex = Mutex()
    private val trustMutex = Mutex()
    private val sourceMutex = Mutex()
    private val archiveCleanupRequests = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED)
    private val _pluginStates = MutableStateFlow(emptyMap<String, PatcherRuntimePluginState>())
    private val _sourceStates = MutableStateFlow(emptyMap<String, PatcherRuntimePluginSourceState>())
    private val _hasLoadedInitialState = MutableStateFlow(false)
    private val _downloadProgress = MutableStateFlow<RuntimeDownloadProgress?>(null)
    private val installedPluginPackageNames = MutableStateFlow(emptySet<String>())
    private val installedPluginPackageSnapshots = MutableStateFlow(emptyMap<String, Triple<String?, String?, Long>>())

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action !in PACKAGE_ACTIONS) return
            val packageName = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing && action == Intent.ACTION_PACKAGE_REMOVED) return
            scope.launch {
                runCatching {
                    if (action == Intent.ACTION_PACKAGE_REMOVED && !replacing) {
                        val known = packageName in installedPluginPackageNames.value ||
                            packageName in readTrustedPackages()
                        if (known) {
                            // Installation state is temporary; signer trust is revoked explicitly.
                            removePlugin(packageName, forgetTrust = false)
                            reload()
                        }
                    } else {
                        handlePackageChange(packageName)
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.w(tag, "Failed to handle runtime package change for $packageName", it)
                }
            }
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            for (request in archiveCleanupRequests) {
                sourceMutex.withLock {
                    try {
                        // A corrupt preferences document must never be treated as an empty source list.
                        val raw = prefs.patcherRuntimePluginSourcesJson.get()
                        val entries = if (raw.isBlank()) emptyList() else
                            json.decodeFromString<List<PatcherRuntimePluginSourceEntry>>(raw).map { it.withInternalSupportPolicy() }
                        PatcherRuntimePluginRegistry.withRetainedFiles { retained ->
                            // A failed replacement may still need recovery files in its former source folder.
                            if (entries.all { entry ->
                                    val state = _sourceStates.value[entry.id]
                                    state != null && state.entry == entry && state.state is PatcherRuntimePluginSourceState.State.Loaded
                                }) {
                                pruneOrphanRuntimeDirectories(managedSourceRoot, entries.map { it.id }.toSet(), retained)
                            }
                            entries.forEach { entry ->
                                val state = _sourceStates.value[entry.id]
                                // Preserve recovery copies until the selected archive has loaded successfully.
                                if (state != null && state.entry == entry && state.state is PatcherRuntimePluginSourceState.State.Loaded) {
                                    pruneRuntimeArchives(managedSourceDirectory(entry.id), sourceFile(entry), retained)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(tag, "Failed to clean obsolete runtime archives; will retry after reload", error)
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                PACKAGE_ACTIONS.forEach(::addAction)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                app,
                packageChangeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            Log.w(tag, "Failed to register runtime plugin package change receiver", it)
        }
        scope.launch {
            // Each import has a new identity even if the pending flag stays true or
            // briefly becomes false while this observer is awaiting WorkManager.
            prefs.runtimeRestoreRequest.flow.collect {
                if (prefs.runtimeRestorePending.get()) {
                    try {
                        RuntimeRestoreWorker.enqueue(app)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(tag, "Failed to schedule runtime restore; it will resume next launch", error)
                    }
                }
            }
        }
    }

    val pluginStates = _pluginStates.asStateFlow()
    val sourceStates = _sourceStates.asStateFlow()
    val hasLoadedInitialState = _hasLoadedInitialState.asStateFlow()
    val downloadProgress = _downloadProgress.asStateFlow()
    val newPluginPackageNames = combine(
        installedPluginPackageNames,
        prefs.acknowledgedPatcherRuntimePlugins.flow,
        pluginStates
    ) { installed, acknowledged, states ->
        (installed subtract acknowledged).filterNot { isInternalRuntimePlugin(it, states[it]) }.toSet()
    }

    val loadedRuntimes = combine(pluginStates, sourceStates) { installed, sources ->
        val sourcePlugins = sources.values.sortedBy { if (it.entry.catalogId != null) 0 else 1 }
            .mapNotNull { (it.state as? PatcherRuntimePluginSourceState.State.Loaded)?.plugin }
        val installedPlugins = installed.values
            .mapNotNull { (it as? PatcherRuntimePluginState.Loaded)?.plugin }
        (sourcePlugins + installedPlugins)
            .distinctBy { it.kind }
            .associateBy { it.kind }
    }

    val loadedRuntimeSnapshot: Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>
        get() {
            val sourcePlugins = _sourceStates.value.values.sortedBy { if (it.entry.catalogId != null) 0 else 1 }
                .mapNotNull { (it.state as? PatcherRuntimePluginSourceState.State.Loaded)?.plugin }
            val installedPlugins = _pluginStates.value.values
                .mapNotNull { (it as? PatcherRuntimePluginState.Loaded)?.plugin }
            return (sourcePlugins + installedPlugins)
                .distinctBy { it.kind }
                .associateBy { it.kind }
        }

    val downloadedCatalogRuntimes = sourceStates.map { sources ->
        catalogRuntimePlugins(sources.values, emptySet())
    }

    val catalogRuntimes = combine(sourceStates, pluginStates) { sources, installed ->
        catalogRuntimePlugins(sources.values, installed.keys)
    }

    val catalogRuntimeSnapshot: Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>
        get() = catalogRuntimePlugins(_sourceStates.value.values, _pluginStates.value.keys)

    val runtimeSetupRequired = combine(
        prefs.runtimeSetupVersion.flow, hasLoadedInitialState, pluginStates, sourceStates,
        prefs.runtimeSetupFromBackup.flow
    ) { version, _, _, _, _ ->
        // Read current snapshots together: a separately collected derived catalog flow
        // can still contain its initial empty map after the scan reports completion.
        requiresRuntimeSetup(
            version, hasLoadedInitialState.value, catalogRuntimeSnapshot.keys,
            restoredFromBackup = prefs.runtimeSetupFromBackup.get()
        )
    }

    suspend fun reload() = reloadMutex.withLock {
        try {
            val installedPackages = withContext(Dispatchers.IO) {
                pm.getPackagesWithFeature(RUNTIME_PLUGIN_FEATURE)
            }
            val installedPlugins = withContext(Dispatchers.IO) {
                installedPackages.associate { it.packageName to loadInstalledPlugin(it.packageName) }
            }
            val managedSources = withContext(Dispatchers.IO) {
                buildMap {
                    readSourceEntries().forEach { entry ->
                        put(entry.id, loadManagedSource(entry))
                    }
                }
            }

            _pluginStates.value = installedPlugins
            _sourceStates.value = managedSources
            archiveCleanupRequests.trySend(Unit)
            installedPluginPackageNames.value = installedPlugins.keys
            installedPluginPackageSnapshots.value = installedPackages.associate {
                it.packageName to Triple(it.versionName, it.applicationInfo?.sourceDir, it.lastUpdateTime)
            }

            // Package scans can temporarily omit an APK while Android replaces it.
            // Keep signer trust until an explicit uninstall or revocation in the manager.
        } finally {
            // A failed scan must still exit startup loading so setup can offer recovery.
            _hasLoadedInitialState.value = true
        }
    }

    private suspend fun handlePackageChange(packageName: String) {
        refreshRuntimePluginAfterPackageChange {
            val isRuntime = packageName in installedPluginPackageNames.value ||
                packageName in readTrustedPackages() || withContext(Dispatchers.IO) {
                    pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS)
                        ?.reqFeatures?.any { it.name == RUNTIME_PLUGIN_FEATURE } == true
                }
            if (!isRuntime) return@refreshRuntimePluginAfterPackageChange false
            Log.d(tag, "Refreshing runtime plugin after package change: $packageName")
            reload()
            when (_pluginStates.value[packageName]) {
                is PatcherRuntimePluginState.Loaded -> true
                PatcherRuntimePluginState.Untrusted -> packageName !in readTrustedPackages()
                else -> false
            }
        }
    }

    suspend fun reloadIfChanged(): Boolean = withContext(Dispatchers.IO) {
        val currentPackages = pm.getPackagesWithFeature(RUNTIME_PLUGIN_FEATURE)
        val currentPackageNames = currentPackages.map { it.packageName }.toSet()
        val knownPackageNames = installedPluginPackageNames.value

        if (currentPackageNames != knownPackageNames) {
            reload()
            return@withContext true
        }

        val knownSnapshots = installedPluginPackageSnapshots.value
        val trustedPackages = readTrustedPackages()
        val anyChanged = currentPackages.any { pkg ->
            val snapshot = knownSnapshots[pkg.packageName]
            snapshot == null || snapshot.first != pkg.versionName ||
                snapshot.second != pkg.applicationInfo?.sourceDir || snapshot.third != pkg.lastUpdateTime ||
                _pluginStates.value[pkg.packageName] is PatcherRuntimePluginState.Failed ||
                (pkg.packageName in trustedPackages &&
                    _pluginStates.value[pkg.packageName] !is PatcherRuntimePluginState.Loaded)
        }

        if (anyChanged) {
            reload()
            return@withContext true
        }
        false
    }


    /** Rebind known official sources so manager releases no longer control their updates. */
    suspend fun migrateOfficialSources() = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries()
            val own = pm.getPackageInfo(app.packageName, flags = signingPackageInfoFlags)
                ?: return@withLock
            val ownSigner = archiveSigners(own).identity
            val officialRepos = setOf(
                "https://github.com/Jman-Github/Universal-Patch-Manager",
                "https://github.com/Jman-Github/Universal-ReVanced-Manager"
            )
            val migrated = entries.map { entry ->
                if (entry.catalogId != null || officialRepos.none { it.equals(entry.repoUrl.trimEnd('/'), true) }) return@map entry
                runCatching {
                    val file = sourceFile(entry)
                    val pkg = readArchivePackageInfo(file)
                    if (verifyArchiveTrust(entry, pkg) != ArchiveTrust.Trusted || !archiveSigners(pkg).trusts(ownSigner)) return@runCatching entry
                    val kind = pkg.applicationInfo?.metaData?.getString(METADATA_RUNTIME_KIND)
                    val item = catalog.cached().plugins.firstOrNull { it.kind == kind && it.packageName == pkg.packageName }
                        ?: return@runCatching entry
                    val hash = runtimeFileHash(file)
                    val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
                    entry.copy(
                        repoUrl = item.repoUrl, assetSelector = item.assetName.removeSuffix(".apk"),
                        catalogId = item.id, expectedPackageName = pkg.packageName, expectedKind = item.kind,
                        expectedSha256 = hash, expectedVersionCode = code, legacyProtocol = true
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    entry
                }
            }
            if (migrated != entries) writeSourceEntries(migrated)
            reload()
        }
    }

    private fun runtimeFileHash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Apply runtime settings and the restore marker in the same preferences transaction. */
    suspend fun importSettingsWithRuntimeRestore(
        settings: PreferencesManager.SettingsSnapshot,
        applySettings: suspend (PreferencesManager.SettingsSnapshot, Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val raw = settings.patcherRuntimePluginSourcesJson
            if (raw == null) {
                applySettings(settings, false)
                return@withLock
            }
            val local = readSourceEntries()
            val imported = if (raw.isBlank()) emptyList() else
                json.decodeFromString<List<PatcherRuntimePluginSourceEntry>>(raw)
            // Older backups omit formerly bundled engines. Preserve local choices in that case.
            val entries = rebindImportedRuntimeSources(imported.ifEmpty { local }, local)
            entries.forEach { sourceFile(it) } // Validate storage paths before saving any settings.
            applySettings(settings.copy(patcherRuntimePluginSourcesJson = json.encodeToString(entries)), true)
            try {
                reconcilePendingUpdates(entries)
                reload()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(tag, "Imported runtime settings will be recovered in the background", error)
            }
        }
    }

    /** Reconcile saved choices without opening setup or invoking Android's app installer. */
    suspend fun restoreImportedRuntimeSources(): Boolean = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            if (!prefs.runtimeRestorePending.get()) return@withLock true
            if (!networkInfo.isConnected() ||
                !prefs.allowMeteredUpdates.get() && !networkInfo.isUnmetered()) return@withLock false
            val available = try {
                catalog.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(tag, "Using the cached catalog for runtime restore", error)
                catalog.cached()
            }
            val entries = readSourceEntries().map { entry ->
                val item = available.plugins.firstOrNull { it.id == entry.catalogId }
                val officialRepo = entry.repoUrl.trimEnd('/').let { repo ->
                    repo.equals(RuntimeCatalogRepository.REPOSITORY_URL, true) ||
                        repo.equals("https://github.com/Jman-Github/Universal-Patch-Manager", true) ||
                        repo.equals("https://github.com/Jman-Github/Universal-ReVanced-Manager", true)
                }
                val legacy = if (item == null && officialRepo) available.plugins.firstOrNull {
                    canonicalAssetSelector(entry.assetSelector.removeSuffix(".apk")) ==
                        canonicalAssetSelector(it.assetName.removeSuffix(".apk"))
                } else null
                if (legacy == null) entry else entry.copy(
                    catalogId = legacy.id, repoUrl = legacy.repoUrl,
                    assetSelector = legacy.assetName.removeSuffix(".apk"), legacyProtocol = true,
                    expectedPackageName = legacy.packageName, expectedKind = legacy.kind,
                    versionKey = null, expectedVersionCode = null
                )
            }.toMutableList()
            // Removed catalog sources are handled individually below; they must not
            // prevent supported runtimes and their dependencies from being restored.
            val selected = entries.mapNotNull { it.catalogId }.toSet()
                .intersect(available.plugins.map { it.id }.toSet())
            val order = available.installationOrder(selected)
            for (item in order) {
                if (entries.none { it.catalogId == item.id }) entries += PatcherRuntimePluginSourceEntry(
                    id = "catalog-${item.id}".takeUnless { id -> entries.any { it.id == id } }
                        ?: UUID.randomUUID().toString(),
                    repoUrl = item.repoUrl,
                    assetSelector = item.assetName.removeSuffix(".apk"), catalogId = item.id, latest = true,
                    expectedPackageName = item.packageName, expectedKind = item.kind
                )
            }
            writeSourceEntries(entries)
            reload()
            val orderedIds = order.map { it.id }
            var failed = false
            entries.sortedBy { orderedIds.indexOf(it.catalogId).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .forEach { original ->
                    // Imported trust decisions for third-party and engine plugins stay intact.
                    if (original.trustedSignatureHex == null && !original.isInternalSupport) return@forEach
                    try {
                        // Frozen local imports have no downloadable release; recover only their exact APK.
                        if (!original.autoUpdate && !original.latest &&
                            original.versionKey?.startsWith("local:") == true &&
                            loadManagedSource(original).state !is PatcherRuntimePluginSourceState.State.Loaded
                        ) {
                            check(original.catalogId != null && reuseCachedCatalogArchive(original)) {
                                "The saved local runtime APK must be imported again"
                            }
                            reload()
                        }
                        val state = loadManagedSource(original).state
                        val needsRelease = original.autoUpdate || original.latest || original.legacyProtocol ||
                            state !is PatcherRuntimePluginSourceState.State.Loaded
                        if (!needsRelease) return@forEach
                        val item = available.plugins.firstOrNull { it.id == original.catalogId }
                        val updated = if (item != null) {
                            val tag = importedRuntimeReleaseTag(original).takeUnless { original.legacyProtocol }
                            val resolved = catalog.resolve(item, original.releasePrereleaseFilter, tag)
                            if (tag != null && original.expectedSha256 != null) require(
                                resolved.manifest.sha256.equals(original.expectedSha256, true)
                            ) { "The saved runtime release checksum has changed" }
                            // A channel change in the backup can deliberately select an older release.
                            installCatalogEntry(original.copy(expectedVersionCode = null), resolved)
                        } else if (!original.autoUpdate && !original.latest && original.versionKey != null) {
                            val release = api.getRepositoryReleaseByTag(
                                original.repoUrl, original.versionKey.substringBefore(':')
                            ).successOrThrow("saved runtime release")
                            val asset = release.assets.firstOrNull { it.matchesAssetSelector(original.assetSelector) }
                                ?: error("The saved runtime APK is unavailable")
                            downloadAssetToEntry(original, asset)
                            original
                        } else updateEntry(original, force = state is PatcherRuntimePluginSourceState.State.Failed)
                        entries[entries.indexOfFirst { it.id == original.id }] = updated
                        writeSourceEntries(entries)
                        reload()
                        check(_sourceStates.value[updated.id]?.state is PatcherRuntimePluginSourceState.State.Loaded) {
                            "The restored runtime could not be loaded"
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failed = true
                        Log.e(tag, "Failed to restore runtime ${original.id}", error)
                    }
                }
            if (!failed) prefs.runtimeRestorePending.update(false)
            !failed
        }
    }

    suspend fun importCatalogRuntimes(
        selected: Set<String>,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val available = catalog.cached()
            try {
                for (item in available.installationOrder(selected)) {
                    onProgress(item.name)
                    _downloadProgress.value = RuntimeDownloadProgress(item.id, item.name, RuntimeDownloadStage.PREPARING)
                    // Reuse completed modular downloads, never the installed or legacy copies.
                    val completed = _sourceStates.value.values.firstOrNull {
                        it.entry.catalogId == item.id && !it.entry.legacyProtocol &&
                            it.state is PatcherRuntimePluginSourceState.State.Loaded
                    }
                    if (completed != null) {
                        _downloadProgress.value = RuntimeDownloadProgress(item.id, item.name, RuntimeDownloadStage.FINISHING)
                        activateCatalogReplacement(item, completed.entry)
                        continue
                    }
                    val entries = readSourceEntries().toMutableList()
                    val index = entries.indexOfFirst { it.catalogId == item.id }
                    val old = entries.getOrNull(index) ?: PatcherRuntimePluginSourceEntry(
                        id = "catalog-${item.id}", repoUrl = item.repoUrl,
                        assetSelector = item.assetName.removeSuffix(".apk"), latest = true
                    )
                    val updated = installCatalogEntry(old, catalog.resolve(item, old.releasePrereleaseFilter))
                    _downloadProgress.value = RuntimeDownloadProgress(item.id, item.name, RuntimeDownloadStage.FINISHING)
                    activateCatalogReplacement(item, updated)
                }
            } finally {
                _downloadProgress.value = null
            }
        }
    }

    /** Stage and verify first, then uninstall the old app before activating its replacement. */
    private suspend fun activateCatalogReplacement(
        item: app.universal.patch.manager.network.runtime.RuntimeCatalogItem,
        replacement: PatcherRuntimePluginSourceEntry
    ) {
        require(!replacement.legacyProtocol && replacement.catalogId == item.id &&
            replacement.expectedPackageName == item.packageName && replacement.expectedKind == item.kind)
        val active = sourceFile(replacement)
        val archive = readArchivePackageInfo(active)
        validateManagedArchive(replacement, archive, active)
        resolvePlugin(archive, sourceId = replacement.id)

        // The old app may have a different signing key or version scheme. Android's
        // uninstall confirmation authorizes removing this exact catalog package.
        if (pm.getPackageInfo(item.packageName, flags = signingPackageInfoFlags) != null) {
            val result = pm.uninstallPackage(item.packageName)
            check(result == Session.State.Succeeded) {
                app.getString(R.string.runtime_catalog_remove_old_failed, item.name)
            }
            removePlugin(item.packageName)
        }
        val entries = readSourceEntries()
        val updated = entries.filterNot { it.catalogId == item.id || it.id == replacement.id } + replacement
        writeSourceEntries(updated)
        reload()
        check(_sourceStates.value[replacement.id]?.state is PatcherRuntimePluginSourceState.State.Loaded) {
            "The verified ${item.name} replacement could not be loaded. Try setup again."
        }
        // Reload queues cleanup under sourceMutex; selected old runtimes remain protected.
        archiveCleanupRequests.trySend(Unit)
    }

    suspend fun importCatalogFile(file: File): String = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val pkg = readArchivePackageInfo(file)
            val kind = pkg.applicationInfo?.metaData?.getString(METADATA_RUNTIME_KIND)
            val item = catalog.cached().plugins.firstOrNull { it.packageName == pkg.packageName && it.kind == kind }
                ?: error("This file is not a runtime from the catalog")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val ownPackage = pm.getPackageInfo(app.packageName, flags = signingPackageInfoFlags)
                ?: error("Cannot read manager signing identity")
            val entries = readSourceEntries().toMutableList()
            val index = entries.indexOfFirst { it.catalogId == item.id }
            val old = entries.getOrNull(index)
            val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
            val entry = PatcherRuntimePluginSourceEntry(
                id = old?.id ?: "catalog-${item.id}", repoUrl = item.repoUrl,
                assetSelector = item.assetName.removeSuffix(".apk"),
                catalogId = item.id, expectedPackageName = pkg.packageName, expectedKind = item.kind,
                expectedSha256 = hash, expectedVersionCode = code, expectedSize = file.length(), archiveName = hash + ".apk",
                trustedSignatureHex = archiveSigners(ownPackage).identity,
                versionKey = "local:" + hash, autoUpdate = old?.autoUpdate ?: true,
                latest = old?.latest ?: true, prerelease = old?.prerelease ?: false
            )
            validateManagedArchive(entry, pkg, file)
            val target = sourceFile(entry)
            target.parentFile?.mkdirs()
            if (!target.exists() || runCatching { validateManagedArchive(entry, readArchivePackageInfo(target), target) }.isFailure) {
                val staging = File(target.path + ".tmp")
                try {
                    file.copyTo(staging, overwrite = true)
                    ensureManagedSourceIsReadOnly(staging)
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { staging.delete() }
            }
            activateCatalogReplacement(item, entry)
            item.id
        }
    }

    private suspend fun installCatalogEntry(
        old: PatcherRuntimePluginSourceEntry,
        resolved: CatalogRuntimeRelease
    ): PatcherRuntimePluginSourceEntry {
        val info = resolved.manifest
        val ownPackage = pm.getPackageInfo(app.packageName, flags = signingPackageInfoFlags)
            ?: error("Cannot read manager signing identity")
        val entry = old.copy(
            repoUrl = resolved.item.repoUrl,
            assetSelector = resolved.item.assetName.removeSuffix(".apk"),
            catalogId = resolved.item.id,
            trustedSignatureHex = archiveSigners(ownPackage).identity,
            expectedPackageName = info.packageName,
            expectedKind = info.kind,
            expectedSha256 = info.sha256.lowercase(),
            expectedVersionCode = info.versionCode,
            expectedSize = info.size,
            legacyProtocol = false,
            archiveName = info.sha256.lowercase() + ".apk",
            versionKey = resolved.release.tagName + ":" + info.sha256.lowercase()
        )
        val oldCode = old.expectedVersionCode
        require(oldCode == null || info.versionCode >= oldCode) { "Runtime release would downgrade an installed plugin" }
        // Backups use source identities from another device. Search the local verified cache
        // as well as this source's folder before transferring an APK again.
        _downloadProgress.value = runtimeProgress(entry, RuntimeDownloadStage.VERIFYING)
        val cached = try {
            reuseCachedCatalogArchive(entry)
        } finally {
            _downloadProgress.value = null
        }
        if (cached) return entry
        downloadAssetToEntry(entry, resolved.asset)
        return entry
    }

    private fun reuseCachedCatalogArchive(entry: PatcherRuntimePluginSourceEntry): Boolean {
        val target = sourceFile(entry)
        val candidates = listOf(target) + managedSourceRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != ".downloads" }
            .flatMap { listOf(it.resolve(target.name), it.resolve("patcher-runtime.apk")) }
        for (candidate in candidates.distinct()) {
            if (!candidate.isFile || runCatching {
                    validateManagedArchive(entry, readArchivePackageInfo(candidate), candidate)
                }.isFailure) continue
            if (candidate != target) {
                target.parentFile?.mkdirs()
                val staging = File(target.path + ".tmp")
                try {
                    candidate.copyTo(staging, overwrite = true)
                    ensureManagedSourceIsReadOnly(staging)
                    Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { staging.delete() }
            }
            return true
        }
        return false
    }

    private fun validateManagedArchive(entry: PatcherRuntimePluginSourceEntry, pkg: PackageInfo, file: File) {
        val result = ApkVerifier.Builder(file).build().verify()
        require(result.isVerified) { "Runtime APK signature verification failed" }
        if (entry.catalogId == null) return
        val ownPackage = pm.getPackageInfo(app.packageName, flags = signingPackageInfoFlags)
            ?: error("Cannot read manager signing identity")
        require(archiveSigners(pkg).trusts(archiveSigners(ownPackage).identity)) {
            "Official runtime must be signed with the manager's signing key"
        }
        require(pkg.packageName == entry.expectedPackageName) { "Unexpected runtime package" }
        val metadata = pkg.applicationInfo?.metaData
        require(metadata?.getString(METADATA_RUNTIME_KIND) == entry.expectedKind) { "Unexpected runtime kind" }
        val protocol = metadata?.getInt(METADATA_RUNTIME_PROTOCOL, 0) ?: 0
        require(protocol == RUNTIME_HOST_PROTOCOL || entry.legacyProtocol && protocol == 0 &&
            entry.expectedKind in setOf("revanced-v21", "revanced-v22")) { "Incompatible runtime protocol" }
        val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        require(code == entry.expectedVersionCode) { "Unexpected runtime version" }
        val hash = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        require(hash == entry.expectedSha256) { "Runtime download checksum does not match" }
        validateRuntimePayload(requireNotNull(PatcherRuntimeKind.fromId(entry.expectedKind)), file)
    }

    private fun validateRuntimePayload(kind: PatcherRuntimeKind, file: File) {
        val abis = if (android.os.Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        validateRuntimePluginPayload(kind, file, abis.toList())
    }

    suspend fun importSourcesFromUrl(rawUrl: String): Int = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val imported = importSourceEntries(parseImportUrl(rawUrl))
            reload()
            imported.size
        }
    }

    suspend fun updateSource(id: String) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries().toMutableList()
            val index = entries.indexOfFirst { it.id == id }
            if (index == -1) return@withContext
            // A manual check should reuse the current release, while still allowing repair
            // of an archive that failed to load. Missing files are retried by updateEntry.
            val needsRepair = _sourceStates.value[id]?.state is PatcherRuntimePluginSourceState.State.Failed
            entries[index] = updateEntry(entries[index], force = needsRepair)
            writeSourceEntries(entries)
            reload()
        }
    }

    suspend fun removeSource(id: String) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val sources = readSourceEntries()
            if (sources.any { it.id == id && it.isInternalSupport }) return@withLock
            val entries = sources.filterNot { it.id == id }
            managedSourceDirectory(id).deleteRecursively()
            writeSourceEntries(entries)
            prefs.runtimeSetupFromBackup.update(false)
            reload()
        }
    }

    suspend fun clearManagedSources() = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            check(!managedSourceRoot.exists() || managedSourceRoot.deleteRecursively()) {
                "Failed to delete managed patcher runtime plugin files"
            }
            check(managedSourceRoot.mkdirs() || managedSourceRoot.isDirectory) {
                "Failed to recreate managed patcher runtime plugin directory"
            }
            writeSourceEntries(emptyList())
            prefs.runtimeRestorePending.update(false)
            prefs.runtimeSetupFromBackup.update(false)
            reload()
        }
    }

    suspend fun trustSource(id: String) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries().toMutableList()
            val index = entries.indexOfFirst { it.id == id }
            if (index == -1) return@withContext
            val entry = entries[index]
            if (entry.isInternalSupport) return@withLock
            val packageInfo = readArchivePackageInfo(sourceFile(entry))
            resolvePlugin(packageInfo, sourceId = entry.id)
            entries[index] = entry.copy(trustedSignatureHex = archiveSignatureHex(packageInfo))
            writeSourceEntries(entries)
            reload()
        }
    }

    suspend fun revokeTrustForSource(id: String) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries().map { entry ->
                if (entry.id == id && !entry.isInternalSupport) entry.copy(trustedSignatureHex = null) else entry
            }
            writeSourceEntries(entries)
            reload()
        }
    }

    suspend fun setSourceAutoUpdate(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries().map { entry ->
                if (entry.id == id && !entry.isInternalSupport) entry.copy(autoUpdate = enabled) else entry
            }
            writeSourceEntries(entries)
            reload()
        }
    }

    suspend fun setSourceLatest(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries().map { entry ->
                if (entry.id == id && !entry.isInternalSupport) {
                    entry.copy(
                        latest = enabled
                    )
                } else {
                    entry
                }
            }
            writeSourceEntries(entries)
            reload()
        }
    }

    suspend fun setSourcePrerelease(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            val entries = readSourceEntries().map { entry ->
                if (entry.id == id && !entry.isInternalSupport) entry.copy(prerelease = enabled) else entry
            }
            writeSourceEntries(entries)
            reload()
        }
    }

    suspend fun trustPackage(packageName: String) = withContext(Dispatchers.IO) {
        val packageInfo = pm.getPackageInfo(packageName, flags = signingPackageInfoFlags)
            ?: throw IllegalArgumentException("Runtime plugin $packageName is not installed")
        resolvePlugin(packageInfo, sourceId = null)
        trustMutex.withLock {
            val approval = installedTrust(packageInfo, archiveSignatureHex(packageInfo))
            writeTrustedPackages(readTrustedPackages() + (packageName to approval.encode()))
        }
        prefs.acknowledgedPatcherRuntimePlugins.update(
            prefs.acknowledgedPatcherRuntimePlugins.get() + packageName
        )
        reload()
    }

    suspend fun revokeTrustForPackage(packageName: String) = withContext(Dispatchers.IO) {
        if (isInternalRuntimePlugin(packageName, _pluginStates.value[packageName])) return@withContext
        trustMutex.withLock {
            writeTrustedPackages(readTrustedPackages() - packageName)
        }
        reload()
    }

    suspend fun acknowledgeAllNewPlugins() =
        prefs.acknowledgedPatcherRuntimePlugins.update(installedPluginPackageNames.value)

    suspend fun removePlugin(packageName: String, forgetTrust: Boolean = true) {
        if (forgetTrust) trustMutex.withLock {
            writeTrustedPackages(readTrustedPackages() - packageName)
        }
        prefs.acknowledgedPatcherRuntimePlugins.update(
            prefs.acknowledgedPatcherRuntimePlugins.get() - packageName
        )
        _pluginStates.value = _pluginStates.value - packageName
        installedPluginPackageNames.value = installedPluginPackageNames.value - packageName
    }

    val availableRuntimeVersions = combine(sourceStates, prefs.pendingRuntimeUpdatesJson.flow) { states, raw ->
        decodePendingUpdates(raw).filter { it.matches(states[it.sourceId]?.entry) }
            .associate { it.sourceId to it.version }
    }

    private fun decodePendingUpdates(raw: String): List<PendingRuntimeUpdate> =
        runCatching { json.decodeFromString<List<PendingRuntimeUpdate>>(raw) }.getOrDefault(emptyList())

    private fun PendingRuntimeUpdate.matches(entry: PatcherRuntimePluginSourceEntry?): Boolean =
        entry != null && entry.trustedSignatureHex != null && !entry.isInternalSupport &&
            installedVersionKey == entry.versionKey && prerelease == entry.releasePrereleaseFilter

    private suspend fun reconcilePendingUpdates(
        entries: List<PatcherRuntimePluginSourceEntry>,
        checked: Map<String, PendingRuntimeUpdate?> = emptyMap(),
        notify: Boolean = false
    ) {
        val sources = entries.associateBy { it.id }
        val previous = decodePendingUpdates(prefs.pendingRuntimeUpdatesJson.get())
        val pending = previous.filter { it.matches(sources[it.sourceId]) }
            .associateByTo(linkedMapOf()) { it.sourceId }
        checked.forEach { (id, update) ->
            if (update != null && update.matches(sources[id])) pending[id] = update
            else pending.remove(id)
        }
        val updates = pending.values.toList()
        if (updates != previous) prefs.pendingRuntimeUpdatesJson.update(json.encodeToString(updates))
        runCatching {
            RuntimeUpdateNotifications.showAvailable(
                app, updates.filter { sources[it.sourceId]?.autoUpdate == false }, onlyIfActive = !notify
            )
        }.onFailure { Log.w(tag, "Failed to show runtime updates", it) }
    }

    suspend fun updateCheck(background: Boolean = true) = withContext(Dispatchers.IO) {
        sourceMutex.withLock {
            if (!networkInfo.isConnected()) return@withLock
            if (!prefs.allowMeteredUpdates.get() && !networkInfo.isUnmetered()) return@withLock
            val entries = readSourceEntries().toMutableList()
            if (entries.isEmpty()) {
                reconcilePendingUpdates(entries, notify = background)
                return@withLock
            }
            val currentCatalog = try { catalog.refresh() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(tag, "Using cached runtime catalog for updates", error)
                catalog.cached()
            }
            reload()
            val checked = linkedMapOf<String, PendingRuntimeUpdate?>()
            val updated = mutableListOf<PendingRuntimeUpdate>()
            entries.indices.forEach { index ->
                val entry = entries[index]
                if (entry.trustedSignatureHex == null) return@forEach
                try {
                    val state = sourceStates.value[entry.id]
                    val name = (state?.name ?: entry.assetSelector.toReadableSourceName()).toRuntimeMainName()
                    val resolved = entry.catalogId?.let { catalogId ->
                        val item = currentCatalog.plugins.firstOrNull { it.id == catalogId }
                            ?: error("Runtime was removed from the catalog")
                        catalog.resolve(item, entry.releasePrereleaseFilter)
                    }
                    val release = if (resolved == null) latestReleaseFor(
                        entry.repoUrl, entry.releasePrereleaseFilter, entry.assetSelector
                    ) else null
                    val asset = resolved?.asset ?: release!!.assets
                        .filter(::isSupportedRuntimeAsset)
                        .firstOrNull { it.matchesAssetSelector(entry.assetSelector) }
                        ?: error("No matching runtime asset")
                    val key = if (resolved != null)
                        resolved.release.tagName + ":" + resolved.manifest.sha256.lowercase()
                    else releaseVersionKey(release!!, asset)
                    // A filtered channel may only expose releases older than the
                    // installed runtime. Installation rejects these; do not advertise them.
                    if (resolved != null && entry.expectedVersionCode != null &&
                        resolved.manifest.versionCode < entry.expectedVersionCode) {
                        checked[entry.id] = null
                        return@forEach
                    }
                    // Local imports use "local:<sha>" rather than a release tag.
                    // For catalog runtimes, identical verified bytes are not an update.
                    val changed = if (resolved != null && entry.expectedSha256 != null) {
                        !resolved.manifest.sha256.equals(entry.expectedSha256, ignoreCase = true)
                    } else key != entry.versionKey
                    val version = resolved?.manifest?.version ?: release!!.tagName
                    val candidate = PendingRuntimeUpdate(
                        entry.id, entry.versionKey, entry.releasePrereleaseFilter, name,
                        if (version.firstOrNull()?.isDigit() == true) "v$version" else version
                    )
                    checked[entry.id] = candidate.takeIf { changed }
                    if (!background || !entry.autoUpdate) return@forEach
                    val mode = shouldAutoUpdate(entry, state?.state)
                    if (mode == SourceUpdateMode.SKIP) return@forEach
                    if (!changed && mode != SourceUpdateMode.FORCE &&
                        state?.state is PatcherRuntimePluginSourceState.State.Loaded &&
                        sourceFile(entry).exists()) return@forEach
                    val replacement = if (resolved != null) installCatalogEntry(entry, resolved)
                    else {
                        downloadAssetToEntry(entry, asset)
                        entry.copy(versionKey = key)
                    }
                    entries[index] = replacement
                    // Persist each success before cancellation or another source's failure.
                    writeSourceEntries(entries)
                    reload()
                    if (changed && sourceStates.value[entry.id]?.state is PatcherRuntimePluginSourceState.State.Loaded)
                        updated += candidate
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "Failed to check runtime source " + entry.repoUrl, error)
                }
            }
            reconcilePendingUpdates(entries, checked, notify = background)
            runCatching { RuntimeUpdateNotifications.showCompleted(app, updated) }
                .onFailure { Log.w(tag, "Failed to show runtime update notifications", it) }
        }
    }

    private suspend fun loadInstalledPlugin(packageName: String): PatcherRuntimePluginState {
        return try {
            val packageInfo = pm.getPackageInfo(
                packageName,
                flags = signingPackageInfoFlags
            ) ?: throw IllegalArgumentException("Runtime plugin $packageName is not installed")
            if (!verifyPackageTrust(packageInfo)) return PatcherRuntimePluginState.Untrusted
            PatcherRuntimePluginState.Loaded(resolvePlugin(packageInfo, sourceId = null))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load patcher runtime plugin $packageName", t)
            PatcherRuntimePluginState.Failed(t)
        }
    }

    private fun loadManagedSource(entry: PatcherRuntimePluginSourceEntry): PatcherRuntimePluginSourceState {
        val fallbackName = entry.assetSelector.toReadableSourceName()
        return try {
            val file = sourceFile(entry)
            if (!file.exists()) {
                return PatcherRuntimePluginSourceState(
                    entry = entry,
                    name = fallbackName,
                    version = null,
                    repoUrl = entry.repoUrl,
                    state = PatcherRuntimePluginSourceState.State.Missing
                )
            }

            ensureManagedSourceIsReadOnly(file)
            val packageInfo = readArchivePackageInfo(file)
            validateManagedArchive(entry, packageInfo, file)
            when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                ArchiveTrust.Trusted -> Unit
                is ArchiveTrust.Untrusted -> {
                    return PatcherRuntimePluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = PatcherRuntimePluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex
                        )
                    )
                }
                is ArchiveTrust.Mismatch -> {
                    return PatcherRuntimePluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = PatcherRuntimePluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex
                        )
                    )
                }
                is ArchiveTrust.Unreadable -> {
                    return PatcherRuntimePluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = PatcherRuntimePluginSourceState.State.Failed(trust.throwable)
                    )
                }
            }
            val plugin = resolvePlugin(packageInfo, sourceId = entry.id)
            PatcherRuntimePluginSourceState(
                entry = entry,
                name = plugin.name,
                version = plugin.version,
                repoUrl = entry.repoUrl,
                state = PatcherRuntimePluginSourceState.State.Loaded(plugin)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load managed patcher runtime source ${entry.repoUrl}", t)
            PatcherRuntimePluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = PatcherRuntimePluginSourceState.State.Failed(t)
            )
        }
    }

    private fun resolvePlugin(packageInfo: PackageInfo, sourceId: String?): LoadedPatcherRuntimePlugin {
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
            else packageInfo.versionCode.toLong()
        require(!runtimePluginRequiresUpdate(packageInfo.packageName, versionCode)) {
            "Update the ReVanced v21 runtime plugin to 1.8.1 or newer to use it with this UPM version."
        }
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("Missing ApplicationInfo for ${packageInfo.packageName}")
        val kind = PatcherRuntimeKind.fromId(
            applicationInfo.metaData?.getString(METADATA_RUNTIME_KIND)
        ) ?: throw IllegalArgumentException(
            "Missing patcher runtime metadata $METADATA_RUNTIME_KIND in ${packageInfo.packageName}"
        )
        val protocol = applicationInfo.metaData?.getInt(METADATA_RUNTIME_PROTOCOL, 0) ?: 0
        require(protocol == 0 || protocol == RUNTIME_HOST_PROTOCOL) { "Incompatible runtime protocol" }
        require(protocol != 0 || kind in setOf(PatcherRuntimeKind.REVANCED_V21, PatcherRuntimeKind.REVANCED_V22)) {
            "This runtime needs an update for modular UPM"
        }
        val sourceDir = applicationInfo.sourceDir
            ?: throw IllegalStateException("Missing source path for ${packageInfo.packageName}")
        return LoadedPatcherRuntimePlugin(
            packageName = packageInfo.packageName,
            name = with(pm) { packageInfo.label() },
            version = packageInfo.versionName ?: "0",
            upstreamVersion = applicationInfo.metaData?.getString("app.universal.patch.manager.patcher.runtime.upstream"),
            kind = kind,
            sourceId = sourceId,
            apkFile = File(sourceDir)
        )
    }

    private suspend fun verifyPackageTrust(packageInfo: PackageInfo): Boolean = trustMutex.withLock {
        val trusted = readTrustedPackages()
        val approval = RuntimePluginInstalledTrust.decode(trusted[packageInfo.packageName])
            ?: return@withLock false
        val signers = archiveSigners(packageInfo)
        val uid = packageInfo.applicationInfo?.uid ?: -1
        if (!approval.trusts(packageInfo.packageName, uid, packageInfo.firstInstallTime, signers)) {
            return@withLock false
        }

        // Migrate legacy approvals while the signer still matches, and retain the
        // current signer as the fallback if this package is later reinstalled.
        val refreshed = installedTrust(packageInfo, signers.identity).encode()
        if (trusted[packageInfo.packageName] != refreshed) {
            writeTrustedPackages(trusted + (packageInfo.packageName to refreshed))
        }
        true
    }

    private fun installedTrust(packageInfo: PackageInfo, signer: String) = RuntimePluginInstalledTrust(
        signer = signer,
        packageName = packageInfo.packageName,
        uid = packageInfo.applicationInfo?.uid,
        firstInstallTime = packageInfo.firstInstallTime
    )

    private suspend fun readSourceEntries(): List<PatcherRuntimePluginSourceEntry> {
        val raw = prefs.patcherRuntimePluginSourcesJson.get()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PatcherRuntimePluginSourceEntry>>(raw).map { it.withInternalSupportPolicy() }
        }.getOrElse {
            Log.e(tag, "Failed to decode patcher runtime source entries", it)
            emptyList()
        }
    }

    private suspend fun writeSourceEntries(entries: List<PatcherRuntimePluginSourceEntry>) {
        prefs.patcherRuntimePluginSourcesJson.update(
            json.encodeToString(entries.sortedBy { "${it.repoUrl}|${it.assetSelector}|${it.id}" })
        )
        reconcilePendingUpdates(entries)
    }

    private suspend fun readTrustedPackages(): Map<String, String> {
        val raw = prefs.trustedPatcherRuntimePluginsJson.get()
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrElse {
                Log.e(tag, "Failed to decode trusted patcher runtime plugins", it)
                emptyMap()
            }
    }

    private suspend fun writeTrustedPackages(entries: Map<String, String>) {
        prefs.trustedPatcherRuntimePluginsJson.update(
            if (entries.isEmpty()) "" else json.encodeToString<Map<String, String>>(entries.toSortedMap().toMap())
        )
    }

    private fun readArchivePackageInfo(file: File): PackageInfo =
        pm.getPackageInfo(file, includeSigning = true)
            ?: throw Exception("Failed to read patcher runtime archive ${file.name}")

    private fun archiveSignatureHex(packageInfo: PackageInfo): String = archiveSigners(packageInfo).identity

    private fun archiveSigners(packageInfo: PackageInfo): RuntimePluginSigners {
        fun android.content.pm.Signature.hex() =
            toByteArray().joinToString(separator = "") { byte -> "%02X".format(byte) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.let { signingInfo ->
                val history = if (signingInfo.hasMultipleSigners()) emptyList()
                    else signingInfo.signingCertificateHistory.orEmpty().map { it.hex() }
                val current = signingInfo.apkContentsSigners.orEmpty().map { it.hex() }
                    .ifEmpty { history.takeLast(1) }
                if (current.isNotEmpty()) return RuntimePluginSigners(current, history)
            }
        }
        @Suppress("DEPRECATION")
        val current = packageInfo.signatures.orEmpty().map { it.hex() }
        if (current.isEmpty()) throw SecurityException(app.getString(R.string.runtime_signer_unreadable, packageInfo.packageName))
        return RuntimePluginSigners(current)
    }

    private fun verifyArchiveTrust(
        entry: PatcherRuntimePluginSourceEntry,
        packageInfo: PackageInfo
    ): ArchiveTrust {
        val signers = runCatching { archiveSigners(packageInfo) }
            .getOrElse { return ArchiveTrust.Unreadable(it) }
        val actualSignatureHex = signers.identity
        val expectedSignatureHex = entry.trustedSignatureHex
            ?: return ArchiveTrust.Untrusted(actualSignatureHex)

        return if (signers.trusts(expectedSignatureHex)) {
            ArchiveTrust.Trusted
        } else {
            ArchiveTrust.Mismatch(actualSignatureHex)
        }
    }

    private suspend fun importSourceEntries(
        importRequest: ImportRequest
    ): List<PatcherRuntimePluginSourceEntry> {
        val selectedAssets = when (importRequest) {
            is ImportRequest.Asset -> {
                val release = releaseForTag(importRequest.repoUrl, importRequest.releaseTag)
                release.assets.filter {
                    isSupportedRuntimeAsset(it) && it.matchesAssetSelector(importRequest.assetSelector)
                }.map { release to it }
            }
            is ImportRequest.Repository -> {
                val stable = releaseHistoryFor(importRequest.repoUrl, prerelease = false, limit = 100)
                val prereleases = releaseHistoryFor(importRequest.repoUrl, prerelease = true, limit = 100)
                selectRuntimePluginAssets(stable + prereleases) { asset ->
                    if (isSupportedRuntimeAsset(asset)) {
                        canonicalAssetSelector(normalizeAssetSelector(asset.name))
                    } else null
                }
            }
        }
        if (selectedAssets.isEmpty()) {
            error("No matching patcher runtime APK asset found for ${importRequest.repoUrl}")
        }

        val entries = readSourceEntries().toMutableList()
        val importedEntries = mutableListOf<PatcherRuntimePluginSourceEntry>()
        selectedAssets.forEach { (release, asset) ->
            val selector = canonicalAssetSelector(normalizeAssetSelector(asset.name))
            val versionKey = releaseVersionKey(release, asset)
            val existingIndex = entries.indexOfFirst {
                it.repoUrl == importRequest.repoUrl &&
                    canonicalAssetSelector(it.assetSelector) == selector
            }
            val existing = entries.getOrNull(existingIndex)
            val entry = (existing ?: PatcherRuntimePluginSourceEntry(
                id = UUID.randomUUID().toString(),
                repoUrl = importRequest.repoUrl,
                assetSelector = selector,
                latest = true
            )).copy(
                repoUrl = importRequest.repoUrl,
                assetSelector = selector,
                prerelease = release.prerelease,
                versionKey = versionKey
            )
            try {
                downloadAssetToEntry(entry, asset)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (importRequest is ImportRequest.Repository && t.isMissingRuntimeMetadata()) {
                    if (existing == null) managedSourceDirectory(entry.id).deleteRecursively()
                    return@forEach
                }
                throw t
            }

            if (existingIndex >= 0) entries[existingIndex] = entry else entries += entry
            importedEntries += entry
        }

        if (importedEntries.isEmpty()) {
            error("No valid patcher runtime APK assets found for ${importRequest.repoUrl}")
        }

        writeSourceEntries(entries)
        return importedEntries
    }

    private suspend fun updateEntry(
        entry: PatcherRuntimePluginSourceEntry,
        force: Boolean
    ): PatcherRuntimePluginSourceEntry {
        if (entry.catalogId != null) {
            val item = catalog.cached().plugins.firstOrNull { it.id == entry.catalogId }
                ?: error("Runtime was removed from the catalog")
            return installCatalogEntry(entry, catalog.resolve(item, entry.releasePrereleaseFilter))
        }
        val release = latestReleaseFor(
            repoUrl = entry.repoUrl,
            prerelease = entry.releasePrereleaseFilter,
            assetSelector = entry.assetSelector
        )
        val asset = release.assets
            .filter(::isSupportedRuntimeAsset)
            .firstOrNull { it.matchesAssetSelector(entry.assetSelector) }
            ?: throw Exception("No matching patcher runtime APK asset found for ${entry.repoUrl}")

        val versionKey = releaseVersionKey(release, asset)
        if (!force && versionKey == entry.versionKey && sourceFile(entry).exists()) {
            return entry
        }

        downloadAssetToEntry(entry, asset)
        return entry.copy(versionKey = versionKey)
    }

    private suspend fun shouldAutoUpdate(
        entry: PatcherRuntimePluginSourceEntry,
        currentState: PatcherRuntimePluginSourceState.State?
    ): SourceUpdateMode {
        if (!entry.autoUpdate || entry.trustedSignatureHex == null) return SourceUpdateMode.SKIP
        if (currentState is PatcherRuntimePluginSourceState.State.Missing) return SourceUpdateMode.FORCE
        if (currentState is PatcherRuntimePluginSourceState.State.Failed) return SourceUpdateMode.CHECK
        val file = sourceFile(entry)
        if (!file.exists()) return SourceUpdateMode.FORCE

        val packageInfo = runCatching { readArchivePackageInfo(file) }
            .getOrElse {
                Log.e(tag, "Failed to inspect patcher runtime source ${entry.repoUrl}", it)
                return SourceUpdateMode.FORCE
            }
        return when (val trust = verifyArchiveTrust(entry, packageInfo)) {
            ArchiveTrust.Trusted -> SourceUpdateMode.CHECK
            is ArchiveTrust.Untrusted, is ArchiveTrust.Mismatch -> SourceUpdateMode.SKIP
            is ArchiveTrust.Unreadable -> {
                Log.e(tag, "Failed to verify patcher runtime source ${entry.repoUrl}", trust.throwable)
                SourceUpdateMode.FORCE
            }
        }
    }

    private suspend fun releaseForTag(repoUrl: String, releaseTag: String): GitHubRelease =
        releaseHistoryFor(repoUrl, prerelease = null, limit = 100)
            .firstOrNull { it.tagName == releaseTag }
            ?: throw Exception("Release $releaseTag not found for $repoUrl")

    private suspend fun releaseHistoryFor(
        repoUrl: String,
        prerelease: Boolean?,
        limit: Int
    ): List<GitHubRelease> =
        api.getRepositoryReleaseHistory(
            repoUrl = repoUrl,
            prerelease = prerelease,
            limit = limit
        ).successOrThrow("patcher runtime releases for $repoUrl")

    private suspend fun findLatestReleaseFor(
        repoUrl: String,
        prerelease: Boolean?,
        assetSelector: String?
    ): GitHubRelease? =
        releaseHistoryFor(repoUrl = repoUrl, prerelease = prerelease, limit = 100)
            .firstOrNull { release ->
                release.assets
                    .filter(::isSupportedRuntimeAsset)
                    .any { assetSelector == null || it.matchesAssetSelector(assetSelector) }
            }

    private suspend fun latestReleaseFor(
        repoUrl: String,
        prerelease: Boolean?,
        assetSelector: String
    ): GitHubRelease =
        findLatestReleaseFor(
            repoUrl = repoUrl,
            prerelease = prerelease,
            assetSelector = assetSelector
        )
            ?: throw Exception("No releases found for $repoUrl")

    private fun runtimeProgress(
        entry: PatcherRuntimePluginSourceEntry,
        stage: RuntimeDownloadStage,
        bytes: Long = 0,
        total: Long? = entry.expectedSize
    ) = RuntimeDownloadProgress(
        catalogId = entry.catalogId,
        name = PatcherRuntimeKind.fromId(entry.expectedKind)?.displayName
            ?: _sourceStates.value[entry.id]?.name ?: entry.assetSelector.toReadableSourceName(),
        stage = stage, bytesDownloaded = bytes.coerceAtLeast(0), totalBytes = total
    )

    private suspend fun downloadAssetToEntry(
        entry: PatcherRuntimePluginSourceEntry,
        asset: GitHubAsset
    ) {
        _downloadProgress.value = runtimeProgress(entry, RuntimeDownloadStage.DOWNLOADING)
        try {
            performRuntimeDownload(entry, asset)
        } finally {
            _downloadProgress.value = null
        }
    }

    private suspend fun performRuntimeDownload(
        entry: PatcherRuntimePluginSourceEntry,
        asset: GitHubAsset
    ) {
        val directory = managedSourceDirectory(entry.id)
        directory.mkdirs()
        val target = sourceFile(entry)
        val resumable = entry.expectedSha256 != null && entry.expectedSize != null
        if (resumable) {
            require(requireNotNull(entry.expectedSha256).matches(Regex("[a-f0-9]{64}")) &&
                requireNotNull(entry.expectedSize) > 0) { "Invalid resumable runtime download metadata" }
        }
        val tempFile = if (resumable) {
            managedSourceRoot.resolve(".downloads").apply { mkdirs() }
                .resolve(entry.expectedSha256 + ".part")
        } else directory.resolve("patcher-runtime.tmp")
        if (!resumable && tempFile.exists()) tempFile.delete()
        if (resumable && tempFile.length() > requireNotNull(entry.expectedSize)) tempFile.delete()
        var transferred = false
        fun runtimeTitle(name: String) = if (name.contains("runtime support", ignoreCase = true)) name
            else app.getString(R.string.runtime_download_title, name.toRuntimeMainName())
        val progressNotification = downloadProgressNotifier.begin(
            runtimeTitle(
                catalog.cached().plugins.firstOrNull { it.id == entry.catalogId }?.name
                    ?: _sourceStates.value[entry.id]?.name
                    ?: when {
                        asset.name.contains("revanced-v21", true) -> "ReVanced v21"
                        asset.name.contains("revanced-v22", true) -> "ReVanced v22"
                        asset.name.contains("morphe", true) -> "Morphe"
                        asset.name.contains("reseam", true) -> "Reseam"
                        asset.name.contains("shared-support", true) -> "UPM"
                        else -> entry.assetSelector.toReadableSourceName()
                    })
        )

        fun reportProgress(bytes: Long, total: Long?) {
            progressNotification.update(bytes, total)
            _downloadProgress.value = runtimeProgress(entry, RuntimeDownloadStage.DOWNLOADING,
                bytes, entry.expectedSize ?: total)
        }
        runCatching {
            reportProgress(tempFile.length(), entry.expectedSize)
            if (resumable) {
                if (tempFile.length() != entry.expectedSize) {
                    kotlinx.coroutines.coroutineScope {
                        val progress = launch {
                            while (true) {
                                reportProgress(tempFile.length(), entry.expectedSize)
                                kotlinx.coroutines.delay(250)
                            }
                        }
                        try {
                            httpService.download(saveLocation = tempFile, resumeFrom = tempFile.length()) {
                                url(asset.downloadUrl)
                                headers.append("Accept-Encoding", "identity")
                            }
                        } finally {
                            progress.cancel()
                            progress.join()
                        }
                    }
                }
                require(tempFile.length() == entry.expectedSize) { "Runtime download is incomplete" }
            } else {
                httpService.downloadToFile(
                    saveLocation = tempFile,
                    builder = { url(asset.downloadUrl) },
                    onProgress = ::reportProgress
                )
            }
            transferred = true
            _downloadProgress.value = runtimeProgress(entry, RuntimeDownloadStage.VERIFYING,
                tempFile.length(), entry.expectedSize ?: tempFile.length())
            val packageInfo = readArchivePackageInfo(tempFile)
            validateManagedArchive(entry, packageInfo, tempFile)
            val plugin = resolvePlugin(packageInfo, sourceId = entry.id)
            progressNotification.updateTitle(runtimeTitle(plugin.name))
            if (entry.trustedSignatureHex != null) {
                when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                    ArchiveTrust.Trusted -> Unit
                    is ArchiveTrust.Mismatch -> throw SecurityException(
                        "Signer mismatch for ${packageInfo.packageName}"
                    )
                    is ArchiveTrust.Unreadable -> throw SecurityException(
                        app.getString(R.string.runtime_signer_unreadable, packageInfo.packageName),
                        trust.throwable
                    )
                    is ArchiveTrust.Untrusted -> throw SecurityException(
                        "Expected trusted patcher runtime source for ${packageInfo.packageName}"
                    )
                }
            }
            ensureManagedSourceIsReadOnly(tempFile)
            Files.move(tempFile.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { error ->
            if (error is CancellationException) {
                progressNotification.cancel()
            } else {
                progressNotification.fail()
            }
            // Keep only interrupted transfers; failed integrity checks must restart from zero.
            if (!resumable || transferred) tempFile.delete()
            throw error
        }

        tempFile.delete()
        progressNotification.complete()
    }

    private fun parseImportUrl(rawUrl: String): ImportRequest {
        val normalizedUrl = extractImportUrl(rawUrl)
        require(normalizedUrl.isNotBlank()) { "Enter a GitHub repository or release asset URL." }
        val uri = try {
            URI(normalizedUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("Unsupported patcher runtime source URL: $normalizedUrl")
        }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Unsupported patcher runtime source URL: $normalizedUrl")
        val parts = uri.path.trim('/').split('/').filter(String::isNotBlank)
        return when (host) {
            "github.com" -> {
                require(parts.size >= 2) { "Unsupported patcher runtime source URL: $normalizedUrl" }
                val repoUrl = githubRepoUrl(parts[0], parts[1])
                if (parts.size >= 5 && parts[2] == "releases" && parts[3] == "download") {
                    ImportRequest.Asset(
                        repoUrl = repoUrl,
                        assetSelector = canonicalAssetSelector(normalizeAssetSelector(parts.last())),
                        releaseTag = parts[4]
                    )
                } else {
                    ImportRequest.Repository(repoUrl)
                }
            }
            "api.github.com" -> {
                val reposIndex = parts.indexOf("repos")
                require(reposIndex != -1 && parts.size >= reposIndex + 3) {
                    "Unsupported patcher runtime source URL: $normalizedUrl"
                }
                ImportRequest.Repository(githubRepoUrl(parts[reposIndex + 1], parts[reposIndex + 2]))
            }
            else -> throw IllegalArgumentException(
                "Only GitHub repository or release asset URLs are supported."
            )
        }
    }

    private fun extractImportUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return trimmed
        val matched = GITHUB_URL_REGEX.find(trimmed)?.value ?: trimmed
        return matched
            .trim()
            .trim('<', '>', '"', '\'')
            .trimStart('(', '[', '{')
            .trimEnd(')', ']', '}', '.', ',', ';')
    }

    private fun managedSourceDirectory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid runtime source identity" }
        return managedSourceRoot.resolve(id)
    }
    fun sourceIconFile(entry: PatcherRuntimePluginSourceEntry): File? = sourceFile(entry).takeIf { it.isFile }

    private fun sourceFile(entry: PatcherRuntimePluginSourceEntry): File {
        val name = entry.archiveName ?: "patcher-runtime.apk"
        require(name == "patcher-runtime.apk" || name.matches(Regex("[a-f0-9]{64}\\.apk"))) { "Invalid runtime archive name" }
        return managedSourceDirectory(entry.id).resolve(name)
    }

    private fun ensureManagedSourceIsReadOnly(file: File) {
        if (!file.exists() || !file.canWrite()) return
        check(file.setReadOnly() || !file.canWrite()) {
            "Managed patcher runtime source ${file.name} must be read-only before loading."
        }
    }

    private fun isSupportedRuntimeAsset(asset: GitHubAsset): Boolean =
        isRuntimePluginAssetName(asset.name)

    private fun GitHubAsset.matchesAssetSelector(assetSelector: String): Boolean =
        canonicalAssetSelector(normalizeAssetSelector(name)) ==
            canonicalAssetSelector(assetSelector)

    private fun Throwable.isMissingRuntimeMetadata(): Boolean =
        this is IllegalArgumentException &&
            message?.contains(METADATA_RUNTIME_KIND, ignoreCase = true) == true

    private fun releaseVersionKey(release: GitHubRelease, asset: GitHubAsset) =
        "${release.tagName}:${asset.name}"

    private fun normalizeAssetSelector(assetName: String): String {
        val baseName = assetName.substringBeforeLast('.', assetName)
        val match = VERSION_SUFFIX_REGEX.matchEntire(baseName)
        return (match?.groupValues?.getOrNull(1) ?: baseName).lowercase()
    }

    private fun canonicalAssetSelector(selector: String): String {
        val normalized = selector.lowercase().removePrefix("universal-revanced-manager-")
        return when (normalized) {
            "revanced-runtime-v21", "revanced-runtime-v21-release",
            "revanced.v21-runtime-plugin", "revanced.v21-runtime-plugin-release" ->
                "revanced.v21-plugin"
            else -> normalized
        }
    }

    private fun String.toReadableSourceName(): String =
        split(Regex("[-_]+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.uppercase() } }

    private sealed interface ImportRequest {
        val repoUrl: String
        data class Repository(override val repoUrl: String) : ImportRequest
        data class Asset(
            override val repoUrl: String,
            val assetSelector: String,
            val releaseTag: String
        ) : ImportRequest
    }

    private sealed interface ArchiveTrust {
        data object Trusted : ArchiveTrust
        data class Mismatch(val signatureHex: String) : ArchiveTrust
        data class Untrusted(val signatureHex: String) : ArchiveTrust
        data class Unreadable(val throwable: Throwable) : ArchiveTrust
    }

    private enum class SourceUpdateMode {
        SKIP,
        CHECK,
        FORCE
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val RUNTIME_PLUGIN_FEATURE = "app.universal.patch.manager.patcher.runtime"
        const val METADATA_RUNTIME_PROTOCOL = "app.universal.patch.manager.patcher.runtime.protocol"
        const val METADATA_RUNTIME_KIND = "app.universal.patch.manager.patcher.runtime.kind"
        private val PACKAGE_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED
        )
        val signingPackageInfoFlags: Int
            get() {
                val signingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
                return PackageManager.GET_META_DATA or signingFlag
            }
        val VERSION_SUFFIX_REGEX = Regex(
            pattern = "^(.*?)-v?\\d+(?:\\.\\d+)+(?:[-._][A-Za-z0-9]+)*$",
            option = RegexOption.IGNORE_CASE
        )
        val GITHUB_URL_REGEX = Regex(
            pattern = "https?://(?:github\\.com|api\\.github\\.com)\\S+",
            option = RegexOption.IGNORE_CASE
        )
        fun githubRepoUrl(owner: String, repo: String) = "https://github.com/$owner/$repo"
    }
}

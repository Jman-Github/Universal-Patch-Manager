package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.network.downloader.toDownloaderMainName
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.data.room.AppDatabase
import app.universal.patch.manager.data.room.plugins.TrustedDownloaderPlugin
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.api.successOrThrow
import app.universal.patch.manager.network.downloader.ApkDownloadHelperContract
import app.universal.patch.manager.network.downloader.DownloaderPluginSourceEntry
import app.universal.patch.manager.network.downloader.DownloaderPluginSourceState
import app.universal.patch.manager.network.downloader.DownloaderPluginState
import app.universal.patch.manager.network.downloader.LoadedDownloaderPlugin
import app.universal.patch.manager.network.downloader.ParceledDownloaderData
import app.universal.patch.manager.network.dto.GitHubAsset
import app.universal.patch.manager.network.dto.GitHubRelease
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.plugin.downloader.DownloaderBuilder
import app.universal.patch.manager.plugin.downloader.GetScope as LegacyGetScope
import app.universal.patch.manager.plugin.downloader.OutputDownloadScope as LegacyOutputDownloadScope
import app.universal.patch.manager.plugin.downloader.PluginHostApi
import app.universal.patch.manager.plugin.downloader.Scope as LegacyScope
import app.revanced.manager.downloader.DownloaderBuilder as RevancedModernDownloaderBuilder
import app.revanced.manager.downloader.DownloaderHostApi as RevancedModernDownloaderHostApi
import app.revanced.manager.downloader.Scope as RevancedModernScope
import app.revanced.manager.plugin.downloader.DownloaderBuilder as RevancedLegacyDownloaderBuilder
import app.revanced.manager.plugin.downloader.Scope as RevancedLegacyScope
import app.universal.patch.manager.downloader.DownloaderBuilder as ModernDownloaderBuilder
import app.universal.patch.manager.downloader.DownloaderHostApi as ModernDownloaderHostApi
import app.universal.patch.manager.downloader.Scope as ModernScope
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.DownloadProgressNotifier
import app.universal.patch.manager.util.tag
import dalvik.system.PathClassLoader
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI
import java.util.UUID

@OptIn(PluginHostApi::class, ModernDownloaderHostApi::class, RevancedModernDownloaderHostApi::class)
class DownloaderPluginRepository(
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val app: Application,
    private val api: UpmAPI,
    private val httpService: HttpService,
    private val networkInfo: NetworkInfo,
    private val downloadProgressNotifier: DownloadProgressNotifier,
    db: AppDatabase
) {
    private val trustDao = db.trustedDownloaderPluginDao()
    private val managedSourceRoot = app.getDir("managed_downloader_plugins", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val reloadMutex = Mutex()
    private val _pluginStates = MutableStateFlow(emptyMap<String, DownloaderPluginState>())
    private val _sourceStates = MutableStateFlow(emptyMap<String, DownloaderPluginSourceState>())
    private val _apkDownloadHelpers = MutableStateFlow(emptyList<ApkDownloadHelperContract.Helper>())
    private val _trustedApkDownloadHelpers = MutableStateFlow(emptyList<ApkDownloadHelperContract.Helper>())
    private val _hasLoadedInitialState = MutableStateFlow(false)
    val pluginStates = _pluginStates.asStateFlow()
    val sourceStates = _sourceStates.asStateFlow()
    val apkDownloadHelpers = _apkDownloadHelpers.asStateFlow()
    val trustedApkDownloadHelpers = _trustedApkDownloadHelpers.asStateFlow()
    val hasLoadedInitialState = _hasLoadedInitialState.asStateFlow()
    val loadedPluginsFlow = combine(pluginStates, sourceStates) { installed, sources ->
        installed.values
            .filterIsInstance<DownloaderPluginState.Loaded>()
            .flatMap { it.plugins } +
            sources.values
                .mapNotNull { state ->
                    (state.state as? DownloaderPluginSourceState.State.Loaded)?.plugins
                }
                .flatten()
    }

    private val acknowledgedDownloaderPlugins = prefs.acknowledgedDownloaderPlugins
    private val installedPluginPackageNames = MutableStateFlow(emptySet<String>())
    private val installedPluginPackageSnapshots = MutableStateFlow(emptyMap<String, Pair<String?, String?>>())

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
                            packageName in acknowledgedDownloaderPlugins.get() ||
                            trustDao.getTrustedSignature(packageName) != null ||
                            _apkDownloadHelpers.value.any { it.packageName == packageName }
                        if (known) {
                            removePlugin(packageName)
                            reload()
                        }
                    } else {
                        handlePackageChange(packageName)
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.w(tag, "Failed to handle package change for $packageName", it)
                }
            }
        }
    }

    init {
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
            Log.w(tag, "Failed to register downloader plugin package change receiver", it)
        }
    }
    val newPluginPackageNames = combine(
        installedPluginPackageNames,
        acknowledgedDownloaderPlugins.flow
    ) { installed, acknowledged ->
        installed subtract acknowledged
    }

    suspend fun reload() = reloadMutex.withLock {
        val installedPackages =
            withContext(Dispatchers.IO) {
                pm.getPackagesWithFeatures(DOWNLOADER_PLUGIN_FEATURES)
            }
        val installedPlugins =
            withContext(Dispatchers.IO) {
                installedPackages.associate { it.packageName to loadInstalledPlugin(it.packageName) }
            }
        val apkDownloadHelpers = withContext(Dispatchers.IO) {
            // Code adapted from Morphe, see third-party/NOTICE for more information
            // https://github.com/MorpheApp/morphe-manager/pull/797
            ApkDownloadHelperContract.findHelpers(app)
        }
        val installedHelperVersions = apkDownloadHelpers
            .groupBy(ApkDownloadHelperContract.Helper::packageName)
            .mapValues { (_, helpers) -> helpers.firstNotNullOfOrNull { it.version } }
        val installedHelperSignatures = withContext(Dispatchers.IO) {
            apkDownloadHelpers
                .map(ApkDownloadHelperContract.Helper::packageName)
                .distinct()
                .mapNotNull { packageName ->
                    runCatching { installedSignatureHex(packageName) }
                        .getOrNull()
                        ?.let { packageName to it }
                }
                .toMap()
        }
        val sourceEntries = withContext(Dispatchers.IO) {
            val storedEntries = readSourceEntries()
            val scopedEntries = storedEntries.map(::migrateHelperTrustScope)
            if (scopedEntries != storedEntries) {
                writeSourceEntries(scopedEntries)
            }
            scopedEntries
        }
        val managedSources =
            withContext(Dispatchers.IO) {
                buildMap {
                    sourceEntries.forEach { entry ->
                        put(
                            entry.id,
                            loadManagedSource(
                                entry,
                                installedHelperVersions,
                                installedHelperSignatures
                            )
                        )
                    }
                }
            }
        val managedHelperPackages = managedSources.values.mapNotNullTo(mutableSetOf()) { source ->
            when (val state = source.state) {
                is DownloaderPluginSourceState.State.HelperApp -> state.packageName
                is DownloaderPluginSourceState.State.Untrusted ->
                    state.packageName.takeIf { state.helperApp }
                else -> null
            }
        }
        val trustedStandaloneHelpers = withContext(Dispatchers.IO) { readTrustedApkDownloadHelpers() }
        val trustedHelpers = apkDownloadHelpers.filter { helper ->
            val signature = installedHelperSignatures[helper.packageName] ?: return@filter false
            if (helper.packageName in managedHelperPackages) {
                managedSources.values.any { source ->
                    val state = source.state as? DownloaderPluginSourceState.State.HelperApp
                    state?.packageName == helper.packageName && state.installedSignerTrusted
                }
            } else {
                trustedStandaloneHelpers[helper.packageName] == signature
            }
        }

        _pluginStates.value = installedPlugins
        _sourceStates.value = managedSources
        _apkDownloadHelpers.value = apkDownloadHelpers
        _trustedApkDownloadHelpers.value = trustedHelpers
        installedPluginPackageNames.value = installedPlugins.keys
        installedPluginPackageSnapshots.value = installedPackages.associate {
            it.packageName to (it.versionName to it.applicationInfo?.sourceDir)
        }
        _hasLoadedInitialState.value = true

        // Android can briefly omit a package while replacing its APK. Only a confirmed
        // removal or explicit revocation should discard the stored signer trust.
    }

    private suspend fun handlePackageChange(packageName: String) {
        val isKnown = packageName in installedPluginPackageNames.value ||
            _apkDownloadHelpers.value.any { it.packageName == packageName }
        val isDownloader = isKnown || withContext(Dispatchers.IO) {
            pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS)
                ?.reqFeatures?.any { it.name in DOWNLOADER_PLUGIN_FEATURES } == true ||
                ApkDownloadHelperContract.findHelpers(app).any { it.packageName == packageName }
        }
        if (isDownloader) {
            Log.d(tag, "Downloader plugin package change detected for $packageName, reloading")
            reload()
        }
    }

    suspend fun reloadIfChanged(): Boolean = withContext(Dispatchers.IO) {
        val currentPackages = pm.getPackagesWithFeatures(DOWNLOADER_PLUGIN_FEATURES)
        val currentPackageNames = currentPackages.map { it.packageName }.toSet()
        val knownPackageNames = installedPluginPackageNames.value

        if (currentPackageNames != knownPackageNames) {
            reload()
            return@withContext true
        }

        val knownSnapshots = installedPluginPackageSnapshots.value
        val anyChanged = currentPackages.any { pkg ->
            val snapshot = knownSnapshots[pkg.packageName]
            snapshot == null || snapshot.first != pkg.versionName || snapshot.second != pkg.applicationInfo?.sourceDir
        }

        if (anyChanged) {
            reload()
            return@withContext true
        }

        val currentHelpers = ApkDownloadHelperContract.findHelpers(app)
        val knownHelpers = _apkDownloadHelpers.value
        val currentHelperMap = currentHelpers.associate { it.componentName to it.version }
        val knownHelperMap = knownHelpers.associate { it.componentName to it.version }
        if (currentHelperMap != knownHelperMap) {
            reload()
            return@withContext true
        }
        false
    }

    suspend fun importSourcesFromUrl(rawUrl: String): Int = withContext(Dispatchers.IO) {
        val importRequest = parseImportUrl(rawUrl)
        val importedEntries = importSourceEntries(importRequest)
        reload()
        importedEntries.size
    }

    suspend fun updateSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext

        val updatedEntry = updateEntry(entries[index], force = true)
        entries[index] = updatedEntry
        writeSourceEntries(entries)
        reload()
    }

    suspend fun removeSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().filterNot { it.id == id }
        managedSourceDirectory(id).deleteRecursively()
        writeSourceEntries(entries)
        reload()
    }

    suspend fun getManagedSourceApk(id: String): File? = withContext(Dispatchers.IO) {
        val entry = readSourceEntries().firstOrNull { it.id == id } ?: return@withContext null
        sourceFile(entry).takeIf(File::isFile)
    }

    suspend fun removeSources(ids: Set<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val entries = readSourceEntries()
        entries
            .filter { it.id in ids }
            .forEach { managedSourceDirectory(it.id).deleteRecursively() }
        writeSourceEntries(entries.filterNot { it.id in ids })
        reload()
    }

    suspend fun trustApkDownloadHelper(
        packageName: String,
        expectedSignatureHex: String
    ) = withContext(Dispatchers.IO) {
        require(ApkDownloadHelperContract.findHelpers(app).any { it.packageName == packageName }) {
            "APK download helper $packageName is not installed"
        }
        val installedSignature = installedSignatureHex(packageName)
        require(installedSignature == expectedSignatureHex) {
            "APK download helper $packageName signer changed before trust was confirmed"
        }
        writeTrustedApkDownloadHelpers(
            readTrustedApkDownloadHelpers() + (packageName to installedSignature)
        )
        reload()
    }

    suspend fun revokeApkDownloadHelperTrust(packageName: String) = withContext(Dispatchers.IO) {
        writeTrustedApkDownloadHelpers(readTrustedApkDownloadHelpers() - packageName)
        reload()
    }

    suspend fun forgetApkDownloadHelper(packageName: String) = withContext(Dispatchers.IO) {
        writeTrustedApkDownloadHelpers(readTrustedApkDownloadHelpers() - packageName)
    }

    suspend fun trustSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext

        val entry = entries[index]
        val file = sourceFile(entry)
        val packageInfo = readArchivePackageInfo(file)
        val signatureHex = archiveSignatureHex(packageInfo)
        entries[index] = entry.copy(
            trustedSignatureHex = signatureHex,
            trustedAsHelperApp = ApkDownloadHelperContract.isHelperArchive(file, packageInfo)
        )
        writeSourceEntries(entries)
        reload()
    }

    suspend fun revokeTrustForSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext

        val entry = entries[index]
        if (entry.trustedSignatureHex == null) return@withContext

        entries[index] = entry.copy(
            trustedSignatureHex = null,
            trustedAsHelperApp = false
        )
        writeSourceEntries(entries)
        reload()
    }

    suspend fun setSourceAutoUpdate(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) entry.copy(autoUpdate = enabled) else entry
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun setSourceLatest(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) {
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

    suspend fun setSourcePrerelease(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) entry.copy(prerelease = enabled) else entry
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun updateCheck() {
        if (!networkInfo.isConnected()) return
        if (!prefs.allowMeteredUpdates.get() && !networkInfo.isUnmetered()) return

        val entries = withContext(Dispatchers.IO) { readSourceEntries() }
        if (entries.none { it.autoUpdate && it.trustedSignatureHex != null }) return
        val currentSourceStates = sourceStates.value

        val updatedEntries = buildList {
            entries.forEach { entry ->
                val updateMode = shouldAutoUpdate(entry, currentSourceStates[entry.id]?.state)
                if (updateMode == SourceUpdateMode.SKIP) {
                    add(entry)
                    return@forEach
                }

                val updated = runCatching { updateEntry(entry, force = updateMode == SourceUpdateMode.FORCE) }
                    .onFailure { Log.e(tag, "Failed to update downloader source ${entry.repoUrl}", it) }
                    .getOrElse { entry }
                add(updated)
            }
        }

        withContext(Dispatchers.IO) {
            writeSourceEntries(updatedEntries)
        }
        reload()
    }

    fun unwrapParceledData(data: ParceledDownloaderData): Pair<LoadedDownloaderPlugin, Parcelable> {
        val allLoadedPlugins = _pluginStates.value.values
            .filterIsInstance<DownloaderPluginState.Loaded>()
            .flatMap { it.plugins } +
            _sourceStates.value.values
                .mapNotNull { state ->
                    (state.state as? DownloaderPluginSourceState.State.Loaded)?.plugins
                }
                .flatten()

        val plugin = data.pluginId
            ?.let { pluginId -> allLoadedPlugins.firstOrNull { it.id == pluginId } }
            ?: run {
                val fallbackPlugins = (_pluginStates.value[data.pluginPackageName] as? DownloaderPluginState.Loaded)
                    ?.plugins
                    ?: allLoadedPlugins.filter { it.packageName == data.pluginPackageName }
                if (fallbackPlugins.isEmpty()) {
                    throw Exception("Downloader plugin with name ${data.pluginPackageName} is not available")
                }

                data.pluginClassName
                    ?.let { className -> fallbackPlugins.firstOrNull { it.className == className } }
                    ?: fallbackPlugins.firstOrNull()
            }
            ?: throw Exception("No downloader implementation is available for ${data.pluginPackageName}")

        return plugin to data.unwrapWith(plugin)
    }

    suspend fun trustPackage(packageName: String) {
        trustDao.upsertTrust(
            TrustedDownloaderPlugin(
                packageName,
                pm.getSignature(packageName).toByteArray()
            )
        )

        reload()
        prefs.edit {
            acknowledgedDownloaderPlugins += packageName
        }
    }

    suspend fun revokeTrustForPackage(packageName: String) =
        trustDao.remove(packageName).also { reload() }

    suspend fun acknowledgeAllNewPlugins() =
        acknowledgedDownloaderPlugins.update(installedPluginPackageNames.value)

    suspend fun removePlugin(packageName: String) {
        trustDao.remove(packageName)
        acknowledgedDownloaderPlugins.update(acknowledgedDownloaderPlugins.get() - packageName)
        _pluginStates.value = _pluginStates.value - packageName
        installedPluginPackageNames.value = installedPluginPackageNames.value - packageName
    }

    private suspend fun loadInstalledPlugin(packageName: String): DownloaderPluginState {
        try {
            if (!verify(packageName)) return DownloaderPluginState.Untrusted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Got exception while verifying plugin $packageName", e)
            return DownloaderPluginState.Failed(e)
        }

        return try {
            val packageInfo = pm.getPackageInfo(packageName, flags = PackageManager.GET_META_DATA)!!
            val pluginContext = app.createPackageContext(packageName, 0)
            val resolved = loadResolvedPluginPackage(
                packageInfo = packageInfo,
                pluginContext = pluginContext,
                sourceId = null
            )

            DownloaderPluginState.Loaded(
                plugins = resolved.plugins,
                classLoader = resolved.classLoader,
                name = resolved.displayName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load plugin $packageName", t)
            DownloaderPluginState.Failed(t)
        }
    }

    private suspend fun loadManagedSource(
        entry: DownloaderPluginSourceEntry,
        installedHelperVersions: Map<String, String?>,
        installedHelperSignatures: Map<String, String>
    ): DownloaderPluginSourceState {
        val file = sourceFile(entry)
        val fallbackName = entry.assetSelector.toReadableSourceName()
        if (!file.exists()) {
            return DownloaderPluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = DownloaderPluginSourceState.State.Missing
            )
        }

        return try {
            ensureManagedSourceIsReadOnly(file)
            val packageInfo = readArchivePackageInfo(file)
            val helperApp = ApkDownloadHelperContract.isHelperArchive(file, packageInfo)
            val displayName = pm.getArchiveLabel(file, packageInfo) ?: fallbackName
            when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                ArchiveTrust.Trusted -> Unit
                is ArchiveTrust.Untrusted -> {
                    return DownloaderPluginSourceState(
                        entry = entry,
                        name = displayName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = DownloaderPluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex,
                            helperApp = helperApp
                        )
                    )
                }
                is ArchiveTrust.Mismatch -> {
                    return DownloaderPluginSourceState(
                        entry = entry,
                        name = displayName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = DownloaderPluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex,
                            helperApp = helperApp
                        )
                    )
                }
                is ArchiveTrust.Unreadable -> {
                    return DownloaderPluginSourceState(
                        entry = entry,
                        name = displayName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = DownloaderPluginSourceState.State.Failed(trust.throwable)
                    )
                }
            }

            if (entry.trustedAsHelperApp && !helperApp) {
                return DownloaderPluginSourceState(
                    entry = entry,
                    name = displayName,
                    version = packageInfo.versionName,
                    repoUrl = entry.repoUrl,
                    state = DownloaderPluginSourceState.State.Untrusted(
                        packageName = packageInfo.packageName,
                        signature = archiveSignatureHex(packageInfo),
                        helperApp = false
                    )
                )
            }

            if (helperApp) {
                return DownloaderPluginSourceState(
                    entry = entry,
                    name = displayName,
                    version = packageInfo.versionName,
                    repoUrl = entry.repoUrl,
                    state = DownloaderPluginSourceState.State.HelperApp(
                        packageName = packageInfo.packageName,
                        installed = packageInfo.packageName in installedHelperVersions,
                        installedVersion = installedHelperVersions[packageInfo.packageName],
                        installedSignerTrusted = installedHelperSignatures[packageInfo.packageName]
                            ?.let { it == entry.trustedSignatureHex }
                            ?: false
                    )
                )
            }

            val pluginContext = createArchiveContext(packageInfo)
            val resolved = loadResolvedPluginPackage(
                packageInfo = packageInfo,
                pluginContext = pluginContext,
                sourceId = entry.id
            )
            DownloaderPluginSourceState(
                entry = entry,
                name = resolved.displayName,
                version = resolved.version,
                repoUrl = entry.repoUrl,
                state = DownloaderPluginSourceState.State.Loaded(resolved.plugins)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load managed downloader source ${entry.repoUrl}", t)
            DownloaderPluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = DownloaderPluginSourceState.State.Failed(t)
            )
        }
    }

    private data class ResolvedPluginPackage(
        val plugins: List<LoadedDownloaderPlugin>,
        val classLoader: ClassLoader,
        val displayName: String,
        val version: String
    )

    private fun loadResolvedPluginPackage(
        packageInfo: PackageInfo,
        pluginContext: Context,
        sourceId: String?
    ): ResolvedPluginPackage {
        val classNames = resolveClassNames(packageInfo, pluginContext)
        val classLoader = PathClassLoader(packageInfo.applicationInfo!!.sourceDir, app.classLoader)
        val packageLabel = with(pm) { packageInfo.label() }

        val scopeImpl = object : LegacyScope, ModernScope, RevancedLegacyScope, RevancedModernScope {
            override val hostPackageName = app.packageName
            override val pluginPackageName = pluginContext.packageName
            override val downloaderPackageName = pluginContext.packageName
        }

        val loadedPlugins = classNames.map { className ->
            val downloader = classLoader
                .loadClass(className)
                .getDownloaderBuilder()
                .buildDownloader(scopeImpl, pluginContext)
            val fallbackName = if (classNames.size > 1) {
                className.substringAfterLast('.')
            } else {
                packageLabel
            }

            LoadedDownloaderPlugin(
                packageName = packageInfo.packageName,
                className = className,
                name = downloader.resolveName(pluginContext, fallbackName),
                version = packageInfo.versionName ?: "0",
                sourceId = sourceId,
                getImpl = downloader.resolveGet(),
                downloadImpl = downloader.resolveDownload(),
                classLoader = classLoader
            )
        }

        return ResolvedPluginPackage(
            plugins = loadedPlugins,
            classLoader = classLoader,
            displayName = if (loadedPlugins.size == 1) loadedPlugins.first().name else packageLabel,
            version = packageInfo.versionName ?: "0"
        )
    }

    private suspend fun verify(packageName: String): Boolean {
        val expectedSignature = trustDao.getTrustedSignature(packageName) ?: return false
        return pm.hasSignature(packageName, expectedSignature)
    }

    private suspend fun verify(packageInfo: PackageInfo): Boolean {
        val expectedSignature = trustDao.getTrustedSignature(packageInfo.packageName) ?: return false
        val actualSignature = pm.getSignature(packageInfo)?.toByteArray() ?: return false
        return actualSignature.contentEquals(expectedSignature)
    }

    private fun readArchivePackageInfo(file: File): PackageInfo {
        val packageInfo = pm.getPackageInfo(file, includeSigning = true)
            ?: throw Exception("Failed to read downloader archive ${file.name}")
        return packageInfo
    }

    private fun verifyArchiveTrust(
        entry: DownloaderPluginSourceEntry,
        packageInfo: PackageInfo
    ): ArchiveTrust {
        val actualSignatureHex = runCatching { archiveSignatureHex(packageInfo) }
            .getOrElse { return ArchiveTrust.Unreadable(it) }
        val expectedSignatureHex = entry.trustedSignatureHex
            ?: return ArchiveTrust.Untrusted(actualSignatureHex)

        return if (actualSignatureHex == expectedSignatureHex) {
            ArchiveTrust.Trusted
        } else {
            ArchiveTrust.Mismatch(actualSignatureHex)
        }
    }

    private fun archiveSignatureHex(packageInfo: PackageInfo): String {
        val signature = pm.getSignature(packageInfo)?.toByteArray()
            ?: throw SecurityException("Failed to read signer for ${packageInfo.packageName}")
        return signature.toSignatureHex()
    }

    private fun installedSignatureHex(packageName: String): String =
        pm.getSignature(packageName).toByteArray().toSignatureHex()

    private fun ByteArray.toSignatureHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte) }

    private suspend fun readTrustedApkDownloadHelpers(): Map<String, String> {
        val raw = prefs.trustedApkDownloadHelpersJson.get()
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrElse {
                Log.e(tag, "Failed to decode trusted APK download helpers", it)
                emptyMap()
            }
    }

    private suspend fun writeTrustedApkDownloadHelpers(entries: Map<String, String>) {
        prefs.trustedApkDownloadHelpersJson.update(
            if (entries.isEmpty()) "" else json.encodeToString<Map<String, String>>(entries.toSortedMap())
        )
    }

    private fun migrateHelperTrustScope(
        entry: DownloaderPluginSourceEntry
    ): DownloaderPluginSourceEntry {
        if (entry.trustedSignatureHex == null || entry.trustedAsHelperApp) return entry
        val file = sourceFile(entry)
        if (!file.isFile) return entry
        val packageInfo = runCatching { readArchivePackageInfo(file) }.getOrNull() ?: return entry
        return if (ApkDownloadHelperContract.isHelperArchive(file, packageInfo)) {
            entry.copy(trustedAsHelperApp = true)
        } else {
            entry
        }
    }

    private suspend fun readSourceEntries(): List<DownloaderPluginSourceEntry> {
        val raw = prefs.downloaderPluginSourcesJson.get()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<DownloaderPluginSourceEntry>>(raw)
        }.getOrElse {
            Log.e(tag, "Failed to decode downloader source entries", it)
            emptyList()
        }
    }

    private suspend fun writeSourceEntries(entries: List<DownloaderPluginSourceEntry>) {
        prefs.downloaderPluginSourcesJson.update(
            json.encodeToString(entries.sortedBy { "${it.repoUrl}|${it.assetSelector}|${it.id}" })
        )
    }

    private suspend fun importSourceEntries(
        importRequest: ImportRequest
    ): List<DownloaderPluginSourceEntry> {
        val release = releaseForImport(importRequest)
        val apkAssets = release.assets.filter(::isSupportedDownloaderAsset)
        if (apkAssets.isEmpty()) {
            error("No downloader APK assets found for ${importRequest.repoUrl}")
        }

        val selectedAssets = when (importRequest) {
            is ImportRequest.Asset -> {
                apkAssets.filter {
                    canonicalAssetSelector(normalizeAssetSelector(it.name)) ==
                        canonicalAssetSelector(importRequest.assetSelector)
                }
            }

            is ImportRequest.Release,
            is ImportRequest.Repository -> apkAssets
        }
        if (selectedAssets.isEmpty()) {
            error("No matching downloader APK asset found for ${importRequest.repoUrl}")
        }

        val entries = readSourceEntries().toMutableList()
        val importedEntries = mutableListOf<DownloaderPluginSourceEntry>()
        selectedAssets.forEach { asset ->
            val selector = canonicalAssetSelector(normalizeAssetSelector(asset.name))
            val versionKey = releaseVersionKey(release, asset)
            val existingIndex = entries.indexOfFirst {
                it.repoUrl == importRequest.repoUrl &&
                    canonicalAssetSelector(it.assetSelector) == selector
            }
            val existing = entries.getOrNull(existingIndex)
            val entry = (existing ?: DownloaderPluginSourceEntry(
                id = UUID.randomUUID().toString(),
                repoUrl = importRequest.repoUrl,
                assetSelector = selector
            )).copy(
                repoUrl = importRequest.repoUrl,
                assetSelector = selector,
                prerelease = release.prerelease,
                versionKey = versionKey
            )
            downloadAssetToEntry(entry, asset)

            if (existingIndex >= 0) {
                entries[existingIndex] = entry
            } else {
                entries += entry
            }
            importedEntries += entry
        }

        writeSourceEntries(entries)
        return importedEntries
    }

    private suspend fun updateEntry(
        entry: DownloaderPluginSourceEntry,
        force: Boolean
    ): DownloaderPluginSourceEntry {
        val release = latestReleaseFor(
            repoUrl = entry.repoUrl,
            prerelease = if (entry.latest) null else entry.prerelease
        )
        val asset = release.assets
            .filter(::isSupportedDownloaderAsset)
            .firstOrNull {
                canonicalAssetSelector(normalizeAssetSelector(it.name)) ==
                    canonicalAssetSelector(entry.assetSelector)
            }
            ?: throw Exception("No matching downloader APK asset found for ${entry.repoUrl}")

        val versionKey = releaseVersionKey(release, asset)
        if (!force && versionKey == entry.versionKey && sourceFile(entry).exists()) {
            return entry
        }

        downloadAssetToEntry(entry, asset)
        return entry.copy(versionKey = versionKey)
    }

    private suspend fun shouldAutoUpdate(
        entry: DownloaderPluginSourceEntry,
        currentState: DownloaderPluginSourceState.State?
    ): SourceUpdateMode {
        if (!entry.autoUpdate || entry.trustedSignatureHex == null) return SourceUpdateMode.SKIP
        if (currentState is DownloaderPluginSourceState.State.Missing) return SourceUpdateMode.FORCE
        if (currentState is DownloaderPluginSourceState.State.Failed) return SourceUpdateMode.CHECK

        val file = sourceFile(entry)
        if (!file.exists()) return SourceUpdateMode.FORCE

        val packageInfo = runCatching { readArchivePackageInfo(file) }
            .getOrElse {
                Log.e(tag, "Failed to inspect downloader source ${entry.repoUrl} before auto-update", it)
                return SourceUpdateMode.FORCE
            }

        return when (val trust = verifyArchiveTrust(entry, packageInfo)) {
            ArchiveTrust.Trusted -> SourceUpdateMode.CHECK
            is ArchiveTrust.Untrusted, is ArchiveTrust.Mismatch -> SourceUpdateMode.SKIP
            is ArchiveTrust.Unreadable -> {
                Log.e(tag, "Failed to verify downloader source ${entry.repoUrl} before auto-update", trust.throwable)
                SourceUpdateMode.FORCE
            }
        }
    }

    private suspend fun releaseForImport(importRequest: ImportRequest): GitHubRelease = when (importRequest) {
        is ImportRequest.Repository -> latestImportReleaseFor(importRequest.repoUrl)
        is ImportRequest.Release -> releaseForTag(importRequest.repoUrl, importRequest.releaseTag)
        is ImportRequest.Asset -> releaseForTag(importRequest.repoUrl, importRequest.releaseTag)
    }

    private suspend fun latestImportReleaseFor(repoUrl: String): GitHubRelease {
        return findLatestReleaseFor(repoUrl, prerelease = false)
            ?: findLatestReleaseFor(repoUrl, prerelease = true)
            ?: throw Exception("No releases found for $repoUrl")
    }

    private suspend fun releaseForTag(repoUrl: String, releaseTag: String): GitHubRelease =
        api.getRepositoryReleaseByTag(repoUrl, releaseTag)
            .successOrThrow("downloader release $releaseTag for $repoUrl")

    private suspend fun releaseHistoryFor(
        repoUrl: String,
        prerelease: Boolean?,
        limit: Int
    ): List<GitHubRelease> {
        return api.getRepositoryReleaseHistory(
            repoUrl = repoUrl,
            prerelease = prerelease,
            limit = limit
        ).successOrThrow("downloader releases for $repoUrl")
    }

    private suspend fun findLatestReleaseFor(repoUrl: String, prerelease: Boolean?): GitHubRelease? {
        return releaseHistoryFor(
            repoUrl = repoUrl,
            prerelease = prerelease,
            limit = 1
        ).firstOrNull()
    }

    private suspend fun latestReleaseFor(repoUrl: String, prerelease: Boolean?): GitHubRelease {
        return findLatestReleaseFor(
            repoUrl = repoUrl,
            prerelease = prerelease
        )
            ?: throw Exception("No releases found for $repoUrl")
    }

    private suspend fun downloadAssetToEntry(
        entry: DownloaderPluginSourceEntry,
        asset: GitHubAsset
    ) {
        val directory = managedSourceDirectory(entry.id)
        directory.mkdirs()
        val target = sourceFile(entry)
        val tempFile = directory.resolve("downloader.tmp")
        if (tempFile.exists()) tempFile.delete()
        val progressNotification = downloadProgressNotifier.begin(
            _sourceStates.value[entry.id]?.name?.let {
                app.getString(R.string.downloader_download_title, it.toDownloaderMainName())
            } ?: asset.name
        )

        runCatching {
            httpService.downloadToFile(
                saveLocation = tempFile,
                builder = {
                    url(asset.downloadUrl)
                },
                onProgress = progressNotification::update
            )
            val packageInfo = readArchivePackageInfo(tempFile)
            pm.getArchiveLabel(tempFile, packageInfo)?.let { name ->
                progressNotification.updateTitle(app.getString(R.string.downloader_download_title, name.toDownloaderMainName()))
            }
            if (entry.trustedSignatureHex != null) {
                when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                    ArchiveTrust.Trusted -> Unit
                    is ArchiveTrust.Mismatch -> {
                        throw SecurityException(
                            "Signer mismatch for ${packageInfo.packageName}"
                        )
                    }
                    is ArchiveTrust.Unreadable -> {
                        throw SecurityException(
                            "Failed to read signer for ${packageInfo.packageName}",
                            trust.throwable
                        )
                    }
                    is ArchiveTrust.Untrusted -> {
                        throw SecurityException(
                            "Expected trusted downloader source for ${packageInfo.packageName}"
                        )
                    }
                }
                if (
                    entry.trustedAsHelperApp &&
                    !ApkDownloadHelperContract.isHelperArchive(tempFile, packageInfo)
                ) {
                    throw SecurityException(
                        "Trusted helper source ${packageInfo.packageName} no longer implements the APK download helper contract"
                    )
                }
            }
            if (target.exists()) target.delete()
            tempFile.copyTo(target, overwrite = true)
            ensureManagedSourceIsReadOnly(target)
        }.getOrElse { error ->
            if (error is CancellationException) {
                progressNotification.cancel()
            } else {
                progressNotification.fail()
            }
            tempFile.delete()
            throw error
        }

        tempFile.delete()
        progressNotification.complete()
    }

    private sealed interface ImportRequest {
        val repoUrl: String

        data class Repository(
            override val repoUrl: String
        ) : ImportRequest

        data class Release(
            override val repoUrl: String,
            val releaseTag: String
        ) : ImportRequest

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

    private fun parseImportUrl(rawUrl: String): ImportRequest {
        val normalizedUrl = extractImportUrl(rawUrl)
        require(normalizedUrl.isNotBlank()) { "Enter a GitHub repository, release, or release asset URL." }

        val uri = try {
            URI(normalizedUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("Unsupported downloader source URL: $normalizedUrl")
        }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Unsupported downloader source URL: $normalizedUrl")
        val parts = uri.rawPath
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
            .map { segment -> Uri.decode(segment) }

        return when (host) {
            "github.com" -> {
                require(parts.size >= 2) { "Unsupported downloader source URL: $normalizedUrl" }
                val repoUrl = githubRepoUrl(parts[0], parts[1])
                when {
                    parts.size >= 6 && parts[2] == "releases" && parts[3] == "download" -> {
                        ImportRequest.Asset(
                            repoUrl = repoUrl,
                            assetSelector = canonicalAssetSelector(normalizeAssetSelector(parts.last())),
                            releaseTag = parts[4]
                        )
                    }
                    parts.size >= 5 && parts[2] == "releases" && parts[3] == "tag" -> {
                        ImportRequest.Release(
                            repoUrl = repoUrl,
                            releaseTag = parts[4]
                        )
                    }
                    else -> ImportRequest.Repository(repoUrl)
                }
            }

            "api.github.com" -> {
                val reposIndex = parts.indexOf("repos")
                require(reposIndex != -1 && parts.size >= reposIndex + 3) {
                    "Unsupported downloader source URL: $normalizedUrl"
                }
                val repoUrl = githubRepoUrl(parts[reposIndex + 1], parts[reposIndex + 2])
                if (
                    parts.size >= reposIndex + 6 &&
                    parts[reposIndex + 3] == "releases" &&
                    parts[reposIndex + 4] == "tags"
                ) {
                    ImportRequest.Release(
                        repoUrl = repoUrl,
                        releaseTag = parts[reposIndex + 5]
                    )
                } else {
                    ImportRequest.Repository(repoUrl)
                }
            }

            else -> throw IllegalArgumentException(
                "Only GitHub repository, release, or release asset URLs are supported."
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

    private fun managedSourceDirectory(id: String) = managedSourceRoot.resolve(id)

    private fun sourceFile(entry: DownloaderPluginSourceEntry) =
        managedSourceDirectory(entry.id).resolve("downloader.apk")

    private fun ensureManagedSourceIsReadOnly(file: File) {
        if (!file.exists() || !file.canWrite()) return

        check(file.setReadOnly() || !file.canWrite()) {
            "Managed downloader source ${file.name} must be read-only before loading."
        }
    }

    private fun createArchiveContext(packageInfo: PackageInfo): Context {
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("Missing ApplicationInfo for ${packageInfo.packageName}")
        val resources = createArchiveResources(applicationInfo)
        val classLoader = PathClassLoader(applicationInfo.sourceDir, app.classLoader)
        return ArchivePluginContext(
            base = app,
            applicationInfo = applicationInfo,
            resources = resources,
            classLoader = classLoader
        )
    }

    private fun createArchiveResources(applicationInfo: ApplicationInfo): Resources {
        val sourcePath = applicationInfo.publicSourceDir ?: applicationInfo.sourceDir
        require(!sourcePath.isNullOrBlank()) {
            "Missing source path for ${applicationInfo.packageName}"
        }

        val assetManager = AssetManager::class.java
            .getDeclaredConstructor()
            .newInstance()
        val cookie = addAssetPath.invoke(assetManager, sourcePath) as? Int ?: 0
        check(cookie != 0) {
            "Failed to load downloader resources from $sourcePath"
        }

        return Resources(
            assetManager,
            app.resources.displayMetrics,
            app.resources.configuration
        )
    }

    private fun resolveClassNames(
        packageInfo: PackageInfo,
        pluginContext: Context
    ): List<String> {
        val names = linkedSetOf<String>()
        val metaData = packageInfo.applicationInfo?.metaData

        fun addClassName(value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isNotEmpty()) names += normalized
        }

        fun addClassArray(resourceId: Int) {
            if (resourceId == 0) return
            runCatching { pluginContext.resources.getStringArray(resourceId) }
                .getOrNull()
                ?.forEach(::addClassName)
        }

        addClassName(metaData?.getString(LEGACY_METADATA_PLUGIN_CLASS))
        addClassName(metaData?.getString(MODERN_METADATA_PLUGIN_CLASS))
        addClassName(metaData?.getString(REVANCED_LEGACY_METADATA_PLUGIN_CLASS))
        addClassName(metaData?.getString(REVANCED_MODERN_METADATA_PLUGIN_CLASS))

        addClassArray(metaData?.getInt(LEGACY_METADATA_CLASSES_ARRAY, 0) ?: 0)
        addClassArray(metaData?.getInt(MODERN_METADATA_CLASSES_ARRAY, 0) ?: 0)
        addClassArray(metaData?.getInt(REVANCED_LEGACY_METADATA_CLASSES_ARRAY, 0) ?: 0)
        addClassArray(metaData?.getInt(REVANCED_MODERN_METADATA_CLASSES_ARRAY, 0) ?: 0)

        addClassArray(findStringArrayResource(pluginContext, LEGACY_CLASSES_RESOURCE_NAME))
        addClassArray(findStringArrayResource(pluginContext, MODERN_CLASSES_RESOURCE_NAME))
        addClassArray(findStringArrayResource(pluginContext, REVANCED_LEGACY_CLASSES_RESOURCE_NAME))
        addClassArray(findStringArrayResource(pluginContext, REVANCED_MODERN_CLASSES_RESOURCE_NAME))

        if (names.isEmpty()) {
            throw Exception(
                "Missing downloader class metadata. Expected one of " +
                    "$LEGACY_METADATA_PLUGIN_CLASS, $MODERN_METADATA_PLUGIN_CLASS, " +
                    "$REVANCED_LEGACY_METADATA_PLUGIN_CLASS, $REVANCED_MODERN_METADATA_PLUGIN_CLASS, " +
                    "$LEGACY_CLASSES_RESOURCE_NAME, $MODERN_CLASSES_RESOURCE_NAME, " +
                    "$REVANCED_LEGACY_CLASSES_RESOURCE_NAME, or $REVANCED_MODERN_CLASSES_RESOURCE_NAME"
            )
        }

        return names.toList()
    }

    private fun findStringArrayResource(context: Context, resourceName: String): Int =
        runCatching {
            @Suppress("DiscouragedApi")
            context.resources.getIdentifier(resourceName, "array", context.packageName)
        }.getOrDefault(0)

    private fun isSupportedDownloaderAsset(asset: GitHubAsset): Boolean =
        asset.name.endsWith(".apk", ignoreCase = true)

    private fun releaseVersionKey(release: GitHubRelease, asset: GitHubAsset) =
        "${release.tagName}:${asset.name}"

    private fun normalizeAssetSelector(assetName: String): String {
        val baseName = assetName.substringBeforeLast('.', assetName)
        val match = VERSION_SUFFIX_REGEX.matchEntire(baseName)
        return (match?.groupValues?.getOrNull(1) ?: baseName).lowercase()
    }

    private fun canonicalAssetSelector(selector: String): String =
        selector
            .lowercase()
            .removePrefix("revanced-manager-")

    private fun String.toReadableSourceName(): String =
        split(Regex("[-_]+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }

    private enum class SourceUpdateMode {
        SKIP,
        CHECK,
        FORCE
    }

    private fun githubRepoUrl(owner: String, repo: String) = "https://github.com/$owner/$repo"

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val PACKAGE_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED
        )
        const val LEGACY_PLUGIN_FEATURE = "app.universal.patch.manager.plugin.downloader"
        const val MODERN_PLUGIN_FEATURE = "app.universal.patch.manager.downloader"
        const val REVANCED_LEGACY_PLUGIN_FEATURE = "app.revanced.manager.plugin.downloader"
        const val REVANCED_MODERN_PLUGIN_FEATURE = "app.revanced.manager.downloader"
        private val DOWNLOADER_PLUGIN_FEATURES = setOf(
            LEGACY_PLUGIN_FEATURE,
            MODERN_PLUGIN_FEATURE,
            REVANCED_LEGACY_PLUGIN_FEATURE,
            REVANCED_MODERN_PLUGIN_FEATURE
        )

        const val LEGACY_METADATA_PLUGIN_CLASS = "app.universal.patch.manager.plugin.downloader.class"
        const val MODERN_METADATA_PLUGIN_CLASS = "app.universal.patch.manager.downloader.class"
        const val REVANCED_LEGACY_METADATA_PLUGIN_CLASS = "app.revanced.manager.plugin.downloader.class"
        const val REVANCED_MODERN_METADATA_PLUGIN_CLASS = "app.revanced.manager.downloader.class"
        const val LEGACY_METADATA_CLASSES_ARRAY = "app.universal.patch.manager.plugin.downloader.classes"
        const val MODERN_METADATA_CLASSES_ARRAY = "app.universal.patch.manager.downloader.classes"
        const val REVANCED_LEGACY_METADATA_CLASSES_ARRAY = "app.revanced.manager.plugin.downloader.classes"
        const val REVANCED_MODERN_METADATA_CLASSES_ARRAY = "app.revanced.manager.downloader.classes"
        const val LEGACY_CLASSES_RESOURCE_NAME = "app.universal.patch.manager.plugin.downloader.classes"
        const val MODERN_CLASSES_RESOURCE_NAME = "app.universal.patch.manager.downloader.classes"
        const val REVANCED_LEGACY_CLASSES_RESOURCE_NAME = "app.revanced.manager.plugin.downloader.classes"
        const val REVANCED_MODERN_CLASSES_RESOURCE_NAME = "app.revanced.manager.downloader.classes"

        val VERSION_SUFFIX_REGEX = Regex(
            pattern = "^(.*?)-v?\\d+(?:\\.\\d+)+(?:[-._][A-Za-z0-9]+)*$",
            option = RegexOption.IGNORE_CASE
        )

        val GITHUB_URL_REGEX = Regex(
            pattern = "https?://(?:github\\.com|api\\.github\\.com)\\S+",
            option = RegexOption.IGNORE_CASE
        )

        val addAssetPath: Method by lazy {
            AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        }

        const val PUBLIC_STATIC = Modifier.PUBLIC or Modifier.STATIC
        val Int.isPublicStatic get() = (this and PUBLIC_STATIC) == PUBLIC_STATIC

        val Class<*>.isDownloaderBuilder get() =
            DownloaderBuilder::class.java.isAssignableFrom(this) ||
                RevancedLegacyDownloaderBuilder::class.java.isAssignableFrom(this) ||
                RevancedModernDownloaderBuilder::class.java.isAssignableFrom(this) ||
                ModernDownloaderBuilder::class.java.isAssignableFrom(this)

        @Suppress("UNCHECKED_CAST")
        fun Class<*>.getDownloaderBuilder(): Any =
            declaredMethods
                .firstOrNull {
                    it.modifiers.isPublicStatic &&
                        it.returnType.isDownloaderBuilder &&
                        it.parameterTypes.isEmpty()
                }
                ?.invoke(null)
                ?: throw Exception("Could not find a valid downloader implementation in class $canonicalName")

        fun Any.buildDownloader(scopeImpl: Any, context: Context): Any {
            val buildMethod = this::class.java.methods.firstOrNull {
                it.name == "build" && it.parameterTypes.size == 2
            } ?: throw Exception("Could not find build(scope, context) on ${this::class.java.canonicalName}")
            return buildMethod.invoke(this, scopeImpl, context)
                ?: throw Exception("Downloader build returned null for ${this::class.java.canonicalName}")
        }

        @Suppress("UNCHECKED_CAST")
        fun Any.resolveGet() =
            this::class.java.methods
                .firstOrNull { it.name == "getGet" && it.parameterCount == 0 }
                ?.invoke(this) as? (suspend LegacyGetScope.(String, String?) -> Pair<Parcelable, String?>?)
                ?: throw Exception("Downloader ${this::class.java.canonicalName} has no valid get function")

        @Suppress("UNCHECKED_CAST")
        fun Any.resolveDownload() =
            this::class.java.methods
                .firstOrNull { it.name == "getDownload" && it.parameterCount == 0 }
                ?.invoke(this) as? (suspend LegacyOutputDownloadScope.(Parcelable, OutputStream) -> Unit)
                ?: throw Exception("Downloader ${this::class.java.canonicalName} has no valid download function")

        fun Any.resolveName(context: Context, fallback: String): String {
            val resId = this::class.java.methods
                .firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(this) as? Int
            if (resId == null || resId == 0) return fallback

            return runCatching { context.getString(resId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: fallback
        }
    }
}

private class ArchivePluginContext(
    base: Context,
    private val applicationInfo: ApplicationInfo,
    private val resources: Resources,
    private val classLoader: ClassLoader
) : ContextWrapper(base) {
    override fun getApplicationInfo(): ApplicationInfo = applicationInfo

    override fun getPackageName(): String = applicationInfo.packageName

    override fun getResources(): Resources = resources

    override fun getAssets(): AssetManager = resources.assets

    override fun getClassLoader(): ClassLoader = classLoader
}

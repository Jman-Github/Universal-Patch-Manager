package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.util.selectSuggestedPatchVersion

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import app.universal.patch.manager.patcher.patch.mostCommonCompatibleVersions as revancedMostCommonCompatibleVersions
import app.universal.patch.manager.patcher.patch.MetadataPatch as RevancedPatch
import app.universal.patch.manager.R
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.data.redux.Action
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.redux.ActionContext
import app.universal.patch.manager.data.redux.Store
import app.universal.patch.manager.data.room.AppDatabase
import app.universal.patch.manager.data.room.AppDatabase.Companion.generateUid
import app.universal.patch.manager.data.room.bundles.PatchBundleEntity
import app.universal.patch.manager.data.room.bundles.PatchBundleProperties
import app.universal.patch.manager.data.room.bundles.Source
import app.universal.patch.manager.domain.bundles.ExternalBundleMetadata
import app.universal.patch.manager.domain.bundles.ExternalBundleMetadataStore
import app.universal.patch.manager.domain.bundles.ExternalGraphqlPatchBundle
import app.universal.patch.manager.domain.bundles.GitHubPullRequestBundle
import app.universal.patch.manager.domain.bundles.JsonPatchBundle
import app.universal.patch.manager.data.room.bundles.Source as SourceInfo
import app.universal.patch.manager.domain.bundles.LocalPatchBundle
import app.universal.patch.manager.domain.bundles.PatchBundleChangelogEntry
import app.universal.patch.manager.domain.bundles.mergePatchBundleChangelogs
import app.universal.patch.manager.domain.bundles.PatchBundleDownloadProgress
import app.universal.patch.manager.domain.bundles.PatchBundleDownloadResult
import app.universal.patch.manager.domain.bundles.legacyOfficialBundleRegistryUrl
import app.universal.patch.manager.domain.bundles.isLegacyOfficialBundle
import app.universal.patch.manager.domain.bundles.RemotePatchBundle
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.bundles.RepositoryBundleReleaseChannel
import app.universal.patch.manager.domain.bundles.RepositoryBundleSettings
import app.universal.patch.manager.domain.bundles.RepositoryBundleSettingsStore
import app.universal.patch.manager.domain.bundles.legacyDiscoveryEndpoint
import app.universal.patch.manager.domain.bundles.discoveryRegistryMigration
import app.universal.patch.manager.domain.bundles.DiscoveryRegistryMigration
import app.universal.patch.manager.domain.bundles.registryBundleIconSource
import app.universal.patch.manager.domain.bundles.toExternalBundleMetadata
import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.network.dto.ReVancedAsset
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.patcher.morphe.MorpheRuntimeBridge
import app.universal.patch.manager.patcher.revanced.Revanced21RuntimeBridge
import app.universal.patch.manager.patcher.revanced.Revanced22RuntimeBridge
import app.universal.patch.manager.patcher.patch.PatchInfo
import app.universal.patch.manager.patcher.patch.PatchBundle
import app.universal.patch.manager.patcher.patch.PatchBundleInfo
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.patch.ReseamBundleFormat
import app.universal.patch.manager.patcher.reseam.ReseamRuntimeBridge
import app.universal.patch.manager.patcher.runtime.reseam.ReseamRuntimeAssets
import app.universal.patch.manager.patcher.runtime.morphe.MorpheRuntimeAssets
import app.universal.patch.manager.patcher.runtime.revanced.Revanced21RuntimeAssets
import app.universal.patch.manager.patcher.runtime.revanced.Revanced22RuntimeAssets
import app.universal.patch.manager.util.DownloadProgressNotifier
import app.universal.patch.manager.util.removeUpdatedBundleNotifications
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.simpleMessage
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.toast
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import io.ktor.http.Url
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import app.universal.patch.manager.util.bundleImportLabel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.LinkedHashSet
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.text.ifEmpty
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PatchBundleRepository(
    private val app: Application,
    private val networkInfo: NetworkInfo,
    private val prefs: PreferencesManager,
    private val downloadProgressNotifier: DownloadProgressNotifier,
    private val externalBundlesApi: ExternalBundlesApi,
    filesystem: Filesystem,
    db: AppDatabase,
) {
    private val dao = db.patchBundleDao()
    // Construct Filesystem first so stale bundle DEX caches are gone before a bundle loads.
    private val bundlesDir = filesystem.patchBundlesDir

    private val scope = CoroutineScope(Dispatchers.Default)
    private val store = Store(scope, State(metadataLoaded = false))
    private val bundleDigestHashCache = ConcurrentHashMap<Int, String>()

    val sources = store.state.map { it.sources.values.toList() }
    val bundles = store.state.map {
        it.sources.mapNotNull { (uid, src) ->
            uid to (src.patchBundle ?: return@mapNotNull null)
        }.toMap()
    }
    private val cachedAppList = MutableStateFlow<Map<Int, PatchBundleInfo.Global>>(emptyMap())
    // Preview metadata is never exposed to patch execution or option editors.
    val appListBundleInfoFlow = kotlinx.coroutines.flow.combine(cachedAppList, store.state) { cached, state ->
        (if (state.metadataLoaded) state.info else cached).filterValues { it.enabled }
    }
    val allBundlesInfoFlow = store.state.map { it.info }
    val enabledBundlesInfoFlow = allBundlesInfoFlow.map { info ->
        info.filter { (_, bundleInfo) -> bundleInfo.enabled }
    }
    val bundleInfoFlow = enabledBundlesInfoFlow

    fun bundlesByType(type: PatchBundleType) = store.state.map { state ->
        state.sources.mapNotNull { (uid, src) ->
            val bundle = src.patchBundle ?: return@mapNotNull null
            val info = state.info[uid] ?: return@mapNotNull null
            if (info.bundleType != type) return@mapNotNull null
            uid to bundle
        }.toMap()
    }

    suspend fun selectionBundleTypes(selection: PatchSelection): Set<PatchBundleType> {
        if (selection.isEmpty()) return emptySet()
        val activeSelection = selection.filterValues { it.isNotEmpty() }
        if (activeSelection.isEmpty()) return emptySet()
        val info = allBundlesInfoFlow.first()
        return activeSelection.keys.mapNotNull { uid -> info[uid]?.bundleType }.toSet()
    }

    suspend fun selectionBundleType(selection: PatchSelection): PatchBundleType? {
        val types = selectionBundleTypes(selection)
        return if (types.size == 1) types.first() else null
    }

    suspend fun selectionHasMixedBundleTypes(selection: PatchSelection): Boolean =
        selectionBundleTypes(selection).size > 1

    suspend fun selectionHasMixedRevancedPatcherVersions(selection: PatchSelection): Boolean {
        val activeSelection = selection.filterValues { it.isNotEmpty() }
        if (activeSelection.isEmpty()) return false

        val info = allBundlesInfoFlow.first()
        var hasV21 = false
        var hasV22 = false

        activeSelection.keys.forEach { uid ->
            if (info[uid]?.bundleType != PatchBundleType.REVANCED) return@forEach
            when (resolvedRevancedPatcherVersion(uid)) {
                RevancedPatcherVersion.V22 -> hasV22 = true
                else -> hasV21 = true
            }
        }

        return hasV21 && hasV22
    }

    suspend fun selectionUsesRevancedPatcher22(selection: PatchSelection): Boolean {
        val activeSelection = selection.filterValues { it.isNotEmpty() }
        if (activeSelection.isEmpty()) return false

        val info = allBundlesInfoFlow.first()
        return activeSelection.keys.any { uid ->
            info[uid]?.bundleType == PatchBundleType.REVANCED &&
                resolvedRevancedPatcherVersion(uid) == RevancedPatcherVersion.V22
        }
    }

    fun scopedBundleInfoFlow(
        packageName: String,
        version: String?,
        versionCode: Long? = null
    ) = enabledBundlesInfoFlow.map {
        it.map { (_, bundleInfo) ->
            bundleInfo.forPackage(
                packageName,
                version,
                versionCode
            )
        }
    }

    val patchCountsFlow = allBundlesInfoFlow.map { it.mapValues { (_, info) -> info.patches.size } }

    private fun suggestedVersionsForFlow(flow: Flow<Map<Int, PatchBundleInfo.Global>>) = flow.map { bundleInfos ->
        val revancedPatches = bundleInfos.values
            .filter { it.bundleType == PatchBundleType.REVANCED }
            .flatMap { info -> info.patches.map(PatchInfo::toPatcherPatch) }
            .toSet()
        val morphePatches = bundleInfos.values
            .filter { it.bundleType == PatchBundleType.MORPHE || it.bundleType == PatchBundleType.RESEAM }
            .flatMap { info -> info.patches }
        val morpheSuggested = suggestedVersionsForMorphe(morphePatches)
        val revancedSuggested = suggestedVersionsForRevanced(revancedPatches)
        morpheSuggested + revancedSuggested
    }

    private fun suggestedVersionsByBundleForFlow(flow: Flow<Map<Int, PatchBundleInfo.Global>>) = flow.map { bundleInfos ->
        bundleInfos.mapValues { (_, info) ->
            when (info.bundleType) {
                PatchBundleType.REVANCED -> {
                    val patches = info.patches.map(PatchInfo::toPatcherPatch).toSet()
                    suggestedVersionsForRevanced(patches)
                }
                PatchBundleType.MORPHE -> {
                    suggestedVersionsForMorphe(info.patches)
                }
                PatchBundleType.RESEAM -> suggestedVersionsForMorphe(info.patches)
                PatchBundleType.AMPLE -> emptyMap()
            }
        }
    }

    val suggestedVersions = suggestedVersionsForFlow(enabledBundlesInfoFlow)
    val suggestedVersionsByBundle = suggestedVersionsByBundleForFlow(enabledBundlesInfoFlow)
    val appListSuggestedVersions = suggestedVersionsForFlow(appListBundleInfoFlow)
    val appListSuggestedVersionsByBundle = suggestedVersionsByBundleForFlow(appListBundleInfoFlow)

    private val manualUpdateInfoFlow = MutableStateFlow<Map<Int, ManualBundleUpdateInfo>>(emptyMap())
    val manualUpdateInfo: StateFlow<Map<Int, ManualBundleUpdateInfo>> = manualUpdateInfoFlow.asStateFlow()

    private val bundleProgressOrder = AtomicLong(0L)
    private val bundleImportProgressLock = Any()
    private val bundleUpdateProgressFlow = MutableStateFlow<BundleUpdateProgress?>(null)
    val bundleUpdateProgress: StateFlow<BundleUpdateProgress?> = bundleUpdateProgressFlow.asStateFlow()
    private val bundleImportProgressFlow = MutableStateFlow<ImportProgress?>(null)
    val bundleImportProgress: StateFlow<ImportProgress?> = bundleImportProgressFlow.asStateFlow()
    private val reloadInProgressFlow = MutableStateFlow(true)
    val reloadInProgress: StateFlow<Boolean> = reloadInProgressFlow.asStateFlow()
    private val reloadInProgressCount = AtomicInteger(0)
    private val discoveryImportProgressFlow =
        MutableStateFlow<Map<String, DiscoveryImportProgress>>(emptyMap())
    val discoveryImportProgress: StateFlow<Map<String, DiscoveryImportProgress>> =
        discoveryImportProgressFlow.asStateFlow()
    private val discoveryImportQueuedFlow = MutableStateFlow<Set<String>>(emptySet())
    val discoveryImportQueued: StateFlow<Set<String>> = discoveryImportQueuedFlow.asStateFlow()

    private val discoveryMigrationMutex = Mutex()
    private val updateJobMutex = Mutex()
    private var updateJob: Job? = null
    private val updateStateMutex = Mutex()
    private val changelogHistoryMutex = Mutex()
    private val bundleCacheMutex = Mutex()
    @Volatile
    private var bundleCacheInitialized = false
    @Volatile
    private var activeUpdateUids: Set<Int> = emptySet()
    @Volatile
    private var cancelledUpdateUids: Set<Int> = emptySet()
    private val pendingUpdateRequests = mutableListOf<UpdateRequest>()
    private val localImportMutex = Mutex()
    private val localImportStateMutex = Mutex()
    private var localImportQueued = 0
    @Volatile
    private var localImportProcessedSteps = 0
    @Volatile
    private var localImportTotalSteps = 0
    @Volatile
    private var localImportTotalBundles = 0

    private var bundleImportAutoClearJob: Job? = null
    private var bundleUpdateAutoClearJob: Job? = null
    private val discoveryImportMutex = Mutex()
    private val discoveryImportQueue = ArrayDeque<DiscoveryImportRequest>()
    private val discoveryImportQueuedKeys = LinkedHashSet<String>()
    private var discoveryImportJob: Job? = null
    private val changelogHistoryJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun nextBundleProgressOrder(): Long = bundleProgressOrder.incrementAndGet()

    private fun isBundleImportComplete(progress: ImportProgress): Boolean {
        val isDownloadComplete = progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
            progress.bytesRead >= total
        } ?: false
        return progress.processed >= progress.total &&
            (progress.phase != BundleImportPhase.Downloading || isDownloadComplete)
    }

    private fun bundleUpdateProgressOrder(): Long = bundleUpdateProgressFlow.value
        ?.takeIf { it.total > 0 && it.completed < it.total }
        ?.startedOrder
        ?.takeIf { it > 0L }
        ?: nextBundleProgressOrder()

    fun setBundleImportProgress(progress: ImportProgress?) {
        val orderedProgress = synchronized(bundleImportProgressLock) {
            val previous = bundleImportProgressFlow.value
            val next = progress?.let { value ->
                val startsNewOperation = previous == null ||
                    (isBundleImportComplete(previous) && !isBundleImportComplete(value))
                val startedOrder = when {
                    value.startedOrder > 0L -> value.startedOrder
                    startsNewOperation -> nextBundleProgressOrder()
                    else -> previous.startedOrder.takeIf { it > 0L } ?: nextBundleProgressOrder()
                }
                value.copy(startedOrder = startedOrder)
            }
            bundleImportProgressFlow.value = next
            next
        }

        bundleImportAutoClearJob?.cancel()
        if (orderedProgress == null || !isBundleImportComplete(orderedProgress)) return

        val completedOperationOrder = orderedProgress.startedOrder
        bundleImportAutoClearJob = scope.launch {
            delay(8_000)
            val current = bundleImportProgressFlow.value ?: return@launch
            if (
                current.startedOrder == completedOperationOrder &&
                isBundleImportComplete(current)
            ) {
                bundleImportProgressFlow.value = null
            }
        }
    }

    private fun cancelBundleUpdateAutoClear() {
        bundleUpdateAutoClearJob?.cancel()
        bundleUpdateAutoClearJob = null
    }

    private fun scheduleBundleUpdateProgressClear() {
        cancelBundleUpdateAutoClear()
        val current = bundleUpdateProgressFlow.value ?: return
        if (current.total <= 0 || current.completed < current.total) return

        val completedOperationOrder = current.startedOrder
        bundleUpdateAutoClearJob = scope.launch {
            delay(8_000)
            val progress = bundleUpdateProgressFlow.value ?: return@launch
            if (
                progress.startedOrder == completedOperationOrder &&
                progress.total > 0 &&
                progress.completed >= progress.total
            ) {
                bundleUpdateProgressFlow.value = null
            }
        }
    }

    private fun currentUpdateTotal(defaultTotal: Int): Int {
        val active = activeUpdateUids
        return if (active.isNotEmpty()) active.size else defaultTotal
    }

    private suspend fun markActiveUpdateUids(uids: Set<Int>) {
        updateStateMutex.withLock {
            activeUpdateUids = uids
            cancelledUpdateUids = emptySet()
        }
    }

    private suspend fun clearActiveUpdateState() {
        updateStateMutex.withLock {
            activeUpdateUids = emptySet()
            cancelledUpdateUids = emptySet()
        }
    }

    private suspend fun cancelRemoteUpdates(uids: Set<Int>): Pair<Int, Int> {
        return updateStateMutex.withLock {
            if (activeUpdateUids.isEmpty()) return@withLock 0 to 0
            val affected = activeUpdateUids.intersect(uids)
            if (affected.isEmpty()) return@withLock 0 to activeUpdateUids.size
            activeUpdateUids = activeUpdateUids - affected
            cancelledUpdateUids = cancelledUpdateUids + affected
            affected.size to activeUpdateUids.size
        }
    }

    private fun isRemoteUpdateCancelled(uid: Int): Boolean = cancelledUpdateUids.contains(uid)

    private fun RemotePatchBundle.isManualUpdateEligible(): Boolean =
        !updatesAutomatically &&
            searchUpdate &&
            enabled &&
            state is PatchBundleSource.State.Available

    private suspend fun cancelUpdateJob() {
        updateJobMutex.withLock {
            updateJob?.cancel()
        }
    }

    private suspend fun updateProgressAfterRemoval(affectedCount: Int, remaining: Int) {
        if (affectedCount <= 0) return
        if (remaining <= 0) {
            cancelBundleUpdateAutoClear()
            bundleUpdateProgressFlow.value = null
            cancelUpdateJob()
            return
        }
        bundleUpdateProgressFlow.update { progress ->
            if (progress == null) return@update null
            val clampedCompleted = progress.completed.coerceAtMost(remaining)
            progress.copy(total = remaining, completed = clampedCompleted)
        }
    }

    private suspend fun enqueueLocalImport() {
        localImportStateMutex.withLock {
            localImportQueued += 1
            localImportTotalSteps += LOCAL_IMPORT_STEPS
            localImportTotalBundles += 1
            val total = localImportTotalSteps
            val bundleCount = localImportTotalBundles
            bundleImportProgressFlow.update { progress ->
                if (progress?.isStepBased != true) return@update progress
                progress.copy(
                    total = total,
                    processed = progress.processed.coerceAtMost(total),
                    bundleCount = bundleCount
                )
            }
        }
    }

    private suspend fun completeLocalImport() {
        localImportStateMutex.withLock {
            localImportQueued = (localImportQueued - 1).coerceAtLeast(0)
            localImportProcessedSteps += LOCAL_IMPORT_STEPS
            if (localImportQueued == 0 && localImportProcessedSteps >= localImportTotalSteps) {
                localImportProcessedSteps = 0
                localImportTotalSteps = 0
                localImportTotalBundles = 0
            }
        }
    }

    private fun localImportBaseSteps(): Int = localImportProcessedSteps

    private fun localImportTotalSteps(): Int = localImportTotalSteps.coerceAtLeast(LOCAL_IMPORT_STEPS)

    private fun localImportBundleCount(): Int = localImportTotalBundles.coerceAtLeast(1)

    private fun changelogHistoryFile(uid: Int): File =
        directoryOf(uid).resolve("changelog_history.json")

    private fun changelogHistoryIdentityFile(uid: Int): File =
        directoryOf(uid).resolve("changelog_history_identity.txt")

    suspend fun getChangelogHistory(source: PatchBundleSource): List<PatchBundleChangelogEntry> =
        getChangelogHistory(source.uid, resolveChangelogHistoryIdentity(source))

    suspend fun getChangelogHistory(uid: Int): List<PatchBundleChangelogEntry> =
        getChangelogHistory(uid, expectedIdentity = null)

    private suspend fun getChangelogHistory(
        uid: Int,
        expectedIdentity: String?
    ): List<PatchBundleChangelogEntry> =
        withContext(Dispatchers.IO) {
            val storageLimit = normalizedChangelogStorageLimit()
            changelogHistoryMutex.withLock {
                reconcileChangelogHistoryIdentityInternal(uid, expectedIdentity)
                val current = trimChangelogHistoryEntries(readChangelogHistoryInternal(uid), storageLimit)
                writeChangelogHistoryInternal(uid, current)
                current
            }
        }

    suspend fun setChangelogHistory(source: PatchBundleSource, entries: List<PatchBundleChangelogEntry>) {
        setChangelogHistory(source.uid, entries, resolveChangelogHistoryIdentity(source))
    }

    suspend fun setChangelogHistory(uid: Int, entries: List<PatchBundleChangelogEntry>) {
        setChangelogHistory(uid, entries, expectedIdentity = null)
    }

    private suspend fun setChangelogHistory(
        uid: Int,
        entries: List<PatchBundleChangelogEntry>,
        expectedIdentity: String?
    ) {
        withContext(Dispatchers.IO) {
            val storageLimit = normalizedChangelogStorageLimit()
            changelogHistoryMutex.withLock {
                reconcileChangelogHistoryIdentityInternal(uid, expectedIdentity)
                writeChangelogHistoryInternal(uid, trimChangelogHistoryEntries(entries, storageLimit))
            }
        }
    }

    suspend fun mergeChangelogHistory(
        uid: Int,
        entries: List<PatchBundleChangelogEntry>
    ): List<PatchBundleChangelogEntry> = mergeChangelogHistory(uid, entries, expectedIdentity = null)

    private suspend fun mergeChangelogHistory(
        uid: Int,
        entries: List<PatchBundleChangelogEntry>,
        expectedIdentity: String?
    ): List<PatchBundleChangelogEntry> = withContext(Dispatchers.IO) {
        val storageLimit = normalizedChangelogStorageLimit()
        changelogHistoryMutex.withLock {
            reconcileChangelogHistoryIdentityInternal(uid, expectedIdentity)
            val current = trimChangelogHistoryEntries(readChangelogHistoryInternal(uid), storageLimit)
            val updated = mergeChangelogHistoryEntries(current, entries, storageLimit)
            writeChangelogHistoryInternal(uid, updated)
            updated
        }
    }

    suspend fun synchronizeChangelogHistory(source: RemotePatchBundle): List<PatchBundleChangelogEntry> {
        runCatching { source.fetchLatestReleaseInfo() }
            .getOrNull()
            ?.let { latestAsset ->
                recordChangelog(source, latestAsset)
            }
        val historyFetchLimit = normalizedChangelogFetchLimit().let { limit ->
            if (limit == Int.MAX_VALUE) limit else limit + 1
        }
        val historyEntries = source.fetchHistoricalChangelogEntries(historyFetchLimit)
        return if (historyEntries.isEmpty()) {
            getChangelogHistory(source)
        } else {
            mergeChangelogHistory(source.uid, historyEntries, source.changelogHistoryIdentity())
        }
    }

    suspend fun recordChangelog(
        source: RemotePatchBundle,
        asset: app.universal.patch.manager.network.dto.ReVancedAsset,
        hasReleaseBody: Boolean = false
    ) {
        recordChangelog(source.uid, asset, source.changelogHistoryIdentity(), hasReleaseBody)
    }

    suspend fun recordChangelog(uid: Int, asset: app.universal.patch.manager.network.dto.ReVancedAsset) {
        recordChangelog(uid, asset, expectedIdentity = null)
    }

    private suspend fun recordChangelog(
        uid: Int,
        asset: app.universal.patch.manager.network.dto.ReVancedAsset,
        expectedIdentity: String?,
        hasReleaseBody: Boolean = false
    ) {
        val entry = PatchBundleChangelogEntry.fromAsset(asset, hasReleaseBody)
        withContext(Dispatchers.IO) {
            val storageLimit = normalizedChangelogStorageLimit()
            changelogHistoryMutex.withLock {
                reconcileChangelogHistoryIdentityInternal(uid, expectedIdentity)
                val current = trimChangelogHistoryEntries(readChangelogHistoryInternal(uid), storageLimit)
                val updated = mergePatchBundleChangelogs(
                    current, listOf(entry), storageLimit
                )
                writeChangelogHistoryInternal(uid, updated)
            }
        }
    }

    private fun mergeChangelogHistoryEntries(
        current: List<PatchBundleChangelogEntry>,
        incoming: List<PatchBundleChangelogEntry>,
        maxEntries: Int
    ): List<PatchBundleChangelogEntry> =
        mergePatchBundleChangelogs(current, incoming, maxEntries)

    private fun trimChangelogHistoryEntries(
        entries: List<PatchBundleChangelogEntry>,
        maxEntries: Int
    ): List<PatchBundleChangelogEntry> =
        mergePatchBundleChangelogs(emptyList(), entries, maxEntries)

    private suspend fun normalizedChangelogFetchLimit(): Int =
        prefs.bundleChangelogFetchLimit.get()
            .coerceAtLeast(PreferencesManager.MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT)

    private suspend fun normalizedChangelogStorageLimit(): Int =
        prefs.bundleChangelogStorageLimit.get()
            .coerceAtLeast(PreferencesManager.MIN_BUNDLE_CHANGELOG_HISTORY_LIMIT)

    private fun readChangelogHistoryInternal(uid: Int): List<PatchBundleChangelogEntry> {
        val file = changelogHistoryFile(uid)
        if (!file.exists()) return emptyList()
        val content = runCatching { file.readText() }.getOrDefault("")
        if (content.isBlank()) return emptyList()
        return runCatching {
            changelogHistoryJson.decodeFromString<List<PatchBundleChangelogEntry>>(content)
        }.getOrDefault(emptyList())
    }

    private fun writeChangelogHistoryInternal(uid: Int, entries: List<PatchBundleChangelogEntry>) {
        val file = changelogHistoryFile(uid)
        if (entries.isEmpty()) {
            runCatching { file.delete() }
            return
        }
        file.parentFile?.mkdirs()
        runCatching {
            file.writeText(changelogHistoryJson.encodeToString(entries))
        }
    }

    private fun readChangelogHistoryIdentityInternal(uid: Int): String? {
        val file = changelogHistoryIdentityFile(uid)
        if (!file.exists()) return null
        return runCatching { file.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun writeChangelogHistoryIdentityInternal(uid: Int, identity: String?) {
        val file = changelogHistoryIdentityFile(uid)
        val normalized = identity?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            runCatching { file.delete() }
            return
        }
        file.parentFile?.mkdirs()
        runCatching { file.writeText(normalized) }
    }

    private fun reconcileChangelogHistoryIdentityInternal(uid: Int, expectedIdentity: String?) {
        val normalized = expectedIdentity?.trim()?.takeIf { it.isNotEmpty() }
        val current = readChangelogHistoryIdentityInternal(uid)
        if (normalized == null) {
            if (current != null) writeChangelogHistoryIdentityInternal(uid, null)
            return
        }
        if (current != null && current != normalized) {
            writeChangelogHistoryInternal(uid, emptyList())
        }
        if (current != normalized) {
            writeChangelogHistoryIdentityInternal(uid, normalized)
        }
    }

    private suspend fun resolveChangelogHistoryIdentity(source: PatchBundleSource): String? =
        source.asRemoteOrNull
            ?.changelogHistoryIdentity()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun setLocalImportProgress(
        baseProcessed: Int,
        offset: Int,
        displayName: String?,
        phase: BundleImportPhase,
        bytesRead: Long = 0L,
        bytesTotal: Long? = null,
    ) {
        val total = localImportTotalSteps()
        val processed = (baseProcessed + offset).coerceAtMost(total)
        setBundleImportProgress(
            ImportProgress(
                processed = processed,
                total = total,
                bundleCount = localImportBundleCount(),
                currentBundleName = displayName?.takeIf { it.isNotBlank() },
                phase = phase,
                bytesRead = bytesRead,
                bytesTotal = bytesTotal,
                isStepBased = true
            )
        )
    }

    private fun progressLabelFor(bundle: RemotePatchBundle): String {
        val explicitDisplayName = bundle.displayName?.trim().takeUnless { it.isNullOrBlank() }
        if (explicitDisplayName != null) return explicitDisplayName

        val unnamed = app.getString(R.string.patches_name_fallback)
        if (bundle.name == unnamed) {
            return bundleImportLabel(bundle.endpoint)
        }
        return bundle.name
    }

    suspend fun snapshotSelection(selection: PatchSelection) =
        selection.toPayload(sources.first(), bundleInfoFlow.first())

    suspend fun snapshotSelection(selection: PatchSelection, options: Options) =
        selection.toPayload(sources.first(), bundleInfoFlow.first(), options)

    private suspend inline fun dispatchAction(
        name: String,
        crossinline block: suspend ActionContext.(current: State) -> State
    ) {
        store.dispatch(object : Action<State> {
            override suspend fun ActionContext.execute(current: State) = block(current)
            override fun toString() = name
        })
    }

    private suspend inline fun <T> withTrackedReload(crossinline block: suspend () -> T): T {
        reloadInProgressCount.incrementAndGet()
        reloadInProgressFlow.value = true
        return try {
            block()
        } finally {
            if (reloadInProgressCount.decrementAndGet() <= 0) {
                reloadInProgressFlow.value = false
            }
        }
    }

    /**
     * Performs a reload. Do not call this outside of a store action.
     */
    private suspend fun doReload(): State {
        val entities = loadFromDb()

        val sources = entities.associate { it.uid to it.load() }.toMutableMap()
        cachedAppList.value = withContext(Dispatchers.IO) {
            sources.mapNotNull { (uid, source) ->
                if (source.patchBundle == null) return@mapNotNull null
                PatchableAppCache.read(directoryOf(uid), uid, source.displayTitle, source.version, source.enabled)
                    ?.let { uid to it }
            }.toMap()
        }
        val entityByUid = entities.associateBy { it.uid }
        sources.forEach { (uid, source) ->
            val remote = source as? RemotePatchBundle ?: return@forEach
            val entity = entityByUid[uid] ?: return@forEach
            val isExternal = remote is ExternalGraphqlPatchBundle
            var effectiveEntity = entity

            if (isExternal && entity.versionHash.isNullOrBlank()) {
                val externalMeta = ExternalBundleMetadataStore.read(directoryOf(uid))
                val externalVersion = externalMeta?.version?.takeIf { it.isNotBlank() }
                if (externalVersion != null) {
                    updateDb(uid) { it.copy(versionHash = externalVersion) }
                    effectiveEntity = entity.copy(versionHash = externalVersion)
                    sources[uid] = effectiveEntity.load()
                }
            }

            val bundleVersion = remote.version?.takeUnless { it.isBlank() } ?: return@forEach
            val bundleNormalized = normalizeVersionForCompare(bundleVersion) ?: return@forEach
            val storedNormalized = normalizeVersionForCompare(effectiveEntity.versionHash)
            if (storedNormalized == bundleNormalized) return@forEach
            // Keep the persisted remote signature once we have one.
            // Falling back to the local manifest version is only needed to seed empty rows.
            if (!effectiveEntity.versionHash.isNullOrBlank()) {
                return@forEach
            }
            updateDb(uid) { it.copy(versionHash = bundleVersion) }
            sources[uid] = effectiveEntity.copy(versionHash = bundleVersion).load()
        }

        val hasOutOfDateNames = sources.values.any { it.isNameOutOfDate }
        if (hasOutOfDateNames) dispatchAction(
            "Sync names"
        ) { state ->
            val nameChanges = state.sources.mapNotNull { (_, src) ->
                if (!src.isNameOutOfDate) return@mapNotNull null
                val newName = src.patchBundle?.manifestAttributes?.name?.takeIf { it != src.name }
                    ?: return@mapNotNull null

                src.uid to newName
            }
            val sources = state.sources.toMutableMap()
            val info = state.info.toMutableMap()
            nameChanges.forEach { (uid, name) ->
                updateDb(uid) { it.copy(name = name) }
                sources[uid] = sources[uid]!!.copy(name = name)
                info[uid] = info[uid]?.copy(name = name) ?: return@forEach
            }

            State(sources.toPersistentMap(), info.toPersistentMap())
        }
        val info = loadMetadata(sources).toMutableMap()
        // Reseam stores identity in its signed catalog rather than a Java JAR manifest.
        // Read it through the runtime so malformed or untrusted catalogs cannot supply names.
        info.filterValues { it.bundleType == PatchBundleType.RESEAM }.keys.forEach { uid ->
            val source = sources[uid] ?: return@forEach
            val bundle = source.patchBundle ?: return@forEach
            val catalogName = runCatching { ReseamRuntimeBridge.loadBundleName(bundle.patchesJar) }
                .getOrNull() ?: return@forEach
            if (catalogName != source.name) {
                updateDb(uid) { it.copy(name = catalogName) }
                val renamedSource = source.copy(name = catalogName)
                sources[uid] = renamedSource
                info[uid] = info[uid]?.copy(name = renamedSource.displayTitle) ?: return@forEach
            }
        }

        manualUpdateInfoFlow.update { current ->
            current.filterKeys { uid ->
                val bundle = sources[uid] as? RemotePatchBundle
                bundle?.isManualUpdateEligible() == true
            }
        }

        return State(sources.toPersistentMap(), info.toPersistentMap())
    }

    suspend fun reload() = withTrackedReload {
        dispatchAction("Full reload") {
            doReload()
        }
    }

    suspend fun awaitReady() {
        reloadInProgress.first { inProgress -> !inProgress }
    }

    private suspend fun loadFromDb(): List<PatchBundleEntity> =
        dao.all().mapNotNull { entity ->
            if (!isLegacyOfficialBundle(entity.uid, entity.source.toString())) return@mapNotNull entity
            if (prefs.legacyOfficialBundleRemoved.get()) {
                dao.remove(entity.uid)
                directoryOf(entity.uid).deleteRecursively()
                return@mapNotNull null
            }
            // Keep installed data and UID-based selections when upgrading. The old
            // built-in source becomes an ordinary removable repository source.
            val directory = directoryOf(entity.uid)
            val settings = RepositoryBundleSettingsStore.read(directory).forLegacyOfficialMigration(
                apiSource = entity.source is SourceInfo.API,
                legacyPrereleases = prefs.legacyPatchesPrereleases.get()
            )
            RepositoryBundleSettingsStore.write(directory, settings)
            entity.copy(
                source = SourceInfo.Remote(Url(legacyOfficialBundleRegistryUrl(if (settings.useLatest) null else settings.usePrereleases))),
                name = entity.name.ifBlank { app.getString(R.string.patches_name_default) }
            ).also { dao.upsert(it) }
        }

    fun discoveryImportKey(bundle: ExternalBundleSnapshot, preferLatestAcrossChannels: Boolean = false): String {
        val sourceUrl = bundle.sourceUrl.trim().trimEnd('/').lowercase(Locale.US)
        val owner = bundle.ownerName.trim().lowercase(Locale.US)
        val repo = bundle.repoName.trim().lowercase(Locale.US)
        val apiHost = bundle.apiHost.trim().lowercase(Locale.US)
            .takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
        val base = when {
            sourceUrl.isNotBlank() -> sourceUrl
            owner.isNotBlank() || repo.isNotBlank() ->
                listOf(owner, repo).filter { it.isNotBlank() }.joinToString("/")
            !bundle.downloadUrl.isNullOrBlank() ->
                bundle.downloadUrl.trim().lowercase(Locale.US)
            bundle.bundleId > 0 -> "${apiHost ?: "unknown-host"}|id=${bundle.bundleId}"
            else -> "unknown"
        }
        val channel = if (preferLatestAcrossChannels) {
            "latest"
        } else if (bundle.isPrerelease) {
            "prerelease"
        } else {
            "release"
        }
        return "$base|$channel"
    }

    suspend fun enqueueDiscoveryImport(
        bundle: ExternalBundleSnapshot,
        searchUpdate: Boolean,
        autoUpdate: Boolean,
        preferLatestAcrossChannels: Boolean = false
    ): DiscoveryImportEnqueueResult {
        val bundleKey = discoveryImportKey(bundle, preferLatestAcrossChannels)
        return discoveryImportMutex.withLock {
            val inProgress = discoveryImportProgressFlow.value.containsKey(bundleKey)
            val alreadyQueued = discoveryImportQueuedKeys.contains(bundleKey)
            if (inProgress || alreadyQueued) {
                return@withLock DiscoveryImportEnqueueResult.Duplicate
            }
            val wasActive = discoveryImportJob?.isActive == true ||
                discoveryImportProgressFlow.value.isNotEmpty() ||
                discoveryImportQueuedKeys.isNotEmpty()
            discoveryImportQueuedKeys.add(bundleKey)
            discoveryImportQueue.addLast(
                DiscoveryImportRequest(
                    key = bundleKey,
                    bundle = bundle,
                    searchUpdate = searchUpdate,
                    autoUpdate = autoUpdate,
                    preferLatestAcrossChannels = preferLatestAcrossChannels
                )
            )
            discoveryImportProgressFlow.update { current ->
                if (current.containsKey(bundleKey)) current
                else current + (bundleKey to DiscoveryImportProgress(0L, null, DiscoveryImportStatus.Queued))
            }
            updateDiscoveryQueuedLocked()
            if (discoveryImportJob?.isActive != true) {
                discoveryImportJob = scope.launch { runDiscoveryImportQueue() }
            }
            if (wasActive) {
                DiscoveryImportEnqueueResult.Queued
            } else {
                DiscoveryImportEnqueueResult.Started
            }
        }
    }

    private data class BundleDigestInfo(
        val hash: String,
        val size: Long,
        val lastModified: Long,
    )

    private fun bundleDigestFile(uid: Int) = directoryOf(uid).resolve("patches.sha256")

    private fun readBundleDigestInfo(uid: Int): BundleDigestInfo? {
        val file = bundleDigestFile(uid)
        if (!file.exists()) return null
        val content = runCatching { file.readText() }.getOrDefault("").trim()
        if (content.isBlank()) return null
        val parts = content.split('|')
        if (parts.size < 3) return null
        val hash = parts[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val lastModified = parts[2].toLongOrNull() ?: return null
        return BundleDigestInfo(hash, size, lastModified)
    }

    fun installedBundleSha256(uid: Int): String? = bundleDigestHashCache[uid]

    private fun writeBundleDigestInfo(uid: Int, info: BundleDigestInfo) {
        val file = bundleDigestFile(uid)
        file.parentFile?.mkdirs()
        runCatching { file.writeText("${info.hash}|${info.size}|${info.lastModified}") }
    }

    private fun computeBundleHash(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            val hexChars = "0123456789abcdef".toCharArray()
            val result = CharArray(bytes.size * 2)
            var index = 0
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                result[index++] = hexChars[value ushr 4]
                result[index++] = hexChars[value and 0x0f]
            }
            String(result)
        }.getOrNull()
    }

    private fun clearBundleOdex(uid: Int) {
        runCatching {
            val oatDir = directoryOf(uid).resolve("oat")
            if (oatDir.exists()) oatDir.deleteRecursively()
        }
    }

    private fun clearAllBundleOdex() {
        runCatching {
            bundlesDir.listFiles()?.forEach { dir ->
                val oatDir = dir.resolve("oat")
                if (oatDir.exists()) oatDir.deleteRecursively()
            }
        }
    }

    private suspend fun ensureBundleCacheInitialized() {
        if (bundleCacheInitialized) return
        bundleCacheMutex.withLock {
            if (bundleCacheInitialized) return
            val currentVersion = BuildConfig.VERSION_CODE
            val cachedVersion = prefs.patchBundleCacheVersionCode.get()
            if (cachedVersion != currentVersion) {
                withContext(Dispatchers.IO) {
                    clearAllBundleOdex()
                }
                prefs.patchBundleCacheVersionCode.update(currentVersion)
            }
            bundleCacheInitialized = true
        }
    }

    private suspend fun ensureBundleCacheValid(uid: Int, bundle: PatchBundle) {
        withContext(Dispatchers.IO) {
            val file = File(bundle.patchesJar)
            if (!file.exists()) {
                bundleDigestHashCache.remove(uid)
                return@withContext
            }

            val size = runCatching { file.length() }.getOrDefault(0L)
            val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
            val existing = readBundleDigestInfo(uid)
            if (existing != null && existing.size == size && existing.lastModified == lastModified) {
                bundleDigestHashCache[uid] = existing.hash
                return@withContext
            }

            val hash = computeBundleHash(file) ?: run {
                bundleDigestHashCache.remove(uid)
                return@withContext
            }
            val changed = existing == null || existing.hash != hash
            if (changed) {
                clearBundleOdex(uid)
            }
            writeBundleDigestInfo(uid, BundleDigestInfo(hash, size, lastModified))
            bundleDigestHashCache[uid] = hash
        }
    }

    private fun loadBundleMetadataInternal(
        bundle: PatchBundle,
        source: PatchBundleSource? = null
    ): Pair<PatchBundleType, List<PatchInfo>> {
        val bundlePath = bundle.patchesJar
        val declaredType = detectDeclaredBundleType(bundle, source)
        if (declaredType == PatchBundleType.RESEAM) {
            if (!ReseamRuntimeAssets.isAvailable(app)) {
                throw IllegalStateException("Reseam runtime is not included in this build.")
            }
            return PatchBundleType.RESEAM to
                ReseamRuntimeBridge.loadMetadata(bundlePath)
        }
        if (declaredType == PatchBundleType.MORPHE) {
            return PatchBundleType.MORPHE to MorpheRuntimeBridge.loadMetadata(bundlePath)
        }

        val preferredRevancedVersion = source?.uid
            ?.let(::resolvedRevancedPatcherVersion)
            ?: RevancedPatcherVersion.V22
        val revancedVersionsToTry = buildList {
            add(preferredRevancedVersion)
            add(
                when (preferredRevancedVersion) {
                    RevancedPatcherVersion.V21 -> RevancedPatcherVersion.V22
                    RevancedPatcherVersion.V22 -> RevancedPatcherVersion.V21
                }
            )
        }.distinct()

        val revancedFailures = linkedMapOf<RevancedPatcherVersion, Throwable>()
        for ((index, version) in revancedVersionsToTry.withIndex()) {
            val revancedResult = runCatching { loadRevancedMetadata(bundle, bundlePath, version) }
            if (revancedResult.isSuccess) {
                source?.uid?.let { uid ->
                    persistRevancedPatcherHint(uid, version)
                }
                return PatchBundleType.REVANCED to revancedResult.getOrThrow()
            }

            val throwable = revancedResult.exceptionOrNull()
            if (throwable != null) {
                revancedFailures[version] = throwable
                if (index < revancedVersionsToTry.lastIndex) {
                    Log.w(
                        tag,
                        "Failed to load ReVanced metadata with patcher ${version.versionName}; retrying.",
                        throwable
                    )
                }
            }
        }

        val revancedResult = Result.failure<List<PatchInfo>>(
            revancedFailures.values.firstOrNull()
                ?: IllegalStateException("Failed to load patch bundle metadata")
        )

        if (declaredType == PatchBundleType.REVANCED) {
            val error = IllegalStateException("Failed to load patch bundle metadata")
            revancedFailures.values.forEach(error::addSuppressed)
            if (revancedFailures.isEmpty()) {
                revancedResult.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }

        val morpheResult = if (MorpheRuntimeAssets.isAvailable(app)) {
            runCatching { MorpheRuntimeBridge.loadMetadata(bundlePath) }
        } else {
            Result.failure(missingRuntimeException("Morphe"))
        }
        if (morpheResult.isSuccess) {
            return PatchBundleType.MORPHE to morpheResult.getOrThrow()
        }

        val error = IllegalStateException("Failed to load patch bundle metadata")
        revancedFailures.values.forEach(error::addSuppressed)
        if (revancedFailures.isEmpty()) {
            revancedResult.exceptionOrNull()?.let(error::addSuppressed)
        }
        morpheResult.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }

    private fun loadRevancedMetadata(
        bundle: PatchBundle,
        bundlePath: String,
        version: RevancedPatcherVersion
    ): List<PatchInfo> = when (version) {
        RevancedPatcherVersion.V21 -> {
            if (!Revanced21RuntimeAssets.isAvailable(app)) {
                throw missingRuntimeException("ReVanced v21")
            }
            Revanced21RuntimeBridge.loadMetadata(bundlePath)
        }
        RevancedPatcherVersion.V22 -> {
            if (!Revanced22RuntimeAssets.isAvailable(app)) throw missingRuntimeException("ReVanced v22")
            Revanced22RuntimeBridge.loadMetadata(bundlePath)
        }
    }

    private fun missingRuntimeException(runtimeName: String): IllegalStateException =
        IllegalStateException("$runtimeName runtime plugin is not installed or trusted.")

    private fun persistRevancedPatcherHint(uid: Int, version: RevancedPatcherVersion) {
        when (version) {
            RevancedPatcherVersion.V21 -> clearRevancedPatcherHint(uid)
            RevancedPatcherVersion.V22 -> writeRevancedPatcherHint(uid, version)
        }
    }

    private fun detectDeclaredBundleType(
        bundle: PatchBundle,
        source: PatchBundleSource?
    ): PatchBundleType? {
        val extension = resolveBundleExtension(bundle, source)
        if (ReseamBundleFormat.isCandidate(java.io.File(bundle.patchesJar))) return PatchBundleType.RESEAM
        if (extension == "reseam") return PatchBundleType.RESEAM
        if (extension == "mpp") return PatchBundleType.MORPHE
        return if (extension == "rvp") PatchBundleType.REVANCED else null
    }

    private fun resolveBundleExtension(
        bundle: PatchBundle,
        source: PatchBundleSource?
    ): String? {
        val sourceHint = source?.uid?.let(::readLocalBundleHint)
        val candidates = sequenceOf(
            source?.asRemoteOrNull?.endpoint,
            sourceHint,
            source?.name,
            source?.displayName,
            bundle.manifestAttributes?.source,
            bundle.manifestAttributes?.website,
            bundle.manifestAttributes?.name
        )
        return candidates
            .mapNotNull(::extractBundleExtension)
            .firstOrNull()
    }

    private fun extractBundleExtension(value: String?): String? {
        val normalized = value
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) return null
        val candidate = normalized
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
        val extension = candidate
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        return extension.takeIf { it in supportedBundleExtensions }
    }

    private fun readLocalBundleHint(uid: Int): String? {
        val file = directoryOf(uid).resolve(LOCAL_BUNDLE_HINT_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun writeLocalBundleHint(uid: Int, hint: String?) {
        val normalized = hint?.trim().takeIf { !it.isNullOrBlank() } ?: return
        val file = directoryOf(uid).resolve(LOCAL_BUNDLE_HINT_FILE)
        runCatching { file.writeText(normalized) }
    }

    private fun readRevancedPatcherHint(uid: Int): RevancedPatcherVersion? {
        val file = directoryOf(uid).resolve(REVANCED_PATCHER_HINT_FILE)
        if (!file.exists()) return null
        val raw = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        return RevancedPatcherVersion.fromStorage(raw)
    }

    private fun resolvedRevancedPatcherVersion(uid: Int): RevancedPatcherVersion =
        readRevancedPatcherHint(uid) ?: RevancedPatcherVersion.V21

    private fun writeRevancedPatcherHint(uid: Int, version: RevancedPatcherVersion) {
        val file = directoryOf(uid).resolve(REVANCED_PATCHER_HINT_FILE)
        runCatching { file.writeText(version.storageValue) }
    }

    private fun clearRevancedPatcherHint(uid: Int) {
        val file = directoryOf(uid).resolve(REVANCED_PATCHER_HINT_FILE)
        if (!file.exists()) return
        runCatching { file.delete() }
    }

    private suspend fun loadBundleMetadata(
        uid: Int,
        bundle: PatchBundle,
        source: PatchBundleSource? = null
    ): Pair<PatchBundleType, List<PatchInfo>> {
        ensureBundleCacheValid(uid, bundle)
        val initial = runCatching { loadBundleMetadataInternal(bundle, source) }
        if (initial.isSuccess) return initial.getOrThrow()

        clearBundleOdex(uid)
        val retry = runCatching { loadBundleMetadataInternal(bundle, source) }
        if (retry.isSuccess) return retry.getOrThrow()

        val error = IllegalStateException("Failed to load patch bundle metadata")
        initial.exceptionOrNull()?.let(error::addSuppressed)
        retry.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }

    private suspend fun loadMetadata(sources: MutableMap<Int, PatchBundleSource>): Map<Int, PatchBundleInfo.Global> {
        // Map bundles -> sources
        val map = sources.mapNotNull { (_, src) ->
            (src.patchBundle ?: return@mapNotNull null) to src
        }.toMap()

        if (map.isEmpty()) return emptyMap()

        ensureBundleCacheInitialized()

        val failures = mutableListOf<Pair<Int, Throwable>>()
        val metadata = mutableMapOf<Int, PatchBundleInfo.Global>()

        for ((bundle, src) in map) {
            try {
                val (bundleType, patches) = loadBundleMetadata(src.uid, bundle, src)
                metadata[src.uid] = PatchBundleInfo.Global(
                    src.displayTitle,
                    src.version,
                    src.uid,
                    bundleType,
                    src.enabled,
                    patches
                )
                withContext(Dispatchers.IO) {
                    PatchableAppCache.write(directoryOf(src.uid), metadata.getValue(src.uid))
                }
            } catch (error: Throwable) {
                detectDeclaredBundleType(bundle, src)?.let { declaredType ->
                    metadata[src.uid] = PatchBundleInfo.Global(
                        src.displayTitle,
                        src.version,
                        src.uid,
                        declaredType,
                        src.enabled,
                        emptyList()
                    )
                }
                failures += src.uid to error
                Log.e(tag, "Failed to load bundle ${src.name}", error)
            }
        }

        // Commit failures with this reload. A queued action could otherwise overwrite
        // a newer successful reload after a runtime install or trust change.
        failures.forEach { (uid, throwable) ->
            sources[uid] = sources[uid]?.copy(error = throwable) ?: return@forEach
        }

        return metadata
    }

    suspend fun findBestBundleVersionMatch(
        packageName: String,
        version: String?,
        versionCode: Long? = null
    ): BundleVersionMatch? =
        withContext(Dispatchers.Default) {
            val scopedBundles = scopedBundleInfoFlow(packageName, version, versionCode).first()
            if (scopedBundles.isEmpty()) return@withContext null

            val suggestedByBundle = suggestedVersionsByBundle.first()
            val normalizedVersion = version?.trim().orEmpty()
            var best: BundleVersionCandidate? = null

            scopedBundles.forEach { bundle ->
                val recommendedVersion = suggestedByBundle[bundle.uid]?.get(packageName)
                val hasPackageSpecificCompatibility = bundle.compatible.any { patch ->
                    patch.compatiblePackages?.any { compatible -> compatible.packageName == packageName } == true
                }
                val hasPackageSpecificAllVersionCompatibility = bundle.compatible.any { patch ->
                    patch.compatiblePackages?.any { compatible ->
                        compatible.packageName == packageName && compatible.versions == null
                    } == true
                }
                val hasUniversalCompatibility = bundle.universal.isNotEmpty()
                val targetsAllVersions = hasPackageSpecificAllVersionCompatibility || hasUniversalCompatibility
                val hasCompatibility = hasPackageSpecificCompatibility || hasUniversalCompatibility
                if (!hasCompatibility) return@forEach

                val recommendedMatchesInput =
                    hasPackageSpecificCompatibility &&
                        recommendedVersion?.equals(normalizedVersion, ignoreCase = true) == true
                val score = when {
                    recommendedMatchesInput -> 3
                    hasPackageSpecificCompatibility -> 2
                    hasUniversalCompatibility -> 1
                    else -> 0
                }
                val versionOverride = when {
                    targetsAllVersions -> null
                    recommendedMatchesInput -> null
                    normalizedVersion.isBlank() -> null
                    else -> normalizedVersion
                }
                val candidate = BundleVersionCandidate(
                    bundleUid = bundle.uid,
                    score = score,
                    versionOverride = versionOverride,
                    targetsAllVersions = targetsAllVersions,
                    usesUniversalFallback = !hasPackageSpecificCompatibility && hasUniversalCompatibility
                )
                if (best == null || candidate.score > best!!.score) {
                    best = candidate
                }
            }

            best?.let {
                BundleVersionMatch(
                    bundleUid = it.bundleUid,
                    versionOverride = it.versionOverride,
                    targetsAllVersions = it.targetsAllVersions,
                    usesUniversalFallback = it.usesUniversalFallback
                )
            }
        }

    suspend fun isVersionAllowed(packageName: String, version: String, versionCode: Long? = null) =
        assessVersionSelection(packageName, version, versionCode).isAllowed

    suspend fun assessVersionSelection(
        packageName: String,
        version: String,
        versionCode: Long? = null
    ) =
        withContext(Dispatchers.Default) {
            val match = findBestBundleVersionMatch(packageName, version, versionCode)
            val suggestedVersion = suggestedVersions.first()[packageName]
            val suggestedVersionCodes = suggestedVersion?.let { targetVersion ->
                enabledBundlesInfoFlow.first().values
                    .asSequence()
                    .filter { it.bundleType == PatchBundleType.MORPHE }
                    .flatMap { it.patches.asSequence() }
                    .flatMap { it.compatiblePackages.orEmpty().asSequence() }
                    .filter { it.packageName == packageName }
                    .flatMap { it.versionCodes?.get(targetVersion).orEmpty().asSequence() }
                    .toSet()
            }.orEmpty()
            val allowUniversalPatches = prefs.disableUniversalPatchCheck.get()
            val allowIncompatiblePatches = prefs.disablePatchVersionCompatCheck.get()
            val requireSuggestedVersion = prefs.suggestedVersionSafeguard.get()
            val usesUniversalFallback = match?.usesUniversalFallback == true
            val enforceSuggestedVersionSafeguards =
                requireSuggestedVersion && !allowIncompatiblePatches
            val requiresUniversalPatchesEnabled =
                usesUniversalFallback &&
                    !allowUniversalPatches
            val canContinueWithUniversalFallback =
                enforceSuggestedVersionSafeguards &&
                    allowUniversalPatches &&
                    usesUniversalFallback

            val isAllowed = when {
                requiresUniversalPatchesEnabled -> false
                !requireSuggestedVersion -> true
                match == null -> false
                canContinueWithUniversalFallback -> false
                else -> true
            }

            VersionSelectionAssessment(
                isAllowed = isAllowed,
                suggestedVersion = suggestedVersion,
                suggestedVersionCodes = suggestedVersionCodes,
                canContinueWithUniversalFallback = canContinueWithUniversalFallback,
                requiresUniversalPatchesEnabled = requiresUniversalPatchesEnabled
            )
        }

    /**
     * Get the directory of the [PatchBundleSource] with the specified [uid], creating it if needed.
     */
    private fun directoryOf(uid: Int) = bundlesDir.resolve(uid.toString()).also { it.mkdirs() }

    private fun PatchBundleEntity.load(): PatchBundleSource {
        val dir = directoryOf(uid)
        val actualName =
            name.ifEmpty { app.getString(R.string.patches_name_fallback) }
        val normalizedDisplayName = displayName?.takeUnless { it.isBlank() }

        return when (source) {
            is SourceInfo.Local -> LocalPatchBundle(
                actualName,
                uid,
                normalizedDisplayName,
                createdAt,
                updatedAt,
                null,
                dir,
                enabled
            )
            is SourceInfo.API -> copy(
                source = SourceInfo.Remote(Url(legacyOfficialBundleRegistryUrl(RepositoryBundleSettingsStore.read(dir).usePrereleases)))
            ).load()

            is SourceInfo.Remote -> {
                val repositorySettings = RepositoryBundleSettingsStore.read(dir)
                JsonPatchBundle(
                    actualName,
                    uid,
                    normalizedDisplayName,
                    createdAt,
                    updatedAt,
                    versionHash,
                    null,
                    dir,
                    source.url.toString(),
                    autoUpdate,
                    searchUpdate,
                    lastNotifiedVersion,
                    enabled,
                    repositorySettings.usePrereleases,
                    repositorySettings.useLatest,
                    repositorySettings.useSpecificVersion,
                    repositorySettings.selectedVersion,
                ).let { jsonBundle ->
                    val external = ExternalBundleMetadataStore.read(dir)
                    if (external == null) {
                        jsonBundle
                    } else {
                        ExternalGraphqlPatchBundle(
                            actualName,
                            uid,
                            normalizedDisplayName,
                            createdAt,
                            updatedAt,
                            versionHash,
                            null,
                            dir,
                            source.url.toString(),
                            autoUpdate,
                            searchUpdate,
                            lastNotifiedVersion,
                            enabled,
                            external
                        )
                    }
                }
            }
            // PR #35: https://github.com/Jman-Github/Universal-Patch-Manager/pull/35
            is SourceInfo.GitHubPullRequest -> GitHubPullRequestBundle(
                actualName,
                uid,
                normalizedDisplayName,
                createdAt,
                updatedAt,
                versionHash,
                null,
                dir,
                source.url.toString(),
                autoUpdate,
                searchUpdate,
                lastNotifiedVersion,
                enabled
            )
        }
    }

    private suspend fun nextSortOrder(): Int = (dao.maxSortOrder() ?: -1) + 1

    private suspend fun ensureUniqueName(requestedName: String?, excludeUid: Int? = null): String {
        val base = requestedName?.trim().takeUnless { it.isNullOrBlank() }
            ?: app.getString(R.string.patches_name_fallback)

        val existing = dao.all()
            .filterNot { entity -> excludeUid != null && entity.uid == excludeUid }
            .map { it.name.lowercase(Locale.US) }
            .toSet()

        if (base.lowercase(Locale.US) !in existing) return base

        var suffix = 2
        var candidate: String
        do {
            candidate = "$base ($suffix)"
            suffix += 1
        } while (candidate.lowercase(Locale.US) in existing)
        return candidate
    }

    private suspend fun createEntity(
        name: String,
        source: Source,
        autoUpdate: Boolean = false,
        searchUpdate: Boolean = true,
        displayName: String? = null,
        uid: Int? = null,
        sortOrder: Int? = null,
        createdAt: Long? = null,
        updatedAt: Long? = null,
        lastNotifiedVersion: String? = null
    ): PatchBundleEntity {
        val resolvedUid = uid ?: generateUid()
        val existingProps = dao.getProps(resolvedUid)
        val normalizedDisplayName = displayName?.takeUnless { it.isBlank() }
            ?: existingProps?.displayName?.takeUnless { it.isBlank() }

        val normalizedName = ensureUniqueName(name, resolvedUid)
        val assignedSortOrder = when {
            sortOrder != null -> sortOrder
            else -> existingProps?.sortOrder ?: nextSortOrder()
        }
        val now = System.currentTimeMillis()
        val resolvedCreatedAt = createdAt ?: existingProps?.createdAt ?: now
        val resolvedUpdatedAt = updatedAt ?: now
        val resolvedEnabled = existingProps?.enabled ?: true
        val resolvedSearchUpdate = existingProps?.searchUpdate ?: searchUpdate
        val resolvedLastNotifiedVersion = lastNotifiedVersion ?: existingProps?.lastNotifiedVersion
        val entity = PatchBundleEntity(
            uid = resolvedUid,
            name = normalizedName,
            displayName = normalizedDisplayName,
            versionHash = null,
            source = source,
            autoUpdate = autoUpdate,
            searchUpdate = resolvedSearchUpdate,
            lastNotifiedVersion = resolvedLastNotifiedVersion,
            enabled = resolvedEnabled,
            sortOrder = assignedSortOrder,
            createdAt = resolvedCreatedAt,
            updatedAt = resolvedUpdatedAt
        )
        dao.upsert(entity)
        return entity
    }

    /**
     * Updates a patch bundle in the database. Do not use this outside an action.
     */
    private suspend fun updateDb(
        uid: Int,
        block: (PatchBundleProperties) -> PatchBundleProperties
    ) {
        val previous = dao.getProps(uid)!!
        val new = block(previous)
        dao.upsert(
            PatchBundleEntity(
                uid = uid,
                name = new.name,
                displayName = new.displayName?.takeUnless { it.isBlank() },
                versionHash = new.versionHash,
                source = new.source,
                autoUpdate = new.autoUpdate,
                searchUpdate = new.searchUpdate,
                lastNotifiedVersion = new.lastNotifiedVersion,
                enabled = new.enabled,
                sortOrder = new.sortOrder,
                createdAt = new.createdAt,
                updatedAt = new.updatedAt
            )
        )
    }

    suspend fun reset() = withTrackedReload {
        dispatchAction("Reset") { state ->
            dao.reset()
            state.sources.keys.forEach { directoryOf(it).deleteRecursively() }
            doReload()
        }
    }

    private suspend fun toast(@StringRes id: Int, vararg args: Any?) =
        withContext(Dispatchers.Main) { app.toast(app.getString(id, *args)) }

    private data class UpdateRequest(
        val force: Boolean,
        val showToast: Boolean,
        val allowUnsafeNetwork: Boolean,
        val showProgress: Boolean,
        val onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)?,
        val predicate: (bundle: RemotePatchBundle) -> Boolean,
    )

    private fun mergeUpdateRequests(requests: List<UpdateRequest>): UpdateRequest {
        val callbacks = requests.mapNotNull { it.onPerBundleProgress }
        val mergedCallback: ((RemotePatchBundle, Long, Long?) -> Unit)? = if (callbacks.isEmpty()) {
            null
        } else {
            { bundle, read, total -> callbacks.forEach { it(bundle, read, total) } }
        }
        return UpdateRequest(
            force = requests.any { it.force },
            showToast = requests.any { it.showToast },
            allowUnsafeNetwork = requests.any { it.allowUnsafeNetwork },
            showProgress = requests.any { it.showProgress },
            onPerBundleProgress = mergedCallback,
            predicate = { bundle -> requests.any { it.predicate(bundle) } }
        )
    }

    private suspend fun enqueueUpdateRequest(request: UpdateRequest) {
        updateStateMutex.withLock {
            pendingUpdateRequests += request
        }
    }

    private suspend fun drainPendingUpdateRequests(): UpdateRequest? {
        return updateStateMutex.withLock {
            if (pendingUpdateRequests.isEmpty()) return@withLock null
            val drained = pendingUpdateRequests.toList()
            pendingUpdateRequests.clear()
            mergeUpdateRequests(drained)
        }
    }

    // Enter the cleanup block even if cancellation arrives before the job is dispatched.
    @OptIn(DelicateCoroutinesApi::class)
    private fun launchRemoteUpdateJob(request: UpdateRequest): Job = scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            coroutineContext.ensureActive()
            performRemoteUpdate(
                force = request.force,
                showToast = request.showToast,
                allowUnsafeNetwork = request.allowUnsafeNetwork,
                showProgress = request.showProgress,
                onPerBundleProgress = request.onPerBundleProgress,
                onBundleUpdated = null,
                predicate = request.predicate
            )
        } finally {
            finishRemoteUpdateJob(coroutineContext.job)
        }
    }

    private suspend fun finishRemoteUpdateJob(owner: Job) = withContext(NonCancellable) {
        updateJobMutex.withLock {
            if (updateJob != null && updateJob != owner) return@withLock
            val next = drainPendingUpdateRequests()
            updateJob = next?.let(::launchRemoteUpdateJob)
        }
    }

    suspend fun disable(vararg bundles: PatchBundleSource) {
        val toggledUids = bundles.map(PatchBundleSource::uid).toIntArray()
        dispatchAction("Disable (${bundles.map { it.uid }.joinToString(",")})") { state ->
            bundles.forEach { bundle ->
                updateDb(bundle.uid) { it.copy(enabled = !it.enabled) }
            }

            val toggledUids = bundles.map(PatchBundleSource::uid).toSet()
            val sources = state.sources.mutate { map ->
                toggledUids.forEach { uid ->
                    map[uid] = map[uid]?.let { src -> src.copy(enabled = !src.enabled) } ?: return@forEach
                }
            }
            val info = state.info.mutate { map ->
                toggledUids.forEach { uid ->
                    val enabled = sources[uid]?.enabled ?: return@forEach
                    map[uid] = map[uid]?.copy(enabled = enabled) ?: return@forEach
                }
            }
            val ineligibleUids = toggledUids.filterTo(mutableSetOf()) { uid ->
                (sources[uid] as? RemotePatchBundle)?.isManualUpdateEligible() != true
            }
            if (ineligibleUids.isNotEmpty()) {
                manualUpdateInfoFlow.update { current -> current - ineligibleUids }
            }

            state.copy(sources = sources, info = info)
        }
        if (toggledUids.isNotEmpty()) checkManualUpdates(*toggledUids)
    }

    suspend fun setEnabledStates(states: Map<Int, Boolean>) {
        var changedUids = emptySet<Int>()
        dispatchAction("Set bundle enabled states") { state ->
            val updates = states.filter { (uid, enabled) ->
                state.sources[uid]?.enabled != enabled
            }
            if (updates.isEmpty()) return@dispatchAction state
            changedUids = updates.keys

            updates.forEach { (uid, enabled) ->
                updateDb(uid) { it.copy(enabled = enabled) }
            }

            val sources = state.sources.mutate { map ->
                updates.forEach { (uid, enabled) ->
                    map[uid] = map[uid]?.copy(enabled = enabled) ?: return@forEach
                }
            }
            val info = state.info.mutate { map ->
                updates.forEach { (uid, enabled) ->
                    map[uid] = map[uid]?.copy(enabled = enabled) ?: return@forEach
                }
            }
            val ineligibleUids = updates.keys.filterTo(mutableSetOf()) { uid ->
                (sources[uid] as? RemotePatchBundle)?.isManualUpdateEligible() != true
            }
            if (ineligibleUids.isNotEmpty()) {
                manualUpdateInfoFlow.update { current -> current - ineligibleUids }
            }

            state.copy(sources = sources, info = info)
        }
        if (changedUids.isNotEmpty()) checkManualUpdates(*changedUids.toIntArray())
    }

    suspend fun remove(vararg bundles: PatchBundleSource) =
        dispatchAction("Remove (${bundles.map { it.uid }.joinToString(",")})") { state ->
            val removedUids = bundles.map(PatchBundleSource::uid).toSet()
            bundles.forEach {
                dao.remove(it.uid)
                directoryOf(it.uid).deleteRecursively()
            }

            manualUpdateInfoFlow.update { current -> current - removedUids }

            val (affectedCount, remaining) = cancelRemoteUpdates(removedUids)
            updateProgressAfterRemoval(affectedCount, remaining)

            val sources = state.sources.mutate { map ->
                removedUids.forEach(map::remove)
            }
            val info = state.info.mutate { map ->
                removedUids.forEach(map::remove)
            }

            state.copy(sources = sources, info = info)
        }

    enum class DisplayNameUpdateResult {
        SUCCESS,
        NO_CHANGE,
        DUPLICATE,
        NOT_FOUND
    }

    suspend fun setDisplayName(uid: Int, displayName: String?): DisplayNameUpdateResult {
        val normalized = displayName?.trim()?.takeUnless { it.isEmpty() }

        val result = withContext(Dispatchers.IO) {
            val props = dao.getProps(uid) ?: return@withContext DisplayNameUpdateResult.NOT_FOUND
            val currentName = props.displayName?.trim()

            if (normalized == null && currentName == null) {
                return@withContext DisplayNameUpdateResult.NO_CHANGE
            }
            if (normalized != null && currentName != null && normalized == currentName) {
                return@withContext DisplayNameUpdateResult.NO_CHANGE
            }

            if (normalized != null && dao.hasDisplayNameConflict(uid, normalized)) {
                return@withContext DisplayNameUpdateResult.DUPLICATE
            }

            dao.upsert(
                PatchBundleEntity(
                    uid = uid,
                    name = props.name,
                    displayName = normalized,
                    versionHash = props.versionHash,
                    source = props.source,
                    autoUpdate = props.autoUpdate,
                    searchUpdate = props.searchUpdate,
                    lastNotifiedVersion = props.lastNotifiedVersion,
                    enabled = props.enabled,
                    sortOrder = props.sortOrder,
                    createdAt = props.createdAt,
                    updatedAt = props.updatedAt
                )
            )
            DisplayNameUpdateResult.SUCCESS
        }

        if (result == DisplayNameUpdateResult.SUCCESS || result == DisplayNameUpdateResult.NO_CHANGE) {
            dispatchAction("Sync display name ($uid)") { state ->
                val src = state.sources[uid] ?: return@dispatchAction state
                val updated = src.copy(displayName = normalized)
                state.copy(sources = state.sources.put(uid, updated))
            }
        }


        return result
    }

    suspend fun updateRemoteEndpoint(
        src: RemotePatchBundle,
        newUrl: String,
        onProgress: PatchBundleDownloadProgress? = null,
        discoverySettings: RepositoryBundleSettings? = null,
    ): Boolean = scope.async {
        // The accepted change and its download must survive the details screen closing.
        // Return failures to the caller without cancelling the repository's store scope.
        runCatching { updateRemoteEndpointInternal(src, newUrl, onProgress, discoverySettings) }
    }.await().getOrThrow()

    private suspend fun updateRemoteEndpointInternal(
        src: RemotePatchBundle,
        newUrl: String,
        onProgress: PatchBundleDownloadProgress?,
        discoverySettings: RepositoryBundleSettings?,
    ): Boolean {
        val normalizedUrl = try {
            normalizeRemoteBundleUrl(newUrl)
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) {
                app.toast(e.message ?: "Invalid bundle URL")
            }
            return false
        }

        var endpointChanged = false
        withRemoteUpdateReservation(onQueued = ::notifyQueuedBundleSetting) {
            dispatchAction("Update bundle url (${src.uid})") { state ->
                val currentSource = state.sources[src.uid] as? RemotePatchBundle
                    ?: return@dispatchAction state
                if (normalizedUrl == currentSource.endpoint) {
                    if (discoverySettings != null) {
                        RepositoryBundleSettingsStore.write(directoryOf(src.uid), discoverySettings)
                        // Retry if the previous download failed after saving the endpoint.
                        endpointChanged = true
                    }
                    return@dispatchAction state
                }
                val props = dao.getProps(src.uid) ?: return@dispatchAction state
                val now = System.currentTimeMillis()
                changelogHistoryMutex.withLock {
                    writeChangelogHistoryInternal(src.uid, emptyList())
                    writeChangelogHistoryIdentityInternal(src.uid, null)
                }
                if (discoverySettings != null) {
                    RepositoryBundleSettingsStore.write(directoryOf(src.uid), discoverySettings)
                } else {
                    ExternalBundleMetadataStore.clear(directoryOf(src.uid))
                    RepositoryBundleSettingsStore.clear(directoryOf(src.uid))
                }
                updateDb(src.uid) {
                    it.copy(
                        source = SourceInfo.from(normalizedUrl),
                        versionHash = null,
                        lastNotifiedVersion = null,
                        updatedAt = now
                    )
                }
                val updatedProps = props.copy(
                    source = SourceInfo.from(normalizedUrl),
                    versionHash = null,
                    lastNotifiedVersion = null,
                    updatedAt = now
                )
                val entity = PatchBundleEntity(
                    uid = src.uid,
                    name = updatedProps.name,
                    displayName = updatedProps.displayName,
                    versionHash = updatedProps.versionHash,
                    source = updatedProps.source,
                    autoUpdate = updatedProps.autoUpdate,
                    searchUpdate = updatedProps.searchUpdate,
                    lastNotifiedVersion = updatedProps.lastNotifiedVersion,
                    enabled = updatedProps.enabled,
                    sortOrder = updatedProps.sortOrder,
                    createdAt = updatedProps.createdAt,
                    updatedAt = updatedProps.updatedAt
                )
                val updatedSource = entity.load()
                manualUpdateInfoFlow.update { current -> current - src.uid }
                endpointChanged = true
                State(
                    sources = state.sources.put(src.uid, updatedSource),
                    info = state.info.remove(src.uid)
                )
            }
        }
        if (!endpointChanged) return false

        val updatedSource = store.state.value.sources[src.uid] as? RemotePatchBundle ?: return true
        val allowUnsafeDownload = prefs.allowMeteredUpdates.get()
        val progressNotification =
            downloadProgressNotifier.begin(bundleImportLabel(updatedSource.endpoint, progressLabelFor(updatedSource)))
        val updated = try {
            updateNow(
                force = true,
                showToast = true,
                allowUnsafeNetwork = allowUnsafeDownload,
                onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                    if (bundle.uid == updatedSource.uid) {
                        progressNotification.update(bytesRead, bytesTotal)
                        onProgress?.invoke(bytesRead, bytesTotal)
                    }
                }
            ) { it.uid == updatedSource.uid }
        } catch (error: CancellationException) {
            progressNotification.cancel()
            throw error
        } catch (error: Exception) {
            progressNotification.fail()
            throw error
        }
        if (updated) progressNotification.complete() else progressNotification.fail()
        return true
    }

    suspend fun updateTimestamps(src: PatchBundleSource, createdAt: Long?, updatedAt: Long?) {
        if (createdAt == null && updatedAt == null) return

        dispatchAction("Update timestamps (${src.uid})") { state ->
            val currentSource = state.sources[src.uid] ?: return@dispatchAction state
            updateDb(src.uid) {
                it.copy(
                    createdAt = createdAt ?: it.createdAt,
                    updatedAt = updatedAt ?: it.updatedAt
                )
            }

            state.copy(
                sources = state.sources.put(
                    src.uid,
                    currentSource.copy(
                        createdAt = createdAt ?: currentSource.createdAt,
                        updatedAt = updatedAt ?: currentSource.updatedAt
                    )
                )
            )
        }
    }

    suspend fun createLocal(
        expectedSize: Long? = null,
        sourceNameHint: String? = null,
        createStream: suspend () -> InputStream
    ) {
        var copyTotal: Long? = expectedSize?.takeIf { it > 0L }
        var copyRead = 0L
        var displayName: String? = null
        enqueueLocalImport()
        localImportMutex.withLock {
            val baseProcessed = localImportBaseSteps()
            try {
                setLocalImportProgress(
                    baseProcessed = baseProcessed,
                    offset = 0,
                    displayName = displayName,
                    phase = BundleImportPhase.Downloading,
                    bytesRead = 0L,
                    bytesTotal = null,
                )

                val tempFile = withContext(Dispatchers.IO) {
                    File.createTempFile("local_bundle", ".jar", app.cacheDir)
                }
                try {
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    withContext(Dispatchers.IO) {
                        tempFile.outputStream().use { output ->
                            createStream().use { input ->
                                if (copyTotal == null) {
                                    copyTotal = when (input) {
                                        is FileInputStream -> runCatching { input.channel.size() }.getOrNull()
                                        else -> runCatching { input.available().takeIf { it > 0 }?.toLong() }.getOrNull()
                                    }
                                }
                                setLocalImportProgress(
                                    baseProcessed = baseProcessed,
                                    offset = 0,
                                    displayName = displayName,
                                    phase = BundleImportPhase.Downloading,
                                    bytesRead = 0L,
                                    bytesTotal = copyTotal,
                                )

                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    sha256.update(buffer, 0, read)
                                    copyRead += read
                                    setLocalImportProgress(
                                        baseProcessed = baseProcessed,
                                        offset = 0,
                                        displayName = displayName,
                                        phase = BundleImportPhase.Downloading,
                                        bytesRead = copyRead,
                                        bytesTotal = copyTotal,
                                    )
                                }
                            }
                        }
                    }
                    val precomputedDigest = sha256.digest()
                    if (copyTotal == null && copyRead > 0L) {
                        copyTotal = copyRead
                    }

                    val manifestName = runCatching {
                        PatchBundle(tempFile.absolutePath).manifestAttributes?.name
                    }.getOrNull()?.takeUnless { it.isNullOrBlank() }

                    val uid = stableLocalUid(manifestName, tempFile, precomputedDigest)
                    val existingProps = dao.getProps(uid)
                    displayName = (manifestName ?: existingProps?.name).orEmpty()

                    val replaceTotal = tempFile.length().takeIf { it > 0L } ?: copyTotal
                    setLocalImportProgress(
                        baseProcessed = baseProcessed,
                        offset = 1,
                        displayName = displayName,
                        phase = BundleImportPhase.Processing,
                        bytesRead = 0L,
                        bytesTotal = replaceTotal,
                    )

                    val entity = createEntity(
                        name = manifestName ?: existingProps?.name.orEmpty(),
                        source = SourceInfo.Local,
                        uid = uid,
                        displayName = existingProps?.displayName
                    )
                    writeLocalBundleHint(
                        uid, if (ReseamBundleFormat.isCandidate(tempFile, sourceNameHint)) "bundle.reseam" else sourceNameHint
                    )
                    val localBundle = entity.load() as LocalPatchBundle

                    try {
                        val moved = localBundle.replaceFromTempFile(
                            tempFile,
                            totalBytes = replaceTotal
                        ) { read, total ->
                            setLocalImportProgress(
                                baseProcessed = baseProcessed,
                                offset = 1,
                                displayName = displayName,
                                phase = BundleImportPhase.Processing,
                                bytesRead = read,
                                bytesTotal = total,
                            )
                        }
                        if (!moved) {
                            tempFile.inputStream().use { patches ->
                                localBundle.replace(
                                    patches,
                                    totalBytes = replaceTotal
                                ) { read, total ->
                                    setLocalImportProgress(
                                        baseProcessed = baseProcessed,
                                        offset = 1,
                                        displayName = displayName,
                                        phase = BundleImportPhase.Processing,
                                        bytesRead = read,
                                        bytesTotal = total,
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(tag, "Got exception while importing bundle", e)
                        withContext(Dispatchers.Main) {
                            app.toast(app.getString(R.string.patches_replace_fail, e.simpleMessage()))
                        }

                        withContext(Dispatchers.IO) {
                            runCatching {
                                localBundle.patchesJarFile.setWritable(true, true)
                            }
                            runCatching {
                                localBundle.patchesJarFile.delete()
                            }
                        }
                    }
                } finally {
                    tempFile.delete()
                }
                setLocalImportProgress(
                    baseProcessed = baseProcessed,
                    offset = LOCAL_IMPORT_STEPS - 1,
                    displayName = displayName,
                    phase = BundleImportPhase.Finalizing,
                    bytesRead = 0L,
                    bytesTotal = null,
                )
                withTrackedReload {
                    dispatchAction("Add bundle") { doReload() }
                }
                setLocalImportProgress(
                    baseProcessed = baseProcessed,
                    offset = LOCAL_IMPORT_STEPS,
                    displayName = displayName,
                    phase = BundleImportPhase.Finalizing,
                    bytesRead = 0L,
                    bytesTotal = null,
                )
            } finally {
                completeLocalImport()
            }
        }
    }

    private fun stableLocalUid(manifestName: String?, file: File, precomputedDigest: ByteArray? = null): Int {
        val digest = precomputedDigest?.let { MessageDigest.getInstance("SHA-256").also { d -> d.update(it) } }
            ?: MessageDigest.getInstance("SHA-256").also { d ->
                val hashedFile = runCatching {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            d.update(buffer, 0, read)
                        }
                    }
                }.isSuccess

                if (!hashedFile) {
                    val normalizedName = manifestName?.trim()?.takeUnless(String::isEmpty)
                    if (normalizedName != null) {
                        d.update("local:name".toByteArray(StandardCharsets.UTF_8))
                        d.update(normalizedName.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8))
                    } else {
                        d.update(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }

        val raw = ByteBuffer.wrap(digest.digest(), 0, 4).order(ByteOrder.BIG_ENDIAN).int
        return if (raw != 0) raw else 1
    }

    suspend fun createRemote(
        url: String,
        searchUpdate: Boolean,
        autoUpdate: Boolean,
        usePrereleases: Boolean = false,
        useLatest: Boolean = false,
        createdAt: Long? = null,
        updatedAt: Long? = null,
        showInAppProgress: Boolean = false,
        importLabel: String? = null,
        onProgress: PatchBundleDownloadProgress? = null,
        useSpecificVersion: Boolean = false,
        selectedVersion: ReVancedAsset? = null,
    ) {
        val normalizedUrl = try {
            normalizeRemoteBundleUrl(url)
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) {
                app.toast(e.message ?: "Invalid bundle URL")
            }
            return
        }

        val entity = createEntity(
            "",
            SourceInfo.from(normalizedUrl),
            autoUpdate,
            searchUpdate = searchUpdate,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
        if (usePrereleases || useLatest || useSpecificVersion || selectedVersion != null) {
            RepositoryBundleSettingsStore.write(
                directoryOf(entity.uid),
                RepositoryBundleSettings(
                    usePrereleases = usePrereleases,
                    useLatest = useLatest,
                    useSpecificVersion = useSpecificVersion,
                    selectedVersion = selectedVersion,
                )
            )
        }
        val src = entity.load() as RemotePatchBundle
        dispatchAction("Add bundle ($url)") { state ->
            state.copy(sources = state.sources.put(src.uid, src))
        }
        withTimeoutOrNull(2_000) {
            sources.first { list -> list.any { it.uid == src.uid } }
        }

        val allowUnsafeDownload = prefs.allowMeteredUpdates.get()
        val progressNotification = downloadProgressNotifier.begin(
            importLabel ?: bundleImportLabel(normalizedUrl)
        )
        val updated = try {
            updateNow(
                allowUnsafeNetwork = allowUnsafeDownload,
                showProgress = showInAppProgress,
                onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                    if (bundle.uid == src.uid) {
                        progressNotification.update(bytesRead, bytesTotal)
                        onProgress?.invoke(bytesRead, bytesTotal)
                    }
                },
                predicate = { it.uid == src.uid }
            )
        } catch (error: CancellationException) {
            progressNotification.cancel()
            throw error
        } catch (error: Exception) {
            progressNotification.fail()
            throw error
        }
        if (updated) progressNotification.complete() else progressNotification.fail()
    }

    suspend fun createRemoteFromDiscovery(
        bundle: ExternalBundleSnapshot,
        searchUpdate: Boolean,
        autoUpdate: Boolean,
        preferLatestAcrossChannels: Boolean = false,
        onProgress: PatchBundleDownloadProgress? = null,
    ) {
        if (bundle.isBundleV3) {
            toast(R.string.catalog_unsupported_format)
            return
        }
        val downloadUrl = bundle.downloadUrl?.trim()?.takeIf { it.isNotBlank() }
        val rawUrl = externalBundleEndpoint(bundle, preferLatestAcrossChannels).trim()
        if (rawUrl.isBlank() || downloadUrl == null) {
            toast(R.string.patch_bundle_discovery_error)
            return
        }

        val validatedUrl = try {
            validateDiscoveryBundleUrl(rawUrl)
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) { app.toast(e.message ?: "Invalid bundle URL") }
            return
        }

        val metadata = bundle.toExternalBundleMetadata(
            prerelease = if (preferLatestAcrossChannels) null else bundle.isPrerelease
        )

        val entity = createEntity(
            "",
            SourceInfo.from(validatedUrl),
            autoUpdate,
            searchUpdate = searchUpdate
        )
        ExternalBundleMetadataStore.write(directoryOf(entity.uid), metadata)

        val src = entity.load() as RemotePatchBundle
        dispatchAction("Add bundle (${bundle.bundleId})") { state ->
            state.copy(sources = state.sources.put(src.uid, src))
        }
        withTimeoutOrNull(2_000) {
            sources.first { list -> list.any { it.uid == src.uid } }
        }
        val allowUnsafeDownload = prefs.allowMeteredUpdates.get()
        val progressNotification = downloadProgressNotifier.begin(
            discoveryImportLabel(bundle, preferLatestAcrossChannels)
        )
        val updated = try {
            updateNow(
                allowUnsafeNetwork = allowUnsafeDownload,
                showProgress = false,
                onPerBundleProgress = { bundleSrc, bytesRead, bytesTotal ->
                    if (bundleSrc.uid == src.uid) {
                        progressNotification.update(bytesRead, bytesTotal)
                        onProgress?.invoke(bytesRead, bytesTotal)
                    }
                },
                predicate = { it.uid == src.uid }
            )
        } catch (error: CancellationException) {
            progressNotification.cancel()
            throw error
        } catch (error: Exception) {
            progressNotification.fail()
            throw error
        }
        if (updated) progressNotification.complete() else progressNotification.fail()
    }

    private fun externalBundleEndpoint(
        bundle: ExternalBundleSnapshot,
        preferLatestAcrossChannels: Boolean = false
    ): String {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        val sourceUrl = bundle.sourceUrl.trim().takeIf { it.isNotBlank() }
            ?: if (owner.isNotBlank() && repo.isNotBlank()) "https://github.com/$owner/$repo" else ""
        val host = bundle.apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
            ?: ExternalBundlesEndpoints.REGISTRY_HOST
        ExternalBundlesEndpoints.latestBundleUrl(
            host = host,
            sourceUrl = sourceUrl,
            prerelease = if (preferLatestAcrossChannels) null else bundle.isPrerelease
        )?.let { return it }
        return externalBundleEndpoint(bundle.bundleId, host)
    }

    private fun externalBundleEndpoint(bundleId: Int, apiHost: String): String {
        val host = apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
            ?: ExternalBundlesEndpoints.REGISTRY_HOST
        return "https://$host/bundles/id?id=$bundleId"
    }

    private fun validateRemoteBundleUrl(input: String): String {
        val trimmed = input.trim()
        val parsed = try {
            Url(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid bundle URL: ${e.message ?: trimmed}")
        }

        val pathNoQuery = parsed.encodedPath.substringBefore('?').substringBefore('#')
        val isJson = pathNoQuery.endsWith(".json", ignoreCase = true)
        val host = parsed.host.lowercase(Locale.US)
        val isExternalBundlesHost = ExternalBundlesEndpoints.isExternalBundlesHost(host)
        val isExternalBundlesEndpoint = isExternalBundlesHost &&
            ExternalBundlesEndpoints.isBundleApiPath(pathNoQuery)
        if (!isJson && !isExternalBundlesEndpoint) {
            throw IllegalArgumentException(
                app.getString(R.string.patch_bundle_url_invalid_json_or_external)
            )
        }

        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val scheme = if (parsed.protocol.name.equals("https", ignoreCase = true)) "https" else "http"
        return "$scheme://${parsed.host}${parsed.encodedPath}$query"
    }

    private fun validateDiscoveryBundleUrl(input: String): String {
        val trimmed = input.trim()
        val parsed = try {
            Url(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid bundle URL: ${e.message ?: trimmed}")
        }

        val host = parsed.host.lowercase(Locale.US)
        val isExternalBundlesHost = ExternalBundlesEndpoints.isExternalBundlesHost(host)
        if (!isExternalBundlesHost) {
            throw IllegalArgumentException(
                app.getString(R.string.patch_bundle_url_invalid_external)
            )
        }

        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val scheme = if (parsed.protocol.name.equals("https", ignoreCase = true)) "https" else "http"
        return "$scheme://${parsed.host}${parsed.encodedPath}$query"
    }

    private fun normalizeRemoteBundleUrl(input: String): String {
        val trimmed = input.trim()
        val parsed = try {
            Url(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid bundle URL: ${e.message ?: trimmed}")
        }

        morpheAddSourceRepositoryUrl(parsed)?.let { repositoryUrl ->
            return normalizeRemoteBundleUrl(repositoryUrl)
        }

        repositoryManifestUrl(parsed)?.let { manifestUrl ->
            return manifestUrl
        }
        customGitRepositoryUrl(trimmed)?.let { return it }

        var host = parsed.host
        var pathSegments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }

        // Allow GitHub pull request URLs untouched (handled separately by Source.from / GitHubPullRequestBundle).
        if (host.equals("github.com", ignoreCase = true) &&
            pathSegments.size >= 3 &&
            pathSegments[2] == "pull"
        ) {
            val scheme = if (parsed.protocol.name.equals("https", ignoreCase = true)) "https" else "http"
            val basePath = "/" + pathSegments.joinToString("/")
            val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            return "$scheme://$host$basePath$query"
        }

        if (host.equals("github.com", ignoreCase = true) && pathSegments.size >= 4 && pathSegments[2] == "blob") {
            // https://github.com/{owner}/{repo}/blob/{branch}/path -> raw.githubusercontent.com/{owner}/{repo}/{branch}/path
            val owner = pathSegments[0]
            val repo = pathSegments[1]
            val branchAndPath = pathSegments.drop(3)
            host = "raw.githubusercontent.com"
            pathSegments = listOf(owner, repo) + branchAndPath
        }

        if (host.equals("gitlab.com", ignoreCase = true)) {
            gitLabBlobUrlToRawUrl(pathSegments)?.let { return it }
        }

        val normalizedPath = "/" + pathSegments.joinToString("/")
        val pathNoQuery = normalizedPath.substringBefore('?').substringBefore('#')
        if (ExternalBundlesEndpoints.isExternalBundlesHost(host)) {
            if (ExternalBundlesEndpoints.isBundleApiPath(pathNoQuery)) {
                val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
                return "https://$host$normalizedPath$query"
            }
        }
        val isJson = pathNoQuery.endsWith(".json", ignoreCase = true)
        if (!isJson) {
            throw IllegalArgumentException(
                app.getString(R.string.patch_bundle_url_invalid_json_or_external)
            )
        }

        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "https://$host$normalizedPath$query"
    }

    private fun morpheAddSourceRepositoryUrl(parsed: Url): String? {
        val host = parsed.host.lowercase(Locale.US)
        val path = parsed.encodedPath.substringBefore('?').substringBefore('#')
        if (host != "morphe.software" || path != "/add-source") return null

        parsed.parameters["github"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return repositoryUrlFromMorpheParameter(it, "github.com") }

        parsed.parameters["gitlab"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return repositoryUrlFromMorpheParameter(it, "gitlab.com") }

        return null
    }

    private fun repositoryUrlFromMorpheParameter(value: String, defaultHost: String): String {
        val trimmed = value.trim().removePrefix("@").trim('/')
        if (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return trimmed
        }

        val lower = trimmed.lowercase(Locale.US)
        val repositoryPath = when {
            lower.startsWith("$defaultHost/") -> trimmed.substring(defaultHost.length + 1)
            lower.startsWith("www.$defaultHost/") -> trimmed.substring(defaultHost.length + 5)
            else -> trimmed
        }.trim('/')

        return "https://$defaultHost/$repositoryPath"
    }

    private fun repositoryManifestUrl(parsed: Url): String? {
        val host = parsed.host.lowercase(Locale.US)
        val pathSegments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
        return when (host) {
            "github.com" -> gitHubRepositoryManifestUrl(pathSegments)
            "gitlab.com" -> gitLabRepositoryManifestUrl(pathSegments)
            else -> null
        }
    }

    private fun gitLabBlobUrlToRawUrl(pathSegments: List<String>): String? {
        val markerIndex = pathSegments.indexOf("-")
        if (markerIndex < 0) return null
        val repoSegments = pathSegments.take(markerIndex)
        if (repoSegments.size < 2) return null
        val mode = pathSegments.getOrNull(markerIndex + 1)
        if (mode != "blob") return null
        val branchAndPath = pathSegments.drop(markerIndex + 2)
        if (branchAndPath.size < 2) return null
        val ref = branchAndPath.first()
        val filePath = branchAndPath.drop(1).joinToString("/")
        if (!filePath.endsWith(".json", ignoreCase = true)) return null
        return "https://gitlab.com/${repoSegments.joinToString("/")}/-/raw/$ref/$filePath"
    }


    suspend fun RemotePatchBundle.setAutoUpdate(value: Boolean) {
        dispatchAction("Set auto update ($name, $value)") { state ->
            updateDb(uid) { it.copy(autoUpdate = value) }
            val newSrc = (state.sources[uid] as? RemotePatchBundle)?.copy(autoUpdate = value)
                ?: return@dispatchAction state
            if (!newSrc.isManualUpdateEligible()) {
                manualUpdateInfoFlow.update { map -> map - uid }
            }

            state.copy(sources = state.sources.put(uid, newSrc))
        }

        if (!value) {
            checkManualUpdates(uid)
        }
    }

    suspend fun RemotePatchBundle.setSearchUpdate(value: Boolean) {
        dispatchAction("Set search update ($name, $value)") { state ->
            updateDb(uid) { it.copy(searchUpdate = value) }
            val newSrc = (state.sources[uid] as? RemotePatchBundle)?.copy(searchUpdate = value)
                ?: return@dispatchAction state
            if (!newSrc.isManualUpdateEligible()) {
                manualUpdateInfoFlow.update { map -> map - uid }
            }

            state.copy(sources = state.sources.put(uid, newSrc))
        }
        if (value) checkManualUpdates(uid)
    }

    suspend fun JsonPatchBundle.setRepositoryReleaseChannel(
        channel: RepositoryBundleReleaseChannel,
        restorePrereleases: Boolean = false
    ): JsonPatchBundle {
        var updatedSource = this
        withRemoteUpdateReservation(onQueued = ::notifyQueuedBundleSetting) {
            dispatchAction("Set repository bundle release channel ($name, $channel)") { state ->
                val source = state.sources[uid] as? JsonPatchBundle ?: return@dispatchAction state
                if (!source.supportsPrereleases || source.useSpecificVersion) {
                    updatedSource = source
                    return@dispatchAction state
                }
                val effectiveChannel = if (restorePrereleases && source.usePrereleases) {
                    RepositoryBundleReleaseChannel.PRERELEASE
                } else channel
                if (source.releaseChannel == effectiveChannel) {
                    updatedSource = source
                    return@dispatchAction state
                }
                RepositoryBundleSettingsStore.write(
                    directoryOf(uid),
                    RepositoryBundleSettingsStore.read(directoryOf(uid)).copy(
                        usePrereleases = if (effectiveChannel == RepositoryBundleReleaseChannel.LATEST) source.usePrereleases
                        else effectiveChannel == RepositoryBundleReleaseChannel.PRERELEASE,
                        useLatest = effectiveChannel == RepositoryBundleReleaseChannel.LATEST
                    )
                )
                updatedSource = source.withReleaseChannel(effectiveChannel)
                manualUpdateInfoFlow.update { current -> current - uid }
                state.copy(sources = state.sources.put(uid, updatedSource))
            }
        }
        return updatedSource
    }

    suspend fun JsonPatchBundle.restoreRepositorySettings(
        settings: RepositoryBundleSettings
    ): JsonPatchBundle {
        var restored = this
        withRemoteUpdateReservation {
            dispatchAction("Restore repository bundle settings ($uid)") { state ->
                val source = state.sources[uid] as? JsonPatchBundle ?: return@dispatchAction state
                restored = source
                if (source.repositorySettings == settings) return@dispatchAction state
                RepositoryBundleSettingsStore.write(directoryOf(uid), settings)
                restored = source.withRepositorySettings(settings)
                manualUpdateInfoFlow.update { it - uid }
                state.copy(sources = state.sources.put(uid, restored))
            }
        }
        return restored
    }

    suspend fun JsonPatchBundle.setVersionSelection(
        enabled: Boolean,
        asset: ReVancedAsset? = null,
    ): JsonPatchBundle {
        var updatedSource = this
        withRemoteUpdateReservation(onQueued = ::notifyQueuedBundleSetting) {
            dispatchAction("Set repository bundle version ($uid)") { state ->
                val source = state.sources[uid] as? JsonPatchBundle ?: return@dispatchAction state
                // Preserve channel preferences while the pin is active.
                val settings = source.repositorySettings.withVersionSelection(enabled, asset)
                RepositoryBundleSettingsStore.write(directoryOf(uid), settings)
                updatedSource = source.withRepositorySettings(settings)
                manualUpdateInfoFlow.update { it - uid }
                state.copy(sources = state.sources.put(uid, updatedSource))
            }
        }
        return updatedSource
    }

    private suspend fun notifyQueuedBundleSetting() = withContext(Dispatchers.Main.immediate) {
        app.toast(app.getString(R.string.bundle_setting_change_queued))
    }

    suspend fun JsonPatchBundle.setUsePrereleases(value: Boolean): JsonPatchBundle =
        setRepositoryReleaseChannel(
            if (value) RepositoryBundleReleaseChannel.PRERELEASE
            else RepositoryBundleReleaseChannel.RELEASE
        )

    suspend fun JsonPatchBundle.setUseLatest(value: Boolean): JsonPatchBundle =
        setRepositoryReleaseChannel(
            if (value) RepositoryBundleReleaseChannel.LATEST
            else RepositoryBundleReleaseChannel.RELEASE,
            restorePrereleases = !value
        )

    private suspend fun updateLastNotifiedVersion(
        uid: Int,
        version: String?,
        expectedSource: RemotePatchBundle? = null,
        expectedInstalledSha256: String? = null,
    ) {
        dispatchAction("Set last notified version ($uid)") { state ->
            val src = (state.sources[uid] as? RemotePatchBundle) ?: return@dispatchAction state
            if (expectedSource != null) {
                if (src.endpoint != expectedSource.endpoint) return@dispatchAction state
                if (src.autoUpdate != expectedSource.autoUpdate) return@dispatchAction state
                if (src.searchUpdate != expectedSource.searchUpdate) return@dispatchAction state
                if (src.enabled != expectedSource.enabled) return@dispatchAction state
                if (
                    normalizeVersionForCompare(src.installedVersionSignature) !=
                    normalizeVersionForCompare(expectedSource.installedVersionSignature)
                ) {
                    return@dispatchAction state
                }
                if (
                    src is JsonPatchBundle &&
                    expectedSource is JsonPatchBundle &&
                    src.releaseSelectionKey != expectedSource.releaseSelectionKey
                ) {
                    return@dispatchAction state
                }
            }
            if (
                expectedInstalledSha256 != null &&
                installedBundleSha256(uid) != expectedInstalledSha256
            ) {
                return@dispatchAction state
            }
            updateDb(uid) { it.copy(lastNotifiedVersion = version) }
            val updated = src.copy(lastNotifiedVersion = version)
            state.copy(sources = state.sources.put(uid, updated))
        }
    }

    suspend fun update(
        vararg sources: RemotePatchBundle,
        showToast: Boolean = false,
        allowUnsafeNetwork: Boolean = false,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
    ) {
        val uids = sources.map { it.uid }.toSet()
        store.dispatch(
            Update(
                showToast = showToast,
                allowUnsafeNetwork = allowUnsafeNetwork,
                onPerBundleProgress = onPerBundleProgress,
            ) { it.uid in uids }
        )
    }

    suspend fun updateNow(
        force: Boolean = false,
        allowUnsafeNetwork: Boolean = false,
        showProgress: Boolean = true,
        showToast: Boolean = false,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
        onBundleUpdated: ((bundle: RemotePatchBundle, updatedName: String?, updatedVersion: String) -> Unit)? = null,
        predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
    ): Boolean = coroutineScope {
        while (true) {
            val operation = async(start = CoroutineStart.LAZY) {
                performRemoteUpdate(
                    force = force,
                    showToast = showToast,
                    allowUnsafeNetwork = allowUnsafeNetwork,
                    showProgress = showProgress,
                    onPerBundleProgress = onPerBundleProgress,
                    onBundleUpdated = onBundleUpdated,
                    predicate = predicate
                )
            }
            val activeJob = updateJobMutex.withLock {
                val current = updateJob?.takeUnless { it.isCompleted }
                if (current != null) {
                    current
                } else {
                    updateJob = operation
                    null
                }
            }
            if (activeJob != null) {
                operation.cancel()
                activeJob.join()
                continue
            }

            try {
                return@coroutineScope operation.await()
            } finally {
                withContext(NonCancellable) {
                    // A cancelled download must finish cleanup before the next update starts.
                    operation.join()
                    finishRemoteUpdateJob(operation)
                }
            }
        }
        error("Unreachable")
    }

    private suspend fun <T> withRemoteUpdateReservation(
        onQueued: suspend () -> Unit = {},
        block: suspend () -> T
    ): T = coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) {
            // Store actions keep running after their caller is cancelled.
            withContext(NonCancellable) { block() }
        }
        withRemoteUpdatesIdleLock(onQueued) {
            updateJob = operation
        }
        try {
            // Store actions can acquire updateJobMutex themselves. Reserve the update
            // slot above, then release the mutex before dispatching or awaiting them.
            withContext(NonCancellable) { operation.await() }
        } finally {
            finishRemoteUpdateJob(operation)
        }
    }

    private suspend fun <T> withRemoteUpdatesIdleLock(
        onQueued: suspend () -> Unit = {},
        block: suspend () -> T
    ): T {
        var notified = false
        while (true) {
            updateJobMutex.lock()
            val activeJob: Job?
            val hasPending: Boolean
            try {
                activeJob = updateJob?.takeUnless { it.isCompleted }
                hasPending = withContext(NonCancellable) {
                    updateStateMutex.withLock { pendingUpdateRequests.isNotEmpty() }
                }
            } catch (error: Throwable) {
                updateJobMutex.unlock()
                throw error
            }
            if (activeJob == null && !hasPending) {
                return try {
                    block()
                } finally {
                    updateJobMutex.unlock()
                }
            }
            updateJobMutex.unlock()
            if (!notified) {
                notified = true
                onQueued()
            }
            if (activeJob != null) activeJob.join() else yield()
        }
    }

    suspend fun <T> withManualUpdateSnapshot(
        block: (Map<Int, ManualBundleUpdateInfo>) -> T,
    ): T = withRemoteUpdatesIdleLock {
        block(manualUpdateInfoFlow.value)
    }

    suspend fun redownloadRemoteBundles(): Boolean =
        scope.async { updateNow(force = true) }.await()

    /**
     * Updates all bundles that should be automatically updated.
     */
    suspend fun updateCheck(showToast: Boolean = false) {
        awaitReady()
        store.dispatch(Update(showToast = showToast) { it.updatesAutomatically })
        checkManualUpdates()
    }

    suspend fun fetchUpdatesAndNotify(
        context: Context,
        predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
        onAlreadyNotified: ((
            bundle: RemotePatchBundle,
            bundleVersion: String,
            notificationIdentity: String
        ) -> Unit)? = null,
        onNotification: (
            bundle: RemotePatchBundle,
            bundleVersion: String,
            notificationIdentity: String
        ) -> Boolean
    ): Boolean = coroutineScope {
        val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
        if (!allowMeteredUpdates && !networkInfo.isSafe()) {
            Log.d(tag, "Skipping background update check because the network is down or metered.")
            return@coroutineScope false
        }

        migrateLegacyDiscoveryEndpoints()
        var notifiedAny = false
        sources.first()
            .filterIsInstance<RemotePatchBundle>()
            .forEach { bundle ->
                if (!predicate(bundle)) return@forEach
                if (!bundle.searchUpdate || !bundle.enabled) return@forEach
                if (bundle.state !is PatchBundleSource.State.Available) return@forEach

                val info = try {
                    bundle.fetchAvailableUpdateInfo()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "Failed to check update for ${bundle.name}", error)
                    return@forEach
                }

                val decision = commitFetchedManualUpdate(
                    fetchedFrom = bundle,
                    info = info,
                    predicate = predicate,
                ) ?: return@forEach
                if (decision.alreadyNotified) {
                    onAlreadyNotified?.invoke(
                        decision.bundle,
                        decision.bundleVersion,
                        decision.notificationIdentity,
                    )
                    return@forEach
                }

                val notified = onNotification(
                    decision.bundle,
                    decision.bundleVersion,
                    decision.notificationIdentity,
                )
                if (notified) {
                    updateLastNotifiedVersion(
                        decision.bundle.uid,
                        decision.notificationIdentity,
                        expectedSource = decision.bundle,
                        expectedInstalledSha256 = decision.installedSha256,
                    )
                    notifiedAny = true
                }
            }
        notifiedAny
    }

    private data class ManualNotificationDecision(
        val bundle: RemotePatchBundle,
        val bundleVersion: String,
        val notificationIdentity: String,
        val alreadyNotified: Boolean,
        val installedSha256: String?,
    )

    private suspend fun commitFetchedManualUpdate(
        fetchedFrom: RemotePatchBundle,
        info: ReVancedAsset,
        predicate: (bundle: RemotePatchBundle) -> Boolean,
    ): ManualNotificationDecision? = withRemoteUpdatesIdleLock {
        val bundle = store.state.value.sources[fetchedFrom.uid] as? RemotePatchBundle
            ?: return@withRemoteUpdatesIdleLock null
        if (bundle.endpoint != fetchedFrom.endpoint) return@withRemoteUpdatesIdleLock null
        if (
            bundle is JsonPatchBundle &&
            fetchedFrom is JsonPatchBundle &&
            bundle.releaseSelectionKey != fetchedFrom.releaseSelectionKey
        ) {
            return@withRemoteUpdatesIdleLock null
        }
        if (!predicate(bundle)) return@withRemoteUpdatesIdleLock null
        if (!bundle.searchUpdate || !bundle.enabled) return@withRemoteUpdatesIdleLock null
        if (bundle.state !is PatchBundleSource.State.Available) return@withRemoteUpdatesIdleLock null

        val latestSignature = normalizeVersionForCompare(info.version)
        if (latestSignature == null) {
            if (!bundle.updatesAutomatically) {
                manualUpdateInfoFlow.update { current -> current - bundle.uid }
            }
            return@withRemoteUpdatesIdleLock null
        }
        val artifactChanged = (bundle as? ExternalGraphqlPatchBundle)
            ?.artifactDiffers(info, installedBundleSha256(bundle.uid))
            ?: false
        if (!artifactChanged && !bundle.isUpdateAvailable(info, forNotification = true)) {
            if (!bundle.updatesAutomatically) {
                manualUpdateInfoFlow.update { current -> current - bundle.uid }
            }
            return@withRemoteUpdatesIdleLock null
        }

        val versionLabel = latestSignature
        val artifactIdentity = (bundle as? ExternalGraphqlPatchBundle)
            ?.artifactNotificationIdentity(info)
        val notificationIdentity = artifactIdentity?.let {
            "$versionLabel|$it"
        } ?: versionLabel
        if (!bundle.updatesAutomatically) {
            manualUpdateInfoFlow.update { current ->
                current + (
                    bundle.uid to ManualBundleUpdateInfo(
                        latestVersion = info.version,
                        pageUrl = info.pageUrl,
                        notificationIdentity = notificationIdentity,
                    )
                    )
            }
        }
        val installedSha256 = (bundle as? ExternalGraphqlPatchBundle)
            ?.let { installedBundleSha256(bundle.uid) }
        val persistedNotificationIdentity = bundle.lastNotifiedVersion
            ?.trim()
            .orEmpty()
        val alreadyNotified = when {
            artifactIdentity == null ->
                normalizeVersionForCompare(
                    persistedNotificationIdentity.substringBefore('|')
                ) == versionLabel

            artifactIdentity.startsWith("sha256:", ignoreCase = true) ->
                persistedNotificationIdentity.equals(
                    notificationIdentity,
                    ignoreCase = true
                )

            else -> {
                val persistedVersion = normalizeVersionForCompare(
                    persistedNotificationIdentity.substringBefore('|')
                )
                val persistedArtifactIdentity = persistedNotificationIdentity
                    .substringAfter('|', missingDelimiterValue = "")
                persistedVersion == versionLabel &&
                    persistedArtifactIdentity == artifactIdentity
            }
        }
        if (alreadyNotified) {
            val effectiveNotificationIdentity = persistedNotificationIdentity
                .takeIf { it.isNotBlank() }
                ?: notificationIdentity
            return@withRemoteUpdatesIdleLock ManualNotificationDecision(
                bundle = bundle,
                bundleVersion = info.version,
                notificationIdentity = effectiveNotificationIdentity,
                alreadyNotified = true,
                installedSha256 = installedSha256,
            )
        }

        ManualNotificationDecision(
            bundle = bundle,
            bundleVersion = info.version,
            notificationIdentity = notificationIdentity,
            alreadyNotified = false,
            installedSha256 = installedSha256,
        )
    }

    /**
     * Retarget only recognized legacy discovery API entries. Keep the installed
     * bundle, selections, ordering and update preferences; catalog IDs are not portable.
     */
    private suspend fun migrateLegacyDiscoveryEndpoints(allowUnsafeNetwork: Boolean = false) = discoveryMigrationMutex.withLock {
        val candidates = store.state.value.sources.values.filterIsInstance<RemotePatchBundle>()
        for (bundle in candidates) {
            val legacy = legacyDiscoveryEndpoint(bundle.endpoint)
            try {
                val directory = directoryOf(bundle.uid)
                val metadata = ExternalBundleMetadataStore.read(directory)
                val registrySource = registryBundleIconSource(
                    bundle.endpoint, metadata?.sourceUrl, metadata?.ownerName, metadata?.repoName, metadata?.isPrerelease,
                    metadata?.downloadUrl
                )
                if (legacy == null && (registrySource == null ||
                    (bundle is ExternalGraphqlPatchBundle &&
                        metadata?.apiHost == ExternalBundlesEndpoints.REGISTRY_HOST &&
                        (!metadata.iconUrl.isNullOrBlank() || !metadata.ownerAvatarUrl.isNullOrBlank())))) continue
                val canLookup = bundleUpdateNetworkBlock(networkInfo.isConnected(),
                    networkInfo.isUnmetered(), prefs.allowMeteredUpdates.get(), allowUnsafeNetwork) == null
                var migration = if (legacy != null) discoveryRegistryMigration(
                    legacy, metadata?.sourceUrl, metadata?.ownerName, metadata?.repoName, metadata?.isPrerelease,
                    metadata?.downloadUrl
                ) else registrySource?.let {
                    DiscoveryRegistryMigration(bundle.endpoint, it.sourceUrl, it.prerelease)
                }
                if (migration == null && legacy?.bundleId != null && canLookup) {
                    val snapshot = externalBundlesApi.getBundleById(legacy.bundleId, legacy.host).getOrNull()
                    migration = snapshot?.let {
                        discoveryRegistryMigration(
                            legacy, it.sourceUrl, it.ownerName, it.repoName,
                            if (metadata != null) metadata.isPrerelease else it.isPrerelease,
                            it.downloadUrl
                        )
                    }
                }
                val resolved = migration ?: continue
                val requestedVersion = legacy?.version
                    ?: runCatching { Url(resolved.endpoint).parameters["version"] }.getOrNull() ?: "latest"
                var snapshot = if (canLookup) externalBundlesApi.getBundleForSource(
                    resolved.sourceUrl, resolved.prerelease, requestedVersion
                ).getOrNull()?.takeIf { !it.isBundleV3 && !it.downloadUrl.isNullOrBlank() } else null
                val fallbackSnapshot = if (canLookup && (snapshot == null ||
                    (snapshot.iconUrl.isNullOrBlank() && snapshot.ownerAvatarUrl.isNullOrBlank()))) {
                    externalBundlesApi.getLatestBundleForSource(resolved.sourceUrl, null).getOrNull()
                } else null
                if (snapshot == null && fallbackSnapshot != null &&
                    requestedVersion.equals("latest", ignoreCase = true) &&
                    !fallbackSnapshot.isBundleV3 && !fallbackSnapshot.downloadUrl.isNullOrBlank()) {
                    snapshot = fallbackSnapshot
                }
                // Repair entries converted by earlier versions too. On failure, preserve the
                // installed bundle and retry hydration at the next check.
                if (legacy == null && snapshot == null && fallbackSnapshot == null) continue
                val hydrated = snapshot?.toExternalBundleMetadata(resolved.prerelease)
                dispatchAction("Migrate discovery source metadata (${bundle.uid})") { state ->
                    val current = state.sources[bundle.uid] as? RemotePatchBundle
                        ?: return@dispatchAction state
                    if (current.endpoint != bundle.endpoint) return@dispatchAction state
                    val entity = dao.all().firstOrNull { it.uid == bundle.uid } ?: return@dispatchAction state
                    val latest = ExternalBundleMetadataStore.read(directory)
                    if (hydrated == null && latest == null && fallbackSnapshot == null) return@dispatchAction state
                    val updatedMetadata = hydrated?.copy(
                        iconUrl = hydrated.iconUrl ?: latest?.iconUrl ?: fallbackSnapshot?.iconUrl,
                        ownerAvatarUrl = hydrated.ownerAvatarUrl ?: latest?.ownerAvatarUrl ?: fallbackSnapshot?.ownerAvatarUrl
                    ) ?: latest?.copy(
                        bundleId = 0,
                        sourceUrl = resolved.sourceUrl,
                        apiHost = ExternalBundlesEndpoints.REGISTRY_HOST,
                        isPrerelease = resolved.prerelease,
                        iconUrl = latest.iconUrl ?: fallbackSnapshot?.iconUrl,
                        ownerAvatarUrl = latest.ownerAvatarUrl ?: fallbackSnapshot?.ownerAvatarUrl
                    ) ?: fallbackSnapshot?.takeIf { !it.downloadUrl.isNullOrBlank() || !latest?.downloadUrl.isNullOrBlank() }?.toExternalBundleMetadata(
                        resolved.prerelease,
                        fallbackDownloadUrl = latest?.downloadUrl
                    )
                    updatedMetadata?.let { ExternalBundleMetadataStore.write(directory, it) }
                    val updated = entity.copy(source = SourceInfo.from(resolved.endpoint))
                    dao.upsert(updated)
                    manualUpdateInfoFlow.update { it - bundle.uid }
                    state.copy(sources = state.sources.put(bundle.uid, updated.load()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(tag, "Could not hydrate discovery source ${bundle.uid}; will retry next check", error)
            }
        }
    }

    suspend fun checkManualUpdates(vararg bundleUids: Int) = coroutineScope {
        migrateLegacyDiscoveryEndpoints()
        val targetUids = bundleUids.toSet().takeIf { it.isNotEmpty() }
        val snapshot = store.state.value
        val manualBundles = snapshot.sources.values
            .filterIsInstance<RemotePatchBundle>()
            .filter { bundle ->
                bundle.isManualUpdateEligible() &&
                    (targetUids == null || bundle.uid in targetUids)
            }
        val eligibleUids = manualBundles.mapTo(mutableSetOf(), RemotePatchBundle::uid)

        manualUpdateInfoFlow.update { map ->
            if (targetUids != null) {
                map - (targetUids - eligibleUids)
            } else {
                map.filterKeys { it in eligibleUids }
            }
        }

        if (manualBundles.isEmpty()) {
            return@coroutineScope
        }

        val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
        if (bundleUpdateNetworkBlock(networkInfo.isConnected(),
                networkInfo.isUnmetered(), allowMeteredUpdates) != null) {
            Log.d(tag, "Skipping manual update check because the network is down or metered.")
            return@coroutineScope
        }

        val fetched = manualBundles.map { bundle ->
            async {
                val info = try {
                    bundle.fetchAvailableUpdateInfo()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(tag, "Failed to check manual update for ${bundle.name}", error)
                    null
                }
                bundle to info
            }
        }.awaitAll()

        withRemoteUpdatesIdleLock {
            val current = store.state.value
            val currentManualUids = current.sources.values
                .filterIsInstance<RemotePatchBundle>()
                .filter { bundle ->
                    bundle.isManualUpdateEligible() &&
                        (targetUids == null || bundle.uid in targetUids)
                }
                .mapTo(mutableSetOf(), RemotePatchBundle::uid)

            manualUpdateInfoFlow.update { map ->
                val next = map.toMutableMap()
                if (targetUids == null) {
                    next.keys.retainAll(currentManualUids)
                } else {
                    (targetUids - currentManualUids).forEach(next::remove)
                }

                fetched.forEach { (fetchedFrom, info) ->
                    val bundle = current.sources[fetchedFrom.uid] as? RemotePatchBundle
                        ?: return@forEach
                    if (bundle.uid !in currentManualUids) return@forEach
                    if (bundle.endpoint != fetchedFrom.endpoint) return@forEach
                    if (
                        bundle is JsonPatchBundle &&
                        fetchedFrom is JsonPatchBundle &&
                        bundle.releaseSelectionKey != fetchedFrom.releaseSelectionKey
                    ) {
                        return@forEach
                    }

                    if (info == null) {
                        return@forEach
                    }
                    val latestSignature = normalizeVersionForCompare(info.version)
                    if (latestSignature == null) {
                        next.remove(bundle.uid)
                        return@forEach
                    }
                    val artifactChanged = (bundle as? ExternalGraphqlPatchBundle)
                        ?.artifactDiffers(info, installedBundleSha256(bundle.uid))
                        ?: false
                    if (!artifactChanged && !bundle.isUpdateAvailable(info, forNotification = true)) {
                        next.remove(bundle.uid)
                    } else {
                        val artifactIdentity = (bundle as? ExternalGraphqlPatchBundle)
                            ?.artifactNotificationIdentity(info)
                        val notificationIdentity = artifactIdentity?.let {
                            "$latestSignature|$it"
                        } ?: latestSignature
                        next[bundle.uid] = ManualBundleUpdateInfo(
                            latestVersion = info.version,
                            pageUrl = info.pageUrl,
                            notificationIdentity = notificationIdentity,
                        )
                    }
                }
                next
            }
        }
    }

    suspend fun reorderBundles(prioritizedUids: List<Int>) = withTrackedReload {
        dispatchAction("Reorder bundles") { state ->
            val currentOrder = state.sources.keys.toList()
            if (currentOrder.isEmpty()) return@dispatchAction state

            val sanitized = LinkedHashSet(prioritizedUids.filter { it in currentOrder })
            if (sanitized.isEmpty()) return@dispatchAction state

            val finalOrder = buildList {
                addAll(sanitized)
                currentOrder.filterNotTo(this) { it in sanitized }
            }

            if (finalOrder == currentOrder) {
                return@dispatchAction state
            }

            finalOrder.forEachIndexed { index, uid ->
                dao.updateSortOrder(uid, index)
            }

            doReload()
        }
    }

    private inner class Update(
        private val force: Boolean = false,
        private val showToast: Boolean = false,
        private val allowUnsafeNetwork: Boolean = false,
        private val showProgress: Boolean = true,
        private val onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
        private val predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
    ) : Action<State> {
        override fun toString() = if (force) "Redownload remote bundles" else "Update check"

        override suspend fun ActionContext.execute(
            current: State
        ): State {
            startRemoteUpdateJob(
                force = force,
                showToast = showToast,
                allowUnsafeNetwork = allowUnsafeNetwork,
                showProgress = showProgress,
                onPerBundleProgress = onPerBundleProgress,
                predicate = predicate
            )
            return current
        }

        override suspend fun catch(exception: Exception) {
            Log.e(tag, "Failed to update patches", exception)
            toast(R.string.patches_download_fail, exception.simpleMessage())
        }
    }

    private suspend fun startRemoteUpdateJob(
        force: Boolean,
        showToast: Boolean,
        allowUnsafeNetwork: Boolean,
        showProgress: Boolean,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)?,
        predicate: (bundle: RemotePatchBundle) -> Boolean,
    ) {
        val request = UpdateRequest(
            force,
            showToast,
            allowUnsafeNetwork,
            showProgress,
            onPerBundleProgress,
            predicate
        )
        updateJobMutex.withLock {
            if (updateJob?.isCompleted == false) {
                // Queue while holding the same mutex the active job uses before it drains
                // pending work. Otherwise the active job can finish between observing it
                // above and enqueueing here, leaving this request stranded with no runner.
                enqueueUpdateRequest(request)
            } else {
                updateJob = launchRemoteUpdateJob(request)
            }
        }
    }

    private suspend fun performRemoteUpdate(
        force: Boolean,
        showToast: Boolean,
        allowUnsafeNetwork: Boolean,
        showProgress: Boolean,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)?,
        onBundleUpdated: ((bundle: RemotePatchBundle, updatedName: String?, updatedVersion: String) -> Unit)?,
        predicate: (bundle: RemotePatchBundle) -> Boolean,
    ): Boolean = coroutineScope {
        val progressStartedOrder = if (showProgress) bundleUpdateProgressOrder() else 0L
        var updateFailed = false
        try {
            if (showProgress) {
                cancelBundleUpdateAutoClear()
            }
            val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
            val networkBlock = bundleUpdateNetworkBlock(networkInfo.isConnected(),
                networkInfo.isUnmetered(), allowMeteredUpdates, allowUnsafeNetwork)
            if (networkBlock != null) {
                Log.d(tag, "Skipping update check because the network is down or metered.")
                updateFailed = true
                if (showToast) toast(R.string.patches_download_fail, app.getString(
                    if (networkBlock == BundleUpdateNetworkBlock.METERED) R.string.bundle_update_metered else R.string.bundle_update_offline))
                if (showProgress) {
                    cancelBundleUpdateAutoClear()
                    bundleUpdateProgressFlow.value = null
                }
                return@coroutineScope false
            }

            migrateLegacyDiscoveryEndpoints(allowUnsafeNetwork)
            val targets = store.state.value.sources.values
                .filterIsInstance<RemotePatchBundle>()
                .filter { predicate(it) }

            if (targets.isEmpty()) {
                if (showToast) toast(R.string.patches_update_unavailable)
                if (showProgress) {
                    cancelBundleUpdateAutoClear()
                    bundleUpdateProgressFlow.value = null
                }
                return@coroutineScope false
            }

            markActiveUpdateUids(targets.map(RemotePatchBundle::uid).toSet())

            if (showProgress) {
                bundleUpdateProgressFlow.value = BundleUpdateProgress(
                    total = currentUpdateTotal(targets.size),
                    completed = 0,
                    phase = BundleUpdatePhase.Checking,
                    startedOrder = progressStartedOrder,
                )
            }

            var hadBundleFailures = false
            var lastUpdateError: Throwable? = null
            val updated: Map<RemotePatchBundle, PatchBundleDownloadResult> = try {
                val results = LinkedHashMap<RemotePatchBundle, PatchBundleDownloadResult>()
                var completed = 0

                for (bundle in targets) {
                    val total = currentUpdateTotal(targets.size)
                    if (total <= 0) {
                        if (showProgress) {
                            bundleUpdateProgressFlow.value = null
                        }
                        return@coroutineScope false
                    }
                    if (isRemoteUpdateCancelled(bundle.uid)) {
                        completed = (completed + 1).coerceAtMost(total)
                        if (showProgress) {
                            bundleUpdateProgressFlow.update { progress ->
                                progress?.copy(
                                    completed = completed,
                                    currentBundleName = progressLabelFor(bundle),
                                    phase = BundleUpdatePhase.Finalizing,
                                    bytesRead = 0L,
                                    bytesTotal = null,
                                )
                            }
                        }
                        continue
                    }

                    Log.d(tag, "Updating patch bundle: ${bundle.name}")

                    if (showProgress) {
                        bundleUpdateProgressFlow.value = BundleUpdateProgress(
                            total = total,
                            completed = completed.coerceAtMost(total),
                            currentBundleName = progressLabelFor(bundle),
                            phase = BundleUpdatePhase.Checking,
                            bytesRead = 0L,
                            bytesTotal = null,
                            startedOrder = progressStartedOrder,
                        )
                    }
                    onPerBundleProgress?.invoke(bundle, 0L, null)
                    var progressNotification: DownloadProgressNotifier.Session? = null

                    val onProgress: PatchBundleDownloadProgress = { bytesRead, bytesTotal ->
                        if (isRemoteUpdateCancelled(bundle.uid)) {
                            throw BundleUpdateCancelled(bundle.uid)
                        }
                        if (showProgress) {
                            bundleUpdateProgressFlow.update { progress ->
                                progress?.copy(
                                    currentBundleName = progressLabelFor(bundle),
                                    phase = BundleUpdatePhase.Downloading,
                                    bytesRead = bytesRead,
                                    bytesTotal = bytesTotal,
                                )
                            }
                        }
                        if (showProgress && onPerBundleProgress == null) {
                            val notification = progressNotification
                                ?: downloadProgressNotifier.begin(bundleImportLabel(bundle.endpoint, progressLabelFor(bundle))).also {
                                    progressNotification = it
                                }
                            notification.update(bytesRead, bytesTotal)
                        }
                        onPerBundleProgress?.invoke(bundle, bytesRead, bytesTotal)
                    }

                    var bundleFailed = false
                    val result = try {
                        withTimeout(REMOTE_BUNDLE_UPDATE_TIMEOUT_MS) {
                            if (force) {
                                bundle.downloadLatest(onProgress)
                            } else {
                                val external = bundle as? ExternalGraphqlPatchBundle
                                if (external == null) {
                                    bundle.update(onProgress)
                                } else {
                                    val latest = external.fetchLatestReleaseInfo()
                                    if (
                                        external.artifactDiffers(
                                            latest,
                                            installedBundleSha256(external.uid)
                                        )
                                    ) {
                                        external.downloadLatest(onProgress)
                                    } else {
                                        external.update(onProgress)
                                    }
                                }
                            }
                        }
                    } catch (e: BundleUpdateCancelled) {
                        null
                    } catch (e: TimeoutCancellationException) {
                        bundleFailed = true
                        hadBundleFailures = true
                        updateFailed = true
                        if (lastUpdateError == null) lastUpdateError = e
                        Log.e(tag, "Timed out while updating patch bundle: ${bundle.name}", e)
                        null
                    } catch (e: CancellationException) {
                        progressNotification?.cancel()
                        throw e
                    } catch (e: Exception) {
                        bundleFailed = true
                        hadBundleFailures = true
                        updateFailed = true
                        if (lastUpdateError == null) lastUpdateError = e
                        Log.e(tag, "Failed to update patch bundle: ${bundle.name}", e)
                        null
                    }

                    when {
                        result != null -> progressNotification?.complete()
                        bundleFailed -> progressNotification?.fail()
                        else -> progressNotification?.cancel()
                    }

                    val downloadedName = if (result != null) {
                        runCatching {
                            PatchBundle(bundle.patchesJarFile.absolutePath).manifestAttributes?.name
                        }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                    } else {
                        null
                    }
                    val displayLabel = progressLabelFor(bundle)
                    if (downloadedName != null && showProgress) {
                        bundleUpdateProgressFlow.update { progress ->
                            progress?.copy(currentBundleName = displayLabel)
                        }
                    }

                    val nextTotal = currentUpdateTotal(targets.size)
                    completed = (completed + 1).coerceAtMost(nextTotal)
                    if (showProgress) {
                        bundleUpdateProgressFlow.update { progress ->
                            progress?.copy(
                                completed = completed,
                                currentBundleName = displayLabel,
                                phase = BundleUpdatePhase.Finalizing,
                                bytesRead = 0L,
                                bytesTotal = null,
                            )
                        }
                    }

                    if (result != null) {
                        results[bundle] = result
                        result.changelogAsset?.let { asset ->
                            runCatching { recordChangelog(bundle, asset) }
                        } ?: runCatching { recordChangelog(bundle, bundle.fetchLatestReleaseInfo()) }
                        onBundleUpdated?.invoke(
                            bundle,
                            downloadedName ?: displayLabel,
                            result.versionSignature
                        )
                    }
                }

                results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to update patches", e)
                updateFailed = true
                hadBundleFailures = true
                if (lastUpdateError == null) lastUpdateError = e
                emptyMap()
            }

            if (updated.isEmpty()) {
                if (showToast) {
                    if (hadBundleFailures) {
                        toast(R.string.patches_download_fail, lastUpdateError?.simpleMessage()
                            ?: app.getString(R.string.patch_bundle_discovery_error))
                    } else {
                        toast(R.string.patches_update_unavailable)
                    }
                }
                return@coroutineScope false
            }

            withContext(NonCancellable) {
                // Store commits outlive their caller; keep the update slot until they finish.
                withTrackedReload {
                    dispatchAction("Apply updated bundles") {
                        updated.forEach { (src, downloadResult) ->
                            if (dao.getProps(src.uid) == null) return@forEach
                            val rawName = runCatching {
                                PatchBundle(src.patchesJarFile.absolutePath).manifestAttributes?.name
                            }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: src.name
                            val name = ensureUniqueName(rawName, src.uid)
                            val now = System.currentTimeMillis()

                            updateDb(src.uid) {
                                it.copy(
                                    versionHash = downloadResult.versionSignature,
                                    lastNotifiedVersion = null,
                                    name = name,
                                    createdAt = downloadResult.assetCreatedAtMillis ?: it.createdAt,
                                    updatedAt = now
                                )
                            }
                        }

                        doReload()
                    }
                }

                val updatedUids = updated.keys.map(RemotePatchBundle::uid).toSet()
                manualUpdateInfoFlow.update { currentMap -> currentMap - updatedUids }
                runCatching {
                    removeUpdatedBundleNotifications(app, updatedUids)
                }.onFailure { error -> Log.w(tag, "Failed to refresh bundle update notification", error) }
                if (showToast) {
                    if (hadBundleFailures) toast(R.string.patches_download_fail,
                        lastUpdateError?.simpleMessage() ?: app.getString(R.string.patch_bundle_discovery_error))
                    else toast(R.string.patches_update_success)
                }
            }
            true
        } catch (cancel: CancellationException) {
            updateFailed = true
            throw cancel
        } catch (error: Exception) {
            updateFailed = true
            Log.e(tag, "Failed to update patch bundles", error)
            if (showToast) toast(R.string.patches_download_fail, error.simpleMessage())
            return@coroutineScope false
        } finally {
            withContext(NonCancellable) {
                if (showProgress) {
                    val progress = bundleUpdateProgressFlow.value
                    if (updateFailed || progress == null || progress.completed < progress.total) {
                        cancelBundleUpdateAutoClear()
                        bundleUpdateProgressFlow.value = null
                    } else {
                        scheduleBundleUpdateProgressClear()
                    }
                }
                clearActiveUpdateState()
            }
        }
    }

    private class BundleUpdateCancelled(val uid: Int) : Exception()

    private fun suggestedVersionsForRevanced(patches: Set<RevancedPatch>): Map<String, String?> {
        val versionCounts = patches.revancedMostCommonCompatibleVersions(countUnusedPatches = true)

        return versionCounts.mapValues { (_, versions) ->
            if (versions.keys.size < 2) {
                return@mapValues versions.keys.firstOrNull()
            }

            var currentHighestPatchCount = -1
            versions.entries.last { (_, patchCount) ->
                if (patchCount >= currentHighestPatchCount) {
                    currentHighestPatchCount = patchCount
                    true
                } else false
            }.key
        }
    }

    private fun suggestedVersionsForMorphe(patches: Iterable<PatchInfo>): Map<String, String?> {
        val versionCounts = mutableMapOf<String, MutableMap<String, MorpheVersionStats>>()

        patches.forEach { patch ->
            patch.compatiblePackages?.forEach { pkg ->
                val versions = pkg.versions ?: return@forEach
                val counts = versionCounts.getOrPut(pkg.packageName) { linkedMapOf() }
                versions.sorted().forEach { version ->
                    val stats = counts.getOrPut(version) { MorpheVersionStats() }
                    if (pkg.experimentalVersions?.contains(version) == true) {
                        stats.experimentalPatchCount += 1
                    } else {
                        stats.stablePatchCount += 1
                    }
                }
            }
        }

        return versionCounts.mapValues { (_, versions) ->
            if (versions.isEmpty()) return@mapValues null

            val stableVersions = versions.mapValues { (_, stats) -> stats.stablePatchCount }
                .filterValues { it > 0 }
            val selectedVersions = stableVersions.ifEmpty {
                versions.mapValues { (_, stats) -> stats.totalPatchCount }
            }
            selectSuggestedPatchVersion(selectedVersions)
        }
    }

    private data class MorpheVersionStats(
        var stablePatchCount: Int = 0,
        var experimentalPatchCount: Int = 0
    ) {
        val totalPatchCount: Int
            get() = stablePatchCount + experimentalPatchCount
    }

    data class BundleVersionMatch(
        val bundleUid: Int,
        val versionOverride: String?,
        val targetsAllVersions: Boolean,
        val usesUniversalFallback: Boolean = false
    )

    data class VersionSelectionAssessment(
        val isAllowed: Boolean,
        val suggestedVersion: String?,
        val suggestedVersionCodes: Set<Long> = emptySet(),
        val canContinueWithUniversalFallback: Boolean = false,
        val requiresUniversalPatchesEnabled: Boolean = false
    )

    private data class BundleVersionCandidate(
        val bundleUid: Int,
        val score: Int,
        val versionOverride: String?,
        val targetsAllVersions: Boolean,
        val usesUniversalFallback: Boolean
    )

    private enum class RevancedPatcherVersion(
        val versionName: String,
        val storageValue: String
    ) {
        V21(versionName = "21.0.0", storageValue = "21"),
        V22(versionName = "22.0.0", storageValue = "22");

        companion object {
            fun fromStorage(value: String): RevancedPatcherVersion? = entries.firstOrNull {
                it.storageValue == value
            }
        }
    }

    data class State(
        val sources: PersistentMap<Int, PatchBundleSource> = persistentMapOf(),
        val info: PersistentMap<Int, PatchBundleInfo.Global> = persistentMapOf(),
        val metadataLoaded: Boolean = true
    )

    data class BundleUpdateProgress(
        val total: Int,
        val completed: Int,
        val currentBundleName: String? = null,
        val phase: BundleUpdatePhase = BundleUpdatePhase.Checking,
        val bytesRead: Long = 0L,
        val bytesTotal: Long? = null,
        val startedOrder: Long = 0L,
    )

    enum class BundleUpdatePhase {
        Checking,
        Downloading,
        Finalizing,
    }

    data class ImportProgress(
        val processed: Int,
        val total: Int,
        val currentBundleName: String? = null,
        val phase: BundleImportPhase = BundleImportPhase.Processing,
        val bytesRead: Long = 0L,
        val bytesTotal: Long? = null,
        val isStepBased: Boolean = false,
        val bundleCount: Int = total,
        val startedOrder: Long = 0L,
    ) {
        val ratio: Float?
            get() {
                val safeTotal = total.coerceAtLeast(1)
                val clampedProcessed = processed.coerceIn(0, safeTotal)
                if (clampedProcessed >= safeTotal) return 1f

                val totalBytes = bytesTotal?.takeIf { it > 0L }
                    ?: return (clampedProcessed.toFloat() / safeTotal).coerceIn(0f, 1f)

                val perStepFraction = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                return ((clampedProcessed + perStepFraction) / safeTotal).coerceIn(0f, 1f)
            }
    }

    enum class BundleImportPhase {
        Processing,
        Downloading,
        Finalizing,
    }

    data class ManualBundleUpdateInfo(
        val latestVersion: String?,
        val pageUrl: String?,
        val notificationIdentity: String,
    )

    private fun normalizeVersionForCompare(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val noPrefix = trimmed.removePrefix("v").removePrefix("V")
        val noBuild = noPrefix.substringBefore('+')
        return noBuild.ifBlank { null }
    }

    private companion object {
        const val LOCAL_IMPORT_STEPS = 2
        const val LOCAL_BUNDLE_HINT_FILE = "bundle_hint.txt"
        const val REVANCED_PATCHER_HINT_FILE = "revanced_patcher_hint.txt"
        val supportedBundleExtensions = setOf("rvp", "mpp", "reseam")
        const val REMOTE_BUNDLE_UPDATE_TIMEOUT_MS = 120_000L

    }

    private data class DiscoveryImportRequest(
        val key: String,
        val bundle: ExternalBundleSnapshot,
        val searchUpdate: Boolean,
        val autoUpdate: Boolean,
        val preferLatestAcrossChannels: Boolean
    )

    data class DiscoveryImportProgress(
        val bytesRead: Long,
        val bytesTotal: Long?,
        val status: DiscoveryImportStatus
    )

    enum class DiscoveryImportStatus {
        Queued,
        Importing
    }

    enum class DiscoveryImportEnqueueResult {
        Started,
        Queued,
        Duplicate
    }

    private fun updateDiscoveryQueuedLocked() {
        discoveryImportQueuedFlow.value = discoveryImportQueuedKeys.toSet()
    }

    private suspend fun runDiscoveryImportQueue() {
        var completed = 0
        while (true) {
            val request = discoveryImportMutex.withLock {
                val next = discoveryImportQueue.removeFirstOrNull()
                if (next != null) {
                    discoveryImportQueuedKeys.remove(next.key)
                }
                updateDiscoveryQueuedLocked()
                next
            } ?: break

            val bundleKey = request.key
            val label = discoveryImportLabel(request.bundle, request.preferLatestAcrossChannels)
            discoveryImportProgressFlow.update { current ->
                current + (bundleKey to DiscoveryImportProgress(0L, null, DiscoveryImportStatus.Importing))
            }
            updateDiscoveryImportBanner(
                completed = completed,
                total = discoveryImportTotal(completed),
                label = label,
                phase = BundleImportPhase.Processing,
                bytesRead = 0L,
                bytesTotal = null
            )
            try {
                withContext(NonCancellable) {
                    createRemoteFromDiscovery(
                        bundle = request.bundle,
                        searchUpdate = request.searchUpdate,
                        autoUpdate = request.autoUpdate,
                        preferLatestAcrossChannels = request.preferLatestAcrossChannels,
                        onProgress = { bytesRead, bytesTotal ->
                            discoveryImportProgressFlow.update { current ->
                                if (!current.containsKey(bundleKey)) current
                                else current + (bundleKey to DiscoveryImportProgress(bytesRead, bytesTotal, DiscoveryImportStatus.Importing))
                            }
                            updateDiscoveryImportBanner(
                                completed = completed,
                                total = discoveryImportTotal(completed),
                                label = label,
                                phase = BundleImportPhase.Downloading,
                                bytesRead = bytesRead,
                                bytesTotal = bytesTotal
                            )
                        }
                    )
                }
            } catch (e: CancellationException) {
                Log.w(tag, "Discovery import cancelled for $label", e)
            } catch (e: Exception) {
                toast(R.string.patches_download_fail, e.simpleMessage())
            } finally {
                discoveryImportProgressFlow.update { current -> current - bundleKey }
                completed += 1
                val total = discoveryImportTotal(completed - 1)
                if (discoveryImportQueue.isEmpty()) {
                    updateDiscoveryImportBanner(
                        completed = completed,
                        total = total.coerceAtLeast(completed),
                        label = label,
                        phase = BundleImportPhase.Finalizing,
                        bytesRead = 0L,
                        bytesTotal = null
                    )
                }
            }
        }
    }

    private fun discoveryImportTotal(completed: Int): Int {
        val queued = discoveryImportQueuedFlow.value.size
        return (completed + queued + 1).coerceAtLeast(1)
    }

    private fun updateDiscoveryImportBanner(
        completed: Int,
        total: Int,
        label: String,
        phase: BundleImportPhase,
        bytesRead: Long,
        bytesTotal: Long?
    ) {
        setBundleImportProgress(
            ImportProgress(
                processed = completed.coerceAtMost(total),
                total = total.coerceAtLeast(1),
                currentBundleName = label,
                phase = phase,
                bytesRead = bytesRead,
                bytesTotal = bytesTotal,
            )
        )
    }

    private fun discoveryImportLabel(
        bundle: ExternalBundleSnapshot,
        preferLatestAcrossChannels: Boolean
    ): String {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        val identity = listOf(owner, repo).filter { it.isNotBlank() }.joinToString("/").ifBlank {
            bundle.sourceUrl
        }
        val channel = when {
            preferLatestAcrossChannels -> "latest"
            bundle.isPrerelease -> "pre-release"
            else -> "release"
        }
        return "$identity $channel patch bundle"
    }
}

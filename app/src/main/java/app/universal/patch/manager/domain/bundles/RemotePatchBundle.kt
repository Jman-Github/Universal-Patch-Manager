package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.dto.ExternalBundleSnapshot
import app.universal.patch.manager.network.dto.GitHubAsset
import app.universal.patch.manager.network.dto.GitHubRelease
import app.universal.patch.manager.network.dto.GitLabRelease
import app.universal.patch.manager.network.dto.GitLabReleaseLink
import app.universal.patch.manager.network.dto.ReVancedAsset
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.network.utils.APIResponse
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.network.utils.getOrThrow
import app.universal.patch.manager.patcher.patch.PatchBundle
import app.universal.patch.manager.patcher.patch.ReseamBundleFormat
import app.universal.patch.manager.patcher.reseam.ReseamRuntimeBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import okhttp3.Protocol

data class PatchBundleDownloadResult(
    val versionSignature: String,
    val assetCreatedAtMillis: Long?,
    val changelogAsset: ReVancedAsset? = null
)

data class PatchBundleChangelogResult(
    val asset: ReVancedAsset,
    val hasReleaseBody: Boolean = false
)

typealias PatchBundleDownloadProgress = (bytesRead: Long, bytesTotal: Long?) -> Unit

sealed class RemotePatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    private val installedVersionSignatureInternal: String?,
    error: Throwable?,
    directory: File,
    val endpoint: String,
    val autoUpdate: Boolean,
    val searchUpdate: Boolean,
    val lastNotifiedVersion: String?,
    enabled: Boolean,
) : PatchBundleSource(name, uid, displayName, createdAt, updatedAt, error, directory, enabled), KoinComponent {
    protected val http: HttpService by inject()
    open val supportsHistoricalChangelog: Boolean = false

    protected abstract suspend fun getLatestInfo(): ReVancedAsset
    abstract fun copy(
        error: Throwable? = this.error,
        name: String = this.name,
        displayName: String? = this.displayName,
        createdAt: Long? = this.createdAt,
        updatedAt: Long? = this.updatedAt,
        autoUpdate: Boolean = this.autoUpdate,
        searchUpdate: Boolean = this.searchUpdate,
        lastNotifiedVersion: String? = this.lastNotifiedVersion,
        enabled: Boolean = this.enabled
    ): RemotePatchBundle

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        enabled: Boolean
    ): RemotePatchBundle = copy(
        error,
        name,
        displayName,
        createdAt,
        updatedAt,
        this.autoUpdate,
        this.searchUpdate,
        this.lastNotifiedVersion,
        enabled
    )

    // PR #35: https://github.com/Jman-Github/Universal-Patch-Manager/pull/35
    protected open suspend fun download(info: ReVancedAsset, onProgress: PatchBundleDownloadProgress? = null) =
        withContext(Dispatchers.IO) {
            val tempFile = directory.resolve("${patchesFile.name}.download")
            try {
                tempFile.parentFile?.mkdirs()
                runCatching { tempFile.setWritable(true, true) }
                runCatching { tempFile.delete() }
                http.downloadToFile(
                    saveLocation = tempFile,
                    builder = { url(info.downloadUrl) },
                    onProgress = onProgress
                )
                validateDownloadedPatchBundle(tempFile, info.downloadUrl)
                runCatching { patchesFile.setWritable(true, true) }
                Files.move(
                    tempFile.toPath(),
                    patchesFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                patchesFile.setReadOnly()
            } catch (t: Throwable) {
                runCatching { tempFile.setWritable(true, true) }
                runCatching { tempFile.delete() }
                throw t
            }

            PatchBundleDownloadResult(
                versionSignature = info.version,
                assetCreatedAtMillis = runCatching {
                    info.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
                }.getOrNull(),
                changelogAsset = info
            )
        }

    /**
     * Downloads the latest version regardless if there is a new update available.
     */
    open suspend fun downloadLatest(onProgress: PatchBundleDownloadProgress? = null): PatchBundleDownloadResult =
        download(fetchLatestReleaseInfo(), onProgress)

    open suspend fun update(onProgress: PatchBundleDownloadProgress? = null): PatchBundleDownloadResult? =
        withContext(Dispatchers.IO) {
            val info = fetchLatestReleaseInfo()
            if (!isUpdateAvailable(info)) return@withContext null
            download(info, onProgress)
        }

    val isVersionPinned: Boolean get() = this is JsonPatchBundle && useSpecificVersion
    val updatesAutomatically: Boolean get() = autoUpdate && !isVersionPinned

    // Notification discovery must not change the artifact selected for download.
    open suspend fun fetchAvailableUpdateInfo(): ReVancedAsset = fetchLatestReleaseInfo()

    fun isUpdateAvailable(info: ReVancedAsset, forNotification: Boolean = false): Boolean =
        isRemoteBundleVersionUpdate(
            latestVersion = info.version,
            installedVersion = installedVersionSignatureInternal.takeIf { hasInstalled() },
            manifestVersion = if (forNotification && isVersionPinned &&
                !installedVersionSignatureInternal.isNullOrBlank()
            ) null else version.takeIf { hasInstalled() },
            preventDowngrade = (this is JsonPatchBundle && useLatest && !useSpecificVersion) ||
                (forNotification && isVersionPinned),
            pinnedVersion = isVersionPinned && !forNotification,
        )

    suspend fun fetchLatestReleaseInfo(): ReVancedAsset {
        val key = "$uid|${latestInfoCacheIdentity()}"
        val now = System.currentTimeMillis()
        val cached = changelogCacheMutex.withLock {
            changelogCache[key]?.takeIf { now - it.timestamp <= CHANGELOG_CACHE_TTL }
        }
        if (cached != null) return cached.asset

        return refreshLatestReleaseInfo()
    }

    suspend fun fetchLatestChangelog(): PatchBundleChangelogResult {
        val asset = fetchLatestReleaseInfo()
        val fallback = PatchBundleChangelogResult(asset)
        if (this is GitHubPullRequestBundle) return fallback
        val repoUrl = inferGitHubRepoUrl(asset.pageUrl, asset.downloadUrl, endpoint) ?: return fallback
        val releaseApi: UpmAPI by inject()
        val release = try {
            releaseApi.getRepositoryReleaseByTag(repoUrl, asset.version).getOrNull()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return fallback
        val body = release.body?.takeIf { it.isNotBlank() } ?: return fallback
        return PatchBundleChangelogResult(asset.copy(description = body), hasReleaseBody = true)
    }

    protected suspend fun refreshLatestReleaseInfo(): ReVancedAsset {
        val key = "$uid|${latestInfoCacheIdentity()}"
        val now = System.currentTimeMillis()
        val asset = getLatestInfo()
        changelogCacheMutex.withLock {
            changelogCache[key] = CachedChangelog(asset, now)
        }
        return asset
    }

    suspend fun fetchHistoricalChangelogEntries(
        limit: Int = DEFAULT_CHANGELOG_HISTORY_LIMIT
    ): List<PatchBundleChangelogEntry> {
        val key = "$uid|$limit|${historicalInfoCacheIdentity()}"
        val now = System.currentTimeMillis()
        val cached = changelogHistoryCacheMutex.withLock {
            changelogHistoryCache[key]?.takeIf { now - it.timestamp <= CHANGELOG_CACHE_TTL }
        }
        if (cached != null) return cached.entries

        val entries = getHistoricalChangelogEntries(limit)
        changelogHistoryCacheMutex.withLock {
            changelogHistoryCache[key] = CachedChangelogHistory(entries, now)
        }
        return entries
    }

    protected open suspend fun latestInfoCacheIdentity(): String = endpoint

    protected open suspend fun historicalInfoCacheIdentity(): String = latestInfoCacheIdentity()

    protected open suspend fun getHistoricalChangelogEntries(limit: Int): List<PatchBundleChangelogEntry> =
        emptyList()

    suspend fun changelogHistoryIdentity(): String = historicalInfoCacheIdentity()

    protected suspend fun fetchGitHubChangelogHistory(
        limit: Int,
        prerelease: Boolean? = null,
        vararg candidates: String?
    ): List<PatchBundleChangelogEntry> {
        val repoUrl = inferGitHubRepoUrl(*candidates) ?: return emptyList()
        val api: UpmAPI by inject()
        return api.getRepositoryReleaseHistory(repoUrl, prerelease = prerelease, limit = limit)
            .getOrThrow()
            .map { release -> release.toChangelogEntry(repoUrl) }
    }

    protected fun inferGitHubRepoUrl(vararg candidates: String?): String? =
        candidates.asSequence()
            .mapNotNull(::parseGitHubRepoUrl)
            .firstOrNull()

    private fun parseGitHubRepoUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { Url(trimmed) }.getOrNull() ?: return null
        val host = parsed.host.lowercase()
        val segments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val ownerRepo = when {
            host == "github.com" -> segments[0] to segments[1]
            host == "raw.githubusercontent.com" -> segments[0] to segments[1]
            host == "api.github.com" -> {
                val reposIndex = segments.indexOf("repos")
                if (reposIndex < 0 || segments.size <= reposIndex + 2) return null
                segments[reposIndex + 1] to segments[reposIndex + 2]
            }
            else -> return null
        }

        val owner = ownerRepo.first.removeSuffix(".git")
        val repo = ownerRepo.second.removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return "https://github.com/$owner/$repo"
    }

    companion object {
        const val updateFailMsg = "Failed to update patches"
        private const val CHANGELOG_CACHE_TTL = 10 * 60 * 1000L
        private const val DEFAULT_CHANGELOG_HISTORY_LIMIT =
            PreferencesManager.DEFAULT_BUNDLE_CHANGELOG_FETCH_LIMIT
        private val changelogCacheMutex = Mutex()
        private val changelogCache = mutableMapOf<String, CachedChangelog>()
        private val changelogHistoryCacheMutex = Mutex()
        private val changelogHistoryCache = mutableMapOf<String, CachedChangelogHistory>()
    }

    val installedVersionSignature: String? get() = installedVersionSignatureInternal

    protected fun normalizeVersionForCompare(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val noPrefix = trimmed.removePrefix("v").removePrefix("V")
        val noBuild = noPrefix.substringBefore('+')
        return noBuild.ifBlank { null }
    }

    private fun validateDownloadedPatchBundle(file: File, fileNameHint: String) {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length < 8L) {
            runCatching { file.delete() }
            throw IOException("Downloading patch bundle produced an empty or truncated patch bundle (size=$length)")
        }

        val isReseamBundle = ReseamBundleFormat.isBundle(file)
        require(!directory.resolve("reseam-signer").exists() || isReseamBundle) {
            "A pinned Reseam source must provide a signed Reseam bundle."
        }
        if (ReseamBundleFormat.isCandidate(file, fileNameHint)) {
            // Verify signatures and signer continuity before replacing an installed bundle.
            ReseamRuntimeBridge.loadMetadata(file.absolutePath)
            return
        }

        val manifestAttributes = runCatching {
            PatchBundle(file.absolutePath).manifestAttributes
        }.getOrNull()
        if (manifestAttributes == null) {
            throw IOException("Downloaded file is not a valid patch bundle archive")
        }

        JarFile(file).use { jar ->
            val entries = jar.entries()
            var hasDexEntry = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".dex", ignoreCase = true)) continue
                hasDexEntry = true
                if (entry.size <= 0L) {
                    throw IOException("Downloaded patch bundle contains empty dex entries")
                }
            }
            if (!hasDexEntry) {
                throw IOException("Downloaded patch bundle is missing dex entries")
            }
        }
    }
}

class JsonPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean,
    val usePrereleases: Boolean = false,
    val useLatest: Boolean = false,
    val useSpecificVersion: Boolean = false,
    val selectedVersion: ReVancedAsset? = null,
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {
    override val supportsHistoricalChangelog: Boolean = true
    private val releaseApi: UpmAPI by inject()
    val supportsPrereleases: Boolean = repositoryReleaseSource() != null
    val releaseChannel: RepositoryBundleReleaseChannel
        get() = when {
            useLatest -> RepositoryBundleReleaseChannel.LATEST
            usePrereleases -> RepositoryBundleReleaseChannel.PRERELEASE
            else -> RepositoryBundleReleaseChannel.RELEASE
        }

    override suspend fun update(onProgress: PatchBundleDownloadProgress?): PatchBundleDownloadResult? {
        if (useSpecificVersion && selectedVersion == null) return null
        return super.update(onProgress)
    }

    override suspend fun fetchAvailableUpdateInfo(): ReVancedAsset =
        if (useSpecificVersion) {
            withRepositorySettings(repositorySettings.copy(useSpecificVersion = false))
                .fetchLatestReleaseInfo()
        } else {
            fetchLatestReleaseInfo()
        }

    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        if (useSpecificVersion) {
            return@withContext selectedVersion
                ?: error("Select a patch bundle version before updating.")
        }
        val releaseSource = repositoryReleaseSource()
        val repositoryUrl = when (releaseSource) {
            is RepositoryReleaseSource.Forgejo -> releaseSource.repositoryUrl
            is RepositoryReleaseSource.GitLab -> "${releaseSource.origin}/${releaseSource.repositoryPath}"
            else -> null
        }
        if (repositoryUrl != null && endpoint.trimEnd('/') == repositoryUrl) {
            return@withContext requestLatestRepositoryRelease(
                requireNotNull(releaseSource), preferredExtension = null,
                prerelease = when (releaseChannel) {
                    RepositoryBundleReleaseChannel.RELEASE -> false
                    RepositoryBundleReleaseChannel.PRERELEASE -> true
                    RepositoryBundleReleaseChannel.LATEST -> null
                }
            ) ?: throw NoSuchElementException("No compatible repository release asset found")
        }
        val document = try {
            Result.success(http.request<JsonObject> { url(endpoint) }.getOrThrow())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val prerelease = when (releaseChannel) {
            RepositoryBundleReleaseChannel.RELEASE -> false
            RepositoryBundleReleaseChannel.PRERELEASE -> true
            RepositoryBundleReleaseChannel.LATEST -> null
        }
        // A Reseam index owns release selection and signer trust even when hosted on GitHub.
        // Parse and validate it outside the legacy repository fallback so errors cannot bypass it.
        val reseam = document.getOrNull()?.let { ReseamBundleIndex.parse(it, endpoint, prerelease) }
        if (reseam != null) return@withContext acceptReseamRelease(reseam)

        suspend fun requestManifest(): ReVancedAsset =
            http.json.decodeFromJsonElement(document.getOrThrow())

        if (!supportsPrereleases || releaseSource == null ||
            (releaseSource is RepositoryReleaseSource.GitLab &&
                releaseSource.origin == "https://gitlab.com" &&
                releaseChannel == RepositoryBundleReleaseChannel.RELEASE)
        ) {
            return@withContext requestManifest()
        }

        resolveRepositoryBundleRelease(
            requestManifest = { requestManifest() },
            requestRelease = { manifest ->
                requestLatestRepositoryRelease(
                    source = releaseSource,
                    preferredExtension = manifest?.downloadUrl?.patchBundleExtension(),
                    prerelease = prerelease,
                )
            },
            fallbackToManifestWhenReleaseMissing =
                releaseChannel == RepositoryBundleReleaseChannel.RELEASE,
        )
    }

    override suspend fun getHistoricalChangelogEntries(limit: Int) = withContext(Dispatchers.IO) {
        val latest = runCatching { fetchLatestReleaseInfo() }.getOrNull()
        fetchGitHubChangelogHistory(
            limit,
            when (releaseChannel) {
                RepositoryBundleReleaseChannel.RELEASE -> false
                RepositoryBundleReleaseChannel.PRERELEASE -> true
                RepositoryBundleReleaseChannel.LATEST -> null
            },
            latest?.pageUrl,
            latest?.downloadUrl,
            endpoint
        )
    }

    val releaseSelectionKey: String
        get() = "$endpoint|channel=${releaseChannel.name}|pin=$useSpecificVersion|" +
            "${selectedVersion?.version}|${selectedVersion?.downloadUrl}"

    override suspend fun latestInfoCacheIdentity(): String = releaseSelectionKey

    override suspend fun historicalInfoCacheIdentity(): String =
        "$endpoint|history|channel=${releaseChannel.name}"

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = JsonPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled,
        usePrereleases,
        useLatest,
        useSpecificVersion,
        selectedVersion,
    )

    fun withReleaseChannel(channel: RepositoryBundleReleaseChannel) = JsonPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled,
        if (channel == RepositoryBundleReleaseChannel.LATEST) usePrereleases
        else channel == RepositoryBundleReleaseChannel.PRERELEASE,
        channel == RepositoryBundleReleaseChannel.LATEST,
        useSpecificVersion,
        selectedVersion,
    )

    val repositorySettings: RepositoryBundleSettings
        get() = RepositoryBundleSettings(usePrereleases, useLatest, useSpecificVersion, selectedVersion)

    fun withRepositorySettings(settings: RepositoryBundleSettings) = JsonPatchBundle(
        name, uid, displayName, createdAt, updatedAt, installedVersionSignature, error,
        directory, endpoint, autoUpdate, searchUpdate, lastNotifiedVersion, enabled,
        settings.usePrereleases, settings.useLatest, settings.useSpecificVersion, settings.selectedVersion
    )

    /** All published stable and development releases with a usable bundle asset. */
    suspend fun fetchAvailableVersions(): List<ReVancedAsset> = withContext(Dispatchers.IO) {
        val document = try {
            http.request<JsonObject> { url(endpoint) }.getOrThrow()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        document?.let { json ->
            ReseamBundleIndex.parseAll(json, endpoint)?.let { releases ->
                return@withContext releases.map(::acceptReseamRelease).distinctBy { it.version }
            }
        }
        val source = repositoryReleaseSource() ?: error("This source has no release history.")
        val preferredExtension = if (ReseamBundleFormat.isBundle(patchesFile)) {
            "reseam"
        } else {
            document?.let { json ->
                runCatching { http.json.decodeFromJsonElement<ReVancedAsset>(json) }
                    .getOrNull()?.downloadUrl?.patchBundleExtension()
            }
        }
        suspend fun gitLabVersions(source: RepositoryReleaseSource.GitLab): List<ReVancedAsset> {
            val project = URLEncoder.encode(source.repositoryPath, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            val result = mutableListOf<ReVancedAsset>()
            var page = 1
            while (true) {
                val batch = http.request<List<GitLabRelease>> {
                    url("${source.origin}/api/v4/projects/$project/releases?per_page=100&page=$page")
                }.getOrThrow()
                result += batch.filterNot { it.upcomingRelease }.mapNotNull {
                    it.toPatchBundleAsset(source.repositoryPath, preferredExtension, source.origin)
                }
                if (batch.size < 100) break
                page++
            }
            return result
        }
        val assets = when (source) {
            is RepositoryReleaseSource.GitHub -> releaseApi.getRepositoryReleaseHistory(
                source.repositoryUrl, prerelease = null, limit = Int.MAX_VALUE
            ).getOrThrow().mapNotNull {
                it.toPatchBundleAsset(source.repositoryUrl, preferredExtension)
            }
            is RepositoryReleaseSource.GitLab -> gitLabVersions(source)
            is RepositoryReleaseSource.Forgejo -> {
                val result = mutableListOf<ReVancedAsset>()
                val seenTags = mutableSetOf<String>()
                var page = 1
                while (true) {
                    val response = http.request<List<GitHubRelease>> {
                        url("${source.apiUrl}/releases?limit=50&page=$page")
                    }
                    if (page == 1 && response is APIResponse.Error &&
                        response.error.code == HttpStatusCode.NotFound) {
                        result += gitLabVersions(RepositoryReleaseSource.GitLab(
                            source.repositoryPath, source.origin
                        ))
                        break
                    }
                    val batch = response.getOrThrow()
                    // Servers may cap page sizes. Continue until exhausted, and guard
                    // against servers that ignore the page parameter.
                    val unseen = batch.filter { seenTags.add(it.tagName) }
                    if (unseen.isEmpty()) break
                    result += unseen.filterNot { it.draft }.mapNotNull {
                        it.toPatchBundleAsset(source.repositoryUrl, preferredExtension)
                    }
                    page++
                }
                result
            }
        }
        assets.distinctBy { it.version }.sortedByDescending { it.createdAt }
    }

    private fun acceptReseamRelease(reseam: ReseamBundleIndex.Release): ReVancedAsset {
        reseam.publicKey?.let { key ->
            val signer = directory.resolve("reseam-signer")
            if (signer.exists()) {
                require(signer.readText().trim().equals(key, ignoreCase = true)) {
                    "Reseam publisher key changed. Remove and import this source again if you trust the new key."
                }
            } else {
                directory.mkdirs()
                signer.writeText(key)
            }
        }
        return reseam.asset
    }

    private fun repositoryReleaseSource(): RepositoryReleaseSource? =
        RepositoryReleaseSourceParser.parse(endpoint)

    private suspend fun requestLatestRepositoryRelease(
        source: RepositoryReleaseSource,
        preferredExtension: String?,
        prerelease: Boolean?,
    ): ReVancedAsset? = when (source) {
        is RepositoryReleaseSource.GitHub -> releaseApi
            .getRepositoryReleaseHistory(source.repositoryUrl, prerelease = prerelease, limit = 50)
            .getOrThrow()
            .asSequence()
            .mapNotNull { release ->
                release.toPatchBundleAsset(source.repositoryUrl, preferredExtension)
            }
            .maxByOrNull { it.createdAt }

        // Forgejo and Gitea use the same public release/asset fields as GitHub.
        // https://forgejo.org/docs/latest/user/api/usage/
        is RepositoryReleaseSource.Forgejo -> {
            val response = http.request<List<GitHubRelease>> {
                url("${source.apiUrl}/releases?limit=50")
            }
            // A repository root does not identify its server software. Try GitLab only if
            // the Forgejo endpoint is absent; authentication and network errors stay errors.
            if (response is APIResponse.Error && response.error.code == HttpStatusCode.NotFound) {
                requestLatestRepositoryRelease(
                    RepositoryReleaseSource.GitLab(source.repositoryPath, source.origin),
                    preferredExtension, prerelease
                )
            } else {
                response.getOrThrow()
                    .asSequence()
                    .filter { !it.draft && (prerelease == null || it.prerelease == prerelease) }
                    .mapNotNull { it.toPatchBundleAsset(source.repositoryUrl, preferredExtension) }
                    .maxByOrNull { it.createdAt }
            }
        }

        is RepositoryReleaseSource.GitLab -> {
            val encodedProject = URLEncoder.encode(
                source.repositoryPath,
                StandardCharsets.UTF_8.name()
            ).replace("+", "%20")
            http.request<List<GitLabRelease>> {
                url("${source.origin}/api/v4/projects/$encodedProject/releases?per_page=50")
            }.getOrThrow()
                .asSequence()
                .filterNot { it.upcomingRelease }
                .mapNotNull { release ->
                    release.toPatchBundleAsset(source.repositoryPath, preferredExtension, source.origin)
                }
                .maxByOrNull { it.createdAt }
        }
    }

    private fun GitHubRelease.toPatchBundleAsset(
        repositoryUrl: String,
        preferredExtension: String?
    ): ReVancedAsset? {
        val asset = assets.selectGitHubPatchBundleAsset(preferredExtension) ?: return null
        val created = (publishedAt ?: createdAt)?.toUtcLocalDateTime() ?: return null
        return ReVancedAsset(
            downloadUrl = asset.downloadUrl,
            createdAt = created,
            signatureDownloadUrl = assets.findGitHubSignatureUrl(asset.name),
            pageUrl = "${repositoryUrl.removeSuffix("/")}/releases/tag/$tagName",
            description = body?.ifBlank { name.orEmpty() } ?: name.orEmpty(),
            version = tagName
        )
    }

    private fun GitLabRelease.toPatchBundleAsset(
        repositoryPath: String,
        preferredExtension: String?,
        origin: String = "https://gitlab.com"
    ): ReVancedAsset? {
        val asset = assets.links.selectGitLabPatchBundleAsset(preferredExtension) ?: return null
        val created = (releasedAt ?: createdAt)?.toUtcLocalDateTime() ?: return null
        return ReVancedAsset(
            downloadUrl = asset.resolvedGitLabUrl(origin),
            createdAt = created,
            signatureDownloadUrl = assets.links.findGitLabSignatureUrl(asset.name, origin),
            pageUrl = "$origin/$repositoryPath/-/releases/$tagName",
            description = description?.ifBlank { name.orEmpty() } ?: name.orEmpty(),
            version = tagName
        )
    }

    private fun List<GitHubAsset>.selectGitHubPatchBundleAsset(
        preferredExtension: String?
    ): GitHubAsset? {
        val candidates = filter { it.name.patchBundleExtension() != null }
        return candidates.firstOrNull { it.name.patchBundleExtension() == preferredExtension }
            ?: candidates.firstOrNull()
    }

    private fun List<GitLabReleaseLink>.selectGitLabPatchBundleAsset(
        preferredExtension: String?
    ): GitLabReleaseLink? {
        val candidates = filter { link ->
            link.name.patchBundleExtension() != null ||
                link.resolvedGitLabUrl().patchBundleExtension() != null
        }
        return candidates.firstOrNull { link ->
            val extension = link.name.patchBundleExtension()
                ?: link.resolvedGitLabUrl().patchBundleExtension()
            extension == preferredExtension
        } ?: candidates.firstOrNull()
    }

    private fun List<GitHubAsset>.findGitHubSignatureUrl(assetName: String): String? {
        val candidates = signatureNames(assetName)
        return firstOrNull { it.name in candidates }?.downloadUrl
    }

    private fun List<GitLabReleaseLink>.findGitLabSignatureUrl(
        assetName: String, origin: String = "https://gitlab.com"
    ): String? {
        val candidates = signatureNames(assetName)
        return firstOrNull { it.name in candidates }?.resolvedGitLabUrl(origin)
    }

    private fun signatureNames(assetName: String): Set<String> {
        val base = assetName.substringBeforeLast('.', assetName)
        return setOf("$assetName.sig", "$assetName.asc", "$base.sig", "$base.asc")
    }

    private fun GitLabReleaseLink.resolvedGitLabUrl(origin: String = "https://gitlab.com"): String {
        val raw = directAssetUrl?.takeUnless { it.isBlank() } ?: url
        return if (raw.startsWith('/')) "$origin$raw" else raw
    }

    private fun String.patchBundleExtension(): String? {
        val path = substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it == "rvp" || it == "mpp" || it == "reseam" }
    }

    private fun String.toUtcLocalDateTime(): LocalDateTime? = runCatching {
        Instant.parse(this).toLocalDateTime(TimeZone.UTC)
    }.getOrNull()

}

// PR #35: https://github.com/Jman-Github/Universal-Patch-Manager/pull/35
class GitHubPullRequestBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {

    private val api: UpmAPI by inject()

    override suspend fun getLatestInfo() = withContext(Dispatchers.IO) {
        val (owner, repo, prNumber) = endpoint.split("/").let { parts ->
            Triple(parts[3], parts[4], parts[6])
        }

        api.getAssetFromPullRequest(owner, repo, prNumber)
    }

    override suspend fun download(info: ReVancedAsset, onProgress: PatchBundleDownloadProgress?) = withContext(Dispatchers.IO) {
        val prefs: PreferencesManager by inject()
        val gitHubPat = prefs.gitHubPat.get().also {
            if (it.isBlank()) throw RuntimeException("PAT is required.")
        }

        val customHttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    // Force HTTP/1.1 to avoid HTTP/2 PROTOCOL_ERROR stream resets when fetching
                    // PR artifacts from GitHub.
                    protocols(listOf(Protocol.HTTP_1_1))
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
                requestTimeoutMillis = 5 * 60_000
            }
        }

        try {
            with(customHttpClient) {
                prepareGet {
                    url(info.downloadUrl)
                    header("Authorization", "Bearer $gitHubPat")
                }.execute { httpResponse ->
                    patchBundleOutputStream().use { patchOutput ->
                        ZipInputStream(httpResponse.bodyAsChannel().toInputStream()).use { zis ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copiedBytes = 0L
                            var lastReportedBytes = 0L
                            var lastReportedAt = 0L
                            var extractedTotal: Long? = null

                            var entry = zis.nextEntry
                            while (entry != null) {
                                val entryName = entry.name.lowercase()
                                if (!entry.isDirectory && (entryName.endsWith(".rvp") || entryName.endsWith(".mpp") || entryName.endsWith(".reseam"))) {
                                    extractedTotal = entry.size.takeIf { it > 0 }
                                    while (true) {
                                        val read = zis.read(buffer)
                                        if (read == -1) break
                                        patchOutput.write(buffer, 0, read)
                                        copiedBytes += read.toLong()
                                        val now = System.currentTimeMillis()
                                        if (copiedBytes - lastReportedBytes >= 64 * 1024 || now - lastReportedAt >= 200) {
                                            lastReportedBytes = copiedBytes
                                            lastReportedAt = now
                                            onProgress?.invoke(copiedBytes, extractedTotal)
                                        }
                                    }
                                    break
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }

                            if (copiedBytes <= 0L) {
                                throw IOException("No .rvp, .mpp or .reseam file found in the pull request artifact.")
                            }
                            onProgress?.invoke(copiedBytes, extractedTotal)
                        }
                    }
                }
            }
            requireNonEmptyPatchesFile("Downloading patch bundle")
        } catch (t: Throwable) {
            runCatching { patchesFile.delete() }
            throw t
        } finally {
            runCatching { customHttpClient.close() }
        }

        PatchBundleDownloadResult(
            versionSignature = info.version,
            assetCreatedAtMillis = runCatching {
                info.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
            }.getOrNull(),
            changelogAsset = info
        )
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = GitHubPullRequestBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled
    )
}

class ExternalGraphqlPatchBundle(
    name: String,
    uid: Int,
    displayName: String?,
    createdAt: Long?,
    updatedAt: Long?,
    installedVersionSignature: String?,
    error: Throwable?,
    directory: File,
    endpoint: String,
    autoUpdate: Boolean,
    searchUpdate: Boolean,
    lastNotifiedVersion: String?,
    enabled: Boolean,
    private var metadata: ExternalBundleMetadata
) : RemotePatchBundle(
    name,
    uid,
    displayName,
    createdAt,
    updatedAt,
    installedVersionSignature,
    error,
    directory,
    endpoint,
    autoUpdate,
    searchUpdate,
    lastNotifiedVersion,
    enabled
) {
    private val api: ExternalBundlesApi by inject()
    private val officialApi: UpmAPI by inject()
    override val supportsHistoricalChangelog: Boolean = true
    private val installedArtifactUrlFile = directory.resolve("installed_artifact_url")
    private data class EndpointMetadata(
        val owner: String?,
        val repo: String?,
        val prerelease: Boolean?,
        val hasExplicitChannel: Boolean,
        val isLatestApiEndpoint: Boolean
    )
    private data class HistorySource(
        val owner: String,
        val repo: String,
        val repoUrl: String?,
        val sourceUrl: String?
    )

    override suspend fun getLatestInfo(): ReVancedAsset = withContext(Dispatchers.IO) {
        val registryEndpoint = externalBundlesHost() == ExternalBundlesEndpoints.REGISTRY_HOST
        val requestedVersion = runCatching { Url(endpoint).parameters["version"] }.getOrNull()
        // An exact legacy tag stays pinned when its endpoint moves to the registry.
        if (registryEndpoint && !requestedVersion.isNullOrBlank() &&
            !requestedVersion.equals("latest", ignoreCase = true)) {
            val asset = http.request<ReVancedAsset> { url(endpoint) }.getOrThrow()
            if (metadata.downloadUrl != asset.downloadUrl || metadata.version != asset.version) {
                metadata = metadata.copy(
                    downloadUrl = safeArtifactUrl(asset.downloadUrl) ?: metadata.downloadUrl,
                    signatureDownloadUrl = asset.signatureDownloadUrl,
                    version = asset.version.ifBlank { metadata.version },
                    fileHash = asset.fileHash,
                    createdAt = asset.createdAt.toString(),
                    description = asset.description.ifBlank { metadata.description }
                )
                ExternalBundleMetadataStore.write(directory, metadata)
            }
            return@withContext asset
        }
        // Discovery already supplied the selected artifact, including historical releases.
        // A migrated source has no registry ID yet and must resolve its source/channel first.
        if (registryEndpoint && metadata.bundleId > 0 && !hasInstalled()) {
            return@withContext snapshotToAsset(null)
        }
        val endpointMetadata = parseEndpointMetadata()
        val owner = endpointMetadata.owner?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.ownerName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val repo = endpointMetadata.repo?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.repoName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val prerelease = if (endpointMetadata.hasExplicitChannel) {
            endpointMetadata.prerelease
        } else {
            metadata.isPrerelease ?: endpointMetadata.prerelease
        }
        val trackLatestAcrossChannels = owner.isNotBlank() && repo.isNotBlank() && prerelease == null
        val (endpointAsset, latestFromServices) = coroutineScope {
            val endpointDeferred = async {
                if (externalBundlesHost() != ExternalBundlesEndpoints.REGISTRY_HOST &&
                    endpointMetadata.hasExplicitChannel && endpointMetadata.isLatestApiEndpoint) {
                    withTimeoutOrNull(ExternalBundlesEndpoints.HOST_QUERY_TIMEOUT_MS) {
                        http.request<ReVancedAsset> { url(endpoint) }.getOrNull()
                    }
                } else {
                    null
                }
            }
            val latestDeferred = async {
                findLatestExternalSnapshot(owner, repo, prerelease)
            }
            endpointDeferred.await() to latestDeferred.await()
        }
        if (endpointAsset != null) {
            val latestCreatedAt = latestFromServices?.let { parseInstant(it.createdAt) }
            val endpointCreatedAt = endpointAsset.createdAt.toInstant(TimeZone.UTC)
            if (latestFromServices != null && latestCreatedAt != null && latestCreatedAt > endpointCreatedAt) {
                metadata = metadataFromSnapshot(
                    snapshot = latestFromServices,
                    preserveChannelSelection = trackLatestAcrossChannels
                )
                ExternalBundleMetadataStore.write(directory, metadata)
                return@withContext snapshotToAsset(latestFromServices)
            }

            val endpointHost = externalBundlesHost()
            val matchingServiceSnapshot = latestFromServices
                ?.takeIf {
                    it.version.trim() == endpointAsset.version.trim() &&
                        it.apiHost.equals(endpointHost, ignoreCase = true)
                }
            if (
                matchingServiceSnapshot != null &&
                snapshotArtifactDiffers(endpointAsset, matchingServiceSnapshot)
            ) {
                metadata = metadataFromSnapshot(
                    snapshot = matchingServiceSnapshot,
                    preserveChannelSelection = trackLatestAcrossChannels
                )
                ExternalBundleMetadataStore.write(directory, metadata)
                return@withContext snapshotToAsset(matchingServiceSnapshot)
            }

            metadata = metadata.copy(
                downloadUrl = endpointAsset.downloadUrl,
                signatureDownloadUrl = endpointAsset.signatureDownloadUrl,
                version = endpointAsset.version,
                fileHash = endpointAsset.fileHash ?: matchingServiceSnapshot
                    ?.takeIf { it.downloadUrl?.trim() == endpointAsset.downloadUrl.trim() }?.fileHash,
                createdAt = endpointAsset.createdAt.toString(),
                description = endpointAsset.description.ifBlank { metadata.description },
                ownerName = owner.takeIf { it.isNotBlank() } ?: metadata.ownerName,
                repoName = repo.takeIf { it.isNotBlank() } ?: metadata.repoName,
                isPrerelease = prerelease
            )
            ExternalBundleMetadataStore.write(directory, metadata)
            val endpointFileHash = matchingServiceSnapshot
                ?.takeIf {
                    it.downloadUrl?.trim().orEmpty() == endpointAsset.downloadUrl.trim()
                }
                ?.fileHash
            return@withContext endpointAsset.copy(
                fileHash = endpointAsset.fileHash ?: endpointFileHash
            )
        }
        if (owner.equals("ReVanced", ignoreCase = true) && repo.equals("revanced-patches", ignoreCase = true)) {
            val officialAsset = if (prerelease == null) {
                val latestRelease = officialApi.getPatchesUpdate(prerelease = false).getOrNull()
                val latestPrerelease = officialApi.getPatchesUpdate(prerelease = true).getOrNull()
                pickNewestOfficialAsset(latestRelease, latestPrerelease)
            } else {
                officialApi.getPatchesUpdate(prerelease = prerelease).getOrNull()
            }
            if (officialAsset != null) {
                metadata = metadata.copy(
                    downloadUrl = officialAsset.downloadUrl,
                    signatureDownloadUrl = officialAsset.signatureDownloadUrl,
                    version = officialAsset.version,
                    fileHash = officialAsset.fileHash ?: latestFromServices?.takeIf {
                        it.version.trim() == officialAsset.version.trim() &&
                            it.downloadUrl?.trim() == officialAsset.downloadUrl.trim()
                    }?.fileHash,
                    createdAt = officialAsset.createdAt.toString(),
                    description = officialAsset.description.ifBlank { metadata.description },
                    isPrerelease = prerelease
                )
                ExternalBundleMetadataStore.write(directory, metadata)
                val officialFileHash = latestFromServices
                    ?.takeIf {
                        it.version.trim() == officialAsset.version.trim() &&
                            it.downloadUrl?.trim().orEmpty() == officialAsset.downloadUrl.trim()
                    }
                    ?.fileHash
                return@withContext officialAsset.copy(
                    fileHash = officialAsset.fileHash ?: officialFileHash
                )
            }
        }
        val latest = latestFromServices
            ?: metadata.bundleId.takeIf { it > 0 }?.let {
                api.getBundleById(it, metadata.apiHost ?: externalBundlesHost()).getOrNull()
            }
        if (latest != null) {
            metadata = metadataFromSnapshot(
                snapshot = latest,
                preserveChannelSelection = trackLatestAcrossChannels
            )
            ExternalBundleMetadataStore.write(directory, metadata)
        }
        snapshotToAsset(latest)
    }

    protected override suspend fun download(
        info: ReVancedAsset,
        onProgress: PatchBundleDownloadProgress?
    ): PatchBundleDownloadResult {
        val result = super.download(info, onProgress)
        writeInstalledArtifactUrl(info.downloadUrl)
        return result
    }

    fun artifactDiffers(info: ReVancedAsset, installedSha256: String?): Boolean {
        val expectedSha256 = artifactSha256(info)
        if (expectedSha256 != null) {
            val installed = installedSha256?.trim()?.lowercase(Locale.US)
            return installed != expectedSha256
        }

        val expectedUrl = info.downloadUrl.trim()
        if (expectedUrl.isBlank()) return false
        return readInstalledArtifactUrl() != expectedUrl
    }

    fun artifactSha256(info: ReVancedAsset): String? =
        normalizeSha256FileHash(info.fileHash)

    fun artifactNotificationIdentity(info: ReVancedAsset): String? =
        artifactSha256(info)?.let { "sha256:$it" }
            ?: info.downloadUrl.trim().takeIf { it.isNotBlank() }?.let { "url:$it" }

    private fun readInstalledArtifactUrl(): String? =
        runCatching { installedArtifactUrlFile.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun writeInstalledArtifactUrl(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        runCatching { installedArtifactUrlFile.writeText(normalized) }
    }

    private fun snapshotArtifactDiffers(
        endpointAsset: ReVancedAsset,
        snapshot: ExternalBundleSnapshot
    ): Boolean {
        val endpointHash = endpointAsset.fileHash?.trim().orEmpty()
        val snapshotHash = snapshot.fileHash?.trim().orEmpty()
        if (
            endpointHash.isNotBlank() &&
            snapshotHash.isNotBlank() &&
            endpointHash != snapshotHash
        ) {
            return true
        }

        val snapshotDownloadUrl = snapshot.downloadUrl?.trim().orEmpty()
        return snapshotDownloadUrl.isNotBlank() &&
            endpointAsset.downloadUrl.trim() != snapshotDownloadUrl
    }

    private suspend fun findLatestExternalSnapshot(
        owner: String,
        repo: String,
        prerelease: Boolean?
    ): ExternalBundleSnapshot? {
        val sourceUrl = metadata.sourceUrl ?: runCatching {
            Url(endpoint).parameters["source_url"]
        }.getOrNull()
        if (!sourceUrl.isNullOrBlank()) {
            return api.getLatestBundleForSource(sourceUrl, prerelease).getOrNull()
        }
        if (owner.isBlank() || repo.isBlank()) return null
        return if (prerelease != null) {
            api.getLatestBundle(owner, repo, prerelease).getOrNull()
        } else {
            api.getLatestBundleAny(owner, repo).getOrNull()
                ?: run {
                    val latestRelease = api.getLatestBundle(owner, repo, prerelease = false).getOrNull()
                    val latestPrerelease = api.getLatestBundle(owner, repo, prerelease = true).getOrNull()
                    pickLatestSnapshot(latestRelease, latestPrerelease)
                }
        }
    }

    override suspend fun getHistoricalChangelogEntries(limit: Int) = withContext(Dispatchers.IO) {
        val endpointMetadata = parseEndpointMetadata()
        val historySource = resolveHistorySource(endpointMetadata)
        val prerelease = if (endpointMetadata.hasExplicitChannel) {
            endpointMetadata.prerelease
        } else {
            metadata.isPrerelease ?: endpointMetadata.prerelease
        }
        val history = fetchExternalHistory(
            source = historySource,
            prerelease = prerelease,
            limit = limit
        )
        // Prefer complete release notes without crossing the selected channel.
        // Optional GitHub backfilling must not discard successfully fetched service history.
        val githubHistory = try {
            fetchGitHubChangelogHistory(
                limit,
                prerelease,
                historySource.repoUrl,
                historySource.sourceUrl,
                endpoint,
                metadata.downloadUrl
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (history.isEmpty()) throw error
            emptyList()
        }
        mergePatchBundleChangelogs(history, githubHistory, limit)
    }

    override suspend fun historicalInfoCacheIdentity(): String {
        val endpointMetadata = parseEndpointMetadata()
        val owner = endpointMetadata.owner?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.ownerName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val repo = endpointMetadata.repo?.trim().takeIf { !it.isNullOrBlank() }
            ?: metadata.repoName?.trim().takeIf { !it.isNullOrBlank() }
            ?: ""
        val prerelease = if (endpointMetadata.hasExplicitChannel) {
            endpointMetadata.prerelease
        } else {
            metadata.isPrerelease ?: endpointMetadata.prerelease
        }
        return "$endpoint|history|owner=$owner|repo=$repo|prerelease=$prerelease"
    }

    override fun copy(
        error: Throwable?,
        name: String,
        displayName: String?,
        createdAt: Long?,
        updatedAt: Long?,
        autoUpdate: Boolean,
        searchUpdate: Boolean,
        lastNotifiedVersion: String?,
        enabled: Boolean
    ) = ExternalGraphqlPatchBundle(
        name,
        uid,
        displayName,
        createdAt,
        updatedAt,
        installedVersionSignature,
        error,
        directory,
        endpoint,
        autoUpdate,
        searchUpdate,
        lastNotifiedVersion,
        enabled,
        metadata
    )

    private fun metadataFromSnapshot(
        snapshot: ExternalBundleSnapshot,
        preserveChannelSelection: Boolean
    ) = ExternalBundleMetadata(
        bundleId = snapshot.bundleId,
        downloadUrl = safeArtifactUrl(snapshot.downloadUrl) ?: metadata.downloadUrl,
        signatureDownloadUrl = snapshot.signatureDownloadUrl ?: metadata.signatureDownloadUrl,
        version = snapshot.version.ifBlank { metadata.version },
        createdAt = snapshot.createdAt.ifBlank { metadata.createdAt },
        description = snapshot.description ?: metadata.description,
        ownerName = snapshot.ownerName.takeIf { it.isNotBlank() } ?: metadata.ownerName,
        repoName = snapshot.repoName.takeIf { it.isNotBlank() } ?: metadata.repoName,
        isPrerelease = if (preserveChannelSelection) null else snapshot.isPrerelease,
        iconUrl = snapshot.iconUrl ?: metadata.iconUrl,
        ownerAvatarUrl = snapshot.ownerAvatarUrl ?: metadata.ownerAvatarUrl,
        sourceUrl = snapshot.sourceUrl.ifBlank { metadata.sourceUrl },
        apiHost = snapshot.apiHost,
        fileHash = snapshot.fileHash
    ).also {
        check(!snapshot.isBundleV3) {
            "This bundle format is not supported"
        }
    }

    private fun snapshotToAsset(snapshot: ExternalBundleSnapshot?): ReVancedAsset {
        val downloadUrl = safeArtifactUrl(snapshot?.downloadUrl)
            ?: safeArtifactUrl(metadata.downloadUrl)
            ?: throw IllegalStateException("External bundle metadata did not contain a downloadable artifact URL")
        val signatureUrl = snapshot?.signatureDownloadUrl ?: metadata.signatureDownloadUrl
        val version = snapshot?.version?.ifBlank { null } ?: metadata.version
        val description = snapshot?.description ?: metadata.description ?: ""
        val createdAtRaw = snapshot?.createdAt ?: metadata.createdAt
        val createdAt = parseCreatedAt(createdAtRaw)

        return ReVancedAsset(
            downloadUrl = downloadUrl,
            createdAt = createdAt,
            signatureDownloadUrl = signatureUrl,
            pageUrl = snapshot?.sourceUrl ?: metadata.sourceUrl,
            description = description,
            version = version,
            fileHash = if (snapshot != null) snapshot.fileHash else metadata.fileHash
        )
    }

    private fun normalizeSha256FileHash(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val digest = when {
            trimmed.startsWith("sha256:", ignoreCase = true) -> trimmed.substringAfter(':')
            ':' !in trimmed -> trimmed
            else -> return null
        }
        if (!digest.matches(Regex("^[0-9a-fA-F]{64}$"))) return null
        return digest.lowercase(Locale.US)
    }

    private fun ExternalBundleSnapshot.toChangelogEntry(): PatchBundleChangelogEntry {
        val repoUrl = when {
            ownerName.isNotBlank() && repoName.isNotBlank() ->
                "https://github.com/$ownerName/$repoName"
            else -> inferGitHubRepoUrl(sourceUrl)
        }
        val pageUrl = when {
            !repoUrl.isNullOrBlank() && version.isNotBlank() ->
                "${repoUrl.removeSuffix("/")}/releases/tag/$version"
            sourceUrl.isNotBlank() -> sourceUrl
            else -> null
        }
        return PatchBundleChangelogEntry(
            version = version,
            description = description?.takeIf { it.isNotBlank() } ?: version,
            publishedAtMillis = parseInstant(createdAt)?.toEpochMilliseconds(),
            pageUrl = pageUrl
        )
    }

    private suspend fun resolveHistorySource(endpointMetadata: EndpointMetadata): HistorySource {
        val endpointOwner = endpointMetadata.owner?.trim().takeIf { !it.isNullOrBlank() }
        val endpointRepo = endpointMetadata.repo?.trim().takeIf { !it.isNullOrBlank() }
        val metadataOwner = metadata.ownerName?.trim().takeIf { !it.isNullOrBlank() }
        val metadataRepo = metadata.repoName?.trim().takeIf { !it.isNullOrBlank() }
        val snapshot = metadata.bundleId.takeIf { it > 0 }?.let { bundleId ->
            api.getBundleById(bundleId, externalBundlesHost()).getOrNull()
        }
        val snapshotOwner = snapshot?.ownerName?.trim().takeIf { !it.isNullOrBlank() }
        val snapshotRepo = snapshot?.repoName?.trim().takeIf { !it.isNullOrBlank() }
        val owner = endpointOwner ?: metadataOwner ?: snapshotOwner ?: ""
        val repo = endpointRepo ?: metadataRepo ?: snapshotRepo ?: ""
        val sourceUrl = snapshot?.sourceUrl?.trim()?.takeIf { it.isNotBlank() }
        val repoUrl = when {
            owner.isNotBlank() && repo.isNotBlank() -> "https://github.com/$owner/$repo"
            else -> inferGitHubRepoUrl(sourceUrl, endpoint, metadata.downloadUrl)
        }

        if (snapshot != null && (metadata.ownerName.isNullOrBlank() || metadata.repoName.isNullOrBlank())) {
            metadata = metadata.copy(
                ownerName = snapshotOwner ?: metadata.ownerName,
                repoName = snapshotRepo ?: metadata.repoName
            )
            ExternalBundleMetadataStore.write(directory, metadata)
        }

        return HistorySource(
            owner = owner,
            repo = repo,
            repoUrl = repoUrl,
            sourceUrl = sourceUrl
        )
    }

    private suspend fun fetchExternalHistory(
        source: HistorySource,
        prerelease: Boolean?,
        limit: Int
    ): List<PatchBundleChangelogEntry> {
        if (source.owner.isBlank() || source.repo.isBlank()) return emptyList()
        return api.getBundleHistory(source.owner, source.repo, prerelease = prerelease, limit = limit, sourceUrl = metadata.sourceUrl)
            .getOrNull()
            .orEmpty()
            .map { snapshot -> snapshot.toChangelogEntry() }
    }

    private fun parseEndpointMetadata(): EndpointMetadata {
        val candidates = listOfNotNull(endpoint, metadata.downloadUrl)
        for (candidate in candidates) {
            val parsed = runCatching { Url(candidate) }.getOrNull() ?: continue
            val segments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }
            if (parsed.encodedPath.equals(ExternalBundlesEndpoints.V3_BUNDLE_PATH, ignoreCase = true)) {
                val sourceUrl = parsed.parameters["source_url"]
                val source = sourceUrl?.let { runCatching { Url(it) }.getOrNull() }
                val sourceSegments = source?.encodedPath
                    ?.trim('/')
                    ?.split('/')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val (hasExplicitChannel, prerelease) = when (parsed.parameters["channel"]?.lowercase()) {
                    "any" -> true to null
                    "stable" -> true to false
                    "prerelease" -> true to true
                    else -> false to null
                }
                return EndpointMetadata(
                    owner = sourceSegments.dropLast(1).joinToString("/").takeIf { it.isNotBlank() },
                    repo = sourceSegments.lastOrNull(),
                    prerelease = prerelease,
                    hasExplicitChannel = hasExplicitChannel,
                    isLatestApiEndpoint = parsed.parameters["version"].equals("latest", ignoreCase = true)
                )
            }
            if (segments.size >= 5 &&
                segments[0].equals("api", ignoreCase = true) &&
                (segments[1].equals("v1", ignoreCase = true) || segments[1].equals("v2", ignoreCase = true)) &&
                segments[2].equals("bundle", ignoreCase = true)
            ) {
                val owner = segments[3]
                val repo = segments[4]
                val (hasExplicitChannel, prerelease) = if (segments[1].equals("v2", ignoreCase = true)) {
                    when (parsed.parameters["channel"]?.lowercase()) {
                        "any" -> true to null
                        "stable" -> true to false
                        "prerelease" -> true to true
                        else -> false to null
                    }
                } else {
                    when (parsed.parameters["prerelease"]?.lowercase()) {
                        "true" -> true to true
                        "false" -> true to false
                        else -> false to null
                    }
                }
                return EndpointMetadata(
                    owner = owner,
                    repo = repo,
                    prerelease = prerelease,
                    hasExplicitChannel = hasExplicitChannel,
                    isLatestApiEndpoint = segments[1].equals("v2", ignoreCase = true) &&
                        segments.getOrNull(5).equals("latest", ignoreCase = true)
                )
            }
        }
        return EndpointMetadata(
            owner = null,
            repo = null,
            prerelease = null,
            hasExplicitChannel = false,
            isLatestApiEndpoint = false
        )
    }

    private fun externalBundlesHost(): String {
        for (candidate in listOfNotNull(endpoint, metadata.downloadUrl)) {
            val host = runCatching { Url(candidate).host }.getOrNull() ?: continue
            if (ExternalBundlesEndpoints.isExternalBundlesHost(host)) return host
        }
        return ExternalBundlesEndpoints.STABLE_HOST
    }

    private fun safeArtifactUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (isExternalBundleApiEndpoint(trimmed)) return null
        return trimmed
    }

    private fun isExternalBundleApiEndpoint(raw: String): Boolean {
        val parsed = runCatching { Url(raw) }.getOrNull() ?: return false
        val host = parsed.host.lowercase()
        val isExternalBundlesHost = ExternalBundlesEndpoints.isExternalBundlesHost(host)
        if (!isExternalBundlesHost) return false
        val pathNoQuery = parsed.encodedPath.substringBefore('?').substringBefore('#')
        return ExternalBundlesEndpoints.isBundleApiPath(pathNoQuery)
    }

    private fun parseCreatedAt(raw: String?): LocalDateTime {
        val trimmed = raw?.trim().orEmpty()
        val instantParsed = runCatching { Instant.parse(trimmed).toLocalDateTime(TimeZone.UTC) }.getOrNull()
        if (instantParsed != null) return instantParsed
        val localParsed = runCatching { LocalDateTime.parse(trimmed) }.getOrNull()
        return localParsed ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }

    private fun pickLatestSnapshot(
        release: ExternalBundleSnapshot?,
        prerelease: ExternalBundleSnapshot?
    ): ExternalBundleSnapshot? {
        if (release == null) return prerelease
        if (prerelease == null) return release

        val releaseInstant = snapshotInstant(release)
        val prereleaseInstant = snapshotInstant(prerelease)
        if (releaseInstant == null && prereleaseInstant == null) return prerelease
        if (releaseInstant == null) return prerelease
        if (prereleaseInstant == null) return release
        return if (prereleaseInstant > releaseInstant) prerelease else release
    }

    private fun snapshotInstant(snapshot: ExternalBundleSnapshot): Instant? =
        parseInstant(snapshot.repoPushedAt)
            ?: parseInstant(snapshot.lastRefreshedAt)
            ?: parseInstant(snapshot.createdAt)

    private fun parseInstant(raw: String?): Instant? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { Instant.parse(trimmed) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(trimmed).toInstant(TimeZone.UTC) }.getOrNull()
    }

    private fun pickNewestOfficialAsset(
        release: ReVancedAsset?,
        prerelease: ReVancedAsset?
    ): ReVancedAsset? {
        if (release == null) return prerelease
        if (prerelease == null) return release
        return if (prerelease.createdAt > release.createdAt) prerelease else release
    }
}

private data class CachedChangelog(val asset: ReVancedAsset, val timestamp: Long)
private data class CachedChangelogHistory(
    val entries: List<PatchBundleChangelogEntry>,
    val timestamp: Long
)

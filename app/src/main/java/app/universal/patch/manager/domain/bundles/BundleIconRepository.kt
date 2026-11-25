package app.universal.patch.manager.domain.bundles

import android.app.Application
import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.network.utils.APIError
import app.universal.patch.manager.network.utils.APIResponse
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.network.utils.getOrThrow
import app.universal.patch.manager.util.isNetworkRateLimit
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

@Serializable
data class BundleIcon(
    val url: String? = null,
    val avatar: String? = null,
    val checkedAt: Long = 0,
    val issue: BundleIconIssue? = null
) {
    val hasImage: Boolean get() = url != null || avatar != null
}

@Serializable
enum class BundleIconIssue { NotProvided, LookupFailed, RateLimited }

/** Repository metadata and missing root images are checked at most once per day. */
class BundleIconRepository(
    private val app: Application,
    private val http: HttpService,
    private val api: ExternalBundlesApi
) {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, BundleIcon>()
    private val cacheDir = File(app.cacheDir, "bundle_icons").also { it.mkdirs() }

    suspend fun resolve(source: PatchBundleSource): BundleIcon = withContext(Dispatchers.IO) {
        val remote = source as? RemotePatchBundle ?: return@withContext BundleIcon()
        mutex.withLock {
            val external = ExternalBundleMetadataStore.read(source.bundleDirectory)
            val registrySource = registryBundleIconSource(
                remote.endpoint,
                external?.sourceUrl,
                external?.ownerName,
                external?.repoName,
                external?.isPrerelease,
                external?.downloadUrl
            )
            if (bundleRepositoryUrl(registrySource?.sourceUrl, external?.sourceUrl, remote.endpoint, external?.downloadUrl)
                    .equals(OFFICIAL_PATCH_REPOSITORY, true)) return@withLock BundleIcon(OFFICIAL_ICON_URL)
            val externalIcon = external?.iconUrl?.trim()?.takeIf(String::isNotBlank)
            val externalAvatar = external?.ownerAvatarUrl?.trim()?.takeIf(String::isNotBlank)
            if (externalIcon != null || externalAvatar != null) {
                return@withLock BundleIcon(externalIcon, externalAvatar)
            }
            // Older versions cached an empty repository lookup for migrated API URLs.
            val key = if (registrySource != null) "registry-icons-v3|${remote.endpoint}" else remote.endpoint
            val file = File(cacheDir, MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) } + ".json")
            val cached = memory[key] ?: runCatching {
                http.json.decodeFromString<BundleIcon>(file.readText())
            }.getOrNull()
            val now = System.currentTimeMillis()
            if (cached != null && (cached.hasImage || cached.issue != null) &&
                now - cached.checkedAt in 0 until CACHE_TTL_MS
            ) return@withLock cached
            var fallback = cached
            val icon = try {
                if (registrySource != null || source is ExternalGraphqlPatchBundle) {
                    val sourceUrl = registrySource?.sourceUrl ?: external?.sourceUrl
                        ?: runCatching { Url(remote.endpoint).parameters["source_url"] }.getOrNull()
                        ?: bundleRepositoryUrl(external?.downloadUrl)
                        ?: external?.takeIf { !it.ownerName.isNullOrBlank() && !it.repoName.isNullOrBlank() }
                            ?.let { "https://github.com/${it.ownerName!!.trim()}/${it.repoName!!.trim()}" }
                    val prerelease = if (registrySource != null) registrySource.prerelease else external?.isPrerelease
                    val bundle = sourceUrl?.let {
                        val preferred = api.getLatestBundleForSource(it, prerelease)
                        if (prerelease == null) preferred.getOrThrow()
                        else preferred.getOrNull() ?: run {
                            val latest = api.getLatestBundleForSource(it, null)
                            latest.getOrNull() ?: if (preferred is APIResponse.Success) {
                                latest.getOrThrow()
                            } else {
                                preferred.getOrThrow()
                            }
                        }
                    }
                    if (bundle != null) {
                        val newIcon = bundle.iconUrl?.trim()?.takeIf(String::isNotBlank)
                        val newAvatar = bundle.ownerAvatarUrl?.trim()?.takeIf(String::isNotBlank)
                        BundleIcon(newIcon, newAvatar, now)
                    } else {
                        (cached ?: BundleIcon()).copy(
                            checkedAt = now - CACHE_TTL_MS + FAILURE_TTL_MS,
                            issue = BundleIconIssue.NotProvided
                        )
                    }
                } else {
                    withTimeoutOrNull(ExternalBundlesEndpoints.HOST_QUERY_TIMEOUT_MS) {
                        fromRepository(bundleRepositoryUrl(remote.endpoint, external?.sourceUrl, external?.downloadUrl) ?: remote.endpoint,
                            now) { avatar ->
                            // Keep the fetched avatar if the root-image probe fails or times out.
                            fallback = BundleIcon(cached?.url, avatar ?: cached?.avatar)
                        } ?: BundleIcon(checkedAt = now)
                    } ?: throw IllegalStateException("Repository image lookup timed out")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val issue = if (error.isNetworkRateLimit()) {
                    BundleIconIssue.RateLimited
                } else {
                    BundleIconIssue.LookupFailed
                }
                (fallback ?: BundleIcon()).copy(
                    checkedAt = now - CACHE_TTL_MS + FAILURE_TTL_MS,
                    issue = issue
                )
            }.let { result ->
                if (!result.hasImage && result.issue == null) {
                    result.copy(issue = BundleIconIssue.NotProvided)
                } else result
            }
            // A temporary fallback must not bypass the next repository lookup.
            if (icon.hasImage && icon.issue == null) {
                if (external != null && (external.iconUrl.isNullOrBlank() || external.ownerAvatarUrl.isNullOrBlank())) {
                    val updated = external.copy(
                        iconUrl = icon.url?.trim()?.takeIf(String::isNotBlank) ?: external.iconUrl?.trim()?.takeIf(String::isNotBlank),
                        ownerAvatarUrl = icon.avatar?.trim()?.takeIf(String::isNotBlank) ?: external.ownerAvatarUrl?.trim()?.takeIf(String::isNotBlank)
                    )
                    ExternalBundleMetadataStore.write(source.bundleDirectory, updated)
                }
            }
            memory[key] = icon
            runCatching { file.writeText(http.json.encodeToString(icon)) }
            icon
        }
    }

    private suspend fun fromRepository(
        endpoint: String,
        now: Long,
        onAvatar: (String?) -> Unit
    ): BundleIcon? {
        val parsed = RepositoryReleaseSourceParser.parse(endpoint) ?: run {
            val url = runCatching { Url(endpoint) }.getOrNull()
            if (url?.host == "gitlab.com") RepositoryReleaseSource.GitLab(
                url.encodedPath.trim('/').removeSuffix(".git")
            ) else null
        } ?: return null
        val repoUrl = when (parsed) {
            is RepositoryReleaseSource.GitHub -> parsed.repositoryUrl
            is RepositoryReleaseSource.GitLab -> "${parsed.origin}/${parsed.repositoryPath}"
            is RepositoryReleaseSource.Forgejo -> parsed.repositoryUrl
        }
        val repositoryPath = Url(repoUrl).encodedPath.trim('/')
        val metadataUrl = when (parsed) {
            is RepositoryReleaseSource.GitHub -> "https://api.github.com/repos/$repositoryPath"
            is RepositoryReleaseSource.GitLab ->
                "${parsed.origin}/api/v4/projects/" + URLEncoder.encode(repositoryPath, "UTF-8")
            is RepositoryReleaseSource.Forgejo -> parsed.apiUrl
        }
        val response = http.http.get(metadataUrl)
        if (!response.status.isSuccess()) {
            throw APIError(response.status, response.bodyAsText())
        }
        val data = http.json.parseToJsonElement(response.bodyAsText()).jsonObject
        val branch = data["default_branch"]?.jsonPrimitive?.contentOrNull ?: return null
        val avatar = if (parsed is RepositoryReleaseSource.GitLab) {
            data["namespace"]?.jsonObject?.get("avatar_url")?.jsonPrimitive?.contentOrNull
                ?: data["avatar_url"]?.jsonPrimitive?.contentOrNull
        } else {
            data["owner"]?.jsonObject?.get("avatar_url")?.jsonPrimitive?.contentOrNull
        }
        onAvatar(avatar)
        val encodedBranch = URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val rootImage = when (parsed) {
            is RepositoryReleaseSource.GitHub ->
                "https://raw.githubusercontent.com/$repositoryPath/$encodedBranch/patches-bundle.png"
            is RepositoryReleaseSource.GitLab -> "$repoUrl/-/raw/$encodedBranch/patches-bundle.png"
            is RepositoryReleaseSource.Forgejo -> "$repoUrl/raw/branch/$encodedBranch/patches-bundle.png"
        }
        val imageResponse = http.http.head(rootImage)
        if (!imageResponse.status.isSuccess() && imageResponse.status != HttpStatusCode.NotFound) {
            throw APIError(imageResponse.status, null)
        }
        return BundleIcon(rootImage.takeIf { imageResponse.status.isSuccess() }, avatar, now)
    }

    companion object {
        // ReVanced/revanced-branding@73b3a5605e20f140994ecc08d232d6d921fa4bcf
        // assets/revanced-logo/revanced-logo.svg
        const val OFFICIAL_ICON_URL = "file:///android_asset/revanced-bundle.svg"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val FAILURE_TTL_MS = 60 * 60 * 1000L
    }
}

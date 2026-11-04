package app.universal.patch.manager.network.api

import android.net.Uri
import android.os.Build
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.network.dto.*
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.network.utils.APIResponse
import app.universal.patch.manager.network.utils.APIFailure
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.util.isManagerReleaseAfter
import app.universal.patch.manager.util.managerReleaseRequiresMigration
import app.universal.patch.manager.util.parseManagerReleaseAssetName
import app.universal.patch.manager.util.pickManagerReleaseAssetName
import io.ktor.client.request.header
import io.ktor.client.request.url
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.runCatching

class UpmAPI(
    private val client: HttpService,
    private val prefs: PreferencesManager
) {
    private data class RepoConfig(
        val owner: String,
        val name: String,
        val apiBase: String,
        val htmlUrl: String,
    )

    private fun repoConfig(): RepoConfig = parseRepoUrl(MANAGER_REPO_URL)

    private fun parseRepoUrl(raw: String): RepoConfig {
        val trimmed = raw.removeSuffix("/")
        return when {
            trimmed.startsWith("https://github.com/") -> {
                val repoPath = trimmed.removePrefix("https://github.com/").removeSuffix(".git")
                val parts = repoPath.split("/").filter { it.isNotBlank() }
                require(parts.size >= 2) { "Invalid GitHub repository URL: $raw" }
                val owner = parts[0]
                val name = parts[1]
                RepoConfig(
                    owner = owner,
                    name = name,
                    apiBase = "https://api.github.com/repos/$owner/$name",
                    htmlUrl = "https://github.com/$owner/$name"
                )
            }

            trimmed.startsWith("https://api.github.com/") -> {
                val repoPath = trimmed.removePrefix("https://api.github.com/").trim('/').removeSuffix(".git")
                val parts = repoPath.split("/").filter { it.isNotBlank() }
                val reposIndex = parts.indexOf("repos")
                val owner = parts.getOrNull(reposIndex + 1) ?: throw IllegalArgumentException("Invalid GitHub API URL: $raw")
                val name = parts.getOrNull(reposIndex + 2) ?: throw IllegalArgumentException("Invalid GitHub API URL: $raw")
                RepoConfig(
                    owner = owner,
                    name = name,
                    apiBase = "https://api.github.com/repos/$owner/$name",
                    htmlUrl = "https://github.com/$owner/$name"
                )
            }

            else -> throw IllegalArgumentException("Unsupported repository URL: $raw")
        }
    }

    private suspend inline fun <reified T> githubRequest(config: RepoConfig, path: String): APIResponse<T> {
        val normalizedPath = path.trimStart('/')
        val pat = prefs.gitHubPat.get()
        return client.request {
            pat.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            url("${config.apiBase}/$normalizedPath")
        }
    }

    private suspend fun apiUrl(): String = prefs.api.get().trim().removeSuffix("/")

    private suspend inline fun <reified T> apiRequest(
        route: String,
        version: String = "v4"
    ): APIResponse<T> {
        val normalizedRoute = route.trimStart('/')
        val baseUrl = apiUrl()
        return client.request {
            url("$baseUrl/$version/$normalizedRoute")
        }
    }

    private suspend inline fun <reified T> apiRequestWithFallback(
        vararg attempts: suspend () -> APIResponse<T>
    ): APIResponse<T> {
        var lastResponse: APIResponse<T>? = null
        attempts.forEach { attempt ->
            val response = attempt()
            if (response is APIResponse.Success) {
                return response
            }
            lastResponse = response
        }

        return lastResponse ?: APIResponse.Failure(
            APIFailure(IllegalStateException("No API request attempts were configured"), null)
        )
    }

    private suspend fun fetchReleaseAsset(
        config: RepoConfig,
        includePrerelease: Boolean,
        matcher: (GitHubAsset) -> Boolean,
        pickAsset: (List<GitHubAsset>) -> GitHubAsset? = { assets ->
            assets.firstOrNull()
        },
        publishedAfter: Instant? = null
    ): APIResponse<ReVancedAsset> {
        return when (val releasesResponse = githubRequest<List<GitHubRelease>>(config, "releases")) {
            is APIResponse.Success -> {
                val mapped = runCatching {
                    val releaseAndAsset = releasesResponse.data
                        .asSequence()
                        .filter { release -> !release.draft && (includePrerelease || !release.prerelease) }
                        .filter { release ->
                            isManagerReleaseAfter(release.publishedAt, publishedAfter?.toEpochMilliseconds())
                        }
                        .mapNotNull { release ->
                            val matchingAssets = release.assets.filter(matcher)
                            pickAsset(matchingAssets)?.let { asset -> release to asset }
                        }
                        .firstOrNull()
                        ?: throw IllegalStateException("No compatible manager release asset found for this device ABI")
                    val (release, asset) = releaseAndAsset
                    mapReleaseToAsset(config, release, asset)
                }

                mapped.fold(
                    onSuccess = { APIResponse.Success(it) },
                    onFailure = { APIResponse.Failure(APIFailure(it, null)) }
                )
            }

            is APIResponse.Error -> APIResponse.Error(releasesResponse.error)
            is APIResponse.Failure -> APIResponse.Failure(releasesResponse.error)
        }
    }

    private fun mapReleaseToAsset(
        config: RepoConfig,
        release: GitHubRelease,
        asset: GitHubAsset
    ): ReVancedAsset {
        val timestamp = release.publishedAt ?: release.createdAt
        require(timestamp != null) { "Release ${release.tagName} does not contain a timestamp" }
        val createdAt = Instant.parse(timestamp).toLocalDateTime(TimeZone.UTC)
        val signatureUrl = findSignatureUrl(release, asset)
        val description = release.body?.ifBlank { release.name.orEmpty() } ?: release.name.orEmpty()

        return ReVancedAsset(
            downloadUrl = asset.downloadUrl,
            createdAt = createdAt,
            signatureDownloadUrl = signatureUrl,
            pageUrl = "${config.htmlUrl}/releases/tag/${release.tagName}",
            description = description,
            version = release.tagName
        )
    }

    private fun findSignatureUrl(release: GitHubRelease, asset: GitHubAsset): String? {
        val base = asset.name.substringBeforeLast('.', asset.name)
        val candidates = listOf(
            "${asset.name}.sig",
            "${asset.name}.asc",
            "$base.sig",
            "$base.asc"
        )
        return release.assets.firstOrNull { it.name in candidates }?.downloadUrl
    }

    private fun isManagerAsset(asset: GitHubAsset): Boolean =
        parseManagerReleaseAssetName(asset.name) != null

    private fun pickManagerAsset(assets: List<GitHubAsset>): GitHubAsset? {
        val name = pickManagerReleaseAssetName(assets.map { it.name }, Build.SUPPORTED_ABIS.toList())
            ?: return null
        return assets.firstOrNull { it.name == name }
    }

    suspend fun getLatestAppInfo(publishedAfter: Instant? = null): APIResponse<ReVancedAsset> {
        val config = repoConfig()
        val includePrerelease =
            prefs.useManagerPrereleasesForVersion(BuildConfig.VERSION_NAME)
        return fetchReleaseAsset(config, includePrerelease, ::isManagerAsset, ::pickManagerAsset, publishedAfter)
    }

    suspend fun getAppUpdate(): ReVancedAsset? {
        // PR and release builds now use separate storage profiles.
        return getLatestAppInfo()
            .getOrNull()
            ?.takeIf {
                BuildConfig.IS_PR_TEST_BUILD ||
                    managerReleaseRequiresMigration(BuildConfig.APPLICATION_ID, it.downloadUrl) ||
                    normalizeManagerVersion(it.version) != normalizeManagerVersion(BuildConfig.VERSION_NAME)
            }
    }

    private fun normalizeManagerVersion(version: String): String {
        val withoutPrefix = version.removePrefix("v")
        return LEGACY_MANAGER_BUILD_PROFILES.firstOrNull { profile ->
            withoutPrefix.endsWith("-$profile", ignoreCase = true)
        }?.let { profile ->
            withoutPrefix.removeSuffix("-$profile")
        } ?: withoutPrefix
    }

    suspend fun getPatchesUpdate(prerelease: Boolean): APIResponse<ReVancedAsset> {
        val currentRoute = if (prerelease) "patches/prerelease" else "patches"
        val legacyRoute = "patches?prerelease=$prerelease"

        return apiRequestWithFallback(
            { apiRequest(currentRoute, version = "v5") },
            { apiRequest(legacyRoute, version = "v5") },
            { apiRequest(legacyRoute, version = "v4") }
        )
    }

    suspend fun getAnnouncements(): APIResponse<List<ReVancedAnnouncement>> =
        apiRequest("announcements", version = "v5")

    suspend fun getAnnouncementTags(): APIResponse<List<ReVancedAnnouncementTag>> =
        apiRequest("announcements/tags")

    suspend fun getRepositoryReleaseHistory(
        repoUrl: String,
        prerelease: Boolean? = null,
        limit: Int = 20,
        tagPrefix: String? = null
    ): APIResponse<List<GitHubRelease>> {
        val config = runCatching { parseRepoUrl(repoUrl) }
            .getOrElse { return APIResponse.Failure(APIFailure(it, null)) }
        val targetLimit = limit.coerceAtLeast(1)
        val perPage = targetLimit.coerceIn(20, 100)
        val releases = mutableListOf<GitHubRelease>()
        var page = 1

        while (releases.size < targetLimit) {
            when (val response = githubRequest<List<GitHubRelease>>(config, "releases?per_page=$perPage&page=$page")) {
                is APIResponse.Success -> {
                    val batch = response.data
                    releases += batch.filter { release ->
                        !release.draft && (prerelease == null || release.prerelease == prerelease) &&
                            (tagPrefix == null || release.tagName.startsWith(tagPrefix))
                    }
                    if (batch.size < perPage) break
                    page += 1
                }
                is APIResponse.Error -> return APIResponse.Error(response.error)
                is APIResponse.Failure -> return APIResponse.Failure(response.error)
            }
        }

        return APIResponse.Success(releases.take(targetLimit))
    }

    suspend fun getRepositoryReleaseByTag(
        repoUrl: String,
        tag: String,
    ): APIResponse<GitHubRelease> {
        val config = runCatching { parseRepoUrl(repoUrl) }
            .getOrElse { return APIResponse.Failure(APIFailure(it, null)) }
        return githubRequest(config, "releases/tags/${Uri.encode(tag)}")
    }

    suspend fun getContributors(): APIResponse<List<ReVancedGitRepository>> {
        val config = repoConfig()
        return when (val response = githubRequest<List<GitHubContributor>>(config, "contributors")) {
            is APIResponse.Success -> {
                val contributors = response.data.map {
                    ReVancedContributor(username = it.login, avatarUrl = it.avatarUrl)
                }
                APIResponse.Success(
                    listOf(
                        ReVancedGitRepository(
                            name = config.name,
                            url = config.htmlUrl,
                            contributors = contributors
                        )
                    )
                )
            }

            is APIResponse.Error -> APIResponse.Error(response.error)
            is APIResponse.Failure -> APIResponse.Failure(response.error)
        }
    }

    suspend fun getAssetFromPullRequest(owner: String, repo: String, pullRequestNumber: String): ReVancedAsset {
        suspend fun getPullWithRun(
            pullRequestNumber: String,
            config: RepoConfig
        ): GitHubActionRun {
            val pull = githubRequest<GitHubPullRequest>(config, "pulls/$pullRequestNumber")
                .successOrThrow("PR #$pullRequestNumber")

            val targetSha = pull.head.sha

            var page = 1
            while (true) {
                val actionsRuns = githubRequest<GitHubActionRuns>(
                    config,
                    "actions/runs?per_page=100&page=$page"
                ).successOrThrow("Workflow runs for PR #$pullRequestNumber (page $page)")

                val match = actionsRuns.workflowRuns.firstOrNull { it.headSha == targetSha }
                if (match != null) return match

                if (actionsRuns.workflowRuns.isEmpty())
                    throw Exception("No GitHub Actions run found for PR #$pullRequestNumber with SHA $targetSha")

                page++
            }
        }

        val config = RepoConfig(
            owner = owner,
            name = repo,
            apiBase = "https://api.github.com/repos/$owner/$repo",
            htmlUrl = "https://github.com/$owner/$repo"
        )

        val currentRun = getPullWithRun(pullRequestNumber, config)

        val artifacts = githubRequest<GitHubActionRunArtifacts>(
            config,
            "actions/runs/${currentRun.id}/artifacts"
        )
            .successOrThrow("PR artifacts for PR #$pullRequestNumber")
            .artifacts

        val artifact = artifacts.firstOrNull()
            ?: throw Exception("The lastest commit in this PR didn't have any artifacts. Did the GitHub action run correctly?")

        return ReVancedAsset(
            downloadUrl = artifact.archiveDownloadUrl,
            createdAt = Instant.parse(artifact.createdAt).toLocalDateTime(TimeZone.UTC),
            pageUrl = "${config.htmlUrl}/pull/$pullRequestNumber",
            description = currentRun.displayTitle,
            version = currentRun.headSha
        )
    }
}

fun <T> APIResponse<T>.successOrThrow(context: String): T {
    return when (this) {
        is APIResponse.Success -> data
        is APIResponse.Error -> throw Exception("Failed fetching $context: ${error.message}", error)
        is APIResponse.Failure -> throw Exception("Failed fetching $context: ${error.message}", error)
    }
}

private const val MANAGER_REPO_URL = "https://github.com/Jman-Github/Universal-Patch-Manager"
private val LEGACY_MANAGER_BUILD_PROFILES = listOf("LITE", "FULL")

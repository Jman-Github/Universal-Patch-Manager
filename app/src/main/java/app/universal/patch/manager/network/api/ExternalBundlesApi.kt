package app.universal.patch.manager.network.api

import android.app.Application
import app.universal.patch.manager.R
import app.universal.patch.manager.network.dto.*
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.network.utils.APIFailure
import app.universal.patch.manager.network.utils.APIResponse
import app.universal.patch.manager.network.utils.getOrNull
import app.universal.patch.manager.network.utils.getOrThrow
import app.universal.patch.manager.network.utils.transform
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ExternalBundlesPage(
    val bundles: List<ExternalBundleSnapshot>,
    val apiHost: String,
    val refreshJob: RefreshJobNode?
)

@Serializable
data class CatalogFilters(
    val query: String = "",
    val packageName: String = "",
    val sourceUrl: String = "",
    val version: String = "",
    val bundleType: String = "",
    val release: String = "latest",
    val sort: String = "updated_desc",
    val exactPackage: Boolean = false
) {
    fun bundleWhere(currentOnly: Boolean = true) = buildJsonObject {
        if ((currentOnly && sourceUrl.isBlank()) || release == "latest") put("is_latest", comparison(true))
        if (sourceUrl.isNotBlank()) put("source", buildJsonObject { put("url", comparison(sourceUrl)) })
        if (version.isNotBlank()) put("version", comparison(version))
        if (bundleType.isNotBlank()) put("bundle_type", comparison(bundleType))
        if (release == "release" || release == "prerelease") {
            put("is_prerelease", comparison(release == "prerelease"))
        }
        if (packageName.isNotBlank()) {
            put("metadata_status", comparison("verified"))
            put("patches", buildJsonObject {
                put("metadata_status", comparison("verified"))
                put("patch_packages", buildJsonObject {
                    put("package", buildJsonObject {
                        val parts = packageName.split('@', limit = 2)
                        put("name", if (exactPackage) comparison(parts[0]) else comparison(parts[0], "_ilike", wildcard = true))
                        parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                            put("_or", buildJsonArray {
                                add(buildJsonObject { put("version", comparison(it)) })
                                add(buildJsonObject { put("version", buildJsonObject { put("_is_null", true) }) })
                            })
                        }
                    })
                })
            })
        }
        if (query.isNotBlank()) put("_or", buildJsonArray {
            add(buildJsonObject { put("version", comparison(query, "_ilike", true)) })
            add(buildJsonObject { put("source", buildJsonObject {
                put("url", comparison(query, "_ilike", true))
            }) })
            add(buildJsonObject { put("source", buildJsonObject {
                put("source_metadatum", buildJsonObject {
                    put("repo_description", comparison(query, "_ilike", true))
                })
            }) })
        })
    }
}

internal fun CatalogFilters.patchWhere(): JsonObject {
    val where = bundleWhere()
    return buildJsonObject {
        put("metadata_status", comparison("verified"))
        where["patches"]?.jsonObject?.get("patch_packages")?.let { put("patch_packages", it) }
        put("bundle", JsonObject(where.filterKeys { it != "patches" }))
    }
}

private fun comparison(value: String, operator: String = "_eq", wildcard: Boolean = false) =
    buildJsonObject {
        val escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        put(operator, if (wildcard) "%$escaped%" else value)
    }

private fun comparison(value: Boolean) = buildJsonObject { put("_eq", value) }

private fun sourceUrlComparison(url: String): JsonObject {
    val clean = url.trim().removeSuffix("/")
    val base = clean.removeSuffix(".git").removeSuffix("/")
    val altSchemeClean = when {
        clean.startsWith("https://", ignoreCase = true) -> "http://" + clean.substring(8)
        clean.startsWith("http://", ignoreCase = true) -> "https://" + clean.substring(7)
        else -> null
    }
    val altSchemeBase = altSchemeClean?.removeSuffix(".git")?.removeSuffix("/")
    val variants = linkedSetOf<String>().apply {
        listOfNotNull(
            clean, "$clean/", base, "$base/", "$base.git", "$base.git/",
            altSchemeClean, altSchemeClean?.let { "$it/" },
            altSchemeBase, altSchemeBase?.let { "$it/" }, altSchemeBase?.let { "$it.git" }, altSchemeBase?.let { "$it.git/" }
        ).forEach {
            add(it)
            add(it.lowercase())
        }
    }.filter(String::isNotBlank)
    return buildJsonObject {
        put("_in", buildJsonArray {
            variants.forEach { add(JsonPrimitive(it)) }
        })
    }
}

/** Read-only registry queries. Pages and details share a bounded, persistent cache. */
class ExternalBundlesApi(private val client: HttpService, private val app: Application) {
    private val cacheDir = File(app.cacheDir, "registry_graphql").also { it.mkdirs() }
    private val requestMutex = Mutex()
    private val discoveryMutex = Mutex()
    private val discoveryIndexes = linkedMapOf<String, Pair<Long, List<BundleNode>>>()
    private val discoveryGenerations = mutableMapOf<String, String?>()
    private val compatibilityCache = linkedMapOf<String, Pair<Long, Set<Int>>>()
    private val failedRequests = linkedMapOf<String, Pair<Long, String>>()
    private var retryAt = 0L
    private val appsIndex = CatalogAppsIndex { query, variables, force, identity, budget ->
        graphql<JsonObject>(query, variables, forceRefresh = force,
            cacheIdentity = identity, requestBudget = budget)
    }

    suspend fun getBundles(
        packageNameQuery: String? = null,
        limit: Int = 30,
        offset: Int = 0,
        filters: CatalogFilters = CatalogFilters(packageName = packageNameQuery.orEmpty()),
        forceRefresh: Boolean = false
    ): APIResponse<ExternalBundlesPage> {
        val result = readBundles(limit, offset, filters, forceRefresh)
        if (offset == 0 && !forceRefresh) {
            try {
                result.getOrThrow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (failure.message.orEmpty().contains("Catalog changed", true)) {
                    return readBundles(limit, offset, filters, true)
                }
            }
        }
        return result
    }

    private suspend fun readBundles(limit: Int, offset: Int, filters: CatalogFilters,
        forceRefresh: Boolean): APIResponse<ExternalBundlesPage> {
        return withContext(Dispatchers.IO) {
            discoveryMutex.withLock {
                try {
                    // Reuse a light index while the user edits filters instead of repeatedly
                    // asking the worker to search/sort its entire release history.
                    val scope = filters.copy(query = "", packageName = "", version = "",
                        bundleType = "", release = if (filters.sourceUrl.isBlank() ||
                            filters.release == "latest") "latest" else "all")
                    val reload = forceRefresh && offset == 0
                    val index = discoveryIndex(scope.bundleWhere(), reload)
                    var matches = index.filter(filters::matchesBundle)
                    if (filters.packageName.isNotBlank() && matches.isNotEmpty() && matches.all { it.catalogId != null }) {
                        val supported = appsIndex.matchingBundles(filters.copy(query = ""), matches,
                            discoveryGenerations[scope.bundleWhere().toString()], reload)
                        matches = matches.filter { it.id in supported }
                    } else if (filters.packageName.isNotBlank() && matches.isNotEmpty()) {
                        val names = matchingPackages(filters, reload)
                        val supported = mutableSetOf<Int>()
                        val budget = CatalogRequestBudget()
                        val groups = if (names.size <= 8) names.map { listOf(it) } else listOf(names)
                        for (group in groups) {
                            supported += compatibleBundleIds(group,
                                filters.packageName.substringAfter('@', "").takeIf(String::isNotBlank),
                                matches, reload, budget)
                        }
                        matches = matches.filter { it.id in supported }
                    }
                    val ordered = when {
                        filters.sort.contains("name", ignoreCase = true) -> matches.sortedWith(
                            compareBy<BundleNode> {
                                listOfNotNull(it.source?.sourceMetadata?.ownerName,
                                    it.source?.sourceMetadata?.repoName).joinToString("/").lowercase()
                            }.thenBy { it.id })
                        else -> matches.sortedWith(compareBy<BundleNode> { it.createdAt.orEmpty() }
                            .thenBy { it.id })
                    }.let { if (filters.sort.endsWith("desc", ignoreCase = true)) it.reversed() else it }
                    APIResponse.Success(ExternalBundlesPage(
                        ordered.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 100))
                            .map { it.toSnapshot() }, ExternalBundlesEndpoints.REGISTRY_HOST, null))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failure(error.message ?: app.getString(R.string.catalog_search_failed))
                }
            }
        }
    }

    private suspend fun discoveryIndex(where: JsonObject, force: Boolean): List<BundleNode> {
        val key = where.toString()
        discoveryIndexes[key]?.takeIf {
            !force && System.currentTimeMillis() - it.first in 0 until CACHE_TTL_MS
        }?.let { return it.second }
        val rows = mutableListOf<BundleNode>()
        var pageSize = 100
        var generation: String? = null
        while (true) {
            val page = try {
                queryBundles(where, pageSize, rows.size, forceRefresh = force,
                    sort = "id_asc", includeReleaseNotes = false).getOrThrow().let { data ->
                        check(generation == null || generation == data.catalogGeneration) {
                            "Catalog changed; refresh the catalog"
                        }
                        generation = data.catalogGeneration
                        data.bundle
                    }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (!error.message.orEmpty().contains("Cloudflare 1102") || pageSize <= 10) throw error
                pageSize = (pageSize / 2).coerceAtLeast(10)
                continue
            }
            rows += page
            if (page.size < pageSize) break
        }
        val result = rows.distinctBy { it.id }
        discoveryIndexes[key] = System.currentTimeMillis() to result
        discoveryGenerations[key] = generation
        while (discoveryIndexes.size > 8) {
            val oldest = discoveryIndexes.keys.first()
            discoveryIndexes.remove(oldest)
            discoveryGenerations.remove(oldest)
        }
        return result
    }

    private suspend fun matchingPackages(filters: CatalogFilters, force: Boolean): List<String> {
        if (filters.exactPackage) return listOf(filters.packageName.substringBefore('@'))
        val names = mutableListOf<String>()
        val lookup = filters.copy(query = "", packageName = filters.packageName.substringBefore('@'),
            sourceUrl = "", version = "", bundleType = "", release = "all")
        var offset = 0
        while (true) {
            val page = getApps(lookup, offset, force).getOrThrow()
            names += page.mapNotNull { it.name }
            if (page.size < 30) break
            offset += page.size
        }
        return names.distinct()
    }

    private suspend fun compatibleBundleIds(
        names: List<String>, appVersion: String?, candidates: List<BundleNode>, force: Boolean,
        budget: CatalogRequestBudget
    ): Set<Int> {
        if (names.isEmpty() || candidates.isEmpty()) return emptySet()
        val identity = candidates.joinToString("|") { "${it.id}:${it.fileHash}:${it.downloadUrl}:${it.hasCurrentPatchMetadata()}:${it.patchCount}" }
        val key = MessageDigest.getInstance("SHA-256").digest(
            "${names.joinToString("|")}|$appVersion|$identity".toByteArray())
            .joinToString("") { "%02x".format(it) }
        compatibilityCache[key]?.takeIf {
            !force && System.currentTimeMillis() - it.first in 0 until CACHE_TTL_MS
        }?.let { return it.second }
        suspend fun scan(ids: List<Int>): Set<Int> {
            if (ids.isEmpty()) return emptySet()
            val found = mutableSetOf<Int>()
            var offset = 0
            try {
                while (true) {
                    val rows = graphql<BundleIdsQueryData>(BUNDLE_IDS_QUERY, buildJsonObject {
                        put("where", scopedPackagePatchWhere(ids, names, appVersion))
                        put("package", names.singleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                        put("offset", offset)
                    }, forceRefresh = force, requestBudget = budget).getOrThrow().patch
                    found += rows.map { it.bundleId }
                    if (rows.size < 100) return found
                    offset += rows.size
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val budgetExceeded = error.message.orEmpty().let {
                    it.contains("too many shards", true) || it.contains("catalog budget", true) ||
                        it.contains("too many catalog files", true) || it.contains("Cloudflare 1102", true)
                }
                if (!budgetExceeded || ids.size <= 1) throw error
                // Split only when necessary; never fall back to an unscoped full-catalog scan.
                val middle = ids.size / 2
                return scan(ids.take(middle)) + scan(ids.drop(middle))
            }
        }
        val result = scan(candidates.filter { it.hasCurrentPatchMetadata() }.map { it.id })
        compatibilityCache[key] = System.currentTimeMillis() to result
        while (compatibilityCache.size > 128) compatibilityCache.remove(compatibilityCache.keys.first())
        return result
    }

    suspend fun getBundleById(
        bundleId: Int,
        apiHost: String = ExternalBundlesEndpoints.REGISTRY_HOST,
        forceRefresh: Boolean = false
    ): APIResponse<ExternalBundleSnapshot?> {
        // Numeric identities from older catalogs must never be looked up in the new registry.
        val host = apiHost.takeIf(ExternalBundlesEndpoints::isExternalBundlesHost)
            ?: ExternalBundlesEndpoints.REGISTRY_HOST
        return queryBundles(buildJsonObject { put("id", buildJsonObject { put("_eq", bundleId) }) },
            1, host = host, forceRefresh = forceRefresh).transform { it.bundle.firstOrNull()?.toSnapshot(host) }
    }

    suspend fun getLatestBundle(owner: String, repo: String, prerelease: Boolean) =
        latest(owner, repo, prerelease)

    suspend fun getLatestBundleAny(owner: String, repo: String) = latest(owner, repo, null)

    suspend fun getLatestBundleForSource(sourceUrl: String, prerelease: Boolean?) =
        getBundleForSource(sourceUrl, prerelease)

    suspend fun getBundleForSource(sourceUrl: String, prerelease: Boolean?, version: String = "latest") =
        queryBundles(buildJsonObject {
            put("source", buildJsonObject { put("url", sourceUrlComparison(sourceUrl)) })
            if (version.equals("latest", true)) {
                if (prerelease == null) put("is_latest", comparison(true))
            } else put("version", comparison(version))
            prerelease?.let { put("is_prerelease", comparison(it)) }
        }, 1).transform { it.bundle.firstOrNull()?.toSnapshot() }

    private suspend fun latest(owner: String, repo: String, prerelease: Boolean?) =
        queryBundles(buildJsonObject {
            put("source", sourceIdentity(owner, repo))
            if (prerelease == null) put("is_latest", comparison(true))
            prerelease?.let { put("is_prerelease", comparison(it)) }
        }, 1).transform { it.bundle.firstOrNull()?.toSnapshot() }

    suspend fun getBundleHistory(
        owner: String, repo: String, prerelease: Boolean? = null, limit: Int = 30,
        sourceUrl: String? = null,
        includeReleaseNotes: Boolean = true
    ): APIResponse<List<ExternalBundleSnapshot>> {
        val history = mutableListOf<ExternalBundleSnapshot>()
        val where = buildJsonObject {
            put("source", if (sourceUrl.isNullOrBlank()) sourceIdentity(owner, repo)
                else buildJsonObject { put("url", sourceUrlComparison(sourceUrl)) })
            prerelease?.let { put("is_prerelease", comparison(it)) }
        }
        while (history.size < limit) {
            // Each description opens a catalog file; leave room for shared indexes.
            val size = minOf(if (includeReleaseNotes) 30 else 100, limit - history.size)
            when (val page = queryBundles(where, size, history.size,
                includeReleaseNotes = includeReleaseNotes)) {
                is APIResponse.Success -> {
                    history += page.data.bundle.map { it.toSnapshot() }
                    if (page.data.bundle.size < size) break
                }
                is APIResponse.Error -> return APIResponse.Error(page.error)
                is APIResponse.Failure -> return APIResponse.Failure(page.error)
            }
        }
        return APIResponse.Success(history)
    }

    suspend fun getSources(forceRefresh: Boolean = false): APIResponse<List<SourceNode>> {
        val sources = mutableListOf<SourceNode>()
        while (true) {
            val page = graphql<SourcesQueryData>(SOURCES_QUERY, buildJsonObject {
                put("offset", sources.size)
            }, forceRefresh = forceRefresh)
            when (page) {
                is APIResponse.Success -> {
                    sources += page.data.source
                    if (page.data.source.size < 100) return APIResponse.Success(sources)
                }
                is APIResponse.Error -> return APIResponse.Error(page.error)
                is APIResponse.Failure -> return APIResponse.Failure(page.error)
            }
        }
    }

    suspend fun getApps(filters: CatalogFilters, offset: Int = 0, forceRefresh: Boolean = false):
        APIResponse<List<PackageNode>> {
        if (filters.release != "all" || filters.sourceUrl.isNotBlank() ||
            filters.bundleType.isNotBlank() || filters.version.isNotBlank() ||
            filters.packageName.substringAfter('@', "").isNotBlank()) {
            return appsIndex.getApps(filters, offset, forceRefresh && offset == 0)
        }
        val where = buildJsonObject {
            val q = filters.query.trim()
            if (q.isNotBlank()) put("_or", buildJsonArray {
                add(buildJsonObject { put("name", comparison(q, "_ilike", true)) })
                add(buildJsonObject { put("app_name", comparison(q, "_ilike", true)) })
            })
            if (filters.packageName.isNotBlank()) {
                val parts = filters.packageName.split('@', limit = 2)
                put("name", if (filters.exactPackage) comparison(parts[0])
                    else comparison(parts[0], "_ilike", true))
                parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { version ->
                    put("_and", buildJsonArray {
                        add(buildJsonObject { put("_or", buildJsonArray {
                            add(buildJsonObject { put("version", comparison(version)) })
                            add(buildJsonObject { put("version", buildJsonObject { put("_is_null", true) }) })
                        }) })
                    })
                }
            }
        }
        return graphql<AppsQueryData>(APPS_QUERY, buildJsonObject {
            put("where", where); put("offset", offset)
        }, forceRefresh = forceRefresh).transform { it.packages }
    }

    suspend fun getAppMetadata(names: List<String>): APIResponse<List<PackageNode>> {
        val result = mutableListOf<PackageNode>()
        for (chunk in names.distinct().sorted().chunked(100)) {
            when (val response = graphql<AppsQueryData>(APP_METADATA_QUERY, buildJsonObject {
                put("names", JsonArray(chunk.map(::JsonPrimitive)))
            })) {
                is APIResponse.Success -> result += response.data.packages
                is APIResponse.Error -> return APIResponse.Error(response.error)
                is APIResponse.Failure -> return APIResponse.Failure(response.error)
            }
        }
        return APIResponse.Success(result)
    }

    suspend fun getBundleReleasePage(sourceUrl: String, version: String, artifactUrl: String? = null): APIResponse<String?> {
        // An archived artifact may have a different tag from its manifest version.
        val catalogId = artifactUrl?.takeIf(String::isNotBlank)?.let { url ->
            graphql<HistoricalBundlesData>("""
                query ReleaseArtifact(${"$"}where: bundle_bool_exp!) {
                  catalog_generation
                  historical_bundles(where: ${"$"}where, limit: 1) { id catalog_id }
                }
            """, buildJsonObject {
                put("where", buildJsonObject { put("download_url", comparison(url)) })
            }).getOrNull()?.bundles?.firstOrNull()?.catalogId
        }
        if (catalogId == null && version.isBlank()) return APIResponse.Success(null)
        return graphql<HistoricalReleasesData>("""
            query BundleReleasePage(${"$"}source: String!, ${"$"}version: String, ${"$"}bundle: String) {
              historical_releases(source: ${"$"}source, version: ${"$"}version, bundle_id: ${"$"}bundle, limit: 1) {
                generation
                data { id release_url }
              }
            }
        """, buildJsonObject {
            put("source", sourceUrl)
            put("version", if (catalogId != null) JsonNull else JsonPrimitive(version))
            put("bundle", catalogId?.let(::JsonPrimitive) ?: JsonNull)
        }).transform { it.releases.data.firstOrNull()?.releaseUrl }
    }

    suspend fun getReleaseHistory(sourceUrl: String, after: String? = null,
        forceRefresh: Boolean = false): APIResponse<HistoricalReleasePage> =
        graphql<HistoricalReleasesData>(CatalogHistoryQueries.RELEASES, buildJsonObject {
            put("source", sourceUrl); put("after", after?.let(::JsonPrimitive) ?: JsonNull)
            put("limit", 20)
        }, forceRefresh = forceRefresh).transform { it.releases }

    suspend fun getHistoricalRelease(id: String, generation: String): APIResponse<HistoricalRelease> =
        catalogResult {
            val response = graphql<HistoricalReleaseData>(CatalogHistoryQueries.RELEASE,
                buildJsonObject { put("id", id) }, cacheIdentity = generation).getOrThrow()
            requireHistoryGeneration(response.generation, generation)
            val release = response.release ?: error(app.getString(R.string.catalog_release_metadata_unavailable))
            if (release.metadataDocumentUrl == null) return@catalogResult release
            // Large original release documents are byte chunks, not independent UTF-8 strings.
            val bytes = java.io.ByteArrayOutputStream()
            var offset = 0
            while (true) {
                val page = graphql<HistoricalDocumentData>(CatalogHistoryQueries.DOCUMENT,
                    buildJsonObject { put("id", id); put("offset", offset) },
                    cacheIdentity = generation).getOrThrow()
                requireHistoryGeneration(page.generation, generation)
                val document = page.release?.document ?: error(app.getString(R.string.catalog_release_document_unavailable))
                require(document.encoding == "base64" && document.totalBytes in 0..8 * 1024 * 1024) {
                    "Release document is too large to display"
                }
                val chunk = java.util.Base64.getDecoder().decode(document.data)
                require(bytes.size() + chunk.size <= document.totalBytes) { "Invalid release document" }
                bytes.write(chunk)
                val next = document.nextOffset
                if (next == null) {
                    require(bytes.size() == document.totalBytes) { "Incomplete release document" }
                    break
                }
                require(next == bytes.size() && next > offset) { "Invalid release document cursor" }
                offset = next
            }
            val original = client.json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
            fun text(key: String) = (original[key] as? JsonPrimitive)?.contentOrNull
            val upstream = original["raw_metadata"] as? JsonObject
            release.copy(changelog = text("changelog") ?: text("body") ?: text("description")
                ?: (upstream?.get("body") as? JsonPrimitive)?.contentOrNull
                ?: (upstream?.get("description") as? JsonPrimitive)?.contentOrNull ?: release.changelog,
                releaseNotes = text("release_notes") ?: release.releaseNotes)
        }

    suspend fun getHistoricalBundles(release: HistoricalRelease, generation: String):
        APIResponse<List<ExternalBundleSnapshot>> = catalogResult {
        val result = mutableListOf<ExternalBundleSnapshot>()
        for (ids in release.bundleIds.distinct().chunked(70)) {
            val data = queryHistoricalBundles(buildJsonObject {
                put("catalog_id", buildJsonObject { put("_in", JsonArray(ids.map(::JsonPrimitive))) })
            }, generation).getOrThrow()
            requireHistoryGeneration(data.generation, generation)
            result += data.bundles.map { it.toSnapshot().copy(historicalGeneration = generation) }
        }
        result
    }

    private suspend fun queryHistoricalBundles(where: JsonObject, generation: String) =
        graphql<HistoricalBundlesData>(CatalogHistoryQueries.BUNDLES, buildJsonObject {
            put("where", where); put("generation", generation)
        }, cacheIdentity = generation)

    private fun requireHistoryGeneration(actual: String, expected: String) {
        require(actual == expected) { "Catalog changed; refresh release history" }
    }

    private suspend fun <T> catalogResult(block: suspend () -> T): APIResponse<T> =
        withContext(Dispatchers.IO) {
            try {
                APIResponse.Success(block())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure(error.message ?: app.getString(R.string.catalog_request_failed))
            }
        }

    suspend fun getBundlePatches(bundle: ExternalBundleSnapshot): APIResponse<List<ExternalBundlePatch>> {
        val host = bundle.apiHost.ifBlank { ExternalBundlesEndpoints.REGISTRY_HOST }
        val identity = buildJsonObject { put("id", buildJsonObject { put("_eq", bundle.bundleId) }) }
        val generation = bundle.historicalGeneration
        val current = if (generation != null) {
            val response = queryHistoricalBundles(identity, generation)
            when (response) {
                is APIResponse.Success -> {
                    if (response.data.generation != generation) {
                        return failure("Catalog changed; refresh release history")
                    }
                    response.data.bundles.firstOrNull()
                }
                is APIResponse.Error -> return APIResponse.Error(response.error)
                is APIResponse.Failure -> return APIResponse.Failure(response.error)
            }
        } else queryBundles(identity, 1, host = host, forceRefresh = true,
            sort = "id_asc", includeReleaseNotes = false)
            .getOrNull()?.bundle?.firstOrNull()
        if (current == null) return failure(app.getString(R.string.catalog_bundle_metadata_unavailable))
        // A zero count can mean stale metadata, not an empty bundle. Check freshness
        // before discarding those flags when converting the catalog row to a snapshot.
        if (!current.hasCurrentPatchMetadata()) {
            return failure(app.getString(R.string.catalog_patch_metadata_unavailable))
        }
        val node = current.toSnapshot(host)
        if (node.sourceUrl.trimEnd('/') != bundle.sourceUrl.trimEnd('/') ||
            node.version != bundle.version ||
            (bundle.bundleType.isNotBlank() && node.bundleType != bundle.bundleType) ||
            node.isPrerelease != bundle.isPrerelease ||
            (!bundle.downloadUrl.isNullOrBlank() && node.downloadUrl != bundle.downloadUrl) ||
            (!bundle.fileHash.isNullOrBlank() && node.fileHash != bundle.fileHash)) {
            return failure(if (generation == null)
                app.getString(R.string.catalog_bundle_changed_discovery)
                else app.getString(R.string.catalog_bundle_changed_history))
        }
        val artifactIdentity = listOf(bundle.fileHash, bundle.downloadUrl, bundle.version,
            bundle.bundleType, bundle.patchCount).joinToString("|")
        val result = mutableListOf<ExternalBundlePatch>()
        var offset = 0
        val pageSize = if (host.equals(ExternalBundlesEndpoints.REGISTRY_HOST, true)) 30 else 20
        while (true) {
            val page = graphql<PatchesQueryData>(when {
                generation != null -> CatalogHistoryQueries.PATCHES
                host.equals(ExternalBundlesEndpoints.REGISTRY_HOST, true) -> PATCHES_QUERY
                else -> LEGACY_PATCHES_QUERY
            }, buildJsonObject {
                put("id", bundle.bundleId); put("offset", offset); put("limit", pageSize)
                generation?.let { put("generation", it) }
            }, host, cacheIdentity = "$artifactIdentity|$generation")
            when (page) {
                is APIResponse.Success -> {
                    for (patch in page.data.patch) {
                        val links = patch.patchPackages.toMutableList()
                        var linkOffset = links.size
                        while (linkOffset > 0 && linkOffset % 100 == 0) {
                            when (val extra = graphql<PatchLinksQueryData>(when {
                                generation != null -> CatalogHistoryQueries.LINKS
                                host.equals(ExternalBundlesEndpoints.REGISTRY_HOST, true) -> PATCH_LINKS_QUERY
                                else -> LEGACY_PATCH_LINKS_QUERY
                            }, buildJsonObject {
                                put("id", patch.id); put("offset", linkOffset)
                                generation?.let { put("generation", it) }
                            }, host, cacheIdentity = "$artifactIdentity|$generation")) {
                                is APIResponse.Success -> {
                                    links += extra.data.links
                                    if (extra.data.links.size < 100) break
                                    linkOffset += extra.data.links.size
                                }
                                is APIResponse.Error -> return APIResponse.Error(extra.error)
                                is APIResponse.Failure -> return APIResponse.Failure(extra.error)
                            }
                        }
                        result += patch.copy(patchPackages = links).toPatch()
                    }
                    if (page.data.patch.size < pageSize) break
                    offset += page.data.patch.size
                }
                is APIResponse.Error -> return APIResponse.Error(page.error)
                is APIResponse.Failure -> return APIResponse.Failure(page.error)
            }
        }
        if (result.size < bundle.patchCount) return failure(app.getString(R.string.catalog_patch_metadata_unavailable))
        return APIResponse.Success(result)
    }

    private fun sourceIdentity(owner: String, repo: String) = buildJsonObject {
        put("source_metadatum", buildJsonObject {
            put("owner_name", comparison(owner)); put("repo_name", comparison(repo))
        })
    }

    private suspend fun queryBundles(
        where: JsonObject, limit: Int, offset: Int = 0,
        host: String = ExternalBundlesEndpoints.REGISTRY_HOST,
        forceRefresh: Boolean = false, sort: String = "updated_desc",
        includeReleaseNotes: Boolean = true
    ) = graphql<BundlesQueryData>(if (host.equals(ExternalBundlesEndpoints.REGISTRY_HOST, true))
        BUNDLES_QUERY else LEGACY_BUNDLES_QUERY, buildJsonObject {
        put("where", where); put("limit", limit.coerceIn(1, 100)); put("offset", offset)
        put("includeReleaseNotes", includeReleaseNotes)
        put("order", bundleOrder(sort))
    }, host, forceRefresh)

    private fun bundleOrder(sort: String) = buildJsonArray {
        val direction = if (sort.endsWith("asc", ignoreCase = true)) "asc" else "desc"
        if (sort.startsWith("id_")) {
            add(buildJsonObject { put("id", direction) })
        } else if (sort.contains("name", ignoreCase = true)) {
            for (field in listOf("owner_name", "repo_name")) add(buildJsonObject {
                put("source", buildJsonObject { put("source_metadatum", buildJsonObject {
                    put(field, direction)
                }) })
            })
        } else add(buildJsonObject { put("created_at", direction) })
        if (!sort.startsWith("id_")) add(buildJsonObject { put("id", "asc") })
    }

    private suspend inline fun <reified T> graphql(
        query: String, variables: JsonObject, host: String = ExternalBundlesEndpoints.REGISTRY_HOST,
        forceRefresh: Boolean = false,
        cacheIdentity: String = "",
        requestBudget: CatalogRequestBudget? = null
    ): APIResponse<T> = withContext(Dispatchers.IO) {
        requestMutex.withLock {
            val payload = client.json.encodeToString(GraphqlRequest(query, variables))
            val key = MessageDigest.getInstance("SHA-256").digest(("v2|$host|$cacheIdentity|$payload").toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = File(cacheDir, "$key.json")
            val now = System.currentTimeMillis()
            val cached = {
                runCatching {
                    client.json.decodeFromString<GraphqlResponse<T>>(file.readText()).data
                }.getOrNull()
            }
            if (!forceRefresh && file.exists() && now - file.lastModified() in 0 until CACHE_TTL_MS) {
                cached()?.let { return@withLock APIResponse.Success(it) }
            }
            if (now < retryAt || (!forceRefresh && now < (failedRequests[key]?.first ?: 0L))) {
                return@withLock failure(if (now < retryAt) app.getString(R.string.catalog_unavailable)
                    else failedRequests[key]?.second ?: app.getString(R.string.catalog_unavailable))
            }
            if (requestBudget != null && --requestBudget.remaining < 0) {
                return@withLock failure(app.getString(R.string.catalog_search_budget_exceeded))
            }
            try {
                val responseAndBody = withTimeoutOrNull(ExternalBundlesEndpoints.HOST_QUERY_TIMEOUT_MS) {
                    client.http.request {
                        method = HttpMethod.Post
                        url(if (host.equals(ExternalBundlesEndpoints.REGISTRY_HOST, true))
                            ExternalBundlesEndpoints.GRAPHQL_URL else "https://$host/hasura/v1/graphql")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(payload)
                    }.let { it to it.bodyAsText() }
                } ?: return@withLock failure<T>(app.getString(R.string.catalog_request_timeout)).also {
                    recordFailure(key, app.getString(R.string.catalog_request_timeout))
                }
                val (response, body) = responseAndBody
                if (!response.status.isSuccess()) {
                    val failedAt = System.currentTimeMillis()
                    val retry = response.headers[HttpHeaders.RetryAfter]
                    val retryMillis = retry?.toLongOrNull()?.times(1000) ?: runCatching {
                        ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - failedAt
                    }.getOrNull()
                    if (response.status.value == 429 || retryMillis != null) {
                        retryAt = failedAt + (retryMillis ?: FAILURE_TTL_MS).coerceAtLeast(1000L)
                    }
                    val resourceError = if (body.trim() == "error code: 1102") " (Cloudflare 1102)" else ""
                    val message = app.getString(R.string.catalog_request_http_error, response.status.value, resourceError)
                    recordFailure(key, message)
                    return@withLock failure(message)
                }
                val decoded = client.json.decodeFromString<GraphqlResponse<T>>(body)
                if (!decoded.errors.isNullOrEmpty() || decoded.data == null) {
                    val message = decoded.errors?.joinToString { it.message.orEmpty() }
                        ?: app.getString(R.string.catalog_response_missing_data)
                    recordFailure(key, message)
                    return@withLock failure(message)
                }
                runCatching {
                    file.writeText(body)
                    cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(128)
                        ?.forEach { it.delete() }
                }
                failedRequests.remove(key)
                APIResponse.Success(decoded.data)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val message = error.message ?: app.getString(R.string.catalog_request_failed)
                recordFailure(key, message)
                failure(message)
            }
        }
    }

    private fun recordFailure(key: String, message: String) {
        failedRequests[key] = (System.currentTimeMillis() + FAILURE_TTL_MS) to message
        while (failedRequests.size > 128) failedRequests.remove(failedRequests.keys.first())
    }

    private fun <T> failure(message: String): APIResponse.Failure<T> =
        APIResponse.Failure(APIFailure(IOException(message), null))

    private fun BundleNode.toSnapshot(host: String = ExternalBundlesEndpoints.REGISTRY_HOST): ExternalBundleSnapshot {
        val metadata = source?.sourceMetadata
        val current = hasCurrentPatchMetadata()
        return ExternalBundleSnapshot(
            apiHost = host, ownerName = metadata?.ownerName.orEmpty(),
            ownerAvatarUrl = ownerAvatarUrl ?: metadata?.ownerAvatarUrl,
            iconUrl = iconUrl ?: metadata?.bundleIconUrl,
            repoName = metadata?.repoName.orEmpty(), repoDescription = metadata?.repoDescription,
            sourceUrl = source?.url.orEmpty(), repoStars = metadata?.repoStars ?: 0,
            repoPushedAt = metadata?.repoPushedAt, isRepoArchived = metadata?.isRepoArchived ?: false,
            bundleId = id, bundleType = bundleType.orEmpty(), createdAt = createdAt.orEmpty(),
            description = description, version = version.orEmpty(), downloadUrl = downloadUrl,
            signatureDownloadUrl = signatureDownloadUrl, fileHash = fileHash,
            isPrerelease = isPrerelease, isBundleV3 = bundleType?.contains("v3", true) == true,
            patchCount = if (current) patchCount ?: patchesAggregate?.aggregate?.count ?: 0 else 0
        )
    }

    private fun PatchNode.toPatch() = ExternalBundlePatch(name, description,
        patchPackages.mapNotNull { it.pkg }.filter { !it.name.isNullOrBlank() }
            .groupBy { it.name!! }.map { (name, rows) ->
                ExternalBundlePackage(name = name, versions = rows.map { it.version }.distinct(),
                    appName = rows.firstNotNullOfOrNull { it.appName?.takeIf(String::isNotBlank) },
                    iconUrl = rows.firstNotNullOfOrNull { it.iconUrl?.takeIf(String::isNotBlank) })
            })

    companion object {
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
        private const val FAILURE_TTL_MS = 60 * 1000L
        internal const val BUNDLES_QUERY = """
            query RegistryBundles(${"$"}where: bundle_bool_exp, ${"$"}limit: Int, ${"$"}offset: Int,
                                  ${"$"}order: [bundle_order_by!], ${"$"}includeReleaseNotes: Boolean! = true) {
              catalog_generation
              bundle(where: ${"$"}where, limit: ${"$"}limit, offset: ${"$"}offset, order_by: ${"$"}order) {
                id catalog_id bundle_type created_at description @include(if: ${"$"}includeReleaseNotes)
                download_url signature_download_url
                file_hash need_patches_update patcher_failure_fingerprint metadata_status
                is_prerelease version icon_url owner_avatar_url patch_count
                source { url source_metadatum { owner_name owner_avatar_url bundle_icon_url
                  repo_name repo_description repo_stars repo_pushed_at is_repo_archived } }
              }
            }
        """
        // Existing imports retain their original catalog IDs and Hasura schema.
        internal const val LEGACY_BUNDLES_QUERY = """
            query LegacyBundles(${"$"}where: bundle_bool_exp, ${"$"}limit: Int, ${"$"}offset: Int,
                                ${"$"}order: [bundle_order_by!], ${"$"}includeReleaseNotes: Boolean! = true) {
              bundle(where: ${"$"}where, limit: ${"$"}limit, offset: ${"$"}offset, order_by: ${"$"}order) {
                id bundle_type created_at description @include(if: ${"$"}includeReleaseNotes)
                download_url signature_download_url file_hash
                need_patches_update patcher_failure_fingerprint is_prerelease version
                source { url source_metadatum { owner_name owner_avatar_url repo_name
                  repo_description repo_stars repo_pushed_at is_repo_archived } }
                patches_aggregate { aggregate { count } }
              }
            }
        """
        internal const val PATCH_BUNDLES_QUERY = """
            query RegistryAppBundles(${"$"}where: patch_bool_exp, ${"$"}limit: Int, ${"$"}offset: Int,
                                     ${"$"}order: [patch_order_by!]) {
              patches: patch(where: ${"$"}where, limit: ${"$"}limit, offset: ${"$"}offset,
                             distinct_on: [bundle_fk], order_by: ${"$"}order) {
                bundle {
                  id bundle_type created_at download_url signature_download_url
                  file_hash need_patches_update patcher_failure_fingerprint metadata_status
                  is_prerelease version icon_url owner_avatar_url patch_count
                  source { url source_metadatum { owner_name owner_avatar_url bundle_icon_url
                    repo_name repo_description repo_stars repo_pushed_at is_repo_archived } }
                }
              }
            }
        """
        private const val BUNDLE_IDS_QUERY = """
            query RegistryCompatibleBundles(${"$"}where: patch_bool_exp!, ${"$"}package: String, ${"$"}offset: Int) {
              patch(package_name: ${"$"}package, where: ${"$"}where, limit: 100, offset: ${"$"}offset,
                    distinct_on: [bundle_fk], order_by: {bundle_fk: asc}) {
                bundle_fk
              }
            }
        """
        internal const val APPS_QUERY = """
            query RegistryApps(${"$"}where: package_bool_exp, ${"$"}offset: Int) {
              packages: package(where: ${"$"}where, limit: 30, offset: ${"$"}offset,
                                distinct_on: [name], order_by: {name: asc}) {
                name app_name icon_url
              }
            }
        """
        private const val APP_METADATA_QUERY = """
            query RegistryAppMetadata(${"$"}names: [String!]!) {
              packages: package(where: {name: {_in: ${"$"}names}}, limit: 100,
                                distinct_on: [name], order_by: {name: asc}) {
                name app_name icon_url
              }
            }
        """
        internal const val SOURCES_QUERY = """
            query RegistrySources(${"$"}offset: Int) {
              source(where: {enabled: {_eq: true}}, limit: 100, offset: ${"$"}offset,
                     order_by: {id: asc}) {
                url source_metadatum { owner_name repo_name }
              }
            }
        """
        internal const val PATCHES_QUERY = """
            query RegistryPatches(${"$"}id: Int!, ${"$"}offset: Int, ${"$"}limit: Int!) {
              patch(where: {bundle_fk: {_eq: ${"$"}id}, metadata_status: {_eq: "verified"}},
                    limit: ${"$"}limit, offset: ${"$"}offset, order_by: {id: asc}) {
                id name description
                patch_packages(limit: 100) {
                  package { name version app_name icon_url }
                }
              }
            }
        """
        internal const val LEGACY_PATCHES_QUERY = """
            query LegacyPatches(${"$"}id: Int!, ${"$"}offset: Int) {
              patch(where: {bundle_fk: {_eq: ${"$"}id}},
                    limit: 20, offset: ${"$"}offset, order_by: {id: asc}) {
                id name description
                patch_packages(limit: 100, order_by: {package_fk: asc}) {
                  package { name version }
                }
              }
            }
        """
        internal const val LEGACY_PATCH_LINKS_QUERY = """
            query LegacyPatchLinks(${"$"}id: Int!, ${"$"}offset: Int) {
              links: patch_package(where: {patch_fk: {_eq: ${"$"}id}}, limit: 100,
                                   offset: ${"$"}offset, order_by: {package_fk: asc}) {
                package { name version }
              }
            }
        """
        private const val PATCH_LINKS_QUERY = """
            query RegistryPatchLinks(${"$"}id: Int!, ${"$"}offset: Int) {
              links: patch_package(where: {patch_fk: {_eq: ${"$"}id}}, limit: 100,
                                   offset: ${"$"}offset) {
                package { name version app_name icon_url }
              }
            }
        """
    }
}

internal fun BundleNode.matchesSnapshotIdentity(bundle: ExternalBundleSnapshot): Boolean? {
    if (bundle.sourceUrl.isBlank() || bundle.version.isBlank()) return null
    if (source?.url?.trim()?.trimEnd('/') != bundle.sourceUrl.trim().trimEnd('/') ||
        version?.trim() != bundle.version.trim() || isPrerelease != bundle.isPrerelease) return false
    if (!bundle.fileHash.isNullOrBlank()) {
        if (fileHash.isNullOrBlank()) return null
        if (fileHash != bundle.fileHash) return false
    }
    if (!bundle.downloadUrl.isNullOrBlank()) {
        if (downloadUrl.isNullOrBlank()) return null
        if (downloadUrl != bundle.downloadUrl) return false
    }
    if (bundle.bundleType.isNotBlank()) {
        if (bundleType.isNullOrBlank()) return null
        if (bundleType != bundle.bundleType) return false
    }
    return true
}

internal fun BundleNode.hasCurrentPatchMetadata(): Boolean =
    patchMetadataVerified && !needPatchesUpdate && patcherFailureFingerprint.isNullOrBlank() &&
        (metadataStatus == null || metadataStatus == "verified")

internal fun List<BundleNode>.selectBestPatchIdentityCandidate(bundle: ExternalBundleSnapshot): BundleNode? {
    val candidates = filter { it.source?.url?.trim() == bundle.sourceUrl.trim() }.ifEmpty { this }
    return candidates.firstOrNull { it.matchesSnapshotIdentity(bundle) == true && it.hasCurrentPatchMetadata() }
        ?: candidates.firstOrNull { it.matchesSnapshotIdentity(bundle) == true }
        ?: candidates.firstOrNull()
}

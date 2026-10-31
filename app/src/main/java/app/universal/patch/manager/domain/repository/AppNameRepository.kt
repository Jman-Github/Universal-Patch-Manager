package app.universal.patch.manager.domain.repository

import android.app.Application
import android.text.Html
import app.universal.patch.manager.util.managerStorageContext
import app.universal.patch.manager.network.api.ExternalBundlesEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Registry metadata with store lookups as a fallback for non-discovery screens. */
class AppNameRepository(app: Application, private val http: HttpClient, private val json: Json) {
    private val directory = File(app.managerStorageContext.cacheDir, "app-names")
    private val locks = AppMetadataRequestLocks()
    private val diskMutex = Mutex()
    private var diskPruned = false
    // Store outages must not hold up API metadata for the rest of the list.
    private val registryRequests = Semaphore(6)
    private val storeRequests = Semaphore(2)
    private val memory = LinkedHashMap<String, CachedAppName>(16, 0.75f, true)
    private val providerRetryAt = ConcurrentHashMap<String, Long>()
    private val mutableNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val names = mutableNames.asStateFlow()
    private val mutableIcons = MutableStateFlow<Map<String, String>>(emptyMap())
    val icons = mutableIcons.asStateFlow()

    suspend fun resolve(packageName: String, localLabel: String? = null): String {
        localLabel?.trim()?.takeIf { it.isNotBlank() && it != packageName }?.let { return it }
        return lookup(packageName)?.name ?: packageName
    }

    suspend fun resolveIconUrl(packageName: String): String? = lookup(packageName)?.iconUrl

    private suspend fun lookup(packageName: String): CachedAppName? {
        synchronized(memory) { memory[packageName]?.takeIf { it.isFresh() } }?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!appNamePackagePattern.matches(packageName)) return@withContext null
            locks.withLock(packageName) {
                val file = File(directory, "$packageName.json")
                val cached = synchronized(memory) { memory[packageName] } ?: diskMutex.withLock {
                    if (!diskPruned) {
                        pruneDisk()
                        diskPruned = true
                    }
                    runCatching {
                        require(file.length() in 1..MAX_METADATA_FILE_BYTES)
                        json.decodeFromString<CachedAppName>(file.readText()).also {
                            file.setLastModified(System.currentTimeMillis())
                        }
                    }.getOrNull()
                }
                cached?.let { rememberEntry(packageName, it) }
                val now = System.currentTimeMillis()
                if (cached != null && cached.isFresh(now)) {
                    return@withLock cached
                }
                run {
                    var failed = false
                    var name: String? = null
                    var iconUrl: String? = null
                    val pages = listOf(
                        "registry" to "https://${ExternalBundlesEndpoints.REGISTRY_HOST}/apps/$packageName",
                        "fdroid" to "https://f-droid.org/en/packages/$packageName/",
                        "izzy" to "https://apt.izzysoft.de/fdroid/index/apk/$packageName",
                        "play" to "https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=US",
                        "play" to "https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=GB",
                        "play" to "https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=IN"
                    )
                    for ((provider, url) in pages) {
                        if (System.currentTimeMillis() < (providerRetryAt[provider] ?: 0L)) {
                            failed = true
                            continue
                        }
                        try {
                            val metadata = (if (provider == "registry") registryRequests else storeRequests).withPermit {
                                // A queued lookup may have been rate-limited by an earlier request.
                                if (System.currentTimeMillis() < (providerRetryAt[provider] ?: 0L)) {
                                    failed = true
                                    return@withPermit null
                                }
                                http.prepareGet(url) {
                                    timeout { requestTimeoutMillis = 6_000; socketTimeoutMillis = 6_000 }
                                }.execute { response ->
                                    if (response.status.value == 404) return@execute null
                                    if (response.status.value == 429 || response.status.value == 503) {
                                        val seconds = response.headers["Retry-After"]?.toLongOrNull()
                                            ?.coerceIn(60, 86_400) ?: 600L
                                        providerRetryAt.merge(provider, System.currentTimeMillis() + seconds * 1000, ::maxOf)
                                    }
                                    if (response.status.value != 200) { failed = true; return@execute null }
                                    // Stream rather than buffering an unbounded listing in HttpClient.get.
                                    val bytes = response.bodyAsChannel().readRemaining(2_000_001).readByteArray()
                                    if (bytes.size > 2_000_000) { failed = true; return@execute null }
                                    val html = bytes.toString(Charsets.UTF_8)
                                    if (provider == "registry") {
                                        return@execute parseRegistryAppMetadata(html, packageName)
                                    }
                                    val parsedName = parseStoreAppName(html, provider, packageName)?.let {
                                        Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                                    }?.takeIf { it.isNotBlank() && it.length <= 200 && it != packageName }
                                    // A successful HTTP response without usable metadata may be a
                                    // consent/error page, not evidence that this app is unlisted.
                                    if (parsedName == null) failed = true
                                    parsedName to parseStoreAppIconUrl(html, provider, packageName)
                                }
                            }
                            if (name == null) name = metadata?.first
                            if (iconUrl == null) iconUrl = metadata?.second
                            if ((name == null || iconUrl == null) &&
                                (metadata?.first != null || metadata?.second != null)) {
                                // Show partial API results immediately while missing fields use fallback.
                                rememberEntry(packageName, CachedAppName(name ?: cached?.name, 0, 0,
                                    iconUrl ?: cached?.iconUrl, metadataVersion = 2))
                            }
                            if (name != null && iconUrl != null) break
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            failed = true
                            // Do not repeat a failing/offline provider for every app in the picker.
                            providerRetryAt.merge(provider, System.currentTimeMillis() + 60_000, ::maxOf)
                        }
                    }
                    // Queueing and network time must not consume the result's cache lifetime.
                    val entry = CachedAppName(name ?: cached?.name, System.currentTimeMillis(), when {
                        failed -> 10L * 60 * 1000
                        // Recheck the primary API daily even when stores supplied a fallback.
                        else -> 24L * 60 * 60 * 1000
                    }, iconUrl ?: cached?.iconUrl, metadataVersion = 2)
                    rememberEntry(packageName, entry)
                    diskMutex.withLock {
                        runCatching {
                            directory.mkdirs()
                            val temporary = File(directory, "$packageName.tmp")
                            temporary.writeText(json.encodeToString(entry))
                            if (!temporary.renameTo(file)) temporary.delete()
                        }
                        pruneDisk()
                    }
                    entry
                }
            }
        }
    }

    private fun rememberEntry(packageName: String, entry: CachedAppName) = synchronized(memory) {
        memory[packageName] = entry
        while (memory.size > MAX_MEMORY_ENTRIES) memory.remove(memory.keys.first())
        // Published metadata is bounded too, not just the backing cache.
        mutableNames.update { memory.mapNotNull { (pkg, value) -> value.name?.let { pkg to it } }.toMap() }
        mutableIcons.update { memory.mapNotNull { (pkg, value) -> value.iconUrl?.let { pkg to it } }.toMap() }
    }

    /** Only direct files in this dedicated metadata directory are ever removed. */
    private fun pruneDisk() {
        runCatching { pruneAppMetadataCache(directory, System.currentTimeMillis()) }
    }

    private companion object {
        const val MAX_MEMORY_ENTRIES = 512
    }
}

@Serializable
private data class CachedAppName(
    val name: String?, val checkedAt: Long, val ttlMs: Long,
    val iconUrl: String? = null, val metadataVersion: Int = 0
)

private fun CachedAppName.isFresh(now: Long = System.currentTimeMillis()): Boolean =
    metadataVersion >= 2 && now - checkedAt in 0 until ttlMs

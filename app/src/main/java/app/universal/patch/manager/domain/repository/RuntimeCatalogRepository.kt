package app.universal.patch.manager.domain.repository

import android.app.Application
import app.universal.patch.manager.R
import android.os.Build
import android.os.Process
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.api.successOrThrow
import app.universal.patch.manager.network.dto.GitHubAsset
import app.universal.patch.manager.network.dto.GitHubRelease
import app.universal.patch.manager.network.runtime.*
import app.universal.patch.manager.network.service.HttpService
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

data class CatalogRuntimeRelease(
    val item: RuntimeCatalogItem,
    val manifest: RuntimeReleaseManifest,
    val release: GitHubRelease,
    val asset: GitHubAsset
) {
    val iconUrl: String? get() = release.assets.firstOrNull { it.name == "runtime-icon.svg" }?.downloadUrl
}

/** Discovery is untrusted metadata. Executable APKs must also match the manager's signing key. */
class RuntimeCatalogRepository(
    private val app: Application,
    private val api: UpmAPI,
    private val http: HttpService
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache get() = File(app.filesDir, "runtime-catalog.json")
    private val processAbis get() =
        (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).toList()

    fun cached(): RuntimeCatalog {
        val cached = runCatching { json.decodeFromString<RuntimeCatalog>(cache.readText()).validated() }.getOrNull()
        return cached ?: app.assets.open("runtime-catalog.json").bufferedReader().use {
            json.decodeFromString<RuntimeCatalog>(it.readText()).validated()
        }
    }

    suspend fun refresh(): RuntimeCatalog = withContext(Dispatchers.IO) {
        val raw = http.request<String> { url(CATALOG_URL) }.successOrThrow("runtime catalog")
        val catalog = json.decodeFromString<RuntimeCatalog>(raw).validated()
        writeRuntimeCatalogCache(cache, raw)
        catalog
    }

    suspend fun resolve(
        item: RuntimeCatalogItem,
        prerelease: Boolean? = null,
        tag: String? = null
    ): CatalogRuntimeRelease {
        // Filter while paginating, so frequent releases of one engine cannot hide another.
        val releases = if (tag != null) {
            listOf(api.getRepositoryReleaseByTag(item.repoUrl, tag).successOrThrow("saved runtime release"))
                .filter { !it.draft }
        } else api.getRepositoryReleaseHistory(
            item.repoUrl, prerelease, limit = 30, tagPrefix = item.id + "-v"
        ).successOrThrow("runtime releases")
        var firstFailure: Throwable? = null
        for (release in releases) {
            val metadata = release.assets.firstOrNull { it.name == "runtime-release.json" } ?: continue
            val asset = release.assets.firstOrNull { it.name == item.assetName } ?: continue
            try {
                val manifest = http.request<RuntimeReleaseManifest> {
                    url(metadata.downloadUrl)
                }.successOrThrow("runtime compatibility metadata")
                if (manifest.isCompatible(item, Build.VERSION.SDK_INT, processAbis)) {
                    return CatalogRuntimeRelease(item, manifest, release, asset)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (firstFailure == null) firstFailure = error
            }
        }
        throw IllegalStateException(app.getString(R.string.runtime_catalog_no_compatible_release, item.name), firstFailure)
    }

    companion object {
        const val REPOSITORY_URL = "https://github.com/Jman-Github/Universal-Patch-Manager-Runtimes"
        const val CATALOG_URL = "https://raw.githubusercontent.com/Jman-Github/Universal-Patch-Manager-Runtimes/main/catalog.json"
    }
}

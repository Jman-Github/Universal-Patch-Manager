package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.patcher.patch.*
import kotlinx.collections.immutable.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only app-list previews live here. Execution always uses fresh runtime metadata. */
internal object PatchableAppCache {
    @Serializable private data class Package(val name: String, val versions: Set<String>?,
        val experimental: Set<String>?, val codes: Map<String, Set<Long>>?)
    @Serializable private data class Patch(val name: String, val packages: List<Package>?)
    @Serializable private data class Snapshot(val schema: Int = 1, val type: String, val patches: List<Patch>)
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_BYTES = 8 * 1024 * 1024
    private fun file(directory: File) = directory.resolve("patchable-apps.json")

    fun read(directory: File, uid: Int, name: String, version: String?, enabled: Boolean): PatchBundleInfo.Global? =
        runCatching {
            val file = file(directory)
            require(file.length() in 1..MAX_BYTES.toLong())
            val saved = json.decodeFromString<Snapshot>(file.readText())
            require(saved.schema == 1)
            PatchBundleInfo.Global(name, version, uid, PatchBundleType.valueOf(saved.type), enabled,
                saved.patches.map { patch ->
                    PatchInfo(patch.name, null, false, patch.packages?.map {
                        CompatiblePackage(it.name, it.versions?.toImmutableSet(), it.experimental?.toImmutableSet(),
                            it.codes?.mapValues { (_, codes) -> codes.toImmutableSet() }?.toImmutableMap())
                    }?.toImmutableList(), null)
                })
        }.getOrNull()

    fun write(directory: File, info: PatchBundleInfo.Global) {
        runCatching {
            val snapshot = Snapshot(type = info.bundleType.name, patches = info.patches.map { patch ->
                Patch(patch.name, patch.compatiblePackages?.map {
                    Package(it.packageName, it.versions, it.experimentalVersions, it.versionCodes)
                })
            })
            val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            val target = file(directory)
            if (bytes.size > MAX_BYTES) { target.delete(); return }
            val staging = File.createTempFile("app-list-", ".tmp", directory)
            try {
                staging.writeBytes(bytes)
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { staging.delete() }
        }
    }
}

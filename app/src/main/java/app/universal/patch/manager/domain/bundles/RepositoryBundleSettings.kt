package app.universal.patch.manager.domain.bundles

import kotlinx.serialization.Serializable
import app.universal.patch.manager.network.dto.ReVancedAsset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RepositoryBundleSettings(
    val usePrereleases: Boolean = false,
    val useLatest: Boolean = false,
    val useSpecificVersion: Boolean = false,
    val selectedVersion: ReVancedAsset? = null,
) {
    val releaseChannel: RepositoryBundleReleaseChannel
        get() = when {
            useLatest -> RepositoryBundleReleaseChannel.LATEST
            usePrereleases -> RepositoryBundleReleaseChannel.PRERELEASE
            else -> RepositoryBundleReleaseChannel.RELEASE
        }

    /** Only the original API source used a global channel preference. */
    fun forLegacyOfficialMigration(apiSource: Boolean, legacyPrereleases: Boolean) =
        if (apiSource) copy(usePrereleases = legacyPrereleases, useLatest = false) else this

    fun withVersionSelection(enabled: Boolean, asset: ReVancedAsset? = null) = copy(
        useSpecificVersion = enabled,
        selectedVersion = asset ?: selectedVersion
    )

    fun withImportedSelection(
        prereleases: Boolean?,
        latest: Boolean?,
        pinned: Boolean?,
        selected: ReVancedAsset?
    ): RepositoryBundleSettings {
        val channelSpecified = prereleases != null || latest != null
        return copy(
            usePrereleases = prereleases ?: usePrereleases,
            useLatest = if (channelSpecified) latest == true else useLatest,
            useSpecificVersion = pinned ?: useSpecificVersion,
            selectedVersion = if (pinned != null) selected else selectedVersion
        )
    }

    companion object {
        fun forReleaseChannel(channel: RepositoryBundleReleaseChannel) = RepositoryBundleSettings(
            usePrereleases = channel == RepositoryBundleReleaseChannel.PRERELEASE,
            useLatest = channel == RepositoryBundleReleaseChannel.LATEST,
        )
    }
}

enum class RepositoryBundleReleaseChannel {
    RELEASE,
    PRERELEASE,
    LATEST,
}

object RepositoryBundleSettingsStore {
    private const val FILE_NAME = "repository_bundle_settings.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(directory: File): RepositoryBundleSettings = runCatching {
        json.decodeFromString<RepositoryBundleSettings>(directory.resolve(FILE_NAME).readText())
    }.getOrDefault(RepositoryBundleSettings())

    fun write(directory: File, settings: RepositoryBundleSettings) {
        directory.resolve(FILE_NAME).writeText(json.encodeToString(settings))
    }

    fun clear(directory: File) {
        runCatching { directory.resolve(FILE_NAME).delete() }
    }
}

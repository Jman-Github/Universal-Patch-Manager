package app.universal.patch.manager.domain.bundles

import app.universal.patch.manager.network.dto.ReVancedAsset
import java.nio.file.Files
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RepositoryBundleSettingsTest {
    private val asset = ReVancedAsset(
        "https://example.com/v1/patches.rvp", LocalDateTime(2026, 1, 1, 0, 0),
        description = "Version one", version = "v1"
    )

    @Test fun unchangedBackupRestoresWithoutTemporarilyDisablingThePin() {
        val pinned = RepositoryBundleSettings(useLatest = true,
            useSpecificVersion = true, selectedVersion = asset)
        assertEquals(pinned, pinned.withImportedSelection(false, true, true, asset))
    }

    @Test fun oldBackupsPreservePinsAndMissingChannelPreferences() {
        val pinned = RepositoryBundleSettings(usePrereleases = true,
            useSpecificVersion = true, selectedVersion = asset)
        assertEquals(pinned, pinned.withImportedSelection(null, null, null, null))
        assertEquals(pinned.copy(useLatest = true, usePrereleases = false),
            pinned.withImportedSelection(false, true, null, null))
    }

    @Test fun queuedTogglePreservesTheMostRecentlySelectedVersion() {
        val newest = asset.copy(version = "v2", downloadUrl = "https://example.com/v2/patches.rvp")
        val current = RepositoryBundleSettings(useLatest = true, selectedVersion = newest)
        val disabled = current.withVersionSelection(false)
        assertEquals(newest, disabled.selectedVersion)
        assertEquals(newest, disabled.withVersionSelection(true).selectedVersion)
        assertTrue(disabled.useLatest)
    }

    @Test fun latestAndPinnedBackupPreservesTheOverriddenPrereleaseChoice() {
        val original = RepositoryBundleSettings(usePrereleases = true, useLatest = true,
            useSpecificVersion = true, selectedVersion = asset)
        assertEquals(original, RepositoryBundleSettings().withImportedSelection(true, true, true, asset))
        val unpinned = original.withVersionSelection(false)
        assertTrue(unpinned.useLatest)
        assertTrue(unpinned.usePrereleases)
        assertEquals(RepositoryBundleReleaseChannel.PRERELEASE,
            unpinned.copy(useLatest = false).releaseChannel)
    }

    @Test fun changingOnlyLatestInAnImportKeepsThePrereleaseChoice() {
        val original = RepositoryBundleSettings(usePrereleases = true)
        val latest = original.withImportedSelection(null, true, null, null)
        assertTrue(latest.usePrereleases)
        assertTrue(latest.useLatest)
        assertEquals(original, latest.withImportedSelection(null, false, null, null))
    }

    @Test fun overriddenPrereleaseChoiceSurvivesDiskRoundTrip() {
        val dir = Files.createTempDirectory("bundle-channels-test").toFile()
        try {
            val original = RepositoryBundleSettings(usePrereleases = true, useLatest = true,
                useSpecificVersion = true, selectedVersion = asset)
            RepositoryBundleSettingsStore.write(dir, original)
            assertEquals(original, RepositoryBundleSettingsStore.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun oldSettingsRemainCompatible() {
        val settings = Json.decodeFromString<RepositoryBundleSettings>(
            """{"usePrereleases":true,"useLatest":false}"""
        )
        assertTrue(settings.usePrereleases)
        assertFalse(settings.useSpecificVersion)
        assertNull(settings.selectedVersion)
    }

    @Test fun selectedReleaseSurvivesRestartAndDisablingRestoresChannel() {
        val dir = Files.createTempDirectory("bundle-settings-test").toFile()
        try {
            val original = RepositoryBundleSettings(useLatest = true)
            RepositoryBundleSettingsStore.write(dir,
                original.copy(useSpecificVersion = true, selectedVersion = asset))
            val restored = RepositoryBundleSettingsStore.read(dir)
            assertTrue(restored.useSpecificVersion)
            assertEquals(asset, restored.selectedVersion)
            assertEquals(original.releaseChannel, restored.copy(useSpecificVersion = false).releaseChannel)
            assertTrue(restored.useLatest)
        } finally {
            dir.deleteRecursively()
        }
    }
}

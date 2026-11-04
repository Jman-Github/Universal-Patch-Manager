package app.universal.patch.manager.network.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeRestorePlanTest {
    private fun entry(id: String) = PatcherRuntimePluginSourceEntry(
        id, "https://github.com/example/runtimes", "morphe-plugin",
        catalogId = "morphe", expectedSha256 = "a".repeat(64), archiveName = "a".repeat(64) + ".apk"
    )

    @Test fun backupReusesLocalStorageWithoutReplacingImportedSettings() {
        val local = entry("this-device").copy(latest = true, expectedSha256 = "b".repeat(64))
        val backup = entry("another-device").copy(
            autoUpdate = false, latest = false, prerelease = true,
            trustedSignatureHex = "saved-signer", versionKey = "morphe-v1.0.0:hash"
        )
        assertEquals(listOf(backup.copy(id = local.id)), rebindImportedRuntimeSources(listOf(backup), listOf(local)))
    }

    @Test fun unrelatedRuntimesNeverReuseEachOthersFolders() {
        val backup = entry("backup").copy(catalogId = "revanced-v22")
        assertEquals(listOf(backup), rebindImportedRuntimeSources(listOf(backup), listOf(entry("local"))))
        val thirdParty = entry("third-party").copy(catalogId = null)
        assertEquals("local", rebindImportedRuntimeSources(listOf(thirdParty),
            listOf(entry("local").copy(catalogId = null, repoUrl = "https://github.com/example/runtimes/"))).single().id)
    }

    @Test fun ambiguousSourceIdentitiesFailBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) {
            rebindImportedRuntimeSources(listOf(entry("one"), entry("two")), listOf(entry("local")))
        }
        val backup = entry("duplicate").copy(catalogId = null, autoUpdate = false, versionKey = "v1:plugin.apk")
        val local = backup.copy(versionKey = "v2:plugin.apk")
        assertThrows(IllegalArgumentException::class.java) {
            rebindImportedRuntimeSources(listOf(backup, backup), listOf(local))
        }
    }

    @Test fun frozenVersionsAndLatestUseDifferentReleasePolicies() {
        val saved = entry("saved").copy(autoUpdate = false, versionKey = "morphe-v1.0.0:hash")
        assertEquals("morphe-v1.0.0", importedRuntimeReleaseTag(saved))
        assertNull(importedRuntimeReleaseTag(saved.copy(latest = true)))
        assertNull(importedRuntimeReleaseTag(saved.copy(autoUpdate = true)))
        assertNull(importedRuntimeReleaseTag(saved.copy(versionKey = "local:hash")))
    }

    @Test fun frozenLegacySourcesCannotRelabelADifferentLocalRelease() {
        val local = entry("same-source").copy(catalogId = null, versionKey = "v2:plugin.apk",
            expectedSha256 = null, archiveName = null)
        val backup = local.copy(versionKey = "v1:plugin.apk", autoUpdate = false)
        val result = rebindImportedRuntimeSources(listOf(backup), listOf(local)).single()
        assertNotEquals(local.id, result.id)
        assertEquals(backup.versionKey, result.versionKey)
        assertEquals(backup.autoUpdate, result.autoUpdate)
    }

    @Test fun trackingLegacySourcesKeepTheActualLocalReleaseMetadata() {
        val local = entry("local").copy(catalogId = null, versionKey = "v2:plugin.apk",
            expectedSha256 = "b".repeat(64), archiveName = "b".repeat(64) + ".apk")
        val backup = local.copy(id = "backup", versionKey = "v1:plugin.apk", latest = true,
            expectedSha256 = "a".repeat(64), archiveName = "a".repeat(64) + ".apk",
            trustedSignatureHex = "backup-trust")
        val result = rebindImportedRuntimeSources(listOf(backup), listOf(local)).single()
        assertEquals(local.id, result.id)
        assertEquals(local.versionKey, result.versionKey)
        assertEquals(local.expectedSha256, result.expectedSha256)
        assertEquals(local.archiveName, result.archiveName)
        assertTrue(result.latest)
        assertEquals("backup-trust", result.trustedSignatureHex)
    }

    @Test fun backupChoicesSkipInteractiveSetupDuringAndAfterRestoration() {
        for (version in listOf(0, RUNTIME_SETUP_VERSION)) {
            assertNull(runtimeSetupRequired(version, false, emptySet(), restoredFromBackup = true))
            assertEquals(false, runtimeSetupRequired(version, true, emptySet(), restoredFromBackup = true))
            assertEquals(false, runtimeSetupRequired(version, true,
                setOf(PatcherRuntimeKind.MORPHE), restoredFromBackup = true))
        }
        assertEquals(true, runtimeSetupRequired(RUNTIME_SETUP_VERSION, true, emptySet()))
    }
}

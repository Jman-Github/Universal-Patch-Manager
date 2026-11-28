package app.universal.patch.manager.network.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeCatalogTest {
    private fun item(id: String, dependencies: List<String> = emptyList()) = RuntimeCatalogItem(
        id, if (id == "shared-support") id else "morphe", id, "description",
        "app.universal.patch.manager.$id.runtime", "https://github.com/example/runtimes",
        "$id.apk", dependencies = dependencies
    )

    @Test fun sharedDependencyIsInstalledOnceBeforeBothConsumers() {
        val catalog = RuntimeCatalog(1, listOf(item("a", listOf("shared-support")), item("b", listOf("shared-support")), item("shared-support")))
        assertEquals(listOf("shared-support", "a", "b"), catalog.installationOrder(setOf("b", "a")).map { it.id })
    }

    @Test fun retiredSavedSelectionsCannotCrashTheChooserOrExposeSupportAsAChoice() {
        val catalog = RuntimeCatalog(1, listOf(item("morphe", listOf("shared-support")), item("shared-support"))).validated()
        val selected = catalog.engineSelection(setOf("retired-engine", "morphe", "shared-support"))
        assertEquals(setOf("morphe"), selected)
        assertEquals(listOf("shared-support", "morphe"), catalog.installationOrder(selected).map { it.id })
        val retiredOnly = catalog.engineSelection(setOf("retired-engine", "shared-support"))
        assertTrue(retiredOnly.isEmpty())
        assertTrue(catalog.installationOrder(retiredOnly).isEmpty())
        assertFalse(canCompleteRuntimeSetup(catalog, retiredOnly,
            setOf(PatcherRuntimeKind.MORPHE, PatcherRuntimeKind.SHARED_SUPPORT)))
    }

    @Test fun cyclesAndMissingDependenciesFailBeforeAnyDownload() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCatalog(1, listOf(item("a", listOf("b")), item("b", listOf("a")))).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCatalog(1, listOf(item("a", listOf("missing")))).validated()
        }
    }

    @Test fun everyEngineRequiresSharedSupportBeforeCatalogActivation() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCatalog(1, listOf(item("morphe"))).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCatalog(1, listOf(item("morphe"), item("shared-support"))).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeCatalog(1, listOf(item("shared-support"))).validated()
        }
        val catalog = RuntimeCatalog(1, listOf(item("morphe", listOf("shared-support")), item("shared-support"))).validated()
        assertEquals(2, catalog.plugins.size)
        // Defend the setup gate even if a caller passes an unvalidated catalog.
        assertFalse(canCompleteRuntimeSetup(RuntimeCatalog(1, listOf(item("morphe"))),
            setOf("morphe"), setOf(PatcherRuntimeKind.MORPHE)))
    }

    @Test fun incompatibleReleasesAndWrongPackageIdentitiesAreRejected() {
        val item = item("morphe")
        val release = RuntimeReleaseManifest(
            1, item.id, item.kind, item.packageName, item.assetName, "1.0", 1, "2.1",
            "a".repeat(64), 100, 1, 1, abis = listOf("arm64-v8a")
        )
        assertTrue(release.isCompatible(item, 26, listOf("arm64-v8a")))
        assertFalse(release.copy(minHostProtocol = 2, maxHostProtocol = 2).isCompatible(item, 36, listOf("arm64-v8a")))
        assertFalse(release.copy(packageName = "other.package").isCompatible(item, 36, listOf("arm64-v8a")))
        assertFalse(release.isCompatible(item, 36, listOf("armeabi-v7a")))
        assertFalse(release.copy(sha256 = "").isCompatible(item, 36, listOf("arm64-v8a")))
    }

    @Test fun catalogRejectsDuplicateIdsAndNonGitHubRepositories() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeCatalog(1, listOf(item("morphe"), item("morphe"))).validated() }
        assertThrows(IllegalArgumentException::class.java) { RuntimeCatalog(1, listOf(item("morphe").copy(repoUrl = "http://example.com/repo"))).validated() }
    }

    @Test fun upgradeSetupAndMissingFilesCannotBeSkippedByAnOldFirstLaunchFlag() {
        val ready = setOf(PatcherRuntimeKind.MORPHE, PatcherRuntimeKind.SHARED_SUPPORT)
        assertEquals(true, runtimeSetupRequired(0, true, ready))
        assertEquals(true, runtimeSetupRequired(1, true, ready))
        assertEquals(false, runtimeSetupRequired(2, true, ready))
        assertEquals(true, runtimeSetupRequired(1, true, emptySet()))
        assertEquals(true, runtimeSetupRequired(1, true, setOf(PatcherRuntimeKind.MORPHE)))
        assertEquals(true, runtimeSetupRequired(2, true, emptySet()))
        assertEquals(true, runtimeSetupRequired(2, true, setOf(PatcherRuntimeKind.MORPHE)))
    }

    @Test fun startupWaitsForTheRuntimeScanBeforeChoosingADestination() {
        val ready = setOf(PatcherRuntimeKind.MORPHE, PatcherRuntimeKind.SHARED_SUPPORT)
        for (version in listOf(0, 1, RUNTIME_SETUP_VERSION)) {
            assertNull(runtimeSetupRequired(version, false, emptySet()))
            assertNull(runtimeSetupRequired(version, false, ready))
        }
        assertEquals(false, runtimeSetupRequired(RUNTIME_SETUP_VERSION, true, ready))
        assertEquals(true, runtimeSetupRequired(RUNTIME_SETUP_VERSION, true, emptySet()))
        assertEquals(true, runtimeSetupRequired(0, true, ready))
    }

    @Test fun partialDownloadsCannotCompleteSetup() {
        val catalog = RuntimeCatalog(1, listOf(item("morphe", listOf("shared-support")), item("shared-support")))
        assertFalse(canCompleteRuntimeSetup(catalog, emptySet(), emptySet()))
        assertFalse(canCompleteRuntimeSetup(catalog, setOf("morphe"), setOf(PatcherRuntimeKind.MORPHE)))
        assertTrue(canCompleteRuntimeSetup(catalog, setOf("morphe"), setOf(PatcherRuntimeKind.MORPHE, PatcherRuntimeKind.SHARED_SUPPORT)))
        assertFalse(canCompleteRuntimeSetup(catalog, setOf("shared-support"), setOf(PatcherRuntimeKind.SHARED_SUPPORT)))
    }
    @Test fun legacyAndInstalledCopiesCannotCompleteModularSetup() {
        val plugin = LoadedPatcherRuntimePlugin(
            "app.universal.patch.manager.morphe.runtime", "Morphe", "1.0.0",
            PatcherRuntimeKind.MORPHE, "source", File("replacement.apk")
        )
        val entry = PatcherRuntimePluginSourceEntry(
            "source", "https://github.com/example/runtimes", "morphe-plugin",
            catalogId = "morphe", expectedPackageName = plugin.packageName, expectedKind = "morphe"
        )
        val source = PatcherRuntimePluginSourceState(
            entry, plugin.name, plugin.version, entry.repoUrl, PatcherRuntimePluginSourceState.State.Loaded(plugin)
        )
        assertEquals(plugin, catalogRuntimePlugins(listOf(source), emptySet())[PatcherRuntimeKind.MORPHE])
        assertTrue(catalogRuntimePlugins(listOf(source), setOf(plugin.packageName)).isEmpty())
        assertTrue(catalogRuntimePlugins(listOf(source.copy(entry = entry.copy(legacyProtocol = true))), emptySet()).isEmpty())
        assertTrue(catalogRuntimePlugins(listOf(source.copy(entry = entry.copy(catalogId = null))), emptySet()).isEmpty())
        assertTrue(catalogRuntimePlugins(listOf(source.copy(entry = entry.copy(expectedPackageName = "other"))), emptySet()).isEmpty())
        assertTrue(catalogRuntimePlugins(listOf(source.copy(state = PatcherRuntimePluginSourceState.State.Missing)), emptySet()).isEmpty())
        // An unrelated installed plugin must not block the new managed runtime.
        assertEquals(1, catalogRuntimePlugins(listOf(source), setOf("unrelated.runtime")).size)
    }

}

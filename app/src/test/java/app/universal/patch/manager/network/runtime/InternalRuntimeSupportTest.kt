package app.universal.patch.manager.network.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InternalRuntimeSupportTest {
    private fun entry() = PatcherRuntimePluginSourceEntry(
        "source", "https://github.com/example/runtimes", "unknown"
    )

    @Test fun supportIsIdentifiableWithoutALoadedApk() {
        val markers = listOf(
            entry().copy(catalogId = "shared-support"),
            entry().copy(expectedKind = "shared-support"),
            entry().copy(expectedPackageName = INTERNAL_RUNTIME_SUPPORT_PACKAGE),
            entry().copy(assetSelector = "shared-support-plugin")
        )
        markers.forEach { support ->
            assertTrue(support.isInternalSupport)
            listOf(
                PatcherRuntimePluginSourceState.State.Missing,
                PatcherRuntimePluginSourceState.State.Failed(IllegalStateException("broken")),
                PatcherRuntimePluginSourceState.State.Untrusted(INTERNAL_RUNTIME_SUPPORT_PACKAGE, "signer")
            ).forEach { state ->
                assertTrue(PatcherRuntimePluginSourceState(support, "support", null, support.repoUrl, state).isInternalSupport)
            }
        }
        assertFalse(entry().copy(catalogId = "morphe", expectedKind = "morphe").isInternalSupport)
    }

    @Test fun loadedSupportAndInstalledSupportAreHiddenRegardlessOfDisplayName() {
        val plugin = LoadedPatcherRuntimePlugin(
            INTERNAL_RUNTIME_SUPPORT_PACKAGE, "custom name", "1.0.0",
            PatcherRuntimeKind.SHARED_SUPPORT, "source", File("support.apk")
        )
        val source = PatcherRuntimePluginSourceState(
            entry(), plugin.name, plugin.version, entry().repoUrl,
            PatcherRuntimePluginSourceState.State.Loaded(plugin)
        )
        assertTrue(source.isInternalSupport)
        assertTrue(isInternalRuntimePlugin(INTERNAL_RUNTIME_SUPPORT_PACKAGE, PatcherRuntimePluginState.Untrusted))
        assertTrue(isInternalRuntimePlugin(INTERNAL_RUNTIME_SUPPORT_PACKAGE, PatcherRuntimePluginState.Failed(Exception())))
        assertTrue(isInternalRuntimePlugin("alternate.package", PatcherRuntimePluginState.Loaded(plugin)))
        assertFalse(isInternalRuntimePlugin("morphe.package", PatcherRuntimePluginState.Loaded(plugin.copy(kind = PatcherRuntimeKind.MORPHE))))
    }

    @Test fun internalUpdatesAreAutomaticWithoutGrantingSignerTrust() {
        val internal = entry().copy(catalogId = "shared-support", autoUpdate = false, latest = false, prerelease = true)
        val normalized = internal.withInternalSupportPolicy()
        assertTrue(normalized.autoUpdate)
        assertTrue(normalized.latest)
        assertFalse(normalized.prerelease)
        assertNull(normalized.trustedSignatureHex)
        assertEquals(internal.copy(autoUpdate = true, latest = true, prerelease = false), normalized)
    }

    @Test fun engineUpdatePreferencesStayUserControlled() {
        for (latest in listOf(false, true)) {
            val engine = entry().copy(catalogId = "morphe", autoUpdate = false, latest = latest, prerelease = true)
            assertEquals(engine, engine.withInternalSupportPolicy())
        }
    }
}

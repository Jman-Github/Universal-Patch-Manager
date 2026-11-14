package app.universal.patch.manager.network.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LoadedPatcherRuntimePluginTest {
    @Test fun normalizesRemoteAndInstalledRevancedNames() {
        for (version in listOf(21, 22)) {
            for (name in listOf("Revanced.v$version Plugin", "revanced-runtime-v$version",
                "ReVanced v$version runtime", "Owner: revanced.v$version plugin")) {
                assertEquals("ReVanced v$version", name.toRuntimeMainName())
            }
        }
        assertEquals("Morphe", "Morphe runtime".toRuntimeMainName())
        assertEquals("revanced.v220", "revanced.v220".toRuntimeMainName())
    }

    private fun runtime(file: File) = LoadedPatcherRuntimePlugin(
        packageName = "fixture.runtime",
        name = "Runtime",
        version = "1.4.0",
        kind = PatcherRuntimeKind.REVANCED_V22,
        sourceId = "source",
        apkFile = file
    )

    @Test fun unchangedRuntimeDoesNotTriggerAnotherReload() {
        val file = Files.createTempFile("runtime", ".apk").toFile()
        try {
            file.writeText("old")
            assertEquals(runtime(file), runtime(file))
        } finally {
            file.delete()
        }
    }

    @Test fun replacingSameVersionAtSamePathChangesTheRuntimeSnapshot() {
        val file = Files.createTempFile("runtime", ".apk").toFile()
        try {
            file.writeText("old")
            val before = runtime(file)
            file.writeText("new runtime")
            val after = runtime(file)
            assertNotEquals(before, after)
            assertEquals(3L, before.apkSize)
            assertEquals(11L, after.apkSize)
            assertNotEquals(mapOf(before.kind to before), mapOf(after.kind to after))
        } finally {
            file.delete()
        }
    }

    @Test fun equalSizeReplacementIsDetectedByItsModificationTime() {
        val file = Files.createTempFile("runtime", ".apk").toFile()
        try {
            file.writeText("old")
            val before = runtime(file)
            file.writeText("new")
            assertTrue(file.setLastModified(before.apkLastModified + 10_000))
            val after = runtime(file)
            assertNotEquals(before, after)
            assertEquals(before.apkSize, after.apkSize)
        } finally {
            file.delete()
        }
    }
}

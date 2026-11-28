package app.universal.patch.manager.domain.batch

import app.universal.patch.manager.ui.model.SelectedApp
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManualBatchPatchQueueTest {
    @Test
    fun `temporary local apk is copied into queue owned storage`() {
        val root = Files.createTempDirectory("manual-batch-queue").toFile()
        try {
            val source = root.resolve("input.apk").apply { writeText("apk-data") }
            val input = SelectedApp.Local(
                packageName = "com.example.app",
                version = "1.0",
                file = source,
                temporary = true
            )
            val (prepared, ownedPath) = prepareManualBatchInput(
                input = input,
                targetDirectory = root.resolve("queue"),
                uniqueSuffix = 42L
            )
            val local = assertIs<SelectedApp.Local>(prepared)

            assertFalse(local.temporary)
            assertNotEquals(source.canonicalPath, local.file.canonicalPath)
            assertTrue(source.delete())
            assertTrue(local.file.isFile)
            assertEquals("apk-data", local.file.readText())
            assertEquals(local.file.canonicalPath, ownedPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `queueing an app with another bundle adds an entry instead of replacing it`() {
        val entries = listOf(entry(bundle = 1))

        assertEquals(-1, manualBatchEntryIndex(entries, null, APP, mapOf(2 to setOf("Other"))))
        assertEquals("$APP#2", nextManualBatchEntryKey(entries, APP))
        assertEquals("com.example.other", nextManualBatchEntryKey(entries, "com.example.other"))
    }

    @Test
    fun `queueing an app with the same bundles replaces its entry`() {
        val entries = listOf(entry(bundle = 1), entry(bundle = 2, key = "$APP#2"))

        assertEquals(1, manualBatchEntryIndex(entries, null, APP, mapOf(2 to setOf("Other"))))
        assertEquals(0, manualBatchEntryIndex(entries, APP, APP, mapOf(2 to setOf("Other"))))
        assertEquals("$APP#3", nextManualBatchEntryKey(entries, APP))
    }

    @Test
    fun `batch items for the same app keep their own keys and order`() {
        val first = BatchPatchItem(
            packageName = APP,
            appName = "App",
            version = null,
            versionCode = null,
            input = null,
            selection = mapOf(1 to setOf("Patch")),
            options = emptyMap(),
            bundles = listOf(
                BatchBundleRef(1, "First bundle", null, setOf("Patch")),
                BatchBundleRef(2, "Second bundle", null, setOf("Patch"))
            ),
            state = BatchItemState.READY
        )
        val second = first.copy(selection = mapOf(2 to setOf("Patch")), key = "$APP#2")

        assertEquals(APP, first.key)
        assertEquals(
            listOf("$APP#2", APP),
            reorderBatchItems(listOf(first, second), listOf("$APP#2", APP)).map { it.key }
        )
        assertEquals("First bundle", first.selectedBundleNames())
        assertEquals("Second bundle", second.selectedBundleNames())
    }

    @Test
    fun `outputs for the same app keep their own saved entries`() {
        val first = item(key = APP).copy(sourceEntryKey = "saved-youtube")
        val second = item(key = "$APP#2").copy(sourceEntryKey = "saved-youtube")
        val other = item(key = "com.example.other").copy(sourceEntryKey = "saved-other")

        assertTrue(sharesBatchSourceEntry(listOf(first, second, other), first))
        assertFalse(sharesBatchSourceEntry(listOf(first, other), first))
        assertFalse(sharesBatchSourceEntry(listOf(first, second, other), other))
    }

    @Test
    fun `an output waits to install while the app is still a queued input`() {
        val patched = item(key = APP).copy(
            state = BatchItemState.SUCCEEDED,
            patchedFile = Files.createTempFile("patched", ".apk").toFile().apply { deleteOnExit() }
        )
        val waiting = item(key = "$APP#2").copy(
            input = SelectedApp.Installed(packageName = APP, version = "1.0")
        )
        val running = BatchRunState(
            items = listOf(patched, waiting),
            phase = BatchPhase.RUNNING,
            policy = BatchInstallPolicy.SAVE_ONLY
        )

        assertFalse(running.canInstallBatchItem(patched))
        assertTrue(running.copy(phase = BatchPhase.FINISHED).canInstallBatchItem(patched))
        assertTrue(
            running.copy(items = listOf(patched, waiting.copy(state = BatchItemState.SUCCEEDED)))
                .canInstallBatchItem(patched)
        )
    }

    private fun item(key: String) = BatchPatchItem(
        packageName = APP,
        appName = "App",
        version = "1.0",
        versionCode = null,
        input = null,
        selection = mapOf(1 to setOf("Patch")),
        options = emptyMap(),
        bundles = emptyList(),
        state = BatchItemState.READY,
        key = key
    )

    private fun entry(bundle: Int, key: String = APP) = ManualBatchPatchEntry(
        input = SelectedApp.Local(
            packageName = APP,
            version = "1.0",
            file = File("app.apk"),
            temporary = false
        ),
        selection = mapOf(bundle to setOf("Patch")),
        options = emptyMap(),
        useMount = false,
        key = key
    )

    private companion object {
        const val APP = "com.example.app"
    }
}

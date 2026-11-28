package app.universal.patch.manager.domain.batch

import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.data.room.apps.installed.InstalledApp
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchPatchingContractTest {
    @Test
    fun `user batch selection requires at least two apps`() {
        assertFalse(isUserBatchSelectionAllowed(0))
        assertFalse(isUserBatchSelectionAllowed(1))
        assertTrue(isUserBatchSelectionAllowed(2))
        assertTrue(isUserBatchSelectionAllowed(8))
    }

    @Test
    fun `user batch keeps patched output temporary until the user acts`() {
        assertFalse(shouldPersistBatchOutputImmediately(scheduled = false))
        assertTrue(shouldPersistBatchOutputImmediately(scheduled = true))
    }

    @Test
    fun `only temporary uninstalled outputs require save before leaving`() {
        val output = File.createTempFile("batch-output", ".apk")
        try {
            val temporary = batchItem("temporary").copy(patchedFile = output)
            val saved = temporary.copy(savedForLater = true)
            val installed = temporary.copy(
                installOutcome = BatchInstallOutcome.INSTALLED,
                installedPackageName = "temporary"
            )

            assertTrue(temporary.needsSaveBeforeLeaving)
            assertFalse(saved.needsSaveBeforeLeaving)
            assertFalse(installed.needsSaveBeforeLeaving)
        } finally {
            output.delete()
        }
    }

    @Test
    fun `user batch requires two runnable apps after exclusions`() {
        val oneRunnable = BatchRunState(
            items = listOf(
                batchItem("ready", state = BatchItemState.READY),
                batchItem("excluded", state = BatchItemState.EXCLUDED)
            ),
            phase = BatchPhase.PREFLIGHT,
            policy = BatchInstallPolicy.SAVE_ONLY
        )
        val twoRunnable = oneRunnable.copy(
            items = listOf(
                batchItem("first", state = BatchItemState.READY),
                batchItem("second", state = BatchItemState.READY)
            )
        )
        val scheduled = oneRunnable.copy(scheduled = true)

        assertFalse(oneRunnable.canStartBatchPatch())
        assertTrue(twoRunnable.canStartBatchPatch())
        assertTrue(scheduled.canStartBatchPatch())
    }

    @Test
    fun `old saved entry is kept when target migration fails`() {
        assertTrue(canDeleteReplacedSavedEntry(true))
        assertFalse(canDeleteReplacedSavedEntry(false))
    }

    @Test
    fun `pending external installer blocks another external installer`() {
        assertTrue(canLaunchExternalBatchInstaller(false))
        assertFalse(canLaunchExternalBatchInstaller(true))
    }

    @Test
    fun `timed out external installer is waited on before the next installer`() {
        assertFalse(shouldWaitForExternalBatchInstaller(false, timedOut = false))
        assertFalse(shouldWaitForExternalBatchInstaller(true, timedOut = false))
        assertTrue(shouldWaitForExternalBatchInstaller(true, timedOut = true))
    }

    @Test
    fun `reordered batch follows requested execution order`() {
        val items = listOf(
            batchItem("one"),
            batchItem("two"),
            batchItem("three"),
            batchItem("unlisted")
        )

        assertEquals(
            listOf("three", "one", "two", "unlisted"),
            reorderBatchItems(items, listOf("three", "one", "two"))
                .map { it.packageName }
        )
    }

    @Test
    fun `manual configuration keeps only available patches and options`() {
        val bundles = listOf(
            BatchBundleRef(
                uid = 7,
                name = "Test bundle",
                version = "1.0",
                patchNames = setOf("Keep", "Other")
            )
        )
        val selection = mapOf(
            7 to setOf("Keep", "Removed"),
            9 to setOf("Missing bundle patch")
        )
        val options = mapOf(
            7 to mapOf(
                "Keep" to mapOf("key" to "value"),
                "Removed" to mapOf("old" to true)
            ),
            9 to mapOf(
                "Missing bundle patch" to mapOf("ignored" to 1)
            )
        )

        val (sanitizedSelection, sanitizedOptions) = sanitizeBatchConfiguration(
            selection = selection,
            options = options,
            bundles = bundles
        )

        assertEquals(mapOf(7 to setOf("Keep")), sanitizedSelection)
        assertEquals(
            mapOf(7 to mapOf("Keep" to mapOf("key" to "value"))),
            sanitizedOptions
        )
    }

    @Test
    fun `restored result rebuilds its patch selection from metadata`() {
        val payload = PatchProfilePayload(
            bundles = listOf(
                PatchProfilePayload.Bundle(
                    bundleUid = 3,
                    patches = listOf("Patch A", "Patch B"),
                    options = emptyMap()
                ),
                PatchProfilePayload.Bundle(
                    bundleUid = 4,
                    patches = emptyList(),
                    options = emptyMap()
                )
            )
        )

        assertEquals(
            mapOf(3 to setOf("Patch A", "Patch B")),
            restoreBatchSelection(payload)
        )
        assertTrue(restoreBatchSelection(null).isEmpty())
    }

    @Test
    fun `legacy blank original package resolves from the saved entry key`() {
        val legacy = InstalledApp(
            currentPackageName = "com.example.app__bundle_variant",
            originalPackageName = "",
            version = "1.0",
            installType = InstallType.SAVED,
            sortOrder = 0,
            createdAt = 1L
        )

        assertEquals("com.example.app", batchOriginalPackageName(legacy))
    }

    @Test
    fun `shortcut remains only while another saved copy exists`() {
        val sibling = InstalledApp(
            currentPackageName = "com.example.app__bundle_two",
            originalPackageName = "com.example.app",
            version = "1.0",
            installType = InstallType.SAVED,
            sortOrder = 0,
            createdAt = 2L
        )

        assertTrue(
            hasBatchShortcutTarget(
                records = listOf(sibling),
                originalPackageName = "com.example.app",
                hasSavedCopy = { true }
            )
        )
        assertFalse(
            hasBatchShortcutTarget(
                records = listOf(sibling),
                originalPackageName = "com.example.app",
                hasSavedCopy = { false }
            )
        )
    }

    @Test
    fun `batch request identity distinguishes different result sources`() {
        val userResult = batchPlanRequestKey(
            packageNames = listOf("one", "two", "one"),
            showExistingResult = true,
            requestId = "user-session"
        )
        val automaticResult = batchPlanRequestKey(
            packageNames = listOf("one", "two"),
            showExistingResult = true,
            scheduled = true,
            requestId = "automatic-session"
        )
        val nextUserResult = batchPlanRequestKey(
            packageNames = listOf("one", "two"),
            showExistingResult = true,
            requestId = "next-user-session"
        )

        assertEquals(listOf("one", "two"), userResult.packageNames)
        assertFalse(userResult == automaticResult)
        assertFalse(userResult == nextUserResult)
        assertTrue(
            userResult.matchesBatchPlanRequest(
                userResult.copy(requestId = null)
            )
        )
        assertFalse(userResult.matchesBatchPlanRequest(nextUserResult))
        assertFalse(
            canOpenBatchPlan(
                currentPhase = BatchPhase.RUNNING,
                currentRequestKey = userResult,
                currentPackageNames = userResult.packageNames,
                currentScheduled = false,
                requestedKey = automaticResult
            )
        )
        assertTrue(
            canOpenBatchPlan(
                currentPhase = BatchPhase.FINISHED,
                currentRequestKey = userResult,
                currentPackageNames = userResult.packageNames,
                currentScheduled = false,
                requestedKey = automaticResult
            )
        )
        assertEquals(BatchResultStore.USER, batchResultStore(false))
        assertEquals(BatchResultStore.AUTOMATIC, batchResultStore(true))
    }

    @Test
    fun `finished batch restoration only accepts the same screen session`() {
        assertTrue(matchesBatchResultSession("session-one", "session-one"))
        assertFalse(matchesBatchResultSession("session-one", "session-two"))
        assertFalse(matchesBatchResultSession(null, "session-one"))
        assertTrue(matchesBatchResultSession("session-one", null))
    }

    @Test
    fun `interrupted install fails only the app being attempted`() {
        val active = batchItem("active", installing = true)
        val untouched = batchItem("untouched", installing = true)
        val installed = batchItem("installed").copy(
            installOutcome = BatchInstallOutcome.INSTALLED
        )
        val result = finishInterruptedInstallState(
            BatchRunState(
                items = listOf(active, untouched, installed),
                phase = BatchPhase.INSTALLING,
                policy = BatchInstallPolicy.SAVE_ONLY,
                activeIndex = 0
            ),
            message = "Installation stopped"
        )

        assertEquals(BatchPhase.FINISHED, result.phase)
        assertEquals(BatchInstallOutcome.FAILED, result.items[0].installOutcome)
        assertEquals("Installation stopped", result.items[0].installMessage)
        assertFalse(result.items[0].installing)
        assertEquals(null, result.items[1].installOutcome)
        assertFalse(result.items[1].installing)
        assertEquals(BatchInstallOutcome.INSTALLED, result.items[2].installOutcome)
    }

    @Test
    fun `completed app is installable while another app is patching`() {
        val output = File.createTempFile("batch-install", ".apk")
        try {
            val finished = batchItem("finished").copy(patchedFile = output)
            val running = batchItem("running", state = BatchItemState.RUNNING)
            val state = BatchRunState(
                items = listOf(finished, running),
                phase = BatchPhase.RUNNING,
                policy = BatchInstallPolicy.SAVE_ONLY,
                activeIndex = 1
            )

            assertTrue(state.canInstallBatchItem(finished))
            assertFalse(state.canInstallBatchItem(running))
            assertFalse(state.canInstallBatchItem(finished.copy(saving = true)))
            assertFalse(state.canInstallBatchItem(finished.copy(
                installOutcome = BatchInstallOutcome.INSTALLED
            )))
            assertFalse(state.copy(phase = BatchPhase.CANCELLING).canInstallBatchItem(finished))
            assertFalse(state.copy(phase = BatchPhase.INSTALLING).canInstallBatchItem(finished))
            assertTrue(state.copy(phase = BatchPhase.FINISHED).canInstallBatchItem(finished))
        } finally {
            output.delete()
        }
    }

    @Test
    fun `another active install blocks per-app installation`() {
        val output = File.createTempFile("batch-install", ".apk")
        try {
            val ready = batchItem("ready").copy(patchedFile = output)
            val installing = batchItem("installing", installing = true)
            val state = BatchRunState(
                items = listOf(ready, installing),
                phase = BatchPhase.RUNNING,
                policy = BatchInstallPolicy.SAVE_ONLY,
                activeInstallKey = installing.packageName
            )

            assertFalse(state.canInstallBatchItem(ready))
        } finally {
            output.delete()
        }
    }

    @Test
    fun `interrupting per-app installation preserves the patch queue`() {
        val installing = batchItem("installing", installing = true)
        val running = batchItem("running", state = BatchItemState.RUNNING)
        val queued = batchItem("queued", state = BatchItemState.READY)
        val state = BatchRunState(
            items = listOf(installing, running, queued),
            phase = BatchPhase.RUNNING,
            policy = BatchInstallPolicy.INSTALL_AFTER,
            activeIndex = 1,
            activeInstallKey = installing.packageName,
            progress = 0.4f,
            detail = running.appName
        )
        val result = interruptBatchItemInstall(state, installing.packageName, "Cancelled")

        assertEquals(BatchPhase.RUNNING, result.phase)
        assertEquals(1, result.activeIndex)
        assertEquals(0.4f, result.progress)
        assertEquals(running.appName, result.detail)
        assertEquals(BatchInstallOutcome.FAILED, result.items[0].installOutcome)
        assertFalse(result.items[0].installing)
        assertEquals(null, result.activeInstallKey)
        assertEquals(running, result.items[1])
        assertEquals(queued, result.items[2])
    }

    @Test
    fun `install cancellation after success does not overwrite the result`() {
        val installed = batchItem("installed").copy(
            installOutcome = BatchInstallOutcome.INSTALLED
        )
        val state = BatchRunState(
            items = listOf(installed),
            phase = BatchPhase.RUNNING,
            policy = BatchInstallPolicy.SAVE_ONLY
        )

        assertEquals(installed, interruptBatchItemInstall(state, "installed", "Cancelled").items[0])
    }

    @Test
    fun `saved entry replacement respects overwrite preference`() {
        val existingKey = "com.example.app__bundle_existing"

        assertEquals(
            existingKey,
            selectBatchSavedEntryKey(
                packageName = "com.example.app",
                variantIdentity = "variant",
                overwriteDisabled = false,
                matchingEntryKey = existingKey,
                uniqueSuffix = "12345678"
            )
        )
        val protectedKey = selectBatchSavedEntryKey(
            packageName = "com.example.app",
            variantIdentity = "variant",
            overwriteDisabled = true,
            matchingEntryKey = existingKey,
            uniqueSuffix = "12345678"
        )
        assertTrue(protectedKey.endsWith("__12345678"))
        assertFalse(protectedKey == existingKey)
    }

    @Test
    fun `restored terminal item without events does not expose progress details`() {
        assertFalse(batchItem("restored").hasProgressDetails)
        assertTrue(
            batchItem("running", state = BatchItemState.RUNNING).hasProgressDetails
        )
    }

    @Test
    fun `persisted logs keep the newest text within the total budget`() {
        assertEquals(
            listOf("c", "dddd"),
            takeLastWithinCharacterBudget(
                lines = listOf("aaaa", "bb", "c", "dddd"),
                maxCharacters = 6
            )
        )
        assertEquals(
            listOf("cdef"),
            takeLastWithinCharacterBudget(
                lines = listOf("abcdef"),
                maxCharacters = 4
            )
        )
    }

    @Test
    fun `batch output pruning retains paths from valid saved results only`() {
        val userOutput = File("user-batch.apk").absolutePath
        val automaticOutput = File("automatic-batch.apk").absolutePath
        val userSnapshot = BatchResultSnapshot(
            completedAt = 1L,
            policy = BatchInstallPolicy.SAVE_ONLY.name,
            scheduled = false,
            items = listOf(resultItem("user", userOutput))
        )
        val automaticSnapshot = BatchResultSnapshot(
            completedAt = 2L,
            policy = BatchInstallPolicy.SAVE_ONLY.name,
            scheduled = true,
            items = listOf(resultItem("automatic", automaticOutput))
        )

        assertEquals(
            setOf(userOutput, automaticOutput),
            retainedBatchOutputPaths(
                Json.Default,
                listOf(
                    "not-json",
                    Json.Default.encodeToString(userSnapshot),
                    Json.Default.encodeToString(automaticSnapshot)
                )
            )
        )
    }

    private fun resultItem(packageName: String, path: String) = BatchResultItemSnapshot(
        packageName = packageName,
        appName = packageName,
        version = "1.0",
        versionCode = 1L,
        state = BatchItemState.SUCCEEDED.name,
        message = null,
        patchedFilePath = path,
        installOutcome = null,
        installMessage = null,
        installedPackageName = null
    )

    private fun batchItem(
        packageName: String,
        installing: Boolean = false,
        state: BatchItemState = BatchItemState.SUCCEEDED
    ) = BatchPatchItem(
        packageName = packageName,
        appName = packageName,
        version = "1.0",
        versionCode = 1L,
        input = null,
        selection = emptyMap(),
        options = emptyMap(),
        bundles = emptyList(),
        state = state,
        installing = installing
    )
}

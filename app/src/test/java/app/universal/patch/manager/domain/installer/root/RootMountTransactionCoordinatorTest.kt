package app.universal.patch.manager.domain.installer.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RootMountTransactionCoordinatorTest {
    @Test
    fun `archive repair can reuse installed splits only when the full stock set matches`() = runBlocking {
        val stock = splitState()
        val fixture = Fixture(initial = stock)
        fixture.store.committed = committed(stock).copy(active = false, status = "REPAIR_REQUIRED")
        val cache = kotlin.io.path.createTempDirectory().toFile()
        try {
            fixture.coordinator.restoreCommittedPayload(PACKAGE, 0, cache)
            assertEquals(
                listOf(requireNotNull(stock.basePath)) + stock.splitPaths.sorted(),
                fixture.module.installedStockPathsForRestore
            )

            fixture.reader.state = stock.copy(splitSha256 = stock.splitSha256.mapValues { testSha256("changed") })
            fixture.coordinator.restoreCommittedPayload(PACKAGE, 0, cache)
            assertEquals(emptyList<String>(), fixture.module.installedStockPathsForRestore)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `same version split replacement cannot reuse the previous mount`() = runBlocking {
        val split = splitState()
        val fixture = Fixture(initial = split)
        val previous = committed(split).copy(active = false, status = "STOCK")
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.reader.state = split.copy(splitSha256 = split.splitSha256.mapValues { testSha256("changed") })
        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.MOUNT_ONLY))
        assertFalse(result is RootMountResult.Success)
        assertEquals(0, fixture.verifier.mountCalls)
        assertTrue(fixture.installer.replaceCalls.isEmpty())
    }

    @Test
    fun `complete split input installs and records every installed split`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        val base = fixture.artifact("base-v3.apk", 3, "stock-3")
        val split = fixture.artifact("split-v3.apk", 3, "en-3")
        fixture.reader.artifacts[split.first.path] = split.second.copy(splitName = "config.en", topology = "SPLIT")
        val splitPath = "/data/app/$PACKAGE/split_config.en.apk"
        fixture.installer.onReplace = { _, _ ->
            fixture.reader.state = fixture.rawState(3, "stock-3").copy(
                splitPaths = listOf(splitPath), splitSha256 = mapOf(splitPath to split.second.sha256)
            )
        }
        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.REPLACE_STOCK_AND_MOUNT, patched.first)
                .copy(stockApks = listOf(split.first, base.first))
        )
        assertIs<RootMountResult.Success>(result)
        assertEquals(listOf(split.first, base.first), fixture.installer.lastInstalledFiles)
        assertEquals(mapOf("split_config.en.apk" to split.second.sha256), fixture.store.committed?.stockSplits)
    }

    @Test
    fun `split package mounts and remounts with its complete identity`() = runBlocking {
        val split = splitState()
        val fixture = Fixture(initial = split)
        val patched = fixture.artifact("patched.apk", 2, "patched")
        val stock = fixture.artifact("stock.apk", 2, "stock-2")
        assertIs<RootMountResult.Success>(fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched.first, stock.first)
        ))
        val mounted = requireNotNull(fixture.store.committed)
        assertEquals(split.verifiedSplits(), mounted.stockSplits)
        assertEquals("SPLIT", mounted.topology)
        fixture.module.snapshotHash = mounted.patchedSha256
        assertIs<RootMountResult.Success>(fixture.coordinator.execute(fixture.request(RootMountOperation.MOUNT_ONLY)))
        assertEquals(0, fixture.installer.replaceCalls.size)
    }

    @Test
    fun `split stock replacement failure restores every original APK`() = runBlocking {
        val split = splitState()
        val fixture = Fixture(initial = split)
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.onReplace = { _, _ ->
            fixture.reader.state = fixture.rawState(3, "stock-3").copy(splitPaths = emptyList(), splitSha256 = emptyMap())
        }
        fixture.installer.onBackupRestore = { fixture.reader.state = split }
        fixture.verifier.verifyFailure = IllegalStateException("mount failed")
        val failure = assertIs<RootMountResult.Failure>(fixture.coordinator.execute(
            fixture.request(RootMountOperation.REPLACE_STOCK_AND_MOUNT, patched.first, stock.first)
        ))
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(listOf(split.baseSha256) + split.verifiedSplits().toSortedMap().values,
            fixture.installer.restoredBackups.map { it.sha256 })
        assertEquals(split, fixture.reader.state)
    }

    @Test
    fun `mounting is refused when the package is installed for another Android user`() = runBlocking {
        val fixture = Fixture()
        fixture.reader.installedUsers = setOf(0, 10)

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertTrue(failure.message.contains("also installed for 10"))
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(0, fixture.verifier.removeCalls)
        assertTrue(fixture.scheduler.scheduledUsers.isEmpty())
    }

    @Test
    fun `mount only retargets an unchanged stock APK after Android moves its path`() = runBlocking {
        val movedPath = "/data/app/new/$PACKAGE/base.apk"
        val fixture = Fixture(initial = defaultState().copy(basePath = movedPath))
        val previous = committed(defaultState()).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(movedPath, fixture.module.updatedStates.single().stockPath)
        assertEquals(movedPath, fixture.store.committed?.stockPath)
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(1, fixture.verifier.verifyCalls)
    }

    @Test
    fun `mount only retarget failure does not restore an older module snapshot`() = runBlocking {
        val movedPath = "/data/app/new/$PACKAGE/base.apk"
        val fixture = Fixture(initial = defaultState().copy(basePath = movedPath))
        val previous = committed(defaultState()).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.verifyFailure = IllegalStateException("retargeted mount verification failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(movedPath, fixture.module.updatedStates.last().stockPath)
        assertEquals(false, fixture.store.committed?.active)
    }

    @Test
    fun `mount only reuses a verified module before inspecting recovery payloads`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        val ambiguousPayload = fixture.artifact("saved-or-stock.apk", 2, "ambiguous")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = ambiguousPayload.first,
                stock = ambiguousPayload.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `mount only avoids full APK snapshots when the saved module is already verified`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.committedState = previous

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.module.snapshotCalls)
        assertEquals(0, fixture.module.stockSnapshotCalls)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(0, fixture.module.ensureSpaceCalls)
    }

    @Test
    fun `mount only does not reuse a module with a different stock target`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.committedState = previous.copy(
            stockPath = "/data/app/other/base.apk"
        )
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.snapshotCalls)
    }

    @Test
    fun `mount only does not reuse an unexpectedly active module`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.committedState = previous.copy(
            active = true,
            status = "MOUNTED"
        )
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.snapshotCalls)
    }

    @Test
    fun `mount only refuses committed state that still requires repair`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "REPAIR_REQUIRED"
        )
        fixture.store.committed = previous
        fixture.module.committedState = previous.copy(status = "STOCK")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.PREPARING, failure.phase)
        assertEquals(RootRecoveryState.NONE, failure.recoveryState)
        assertTrue(failure.message.contains("repair", ignoreCase = true))
        assertEquals(0, fixture.module.enableCalls)
        assertEquals(0, fixture.verifier.verifyCalls)
        assertTrue(fixture.scheduler.scheduledUsers.isEmpty())
    }

    @Test
    fun `reused inactive module is not restored from a nonexistent snapshot after mount failure`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.committedState = previous
        fixture.verifier.verifyFailure = IllegalStateException("Mount verification failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.VERIFYING, failure.phase)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(0, fixture.module.snapshotCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertTrue(fixture.module.disabled)
        assertEquals(false, fixture.store.committed?.active)
        assertEquals("STOCK", fixture.store.committed?.status)
    }

    @Test
    fun `stock rollback verifies a surviving target process sees the stock apk`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.store.committed = previous
        fixture.module.committedState = previous
        fixture.reader.stops = false
        fixture.reader.running = listOf(2468)
        fixture.verifier.verifyFailure = IllegalStateException("Mount verification failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(2, fixture.verifier.stockProcessVerifyCalls)
        assertEquals(listOf(listOf(2468), listOf(2468)), fixture.verifier.verifiedStockProcessPids)
        assertEquals(false, fixture.store.committed?.active)
        assertEquals("STOCK", fixture.store.committed?.status)
    }

    @Test
    fun `mount only rejects an ambiguous recovery payload when the module is missing`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.module.snapshotHash = null
        val ambiguousPayload = fixture.artifact("saved-or-stock.apk", 2, "ambiguous")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = ambiguousPayload.first,
                stock = ambiguousPayload.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertTrue(failure.message.contains("separate verified APKs"))
        assertEquals(0, fixture.module.stageCalls)
    }

    @Test
    fun `mount only rebuilds a missing module from the saved payload`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.module.snapshotHash = null
        val patched = fixture.artifact("saved-patched.apk", 2, "saved-patched")
        val stock = fixture.artifact("installed-stock.apk", 2, "stock-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `fresh root install can replace an inactive module with a missing backup`() = runBlocking {
        for (stockInstalled in listOf(true, false)) {
            val fixture = Fixture()
            fixture.store.committed = committed(fixture.initial).copy(active = false, status = "STOCK")
            fixture.module.snapshotHash = null
            if (!stockInstalled) {
                fixture.reader.state = fixture.initial.copy(
                    installed = false, basePath = null, baseSha256 = null
                )
            }
            val (patched, artifact) = fixture.artifact("fresh-patched.apk", 3, "patched-3")
            val (stock, _) = fixture.artifact("fresh-stock.apk", 3, "stock-3")
            fixture.installer.onReplace = { _, _ -> fixture.reader.state = fixture.rawState(3, "stock-3") }

            val result = fixture.coordinator.execute(
                fixture.request(RootMountOperation.REPLACE_STOCK_AND_MOUNT, patched, stock)
            )

            assertIs<RootMountResult.Success>(result)
            assertEquals(artifact.sha256, fixture.store.committed?.patchedSha256)
            assertEquals("MOUNTED", fixture.store.committed?.status)
        }
    }

    @Test
    fun `mount only recovers verified module state when transaction state is missing`() = runBlocking {
        val fixture = Fixture()
        fixture.module.committedState = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        fixture.module.snapshotHash = fixture.module.committedState?.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `active transaction does not promote unfinished module state to committed`() = runBlocking {
        val fixture = Fixture()
        fixture.module.committedState = committed(fixture.initial).copy(
            transactionId = "unfinished-module-state"
        )
        fixture.store.active = journal(
            RootMountPhase.STAGING_PATCHED_PAYLOAD,
            fixture.initial
        ).copy(
            stockMutationStarted = false,
            registrationGap = false,
            moduleMutationStarted = true,
            moduleRestoreRequired = true,
            mountMutationStarted = false
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(0, fixture.module.readCommittedStateCalls)
        assertEquals(null, fixture.store.committed)
    }

    @Test
    fun `mount only rebuilds when committed state is missing`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("saved-patched.apk", 2, "saved-patched")
        val stock = fixture.artifact("installed-stock.apk", 2, "stock-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `mount only rebuilds after corrupt committed state recovers to stock`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptCommitted = true
        val patched = fixture.artifact("saved-patched.apk", 2, "saved-patched")
        val stock = fixture.artifact("installed-stock.apk", 2, "stock-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `corrupt committed recovery verifies stock in a restarted target process`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptCommitted = true
        fixture.reader.stopResults.addAll(listOf(true, false))
        fixture.reader.runningResults.addAll(
            listOf(listOf(9753), listOf(9753), listOf(9753))
        )

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(2, fixture.verifier.stockProcessVerifyCalls)
        assertEquals(
            listOf(listOf(9753), listOf(9753)),
            fixture.verifier.verifiedStockProcessPids
        )
        assertFalse(fixture.store.committedExists(PACKAGE))
    }

    @Test
    fun `caller cancellation cannot interrupt corrupt committed recovery`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptCommitted = true
        lateinit var executionJob: Job
        fixture.verifier.onRemove = {
            executionJob.cancel(CancellationException("caller cancelled corrupt committed recovery"))
            yield()
        }

        val execution = async {
            executionJob = requireNotNull(coroutineContext[Job])
            fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))
        }

        assertFailsWith<CancellationException> { execution.await() }
        assertTrue(fixture.verifier.removeCalls > 0)
        assertTrue(fixture.module.disabled)
        assertFalse(fixture.store.committedExists(PACKAGE))
    }

    @Test
    fun `rebuild preparation failure reports corrupt committed stock recovery`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptCommitted = true
        fixture.module.spaceFailure = IllegalStateException("Insufficient rollback space")
        val patched = fixture.artifact("saved-patched.apk", 2, "saved-patched")
        val stock = fixture.artifact("installed-stock.apk", 2, "stock-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.PREPARING, failure.phase)
        assertEquals(RootRecoveryState.NONE, failure.recoveryState)
        assertEquals("The stock app was restored and left unmounted.", failure.describeOutcome())
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `rebuild preparation failure reports corrupt committed uninstalled recovery`() = runBlocking {
        val fixture = Fixture(
            initial = defaultState().copy(
                installed = false,
                basePath = null,
                baseSha256 = null
            )
        )
        fixture.store.corruptCommitted = true
        fixture.module.spaceFailure = IllegalStateException("Insufficient rollback space")
        val patched = fixture.artifact("saved-patched.apk", 2, "saved-patched")
        val stock = fixture.artifact("installed-stock.apk", 2, "stock-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.PREPARING, failure.phase)
        assertEquals(RootRecoveryState.NONE, failure.recoveryState)
        assertEquals(
            "Automatic recovery removed stale root mount state and left the app uninstalled.",
            failure.describeOutcome()
        )
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `bundle switch never calls PackageInstaller`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(0, fixture.module.stockSnapshotCalls)
        assertEquals(listOf(true), fixture.verifier.lazyRecoveryCalls)
        assertEquals(listOf(0), fixture.scheduler.scheduledUsers)
    }

    @Test
    fun `successful bundle switch cleans the transient module snapshot after commit`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        var cleanupSawDurableCompletionMarker = false
        fixture.module.onCleanupCommittedSnapshot = {
            cleanupSawDurableCompletionMarker =
                fixture.store.active?.phase == RootMountPhase.COMPLETED &&
                    fixture.store.committed?.status == "MOUNTED"
        }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertTrue(cleanupSawDurableCompletionMarker)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `snapshot cleanup failure does not roll back a committed bundle switch`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.module.cleanupCommittedSnapshotFailure = IllegalStateException("cleanup failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertTrue(fixture.store.diagnostics.any { it.contains("transient module snapshot cleanup failed") })

        fixture.module.cleanupCommittedSnapshotFailure = null
        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertEquals(2, fixture.module.cleanupCommittedSnapshotCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `bundle switch succeeds when target app immediately restarts`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.reader.stopResults.addAll(listOf(false, false, false))
        fixture.reader.running = listOf(2468)

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals(2, fixture.verifier.processVerifyCalls)
        assertEquals(listOf(listOf(2468), listOf(2468)), fixture.verifier.verifiedProcessPids)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertTrue(fixture.store.diagnostics.any { it.contains("restart-tolerant mounting") })
        assertTrue(fixture.store.diagnostics.any { it.contains("running process namespaces") })
    }

    @Test
    fun `bundle switch verifies the replacement pid when target process changes during verification`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.reader.stopResults.addAll(listOf(false, false, false))
        fixture.reader.runningResults.addAll(
            listOf(listOf(1001), listOf(1002), listOf(1002), listOf(1002))
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(3, fixture.verifier.processVerifyCalls)
        assertEquals(
            listOf(listOf(1001), listOf(1002), listOf(1002)),
            fixture.verifier.verifiedProcessPids
        )
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `bundle switch rechecks the pid set after the second namespace probe`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.reader.stopResults.addAll(listOf(false, false, false))
        fixture.reader.running = listOf(1002)
        fixture.reader.runningResults.addAll(
            listOf(
                listOf(1001),
                listOf(1001),
                listOf(1002),
                listOf(1002),
                listOf(1002)
            )
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(4, fixture.verifier.processVerifyCalls)
        assertEquals(
            listOf(listOf(1001), listOf(1001), listOf(1002), listOf(1002)),
            fixture.verifier.verifiedProcessPids
        )
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `bundle switch rolls back when restarted target process cannot see patched mount`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.reader.stopResults.addAll(listOf(false, false, false))
        fixture.reader.running = listOf(1357)
        fixture.verifier.processVerifyFailure = IllegalStateException("target process kept stale mount")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(1, fixture.module.restoreCalls)
    }

    @Test
    fun `failed module mutation journal write does not restore an untouched module`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.store.writeActiveFailureOnceWhen = { journal ->
            journal.phase == RootMountPhase.STAGING_PATCHED_PAYLOAD &&
                journal.moduleMutationStarted == true &&
                journal.moduleRestoreRequired == true
        }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `bundle switch retargets an unchanged stock apk after Android moves its data path`() = runBlocking {
        val oldStock = defaultState()
        val relocatedStock = oldStock.copy(basePath = "/data/app/relocated/$PACKAGE/base.apk")
        val fixture = Fixture(relocatedStock)
        val previous = committed(oldStock)
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(relocatedStock.basePath, fixture.store.committed?.stockPath)
        assertEquals(relocatedStock.baseSha256, fixture.store.committed?.stockSha256)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
    }

    @Test
    fun `unmount returns busy without rollback when target restarts before any mutation`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.reader.stops = false

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT)
        )

        val busy = assertIs<RootMountResult.Busy>(result)
        assertEquals(RootMountPhase.STOPPING_APP, busy.phase)
        assertEquals(0, fixture.verifier.removeCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertTrue(fixture.store.diagnostics.any { it.contains("before changing mounts") })
    }

    @Test
    fun `initial force stop shell failure leaves the existing mount untouched`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.shell.failOnceOn = "am force-stop"

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.STOPPING_APP, failure.phase)
        assertEquals(RootRecoveryState.NONE, failure.recoveryState)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `bundle switch accepts a patched version code override with exact stock proof`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v2.apk", 2, "stock-2")
        val patched = fixture.artifact(
            "patched-max-version.apk",
            Int.MAX_VALUE.toLong(),
            "patched-max-version"
        )
        fixture.reader.artifacts[patched.first.path] = patched.second.copy(versionName = "2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            ).copy(
                expectedVersionName = "2",
                expectedVersionCode = Int.MAX_VALUE.toLong(),
                expectedStockVersionCode = 2
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
        assertEquals(2, fixture.store.committed?.versionCode)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `bundle switch keeps committed stock identity while the registered base is mounted`() = runBlocking {
        val stockState = defaultState()
        val mountedState = stockState.copy(baseSha256 = testSha256("previous-patched"))
        val fixture = Fixture(mountedState)
        val previous = committed(stockState)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.onRemove = { fixture.reader.state = stockState }
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val registeredStock = File(requireNotNull(fixture.initial.basePath))

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = registeredStock
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(stockState.baseSha256, fixture.store.committed?.stockSha256)
        assertEquals(listOf(patched.first.absolutePath), fixture.reader.inspectedPaths)
    }

    @Test
    fun `mount only rebuild uses committed stock identity behind the mounted base`() = runBlocking {
        val stockState = defaultState()
        val previous = committed(stockState)
        val fixture = Fixture(stockState.copy(baseSha256 = previous.patchedSha256))
        fixture.store.committed = previous
        // The module needs rebuilding, but the old bind is still visible at sourceDir.
        fixture.module.snapshotHash = null
        fixture.verifier.onRemove = { fixture.reader.state = stockState }
        val patched = fixture.artifact("saved-patched.apk", 2, "saved-patched")
        val registeredStock = File(requireNotNull(stockState.basePath))

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.MOUNT_ONLY,
                patched = patched.first,
                stock = registeredStock
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(stockState.baseSha256, fixture.store.committed?.stockSha256)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertEquals(listOf(patched.first.absolutePath), fixture.reader.inspectedPaths)
    }

    @Test
    fun `progress observer failures cannot alter a verified transaction`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val stock = fixture.artifact("stock-v2.apk", 2, "stock-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            )
        ) { throw IllegalStateException("detached UI observer") }

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
    }

    @Test
    fun `rollback progress cancellation cannot interrupt mandatory recovery`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.transientVerifyFailures = 1
        var rollbackProgressCalls = 0

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        ) { phase ->
            if (phase == RootMountPhase.ROLLING_BACK) {
                rollbackProgressCalls++
                throw CancellationException("detached UI cancelled rollback progress")
            }
        }

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(1, rollbackProgressCalls)
        assertEquals(1, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `failure rollback completes when caller is cancelled during recovery`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.transientVerifyFailures = 1
        fixture.verifier.onRemove = {
            if (fixture.verifier.removeCalls >= 2) yield()
        }

        val execution = async {
            val executionJob = requireNotNull(coroutineContext[Job])
            fixture.coordinator.execute(
                fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
            ) { phase ->
                if (phase == RootMountPhase.ROLLING_BACK) {
                    executionJob.cancel(CancellationException("caller cancelled during rollback"))
                }
            }
        }

        assertFailsWith<CancellationException> { execution.await() }
        assertEquals(1, fixture.module.restoreCalls)
        assertTrue(fixture.verifier.removeCalls >= 2)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `cancellation from completed progress does not roll back a committed mount`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        assertFailsWith<CancellationException> {
            fixture.coordinator.execute(
                fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
            ) { phase ->
                if (phase == RootMountPhase.COMPLETED) {
                    throw CancellationException("cancelled after commit")
                }
            }
        }

        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(1, fixture.verifier.removeCalls)
    }

    @Test
    fun `cancellation before mutation clears the journal and releases the package lock`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val stock = fixture.artifact("stock-v2.apk", 2, "stock-2")
        fixture.shell.cancelOnceOn = "am force-stop"
        val phases = mutableListOf<RootMountPhase>()

        assertFailsWith<CancellationException> {
            fixture.coordinator.execute(
                fixture.request(
                    RootMountOperation.SWITCH_PATCHED_BUILD,
                    patched = patched.first,
                    stock = stock.first
                ),
                phases::add
            )
        }

        assertFalse(RootMountPhase.ROLLING_BACK in phases)
        assertEquals(1, fixture.lock.releaseCalls)
        assertEquals(null, fixture.store.active)
        assertFalse(fixture.module.disabled)
        assertEquals(0, fixture.verifier.removeCalls)
    }

    @Test
    fun `cancellation after stock commit restores the saved stock snapshot`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.onReplaceAttempt = { _, _ ->
            fixture.reader.state = fixture.rawState(3, "stock-3")
            throw CancellationException("cancelled after PackageInstaller commit")
        }
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        assertFailsWith<CancellationException> {
            fixture.coordinator.execute(
                fixture.request(
                    RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                    patched = patched.first,
                    stock = stock.first
                )
            ) { phase ->
                if (phase == RootMountPhase.ROLLING_BACK) {
                    throw CancellationException("detached UI cancelled rollback progress")
                }
            }
        }

        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertEquals(fixture.initial, fixture.reader.state)
        assertEquals(null, fixture.store.active)
    }

    @Test
    fun `cancellation reports a failed rollback instead of hiding it`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.onReplaceAttempt = { _, _ ->
            fixture.reader.state = fixture.rawState(3, "stock-3")
            throw CancellationException("cancelled after PackageInstaller commit")
        }
        fixture.installer.backupRestoreResult = Result.failure(
            IllegalStateException("saved stock could not be restored")
        )

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Failure>(result)
        assertTrue(result.message.contains("automatic recovery failed"))
    }

    @Test
    fun `cancellation during rollback is recovered and still reaches the caller`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )
        var cancelRollback = true
        fixture.verifier.onRemove = {
            if (cancelRollback) {
                cancelRollback = false
                throw CancellationException("cancelled during rollback")
            }
        }

        assertFailsWith<CancellationException> {
            fixture.coordinator.execute(
                fixture.request(
                    RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                    patched = patched.first,
                    stock = stock.first
                )
            )
        }

        assertEquals(2, fixture.verifier.removeCalls)
        assertEquals(null, fixture.store.active)
        assertTrue(fixture.module.disabled)
        assertEquals(1, fixture.lock.releaseCalls)
    }

    @Test
    fun `same-version stock mismatch fails closed without PackageInstaller`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("rebuilt-stock-v2.apk", 2, "different-stock-2")
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertTrue(failure.message.contains("Same-version stock input"))
        assertEquals(emptyList(), fixture.installer.replaceCalls)
        assertEquals(0, fixture.module.stageCalls)
    }

    @Test
    fun `bundle switch refuses to mount without exact stock proof`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(emptyList(), fixture.scheduler.scheduledUsers)
    }

    @Test
    fun `bundle switch rejects a patched payload from another version`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.stageCalls)
    }

    @Test
    fun `verified legacy module migrates into committed transaction state`() = runBlocking {
        val raw = defaultState()
        val fixture = Fixture(initial = raw.copy(baseSha256 = testSha256("legacy-patched")))
        val patched = fixture.artifact("legacy-patched-v2.apk", 2, "legacy-patched")
        fixture.module.legacyPayload = RootBackupArtifact(
            RootPaths.moduleApk(PACKAGE),
            testSha256("legacy-patched")
        )
        fixture.module.snapshotHash = testSha256("legacy-patched")
        fixture.verifier.onRemove = { fixture.reader.state = raw }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(testSha256("stock-2"), fixture.store.committed?.stockSha256)
        assertEquals(testSha256("legacy-patched"), fixture.store.committed?.patchedSha256)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
        assertEquals(fixture.module.legacyPayload, fixture.module.preferredSnapshotPayload)
    }

    @Test
    fun `verified legacy mount can switch to a different patched build`() = runBlocking {
        val raw = defaultState()
        val fixture = Fixture(initial = raw.copy(baseSha256 = testSha256("legacy-patched")))
        val patched = fixture.artifact("new-patched-v2.apk", 2, "new-patched")
        fixture.module.legacyPayload = RootBackupArtifact(
            RootPaths.moduleApk(PACKAGE),
            testSha256("legacy-patched")
        )
        fixture.module.snapshotHash = testSha256("legacy-patched")
        fixture.verifier.onRemove = { fixture.reader.state = raw }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(testSha256("stock-2"), fixture.store.committed?.stockSha256)
        assertEquals(testSha256("new-patched"), fixture.store.committed?.patchedSha256)
        assertEquals(fixture.module.legacyPayload, fixture.module.preferredSnapshotPayload)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
    }

    @Test
    fun `unmounting a verified legacy mount commits a disabled module that can remount`() = runBlocking {
        val raw = defaultState()
        val legacyHash = testSha256("legacy-patched")
        val fixture = Fixture(initial = raw.copy(baseSha256 = legacyHash))
        fixture.module.legacyPayload = RootBackupArtifact(
            RootPaths.moduleApk(PACKAGE),
            legacyHash
        )
        fixture.module.snapshotHash = legacyHash
        fixture.verifier.mounts += MountInfoEntry(
            mountId = 1,
            parentId = 0,
            root = "/",
            mountPoint = requireNotNull(fixture.initial.basePath),
            options = setOf("rw"),
            fileSystem = "ext4",
            source = RootPaths.moduleApk(PACKAGE),
            superOptions = setOf("rw")
        )
        fixture.verifier.onRemove = { fixture.reader.state = raw }

        val unmount = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT)
        )

        assertIs<RootMountResult.Success>(unmount)
        assertEquals(1, fixture.module.stageCalls)
        assertTrue(fixture.module.disabled)
        assertEquals(false, fixture.store.committed?.active)
        assertEquals("STOCK", fixture.store.committed?.status)
        assertEquals(legacyHash, fixture.store.committed?.patchedSha256)
        assertEquals(testSha256("stock-2"), fixture.store.committed?.stockSha256)

        val remount = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        assertIs<RootMountResult.Success>(remount)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `unmount accepts structurally valid split stock without committed state`() = runBlocking {
        val split = splitState()
        val fixture = Fixture(initial = split)

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.UNMOUNT))

        assertIs<RootMountResult.Success>(result)
        assertEquals(null, fixture.store.active)
        assertEquals(null, fixture.store.committed)
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `stale legacy module cannot authorize a bundle switch`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("new-patched-v2.apk", 2, "new-patched")
        fixture.module.legacyPayload = RootBackupArtifact(
            RootPaths.moduleApk(PACKAGE),
            testSha256("legacy-patched")
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(emptyList(), fixture.scheduler.scheduledUsers)
    }

    @Test
    fun `bundle switch rejects stock that differs from committed hash`() = runBlocking {
        val fixture = Fixture(initial = defaultState().copy(baseSha256 = testSha256("rebuilt-stock-2")))
        val previous = committed(defaultState())
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
    }

    @Test
    fun `unmount reports success only after exact raw stock is proven`() = runBlocking {
        val raw = defaultState()
        val fixture = Fixture(initial = raw.copy(baseSha256 = testSha256("previous-patched")))
        fixture.store.committed = committed(raw)
        fixture.verifier.onRemove = { fixture.reader.state = raw }

        assertIs<RootMountResult.Success>(
            fixture.coordinator.execute(fixture.request(RootMountOperation.UNMOUNT))
        )
        assertEquals("STOCK", fixture.store.committed?.status)
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
        assertEquals(listOf(true), fixture.verifier.lazyRecoveryCalls)

        val mismatch = Fixture(initial = raw.copy(baseSha256 = testSha256("previous-patched")))
        mismatch.store.committed = committed(raw)
        mismatch.verifier.onRemove = {
            mismatch.reader.state = raw.copy(baseSha256 = testSha256("untrusted-stock"))
        }

        assertIs<RootMountResult.Failure>(
            mismatch.coordinator.execute(mismatch.request(RootMountOperation.UNMOUNT))
        )
        assertTrue(mismatch.scheduler.stoppedPackages.isEmpty())
    }

    @Test
    fun `unmount snapshot cleanup failure remains discoverable for startup retry`() = runBlocking {
        val raw = defaultState()
        val fixture = Fixture(initial = raw.copy(baseSha256 = testSha256("previous-patched")))
        fixture.store.committed = committed(raw)
        fixture.verifier.onRemove = { fixture.reader.state = raw }
        fixture.module.cleanupCommittedSnapshotFailure = IllegalStateException("cleanup failed")

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.UNMOUNT))

        assertIs<RootMountResult.Success>(result)
        assertEquals(false, fixture.store.committed?.active)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)

        fixture.module.cleanupCommittedSnapshotFailure = null
        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertEquals(2, fixture.module.cleanupCommittedSnapshotCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(false, fixture.store.committed?.active)
    }

    @Test
    fun `unmount retains a newer same signer split stock update`() = runBlocking {
        val raw = defaultState()
        val previous = committed(raw)
        val fixture = Fixture(initial = raw.copy(baseSha256 = previous.patchedSha256))
        fixture.store.committed = previous
        fixture.verifier.onRemove = {
            fixture.reader.state = fixture.rawState(3, "stock-3").copy(
                splitPaths = listOf("/data/app/$PACKAGE/split_config.en.apk"),
                splitSha256 = mapOf("/data/app/$PACKAGE/split_config.en.apk" to testSha256("en"))
            )
        }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
        assertEquals(0, fixture.installer.backupRestoreCalls)
    }

    @Test
    fun `saved root module removal is snapshot backed and recoverable`() = runBlocking {
        val raw = defaultState()
        val previous = committed(raw)
        val success = Fixture(initial = raw.copy(baseSha256 = previous.patchedSha256))
        success.store.committed = previous
        success.module.snapshotHash = previous.patchedSha256
        success.verifier.onRemove = { success.reader.state = raw }

        assertIs<RootMountResult.Success>(
            success.coordinator.execute(
                success.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
            )
        )
        assertEquals(1, success.module.removeCalls)
        assertEquals(1, success.module.purgeCalls)
        assertEquals(null, success.store.committed)

        val failure = Fixture(initial = raw.copy(baseSha256 = previous.patchedSha256))
        failure.store.committed = previous
        failure.module.snapshotHash = previous.patchedSha256
        failure.module.removeFailure = IllegalStateException("module deletion interrupted")
        failure.verifier.onRemove = { failure.reader.state = raw }

        val failedRemoval = assertIs<RootMountResult.Failure>(
            failure.coordinator.execute(
                failure.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
            )
        )
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failedRemoval.recoveryState)
        assertEquals(1, failure.module.restoreCalls)
        assertEquals(0, failure.module.purgeCalls)
    }

    @Test
    fun `permanent removal accepts changed stock without remounting the old payload`() = runBlocking {
        val raw = defaultState()
        val previous = committed(raw)
        val fixture = Fixture(initial = raw.copy(baseSha256 = previous.patchedSha256))
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.onRemove = {
            fixture.reader.state = raw.copy(
                baseSha256 = testSha256("changed-stock"),
                signerSha256 = testSha256("changed-signer"),
                launcherResolvable = false
            )
        }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.removeCalls)
        assertEquals(0, fixture.verifier.mountCalls)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertEquals(null, fixture.store.committed)
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `permanent removal refuses an unverifiable current split set`() = runBlocking {
        val raw = defaultState()
        val previous = committed(raw)
        val fixture = Fixture(initial = raw.copy(baseSha256 = previous.patchedSha256))
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.onRemove = {
            fixture.reader.state = raw.copy(
                baseSha256 = testSha256("changed-stock"),
                splitPaths = listOf("/data/app/$PACKAGE/split_config.en.apk"),
                splitSha256 = emptyMap()
            )
        }

        assertIs<RootMountResult.Failure>(
            fixture.coordinator.execute(
                fixture.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
            )
        )
        assertEquals(0, fixture.module.removeCalls)
    }

    @Test
    fun `permanent module removal accepts relocated unchanged stock`() = runBlocking {
        val raw = defaultState()
        val previous = committed(raw)
        val relocated = raw.copy(basePath = "/data/app/relocated/$PACKAGE/base.apk")
        val fixture = Fixture(initial = relocated)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.removeCalls)
        assertEquals(null, fixture.store.committed)
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `permanent module removal succeeds after package uninstall`() = runBlocking {
        val installed = defaultState()
        val previous = committed(installed)
        val fixture = Fixture(
            initial = RootPackageState(
                packageName = PACKAGE,
                userId = 0,
                installed = false
            )
        )
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.verifier.removeCalls)
        assertEquals(1, fixture.module.removeCalls)
        assertEquals(1, fixture.module.purgeCalls)
        assertEquals(null, fixture.store.committed)
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `ordinary unmount still rejects an uninstalled package`() = runBlocking {
        val installed = defaultState()
        val fixture = Fixture(
            initial = RootPackageState(
                packageName = PACKAGE,
                userId = 0,
                installed = false
            )
        )
        fixture.store.committed = committed(installed)

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(0, fixture.module.removeCalls)
    }

    @Test
    fun `backup cleanup failure cannot roll back a committed permanent removal`() = runBlocking {
        val raw = defaultState()
        val previous = committed(raw)
        val fixture = Fixture(initial = raw.copy(baseSha256 = previous.patchedSha256))
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.module.purgeFailure = IllegalStateException("cleanup failed")
        fixture.verifier.onRemove = { fixture.reader.state = raw }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT).copy(removeModuleAfterUnmount = true)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.purgeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(null, fixture.store.committed)
        assertTrue(fixture.store.diagnostics.any { it.contains("backup cleanup failed") })
    }

    @Test
    fun `recovery clears stale committed metadata from interrupted initial commit`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial).copy(transactionId = "unproven-new-commit")
        fixture.store.active = journal(RootMountPhase.COMMITTING, fixture.initial)
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(null, fixture.store.committed)
    }

    @Test
    fun `recovery accepts unchanged split package from repair-required unmount`() = runBlocking {
        val split = splitState()
        val fixture = Fixture(initial = split)
        fixture.store.active = journal(
            RootMountPhase.ROLLING_BACK,
            split
        ).copy(
            operation = RootMountOperation.UNMOUNT,
            stockMutationStarted = false,
            registrationGap = false,
            status = "REPAIR_REQUIRED"
        )

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(null, fixture.store.active)
        assertEquals(null, fixture.store.committed)
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `request for another Android user cannot mutate committed state`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT).copy(userId = 10)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
        assertEquals(0, fixture.module.stageCalls)
    }

    @Test
    fun `manual recovery cannot mutate another Android user transaction`() = runBlocking {
        val fixture = Fixture()
        val foreignJournal = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial.copy(userId = 10)
        )
        fixture.store.active = foreignJournal

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertTrue(failure.message.contains("belongs to Android user 10"))
        assertEquals(foreignJournal, fixture.store.active)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertFalse(fixture.module.disabled)
    }

    @Test
    fun `bundle switch stops before staging when previous payload snapshot is not proven`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        val patched = fixture.artifact("patched-v2-rebuilt.apk", 2, "patched-2-rebuilt")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(0, fixture.module.stageCalls)
        assertEquals(0, fixture.installer.replaceCalls.size)
    }

    @Test
    fun `upgrade uses replace and never uninstall`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.onReplace = { _, _ -> fixture.reader.state = fixture.rawState(3, "stock-3") }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(listOf(false), fixture.installer.replaceCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
        assertTrue(fixture.reader.stopChecks >= 3)
        assertTrue(fixture.shell.commands.any { it.contains("wait-for-background-handler") })
    }

    @Test
    fun `stock replacement keeps the original enabled and launcher expectations`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.onReplace = { _, _ ->
            fixture.reader.state = fixture.rawState(3, "stock-3").copy(
                enabled = false,
                launcherResolvable = false
            )
        }

        fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertEquals(true, fixture.reader.lastExpected?.enabled)
        assertEquals(true, fixture.reader.lastExpected?.launcherResolvable)
    }

    @Test
    fun `ambiguous upgrade failure restores previous verified stock`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("commit response lost"))
        )
        fixture.installer.onReplaceAttempt = { _, _ -> fixture.reader.state = fixture.rawState(3, "unproven-stock") }
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertEquals(fixture.initial, fixture.reader.state)
    }

    @Test
    fun `state read failure after a stock install attempt still restores the backup`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("commit response lost"))
        )
        fixture.installer.onReplaceAttempt = { _, _ ->
            fixture.reader.state = fixture.rawState(3, "stock-3")
            fixture.reader.readFailures = 1
        }
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertEquals(fixture.initial, fixture.reader.state)
    }

    @Test
    fun `ambiguous replacement response succeeds only after exact stable state is proven`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("commit response lost"))
        )
        fixture.installer.onReplaceAttempt = { _, _ -> fixture.reader.state = fixture.rawState(3, "stock-3") }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
    }

    @Test
    fun `proven in-place downgrade never creates a confirmed fallback gap`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("commit response lost"))
        )
        fixture.installer.onReplaceAttempt = { _, _ -> fixture.reader.state = fixture.rawState(1, "stock-1") }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first,
                confirmed = true
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.installer.uninstallCalls)
    }

    @Test
    fun `rollback space failure and strict process ownership uncertainty abort before mount mutation`() = runBlocking {
        val lowStorage = Fixture()
        val patched = lowStorage.artifact("patched-v2.apk", 2, "patched-2")
        val stock = lowStorage.artifact("stock-v2.apk", 2, "stock-2")
        lowStorage.module.spaceFailure = IllegalStateException("insufficient rollback space")

        val storageResult = lowStorage.coordinator.execute(
            lowStorage.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Failure>(storageResult)
        assertEquals(null, lowStorage.store.active)
        assertEquals(0, lowStorage.verifier.removeCalls)
        assertEquals(0, lowStorage.module.stageCalls)

        val uncertainProcesses = Fixture()
        val secondPatched = uncertainProcesses.artifact("other-patched-v2.apk", 2, "other-patched-2")
        val secondStock = uncertainProcesses.artifact("other-stock-v2.apk", 2, "stock-2")
        uncertainProcesses.reader.stops = false

        val processResult = uncertainProcesses.coordinator.execute(
            uncertainProcesses.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = secondPatched.first,
                stock = secondStock.first
            )
        )

        assertIs<RootMountResult.Busy>(processResult)
        assertEquals(0, uncertainProcesses.verifier.removeCalls)
        assertEquals(0, uncertainProcesses.module.stageCalls)
        assertEquals(0, uncertainProcesses.installer.replaceCalls.size)
    }

    @Test
    fun `downgrade rejection requests confirmation before registration mutation`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.RequiresDowngradeConfirmation>(result)
        assertEquals(listOf(true), fixture.installer.replaceCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
        assertEquals(fixture.initial, fixture.reader.state)
    }

    @Test
    fun `downgrade confirmation keeps the inactive mount when its archived payload is intact`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.rawState(1, "stock-1")).copy(active = false, status = "REPATCH_REQUIRED")
        fixture.store.committed = previous
        fixture.module.inactiveSnapshotHash = previous.patchedSha256
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.REPLACE_STOCK_AND_MOUNT, patched.first, stock.first)
        )

        assertIs<RootMountResult.RequiresDowngradeConfirmation>(result)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
        assertEquals(0, fixture.module.disableCompetingCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
    }

    @Test
    fun `confirmed archive downgrade cannot replace stock that changed after the prompt`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        val approvedStock = fixture.initial.copy(baseSha256 = testSha256("earlier-stock"))

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT, patched.first, stock.first, confirmed = true
            ).copy(downgradeFallbackStock = approvedStock)
        )

        assertFalse(result is RootMountResult.Success)
        assertTrue(fixture.installer.replaceCalls.isEmpty())
        assertEquals(0, fixture.installer.uninstallCalls)
    }

    @Test
    fun `ordinary downgrade install failure never offers destructive fallback`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("insufficient storage"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first,
                confirmed = true
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(0, fixture.installer.uninstallCalls)
    }

    @Test
    fun `failed first stock install restores the original uninstalled state`() = runBlocking {
        val initial = RootPackageState(packageName = PACKAGE, userId = 0, installed = false)
        val fixture = Fixture(initial)
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("insufficient storage"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(initial, fixture.reader.state)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertFalse(fixture.store.committedExists(PACKAGE))
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `confirmed downgrade restores stock when uninstall fails after removing registration`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )
        fixture.installer.onUninstall = {
            fixture.reader.state = fixture.initial.copy(installed = false, basePath = null, baseSha256 = null)
            throw IllegalStateException("uninstall acknowledgement lost")
        }
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first,
                confirmed = true
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(1, fixture.installer.uninstallCalls)
        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertEquals(fixture.initial, fixture.reader.state)
    }

    @Test
    fun `confirmed downgrade restores stock when install fails`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("install failed"))
        )
        fixture.installer.onUninstall = {
            fixture.reader.state = fixture.initial.copy(installed = false, basePath = null, baseSha256 = null)
        }
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first,
                confirmed = true
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(listOf(true, false), fixture.installer.replaceCalls)
        assertEquals(1, fixture.installer.backupRestoreCalls)
    }

    @Test
    fun `confirmed downgrade restores stock when PackageManager never stabilizes`() = runBlocking {
        val fixture = Fixture()
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )
        fixture.installer.onUninstall = {
            fixture.reader.state = fixture.initial.copy(installed = false, basePath = null, baseSha256 = null)
        }
        fixture.installer.onReplace = { call, _ ->
            if (call == 2) fixture.reader.state = fixture.rawState(1, "stock-1")
        }
        fixture.reader.stabilityFailures = 1
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first,
                confirmed = true
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertEquals(fixture.initial, fixture.reader.state)
    }

    @Test
    fun `system app downgrade never removes per-user registration`() = runBlocking {
        val fixture = Fixture(initial = defaultState().copy(systemApp = true))
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.DowngradeRejected(IllegalStateException("downgrade rejected"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first,
                confirmed = true
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(0, fixture.installer.uninstallCalls)
        assertTrue(fixture.reader.state.installed)
    }

    @Test
    fun `partial system package disappearance restores registration before backup`() = runBlocking {
        val fixture = Fixture(initial = defaultState().copy(systemApp = true))
        val stock = fixture.artifact("stock-v1.apk", 1, "stock-1")
        val patched = fixture.artifact("patched-v1.apk", 1, "patched-1")
        fixture.installer.replaceResults.add(
            RootPackageReplaceResult.Failure(IllegalStateException("session result lost"))
        )
        fixture.installer.onReplaceAttempt = { _, _ ->
            fixture.reader.state = fixture.initial.copy(installed = false, basePath = null, baseSha256 = null)
        }
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(1, fixture.installer.restoreRegistrationCalls)
        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertEquals(0, fixture.installer.uninstallCalls)
    }

    @Test
    fun `recovery skips untouched pre-mutation journals and stays fail-safe after mutation`() = runBlocking {
        RootMountPhase.entries.forEach { phase ->
            val fixture = Fixture()
            fixture.store.active = journal(phase, fixture.initial)
            fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

            val result = fixture.coordinator.execute(
                fixture.request(RootMountOperation.RECOVER)
            )

            val mayHaveMutated =
                phase == RootMountPhase.ROLLING_BACK || phase >= RootMountPhase.REMOVING_OLD_MOUNTS
            if (mayHaveMutated) {
                assertIs<RootMountResult.RecoveredToStock>(result, "phase=$phase")
                assertTrue(fixture.module.disabled, "phase=$phase")
            } else {
                assertIs<RootMountResult.Success>(result, "phase=$phase")
                assertFalse(fixture.module.disabled, "phase=$phase")
                assertEquals(0, fixture.verifier.removeCalls, "phase=$phase")
            }
            assertEquals(null, fixture.store.active, "phase=$phase")
        }
    }

    @Test
    fun `interrupted pre-mutation transaction leaves an existing committed mount untouched`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous)

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.module.disabled)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `untouched journal cleanup failure never triggers rollback`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous)
        fixture.store.clearActiveFailure = IllegalStateException("transaction storage is read-only")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.STOPPING_APP, failure.phase)
        assertEquals(RootRecoveryState.NONE, failure.recoveryState)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.module.disabled)
        assertTrue(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
        assertTrue(fixture.store.diagnostics.any { it.contains("left for a later retry") })
    }

    @Test
    fun `persisted rollback with a false mount marker is recovered conservatively`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous).copy(
            phase = RootMountPhase.ROLLING_BACK,
            rollbackFromPhase = RootMountPhase.STOPPING_APP,
            stockMutationStarted = false,
            registrationGap = false,
            mountMutationStarted = false
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToPreviousMount>(result)
        assertTrue(fixture.verifier.removeCalls > 0)
        assertTrue(
            fixture.store.activeWrites.any {
                it.phase == RootMountPhase.ROLLING_BACK && it.mountMutationStarted == true
            }
        )
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `rollback completed journal clear failure keeps the restored previous mount retryable`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.transientVerifyFailures = 1
        fixture.store.clearActiveFailure = IllegalStateException("rollback journal delete failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(1, fixture.module.restoreCalls)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)

        fixture.store.clearActiveFailure = null
        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `new mount records its identity before discarding the previous archive`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        val patched = fixture.artifact("replacement.apk", 2, "replacement-patched")
        val commitsAtCleanup = mutableListOf<RootCommittedState?>()
        fixture.module.onCommitSnapshot = { commitsAtCleanup += fixture.store.committed }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(listOf(patched.second.sha256), commitsAtCleanup.map { it?.patchedSha256 })
        assertEquals(true, commitsAtCleanup.single()?.active)
    }

    @Test
    fun `recovery retries archive cleanup for a durable mount`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        val patched = fixture.artifact("replacement.apk", 2, "replacement-patched")
        var attempts = 0
        fixture.module.onCommitSnapshot = {
            assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)
            if (++attempts == 1) error("Root storage temporarily unavailable")
        }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)
        assertEquals(0, fixture.module.disableCompetingCalls)

        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertEquals(2, attempts)
        assertEquals(1, fixture.module.disableCompetingCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `ambiguous previous rollback completion cleans snapshot before clearing journal`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.transientVerifyFailures = 1
        fixture.store.completeFailureAfterDurableWriteOnce =
            IllegalStateException("completion response was lost")
        var cleanupSawDurableCompletion = false
        fixture.module.onCleanupCommittedSnapshot = {
            cleanupSawDurableCompletion = fixture.store.active?.phase == RootMountPhase.COMPLETED
        }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertTrue(cleanupSawDurableCompletion)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `ambiguous rollback snapshot cleanup failure remains retryable`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.verifier.transientVerifyFailures = 1
        fixture.store.completeFailureAfterDurableWriteOnce =
            IllegalStateException("completion response was lost")
        fixture.module.cleanupCommittedSnapshotFailure = IllegalStateException("cleanup failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.PREVIOUS_MOUNT, failure.recoveryState)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)

        fixture.module.cleanupCommittedSnapshotFailure = null
        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertEquals(2, fixture.module.cleanupCommittedSnapshotCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `ambiguous stock fallback completion cleans snapshot before clearing journal`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.module.restoreResult = false
        fixture.verifier.transientVerifyFailures = 1
        fixture.store.completeFailureAfterDurableWriteOnce =
            IllegalStateException("completion response was lost")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals(1, fixture.module.restoreCalls)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(false, fixture.store.committed?.active)
    }

    @Test
    fun `completed journal clear failure keeps the newly committed mount retryable`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.store.clearActiveFailure = IllegalStateException("active journal delete failed")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(1, fixture.verifier.removeCalls)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)

        fixture.store.clearActiveFailure = null
        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `completed journal clear cancellation leaves a retry marker without rolling back`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.store.clearActiveFailure = CancellationException("active journal delete cancelled")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.SWITCH_PATCHED_BUILD, patched = patched.first)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(patched.second.sha256, fixture.store.committed?.patchedSha256)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(1, fixture.verifier.removeCalls)
        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)

        fixture.store.clearActiveFailure = null
        val recovered = fixture.coordinator.recoverIncompleteTransactions(0)

        assertIs<RootMountResult.Success>(recovered[PACKAGE])
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `completed journal does not roll back an already durable commit`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val completed = previous.copy(
            transactionId = "completed",
            committedAtEpochMs = 2
        )
        fixture.store.committed = completed
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous).copy(
            transactionId = completed.transactionId,
            phase = RootMountPhase.COMPLETED,
            mountMutationStarted = true,
            completionStateRecorded = true,
            completionCommittedState = completed
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(completed, fixture.store.committed)
        assertTrue(fixture.store.diagnostics.any { it.contains("already durable") })
    }

    @Test
    fun `completed recovery snapshot cleanup cancellation propagates`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val completed = previous.copy(
            transactionId = "completed",
            committedAtEpochMs = 2
        )
        fixture.store.committed = completed
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous).copy(
            transactionId = completed.transactionId,
            phase = RootMountPhase.COMPLETED,
            mountMutationStarted = true,
            completionStateRecorded = true,
            completionCommittedState = completed
        )
        fixture.module.cleanupCommittedSnapshotFailure =
            CancellationException("snapshot cleanup cancelled")

        assertFailsWith<CancellationException> {
            fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))
        }

        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)
        assertEquals(completed, fixture.store.committed)
    }

    @Test
    fun `completed recovery journal cleanup cancellation propagates`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val completed = previous.copy(
            transactionId = "completed",
            committedAtEpochMs = 2
        )
        fixture.store.committed = completed
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous).copy(
            transactionId = completed.transactionId,
            phase = RootMountPhase.COMPLETED,
            mountMutationStarted = true,
            completionStateRecorded = true,
            completionCommittedState = completed
        )
        fixture.store.clearActiveFailure =
            CancellationException("completed journal cleanup cancelled")

        assertFailsWith<CancellationException> {
            fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))
        }

        assertEquals(RootMountPhase.COMPLETED, fixture.store.active?.phase)
        assertEquals(completed, fixture.store.committed)
    }

    @Test
    fun `legacy completed journal preserves a matching durable commit`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val completed = previous.copy(
            transactionId = "legacy-completed",
            committedAtEpochMs = 2
        )
        fixture.store.committed = completed
        fixture.store.active = journal(RootMountPhase.COMPLETED, fixture.initial, previous).copy(
            transactionId = completed.transactionId
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(completed, fixture.store.committed)
    }

    @Test
    fun `legacy completed unmount preserves an inactive durable commit`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val inactive = previous.copy(
            active = false,
            status = "STOCK",
            committedAtEpochMs = 2
        )
        fixture.store.committed = inactive
        fixture.store.active = journal(RootMountPhase.COMPLETED, fixture.initial, previous).copy(
            operation = RootMountOperation.UNMOUNT
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(inactive, fixture.store.committed)
    }

    @Test
    fun `legacy completed mount preserves a reactivated durable commit`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(
            active = false,
            status = "STOCK"
        )
        val mounted = previous.copy(
            active = true,
            status = "MOUNTED",
            committedAtEpochMs = 2
        )
        fixture.store.committed = mounted
        fixture.store.active = journal(RootMountPhase.COMPLETED, fixture.initial, previous).copy(
            operation = RootMountOperation.MOUNT_ONLY
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(mounted, fixture.store.committed)
    }

    @Test
    fun `legacy completed repeat mount preserves a matching durable commit`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val completed = previous.copy(committedAtEpochMs = 2)
        fixture.store.committed = completed
        fixture.store.active = journal(RootMountPhase.COMPLETED, fixture.initial, previous).copy(
            operation = RootMountOperation.MOUNT_ONLY,
            stockMutationStarted = false,
            registrationGap = false
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.restoreCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(completed, fixture.store.committed)
    }

    @Test
    fun `legacy completed reconciliation without final-state proof rolls back conservatively`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(RootMountPhase.COMPLETED, fixture.initial, previous).copy(
            operation = RootMountOperation.RECONCILE,
            stockMutationStarted = false,
            registrationGap = false
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToPreviousMount>(result)
        assertTrue(fixture.verifier.removeCalls > 0)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `legacy completed permanent removal preserves cleared committed metadata`() = runBlocking {
        val fixture = Fixture()
        fixture.store.active = journal(RootMountPhase.COMPLETED, fixture.initial).copy(
            operation = RootMountOperation.UNMOUNT,
            status = "MODULE_REMOVED"
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(null, fixture.store.committed)
    }

    @Test
    fun `completed marker without its committed write still rolls back`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val intended = previous.copy(
            transactionId = "new-completed",
            patchedSha256 = testSha256("new-completed-patch"),
            committedAtEpochMs = 2
        )
        fixture.store.committed = previous
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial, previous).copy(
            transactionId = intended.transactionId,
            phase = RootMountPhase.COMPLETED,
            stockMutationStarted = false,
            registrationGap = false,
            moduleMutationStarted = false,
            moduleRestoreRequired = false,
            mountMutationStarted = true,
            completionStateRecorded = true,
            completionCommittedState = intended
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToPreviousMount>(result)
        assertTrue(fixture.verifier.removeCalls > 0)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(previous.patchedSha256, fixture.store.committed?.patchedSha256)
    }

    @Test
    fun `completed null commit does not resurrect module state`() = runBlocking {
        val fixture = Fixture()
        fixture.module.committedState = committed(fixture.initial).copy(active = false, status = "STOCK")
        fixture.store.active = journal(RootMountPhase.STOPPING_APP, fixture.initial).copy(
            phase = RootMountPhase.COMPLETED,
            mountMutationStarted = true,
            completionStateRecorded = true,
            completionCommittedState = null
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(0, fixture.module.readCommittedStateCalls)
        assertEquals(0, fixture.verifier.removeCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(null, fixture.store.committed)
    }

    @Test
    fun `mount request continues after discarding an untouched interrupted transaction`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val stock = fixture.artifact("stock-v2.apk", 2, "stock-2")
        fixture.store.active = journal(RootMountPhase.PREPARING, fixture.initial)

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(null, fixture.store.active)
        assertEquals(1, fixture.lock.releaseCalls)
    }

    @Test
    fun `interrupted recovery progress cancellation cannot block mandatory rollback`() = runBlocking {
        val fixture = Fixture()
        fixture.store.active = journal(RootMountPhase.REMOVING_OLD_MOUNTS, fixture.initial)
        var rollbackProgressCalls = 0

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        ) { phase ->
            if (phase == RootMountPhase.ROLLING_BACK) {
                rollbackProgressCalls++
                throw CancellationException("detached UI cancelled interrupted recovery")
            }
        }

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(1, rollbackProgressCalls)
        assertTrue(fixture.verifier.removeCalls > 0)
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `caller cancellation cannot interrupt rollback of an already mutated journal`() = runBlocking {
        val fixture = Fixture()
        fixture.store.active = journal(RootMountPhase.REMOVING_OLD_MOUNTS, fixture.initial)
        fixture.verifier.onRemove = { yield() }

        val execution = async {
            val executionJob = requireNotNull(coroutineContext[Job])
            fixture.coordinator.execute(
                fixture.request(RootMountOperation.RECOVER)
            ) { phase ->
                if (phase == RootMountPhase.ROLLING_BACK) {
                    executionJob.cancel(CancellationException("caller cancelled interrupted recovery"))
                }
            }
        }

        assertFailsWith<CancellationException> { execution.await() }
        assertTrue(fixture.verifier.removeCalls > 0)
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `resumed preparation failure preserves interrupted recovery outcome`() = runBlocking {
        val fixture = Fixture()
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val stock = fixture.artifact("stock-v2.apk", 2, "stock-2")
        fixture.store.active = journal(RootMountPhase.REMOVING_OLD_MOUNTS, fixture.initial)
        fixture.lock.onRelease = {
            if (fixture.lock.releaseCalls == 1) {
                fixture.store.readActiveFailure = IllegalStateException("State read failed")
            }
        }

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            )
        )

        val failure = assertIs<RootMountResult.Failure>(result)
        assertEquals(RootMountPhase.PREPARING, failure.phase)
        assertEquals(RootRecoveryState.STOCK, failure.recoveryState)
        assertEquals("The stock app was restored and left unmounted.", failure.describeOutcome())
        assertEquals(2, fixture.lock.releaseCalls)
    }

    @Test
    fun `mount only reports repatch after recovery retains updated stock`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val repatch = assertIs<RootMountResult.RequiresRepatch>(result)
        assertTrue(repatch.reason.contains("APK set"))
        assertFalse(repatch.reason.contains("Repatch it"))
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertEquals(1, fixture.lock.releaseCalls)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
    }

    @Test
    fun `stock replacement does not resume after recovery retains updated stock`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val patched = fixture.artifact("patched-v2.apk", 2, "patched-2")
        val stock = fixture.artifact("stock-v2.apk", 2, "stock-2")
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.REPLACE_STOCK_AND_MOUNT,
                patched = patched.first,
                stock = stock.first
            )
        )

        assertIs<RootMountResult.RequiresRepatch>(result)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
        assertEquals(1, fixture.lock.releaseCalls)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
    }

    @Test
    fun `fresh patch for recovered updated stock resumes automatically`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val patched = fixture.artifact("patched-v3.apk", 3, "patched-3")
        val stock = fixture.artifact("stock-v3.apk", 3, "stock-3")
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            fixture.request(
                RootMountOperation.SWITCH_PATCHED_BUILD,
                patched = patched.first,
                stock = stock.first
            ).copy(
                expectedVersionName = "3",
                expectedVersionCode = 3,
                expectedStockVersionCode = 3
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.stageCalls)
        assertEquals(2, fixture.lock.releaseCalls)
        assertEquals(3, fixture.store.committed?.versionCode)
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `mount only immediately reports a persisted repatch requirement`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial).copy(
            active = false,
            status = "REPATCH_REQUIRED"
        )
        fixture.reader.state = fixture.rawState(3, "stock-3").copy(
            splitPaths = listOf("/data/app/$PACKAGE/split_config.en.apk"),
            splitSha256 = mapOf("/data/app/$PACKAGE/split_config.en.apk" to testSha256("en"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.MOUNT_ONLY)
        )

        val repatch = assertIs<RootMountResult.RequiresRepatch>(result)
        assertTrue(repatch.reason.contains("APK set"))
        assertFalse(repatch.reason.contains("Repatch it"))
        assertEquals(1, fixture.reader.readCalls)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.module.snapshotCalls)
        assertTrue(fixture.scheduler.scheduledPackages.isEmpty())
        assertEquals(null, fixture.store.active)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
        assertEquals(1, fixture.lock.releaseCalls)
    }

    @Test
    fun `manual mount retries a stale repatch status when stock identity still matches`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial).copy(active = false, status = "REPATCH_REQUIRED")
        fixture.store.committed = previous
        fixture.module.snapshotHash = previous.patchedSha256
        fixture.reader.state = fixture.initial.copy(launcherResolvable = false)

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.MOUNT_ONLY))

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertEquals(true, fixture.store.committed?.active)
        assertEquals(emptyList(), fixture.installer.replaceCalls)
    }

    @Test
    fun `unmount does not restart after recovery already leaves verified stock`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.UNMOUNT)
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.lock.releaseCalls)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
    }

    @Test
    fun `permanent unmount finishes module removal after updated stock recovery`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")
        fixture.module.snapshotHash = previous.patchedSha256

        val result = fixture.coordinator.execute(
            RootMountRequest(
                packageName = PACKAGE,
                operation = RootMountOperation.UNMOUNT,
                removeModuleAfterUnmount = true
            )
        )

        assertIs<RootMountResult.Success>(result)
        assertEquals(2, fixture.lock.releaseCalls)
        assertEquals(1, fixture.module.removeCalls)
        assertEquals(null, fixture.store.committed)
    }

    @Test
    fun `interrupted recovery retains a newer same signer stock update`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertEquals(null, fixture.store.active)
        assertEquals(false, fixture.store.committed?.active)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `interrupted recovery retains a newer same signer split stock update`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3").copy(
            splitPaths = listOf("/data/app/$PACKAGE/split_config.en.apk"),
            splitSha256 = mapOf("/data/app/$PACKAGE/split_config.en.apk" to testSha256("en"))
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
        assertTrue(fixture.module.disabled)
    }

    @Test
    fun `interrupted recovery rejects an external update with an unsafe split path`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3").copy(
            splitPaths = listOf("/data/app/$PACKAGE/../untrusted.apk")
        )
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToPreviousMount>(result)
        assertEquals(1, fixture.installer.backupRestoreCalls)
    }

    @Test
    fun `interrupted recovery retains an update below the requested replacement`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v4.apk",
                packageName = PACKAGE,
                versionName = "4",
                versionCode = 4,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v4")
            )
        )
        fixture.reader.state = fixture.rawState(3, "stock-3")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
    }

    @Test
    fun `interrupted recovery does not mistake transaction installed stock for an external update`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        val requested = fixture.rawState(3, "stock-3")
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v3.apk",
                packageName = PACKAGE,
                versionName = requested.versionName,
                versionCode = requireNotNull(requested.versionCode),
                signerSha256 = requested.signerSha256,
                sha256 = requireNotNull(requested.baseSha256)
            )
        )
        fixture.reader.state = requested
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToPreviousMount>(result)
        assertEquals(1, fixture.installer.backupRestoreCalls)
    }

    @Test
    fun `corrupt journal recovery retains a newer same signer stock update`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.corruptActive = true
        fixture.reader.state = fixture.rawState(3, "stock-3")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertFalse(fixture.store.corruptActive)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
    }

    @Test
    fun `caller cancellation cannot interrupt corrupt journal recovery`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptActive = true
        lateinit var executionJob: Job
        fixture.verifier.onRemove = {
            executionJob.cancel(CancellationException("caller cancelled corrupt journal recovery"))
            yield()
        }

        val execution = async {
            executionJob = requireNotNull(coroutineContext[Job])
            fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))
        }

        assertFailsWith<CancellationException> { execution.await() }
        assertTrue(fixture.verifier.removeCalls > 0)
        assertTrue(fixture.module.disabled)
        assertFalse(fixture.store.corruptActive)
    }

    @Test
    fun `corrupt journal recovery verifies stock in a restarted target process`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.corruptActive = true
        fixture.reader.stopResults.addAll(listOf(true, false))
        fixture.reader.runningResults.addAll(
            listOf(listOf(2468), listOf(2468), listOf(2468))
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertEquals(2, fixture.verifier.stockProcessVerifyCalls)
        assertEquals(
            listOf(listOf(2468), listOf(2468)),
            fixture.verifier.verifiedStockProcessPids
        )
        assertFalse(fixture.store.corruptActive)
    }

    @Test
    fun `corrupt journal recovery fails closed when restarted process still sees a stale mount`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.corruptActive = true
        fixture.reader.stopResults.addAll(listOf(true, false))
        fixture.reader.running = listOf(1357)
        fixture.verifier.stockProcessVerifyFailure = IllegalStateException("target process kept stale mount")

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(1, fixture.verifier.stockProcessVerifyCalls)
        assertTrue(fixture.store.corruptActive)
        assertEquals("REPAIR_REQUIRED", fixture.store.committed?.status)
    }

    @Test
    fun `recovery never trusts a newer package signed by someone else`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial,
            previous
        ).copy(
            stockArtifact = RootArtifactState(
                path = "/cache/requested-v1.apk",
                packageName = PACKAGE,
                versionName = "1",
                versionCode = 1,
                signerSha256 = fixture.initial.signerSha256,
                sha256 = testSha256("requested-v1")
            )
        )
        fixture.reader.state = fixture.rawState(3, "foreign-stock").copy(
            signerSha256 = testSha256("foreign-signer")
        )

        val result = fixture.coordinator.execute(
            fixture.request(RootMountOperation.RECOVER)
        )

        assertIs<RootMountResult.Failure>(result)
        assertEquals(1, fixture.installer.backupRestoreCalls)
        assertTrue(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `automatic recovery refuses a corrupt journal with no Android user`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptActive = true

        val result = fixture.coordinator.recoverIncompleteTransactions(userId = 0)[PACKAGE]

        assertIs<RootMountResult.Failure>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertTrue(fixture.store.diagnostics.single().contains("no trustworthy Android user"))
    }

    @Test
    fun `automatic recovery reports one package read failure instead of throwing`() = runBlocking {
        val fixture = Fixture()
        fixture.store.active = journal(RootMountPhase.PREPARING, fixture.initial)
        fixture.store.readActiveFailure = IllegalStateException("active journal read failed")

        val result = fixture.coordinator.recoverIncompleteTransactions(userId = 0)[PACKAGE]

        val failure = assertIs<RootMountResult.Failure>(result)
        assertTrue(failure.message.contains("active journal read failed"))
        assertTrue(fixture.store.diagnostics.any { it.contains("Incomplete transaction scan failed") })
    }

    @Test
    fun `automatic recovery reports an invalid transaction package`() = runBlocking {
        val fixture = Fixture()
        val invalidPackage = "invalid"
        fixture.store.incompletePackages = listOf(invalidPackage)
        fixture.store.active = journal(RootMountPhase.PREPARING, fixture.initial)
            .copy(packageName = invalidPackage)

        val result = fixture.coordinator.recoverIncompleteTransactions(userId = 0)[invalidPackage]

        val failure = assertIs<RootMountResult.Failure>(result)
        assertTrue(failure.message.contains("Invalid package name"))
        assertTrue(fixture.store.diagnostics.any { it.contains("Incomplete transaction scan failed") })
    }

    @Test
    fun `automatic recovery leaves another Android user transaction untouched`() = runBlocking {
        val fixture = Fixture()
        fixture.store.active = journal(
            RootMountPhase.INSTALLING_STOCK,
            fixture.initial.copy(userId = 10)
        )

        val results = fixture.coordinator.recoverIncompleteTransactions(userId = 0)

        assertTrue(results.isEmpty())
        assertTrue(fixture.store.active != null)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.installer.backupRestoreCalls)
    }

    @Test
    fun `committed reconciliation cannot bypass unknown user journal refusal`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptActive = true
        fixture.scheduler.tracked += PACKAGE

        val recovery = fixture.coordinator.recoverIncompleteTransactions(userId = 0)[PACKAGE]
        val reconciliation = fixture.coordinator.reconcileCommittedTransactions(userId = 0)[PACKAGE]

        assertIs<RootMountResult.Failure>(recovery)
        assertIs<RootMountResult.Failure>(reconciliation)
        assertTrue(fixture.store.activeExists(PACKAGE))
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertFalse(fixture.module.disabled)
    }

    @Test
    fun `invalid committed state cannot authorize corrupt journal recovery`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptActive = true
        fixture.store.committed = committed(fixture.initial).copy(stockPath = "relative/base.apk")

        val result = fixture.coordinator.recoverIncompleteTransactions(userId = 0)[PACKAGE]

        assertIs<RootMountResult.Failure>(result)
        assertTrue(fixture.store.activeExists(PACKAGE))
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.installer.backupRestoreCalls)
        assertFalse(fixture.module.disabled)
    }

    @Test
    fun `recovery cannot report stock while a foreign mount remains`() = runBlocking {
        val fixture = Fixture()
        fixture.store.active = journal(RootMountPhase.INSTALLING_STOCK, fixture.initial)
        fixture.verifier.clearFailure = IllegalStateException("foreign mount remains")

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))

        assertIs<RootMountResult.Failure>(result)
        assertTrue(result.message.contains("automatic recovery failed"))
        assertTrue(fixture.store.active != null)
    }

    @Test
    fun `rollback remounts only a build compatible with restored stock`() = runBlocking {
        val compatible = Fixture()
        val previous = committed(compatible.initial)
        compatible.store.committed = previous
        compatible.store.active = journal(RootMountPhase.INSTALLING_STOCK, compatible.initial, previous)
        compatible.installer.onBackupRestore = { compatible.reader.state = compatible.initial }

        val restored = compatible.coordinator.execute(compatible.request(RootMountOperation.RECOVER))

        assertIs<RootMountResult.RecoveredToPreviousMount>(restored)
        assertEquals(1, compatible.verifier.verifyCalls)
        assertTrue(compatible.reader.stopChecks >= 2)

        val incompatible = Fixture()
        val stale = committed(incompatible.initial).copy(stockSha256 = testSha256("different-stock"))
        incompatible.store.committed = stale
        incompatible.store.active = journal(RootMountPhase.INSTALLING_STOCK, incompatible.initial, stale)
        incompatible.installer.onBackupRestore = { incompatible.reader.state = incompatible.initial }

        val leftStock = incompatible.coordinator.execute(incompatible.request(RootMountOperation.RECOVER))

        assertIs<RootMountResult.RecoveredToStock>(leftStock)
        assertEquals(0, incompatible.verifier.verifyCalls)
    }

    @Test
    fun `failed previous-mount verification is cleaned back to proven stock`() = runBlocking {
        val fixture = Fixture()
        val previous = committed(fixture.initial)
        fixture.store.committed = previous
        fixture.store.active = journal(RootMountPhase.INSTALLING_STOCK, fixture.initial, previous)
        fixture.installer.onBackupRestore = { fixture.reader.state = fixture.initial }
        fixture.verifier.verifyFailure = IllegalStateException("restored payload hash mismatch")

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.RECOVER))

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertTrue(fixture.verifier.removeCalls >= 2)
        assertEquals(false, fixture.store.committed?.active)
    }

    @Test
    fun `external exact match remounts while mismatch and removal remain stock`() = runBlocking {
        val exact = Fixture()
        exact.store.committed = committed(exact.initial)
        assertIs<RootMountResult.Success>(
            exact.coordinator.execute(exact.request(RootMountOperation.RECONCILE))
        )
        assertEquals(1, exact.verifier.verifyCalls)
        assertEquals(1, exact.module.enableCalls)

        val movedPath = Fixture(
            initial = defaultState().copy(basePath = "/data/app/new/$PACKAGE/base.apk")
        )
        movedPath.store.committed = committed(defaultState())
        assertIs<RootMountResult.Success>(
            movedPath.coordinator.execute(movedPath.request(RootMountOperation.RECONCILE))
        )
        assertEquals("/data/app/new/$PACKAGE/base.apk", movedPath.module.updatedStates.single().stockPath)
        assertEquals("/data/app/new/$PACKAGE/base.apk", movedPath.store.committed?.stockPath)
        val verifyingJournal = movedPath.store.activeWrites.single { it.phase == RootMountPhase.VERIFYING }
        assertEquals(true, verifyingJournal.moduleMutationStarted)
        assertEquals(false, verifyingJournal.moduleRestoreRequired)
        assertEquals("/data/app/new/$PACKAGE/base.apk", verifyingJournal.expectedPackageState?.basePath)
        assertEquals("MODULE_STATE_RETARGET_PENDING", verifyingJournal.status)

        val failedRetarget = Fixture(
            initial = defaultState().copy(basePath = "/data/app/new/$PACKAGE/base.apk")
        )
        failedRetarget.store.committed = committed(defaultState())
        failedRetarget.verifier.verifyFailure = IllegalStateException("retargeted mount verification failed")
        val retargetFailure = assertIs<RootMountResult.Failure>(
            failedRetarget.coordinator.execute(failedRetarget.request(RootMountOperation.RECONCILE))
        )
        assertEquals(RootMountPhase.VERIFYING, retargetFailure.phase)
        assertEquals(RootRecoveryState.STOCK, retargetFailure.recoveryState)
        assertEquals("/data/app/new/$PACKAGE/base.apk", failedRetarget.store.committed?.stockPath)
        assertEquals(false, failedRetarget.store.committed?.active)
        assertEquals("/data/app/new/$PACKAGE/base.apk", failedRetarget.module.updatedStates.last().stockPath)

        val mismatch = Fixture(initial = defaultState().copy(versionCode = 3, versionName = "3"))
        mismatch.store.committed = committed(defaultState())
        assertIs<RootMountResult.RequiresRepatch>(
            mismatch.coordinator.execute(mismatch.request(RootMountOperation.RECONCILE))
        )
        assertEquals(0, mismatch.verifier.verifyCalls)
        assertTrue(mismatch.module.disabled)

        val removal = Fixture(
            initial = defaultState().copy(installed = false, basePath = null, baseSha256 = null)
        )
        removal.store.committed = committed(defaultState())
        assertIs<RootMountResult.Success>(
            removal.coordinator.execute(removal.request(RootMountOperation.RECONCILE))
        )
        assertEquals(0, removal.installer.replaceCalls.size)
        assertEquals(0, removal.installer.backupRestoreCalls)
        assertEquals("INACTIVE", removal.store.committed?.status)
        assertTrue(removal.module.disabled)
    }

    @Test
    fun `startup scan re-enables an already mounted committed module`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(listOf(false), fixture.module.enableRepairPayloads)
        assertEquals(1, fixture.module.cleanupCommittedSnapshotCalls)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(1, fixture.lock.releaseCalls)
    }

    @Test
    fun `package reconciliation leaves a healthy committed mount running`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.reader.running = listOf(2468)

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertFalse(assertIs<RootMountResult.Success>(result).automaticallyRemounted)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(2, fixture.verifier.processVerifyCalls)
        assertEquals(listOf(listOf(2468), listOf(2468)), fixture.verifier.verifiedProcessPids)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(0, fixture.reader.stopChecks)
        assertTrue(fixture.shell.commands.none { it.contains("force-stop") })
    }

    @Test
    fun `package reconciliation repairs a stale running target process namespace`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.reader.running = listOf(1357)
        fixture.verifier.processVerifyFailure = IllegalStateException("target process sees stale mount")

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertTrue(assertIs<RootMountResult.Success>(result).automaticallyRemounted)
        assertEquals(1, fixture.verifier.processVerifyCalls)
        assertEquals(listOf(listOf(1357)), fixture.verifier.verifiedProcessPids)
        assertEquals(2, fixture.verifier.rootVerifyCalls)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals(0, fixture.verifier.removeCalls)
        assertTrue(fixture.shell.commands.any { it.contains("force-stop --user 0") })
        assertTrue(fixture.store.diagnostics.any { it.contains("Repaired committed mount namespaces in place") })
    }

    @Test
    fun `package reconciliation repairs namespace drift without unmounting the committed mount`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.verifier.transientVerifyFailures = 1

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(2, fixture.verifier.verifyCalls)
        assertEquals(2, fixture.verifier.rootVerifyCalls)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals(0, fixture.verifier.removeCalls)
        assertEquals(1, fixture.module.enableCalls)
        assertTrue(fixture.shell.commands.any { it.contains("force-stop --user 0") })
        assertTrue(fixture.store.diagnostics.any { it.contains("Repaired committed mount namespaces in place") })
    }

    @Test
    fun `package reconciliation verifies a repeatedly reopened target process instead of deferring`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.verifier.transientVerifyFailures = 1
        fixture.reader.stops = false
        fixture.reader.running = listOf(1234)

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals(2, fixture.verifier.verifyCalls)
        assertEquals(2, fixture.verifier.processVerifyCalls)
        assertEquals(listOf(listOf(1234), listOf(1234)), fixture.verifier.verifiedProcessPids)
        assertEquals(0, fixture.verifier.removeCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertTrue(fixture.store.diagnostics.any { it.contains("Repaired committed mount namespaces in place") })
    }

    @Test
    fun `package reconciliation accepts a verified target restart after namespace repair`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.verifier.transientVerifyFailures = 1
        fixture.reader.stopResults.addAll(listOf(true, false))
        fixture.reader.running = listOf(4321)

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals(2, fixture.verifier.verifyCalls)
        assertEquals(2, fixture.verifier.processVerifyCalls)
        assertEquals(listOf(listOf(4321), listOf(4321)), fixture.verifier.verifiedProcessPids)
        assertEquals(0, fixture.verifier.removeCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
    }

    @Test
    fun `startup restores absent mounts without attempting namespace repair`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.verifier.transientVerifyFailures = 1
        fixture.verifier.rootVerifyFailure = IllegalStateException("root mount is missing")

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertTrue(assertIs<RootMountResult.Success>(result).automaticallyRemounted)
        assertEquals(1, fixture.verifier.rootVerifyCalls)
        assertEquals(1, fixture.verifier.mountCalls)
        assertTrue(fixture.store.diagnostics.any { it.contains("Committed mount is absent") })
        assertFalse(fixture.store.diagnostics.any { it.contains("Non-destructive committed mount repair") })
        assertFalse(fixture.store.activeExists(PACKAGE))
    }

    @Test
    fun `full reconciliation stays retryable when app reopens before mount changes`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.verifier.transientVerifyFailures = 1
        fixture.verifier.rootVerifyFailure = IllegalStateException("root mount is missing")
        fixture.reader.stopResults.add(false)

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertIs<RootMountResult.Busy>(result)
        assertEquals(0, fixture.verifier.removeCalls)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertTrue(fixture.store.diagnostics.any { it.contains("before changing mounts") })
    }

    @Test
    fun `full reconciliation commits a verified mount when app reopens during mount`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.scheduler.tracked += PACKAGE
        fixture.verifier.transientVerifyFailures = 1
        fixture.verifier.rootVerifyFailure = IllegalStateException("root mount is missing")
        fixture.reader.stopResults.addAll(listOf(true, false, false))
        fixture.reader.running = listOf(5678)

        val result = fixture.coordinator.reconcileCommittedTransactions(0, PACKAGE)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.verifier.removeCalls)
        assertEquals(1, fixture.verifier.mountCalls)
        assertEquals(2, fixture.verifier.processVerifyCalls)
        assertEquals(listOf(listOf(5678), listOf(5678)), fixture.verifier.verifiedProcessPids)
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(true, fixture.store.committed?.active)
        assertEquals("MOUNTED", fixture.store.committed?.status)
        assertTrue(fixture.store.diagnostics.any { it.contains("restarted target process namespaces") })
    }

    @Test
    fun `startup scan continues after package lock release failure`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.lock.releaseFailure = IllegalStateException("release failed")

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(1, fixture.module.enableCalls)
        assertEquals(1, fixture.lock.releaseCalls)
        assertTrue(fixture.store.diagnostics.any { it.contains("Failed to release package lock cleanly") })
    }

    @Test
    fun `startup scan reports one package failure instead of throwing`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.module.enableFailure = IllegalStateException("module enable failed")

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        val failure = assertIs<RootMountResult.Failure>(result)
        assertTrue(failure.message.contains("module enable failed"))
        assertTrue(fixture.store.diagnostics.any { it.contains("Startup reconciliation failed") })
    }

    @Test
    fun `startup scan removes a committed mount when another Android user has the package`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial)
        fixture.reader.installedUsers = setOf(0, 10)

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.RequiresRepatch>(result)
        assertEquals(1, fixture.verifier.removeCalls)
        assertTrue(fixture.module.disabled)
        assertTrue(fixture.shell.commands.any { it.contains("force-stop --user 0") })
        assertTrue(fixture.shell.commands.any { it.contains("force-stop --user 10") })
        assertEquals(false, fixture.store.committed?.active)
        assertEquals("REPATCH_REQUIRED", fixture.store.committed?.status)
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `startup scan fails closed when committed state is unreadable`() = runBlocking {
        val fixture = Fixture()
        fixture.store.corruptCommitted = true

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertTrue(fixture.module.disabled)
        assertEquals(1, fixture.verifier.removeCalls)
        assertFalse(fixture.store.committedExists(PACKAGE))
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `startup scan fails closed when committed safety fields are invalid`() = runBlocking {
        val fixture = Fixture()
        fixture.store.committed = committed(fixture.initial).copy(
            stockPath = "relative/base.apk"
        )

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.RecoveredToStock>(result)
        assertTrue(fixture.module.disabled)
        assertEquals(1, fixture.verifier.removeCalls)
        assertFalse(fixture.store.committedExists(PACKAGE))
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `reconcile without committed state stops package tracking`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.RECONCILE))

        assertIs<RootMountResult.Success>(result)
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `startup scan removes tracked package with no transaction state`() = runBlocking {
        val fixture = Fixture()
        fixture.scheduler.tracked += PACKAGE

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.Success>(result)
        assertEquals(listOf(PACKAGE), fixture.scheduler.stoppedPackages)
    }

    @Test
    fun `pretransaction lock failure is retryable and creates no repair state`() = runBlocking {
        val fixture = Fixture()
        fixture.lock.acquireFailure = IllegalStateException("lock timeout")

        val result = fixture.coordinator.execute(fixture.request(RootMountOperation.RECONCILE))

        val busy = assertIs<RootMountResult.Busy>(result)
        assertTrue(busy.reason.orEmpty().contains("temporarily unavailable"))
        assertFalse(fixture.store.activeExists(PACKAGE))
        assertEquals(0, fixture.lock.releaseCalls)
    }

    @Test
    fun `startup lock failure remains busy instead of becoming repair required`() = runBlocking {
        val fixture = Fixture()
        fixture.scheduler.tracked += PACKAGE
        fixture.lock.acquireFailure = IllegalStateException("lock timeout")

        val result = fixture.coordinator.reconcileCommittedTransactions(0)[PACKAGE]

        assertIs<RootMountResult.Busy>(result)
        Unit
    }

    private class Fixture(val initial: RootPackageState = defaultState()) {
        val shell = FakeShell()
        val reader = FakePackageReader(initial)
        val verifier = FakeVerifier(reader)
        val store = FakeStore()
        val module = FakeModule(reader)
        val installer = FakeInstaller()
        val lock = FakeLock()
        val scheduler = FakeScheduler()
        val coordinator = RootMountTransactionCoordinator(
            shell,
            reader,
            verifier,
            store,
            module,
            installer,
            lock,
            scheduler
        )

        fun artifact(name: String, version: Long, hash: String): Pair<File, RootArtifactState> {
            val file = File(name).absoluteFile
            val artifact = RootArtifactState(
                path = file.path,
                packageName = PACKAGE,
                versionName = version.toString(),
                versionCode = version,
                signerSha256 = testSha256("signer"),
                sha256 = testSha256(hash)
            )
            reader.artifacts[file.path] = artifact
            return file to artifact
        }

        fun rawState(version: Long, hash: String): RootPackageState = initial.copy(
            installed = true,
            versionName = version.toString(),
            versionCode = version,
            basePath = "/data/app/$PACKAGE/base.apk",
            baseSha256 = testSha256(hash)
        )

        fun request(
            operation: RootMountOperation,
            patched: File? = null,
            stock: File? = null,
            confirmed: Boolean = false
        ) = RootMountRequest(
            packageName = PACKAGE,
            operation = operation,
            patchedApk = patched,
            stockApks = listOfNotNull(stock),
            downgradeFallbackConfirmed = confirmed
        )
    }

    private class FakeShell : RootShellGateway {
        val commands = mutableListOf<String>()
        var cancelOnceOn: String? = null
        var failOnceOn: String? = null
        override suspend fun run(command: String): RootCommandResult {
            commands += command
            cancelOnceOn?.takeIf(command::contains)?.let {
                cancelOnceOn = null
                throw CancellationException("cancelled during $it")
            }
            failOnceOn?.takeIf(command::contains)?.let {
                failOnceOn = null
                return RootCommandResult(1, emptyList(), listOf("forced shell failure during $it"))
            }
            return RootCommandResult(0, emptyList(), emptyList())
        }
    }

    private class FakePackageReader(initial: RootPackageState) : PackageStateReader {
        var state = initial
        val artifacts = mutableMapOf<String, RootArtifactState>()
        var stabilityFailures = 0
        var readFailures = 0
        var readCalls = 0
        var stops = true
        val stopResults = ArrayDeque<Boolean>()
        var stopChecks = 0
        var lastExpected: RootPackageState? = null
        var installedUsers: Set<Int>? = null
        var running = emptyList<Int>()
        val runningResults = ArrayDeque<List<Int>>()
        val inspectedPaths = mutableListOf<String>()

        override suspend fun read(packageName: String, userId: Int): RootPackageState {
            readCalls++
            if (readFailures > 0) {
                readFailures--
                throw IllegalStateException("PackageManager state read failed")
            }
            return state
        }
        override suspend fun installedUserIds(packageName: String): Set<Int> =
            installedUsers ?: if (state.installed) setOf(state.userId) else emptySet()
        override fun inspect(file: File): RootArtifactState {
            inspectedPaths += file.absolutePath
            return requireNotNull(artifacts[file.absolutePath])
        }
        override suspend fun waitForStable(expected: RootPackageState, consecutiveReads: Int): RootPackageState {
            lastExpected = expected
            if (stabilityFailures > 0) {
                stabilityFailures--
                throw IllegalStateException("PackageManager stability timeout")
            }
            return state
        }
        override suspend fun runningPids(packageName: String): List<Int> =
            if (runningResults.isEmpty()) running else runningResults.removeFirst()
        override suspend fun waitUntilStopped(packageName: String, timeoutMs: Long): Boolean {
            stopChecks++
            return if (stopResults.isEmpty()) stops else stopResults.removeFirst()
        }
    }

    private class FakeStore : RootTransactionStorage {
        var active: RootMountJournal? = null
        var committed: RootCommittedState? = null
        var corruptActive = false
        var corruptCommitted = false
        var readActiveFailure: Throwable? = null
        var clearActiveFailure: Throwable? = null
        var incompletePackages: List<String>? = null
        var writeActiveFailureOnceWhen: ((RootMountJournal) -> Boolean)? = null
        var completeFailureAfterDurableWriteOnce: Throwable? = null
        val diagnostics = mutableListOf<String>()
        val activeWrites = mutableListOf<RootMountJournal>()

        override suspend fun initialize() = Unit
        override suspend fun writeActive(journal: RootMountJournal) {
            val failWhen = writeActiveFailureOnceWhen
            if (failWhen != null && failWhen(journal)) {
                writeActiveFailureOnceWhen = null
                throw IllegalStateException("Root transaction journal write failed")
            }
            active = journal
            activeWrites += journal
        }
        override suspend fun readActive(packageName: String): RootMountJournal? {
            readActiveFailure?.let { throw it }
            return active
        }
        override suspend fun activeExists(packageName: String): Boolean = corruptActive || active != null
        override suspend fun clearActive(packageName: String) {
            clearActiveFailure?.let { throw it }
            active = null
            corruptActive = false
        }
        override suspend fun clearCommitted(packageName: String) {
            committed = null
            corruptCommitted = false
        }
        override suspend fun writeCommitted(state: RootCommittedState) { committed = state }
        override suspend fun readCommitted(packageName: String): RootCommittedState? = committed
        override suspend fun committedExists(packageName: String): Boolean =
            corruptCommitted || committed != null
        override suspend fun complete(journal: RootMountJournal, committed: RootCommittedState?) {
            val completedJournal = journal.copy(
                phase = RootMountPhase.COMPLETED,
                completionStateRecorded = true,
                completionCommittedState = committed
            )
            active = completedJournal
            activeWrites += completedJournal
            if (committed != null) this.committed = committed
            completeFailureAfterDurableWriteOnce?.let { failure ->
                completeFailureAfterDurableWriteOnce = null
                throw failure
            }
        }
        override suspend fun appendDiagnostic(packageName: String, diagnosticId: String, message: String) {
            diagnostics += "$diagnosticId:$message"
        }
        override suspend fun markRepatchRequired(packageName: String, reason: String): RootCommittedState? {
            committed = committed?.copy(active = false, status = "REPATCH_REQUIRED")
            return committed
        }
        override suspend fun listIncompletePackages(): List<String> =
            incompletePackages ?: if (corruptActive || active != null) listOf(PACKAGE) else emptyList()
        override suspend fun listCommittedPackages(): List<String> =
            if (corruptCommitted || committed != null) listOf(PACKAGE) else emptyList()
        override suspend fun exportDiagnostics(packageName: String): String = diagnostics.joinToString("\n")
    }

    private class FakeModule(private val reader: FakePackageReader) : RootModuleStorage {
        var stageCalls = 0
        var snapshotCalls = 0
        var stockSnapshotCalls = 0
        var ensureSpaceCalls = 0
        var disabled = false
        var spaceFailure: Throwable? = null
        var removeFailure: Throwable? = null
        var snapshotHash: String? = null
        var inactiveSnapshotHash: String? = null
        var disableCompetingCalls = 0
        var legacyPayload: RootBackupArtifact? = null
        var committedState: RootCommittedState? = null
        var readCommittedStateCalls = 0
        var preferredSnapshotPayload: RootBackupArtifact? = null
        var restoreCalls = 0
        var restoreResult = true
        var removeCalls = 0
        var purgeCalls = 0
        var purgeFailure: Throwable? = null
        var cleanupCommittedSnapshotCalls = 0
        var cleanupCommittedSnapshotFailure: Throwable? = null
        var onCleanupCommittedSnapshot: (suspend () -> Unit)? = null
        var enableCalls = 0
        val enableRepairPayloads = mutableListOf<Boolean>()
        var enableFailure: Throwable? = null
        val updatedStates = mutableListOf<RootCommittedState>()
        override suspend fun ensureRollbackSpace(packageName: String, stockPaths: List<String>, incomingBytes: Long) {
            ensureSpaceCalls++
            spaceFailure?.let { throw it }
        }
        override suspend fun snapshot(
            packageName: String,
            preferredPayload: RootBackupArtifact?
        ): String? {
            snapshotCalls++
            preferredSnapshotPayload = preferredPayload
            return snapshotHash
        }
        var installedStockPathsForRestore: List<String>? = null

        override suspend fun copyCommittedPayloads(
            state: RootCommittedState,
            directory: File,
            installedStockPaths: List<String>
        ): List<File> {
            installedStockPathsForRestore = installedStockPaths
            return emptyList()
        }

        override suspend fun snapshotInactive(state: RootCommittedState): String? =
            inactiveSnapshotHash ?: snapshot(state.packageName)

        override suspend fun disableCompetingBootHooks(packageName: String) {
            disableCompetingCalls++
        }

        override suspend fun readLegacyPayload(packageName: String): RootBackupArtifact? = legacyPayload
        override suspend fun readCommittedState(packageName: String): RootCommittedState? {
            readCommittedStateCalls++
            return committedState
        }
        override suspend fun snapshotStock(packageName: String, paths: List<String>): List<RootBackupArtifact> {
            stockSnapshotCalls++
            return paths.mapIndexed { index, path ->
                val hash = if (path == reader.state.basePath) requireNotNull(reader.state.baseSha256)
                    else reader.state.splitSha256.getValue(path)
                RootBackupArtifact("/data/adb/urv/transactions/$packageName/backup/package/$index.apk", hash)
            }
        }
        var onCommitSnapshot: (() -> Unit)? = null

        override suspend fun commitSnapshot(packageName: String) {
            onCommitSnapshot?.invoke()
        }
        override suspend fun cleanupCommittedSnapshot(packageName: String) {
            cleanupCommittedSnapshotCalls++
            onCleanupCommittedSnapshot?.invoke()
            cleanupCommittedSnapshotFailure?.let { throw it }
        }
        override suspend fun stageAndActivate(
            transactionId: String,
            packageName: String,
            label: String,
            patchedApk: File,
            compatible: RootPackageState,
            patchedHash: String
        ): String {
            stageCalls++
            return RootPaths.moduleApk(packageName)
        }
        override suspend fun updateState(state: RootCommittedState) {
            updatedStates += state
        }
        override suspend fun restorePrevious(packageName: String): Boolean {
            restoreCalls++
            return restoreResult
        }
        override suspend fun enable(packageName: String, repairPayloads: Boolean) {
            enableCalls++
            enableRepairPayloads += repairPayloads
            enableFailure?.let { throw it }
        }
        override suspend fun disable(packageName: String) { disabled = true }
        override suspend fun removeActive(packageName: String) {
            removeCalls++
            removeFailure?.let { throw it }
        }
        override suspend fun purgeBackups(packageName: String) {
            purgeCalls++
            purgeFailure?.let { throw it }
        }
    }

    private class FakeInstaller : RootPackageInstallation {
        val replaceCalls = mutableListOf<Boolean>()
        var lastInstalledFiles = emptyList<File>()
        val replaceResults = ArrayDeque<RootPackageReplaceResult>()
        var uninstallCalls = 0
        var backupRestoreCalls = 0
        var restoredBackups = emptyList<RootBackupArtifact>()
        var restoreRegistrationCalls = 0
        var backupRestoreResult: Result<Unit> = Result.success(Unit)
        var onReplace: (suspend (Int, Boolean) -> Unit)? = null
        var onReplaceAttempt: (suspend (Int, Boolean) -> Unit)? = null
        var onUninstall: (suspend () -> Unit)? = null
        var onBackupRestore: (suspend () -> Unit)? = null

        override suspend fun replace(
            apks: List<File>,
            userId: Int,
            allowDowngrade: Boolean
        ): RootPackageReplaceResult {
            replaceCalls += allowDowngrade
            lastInstalledFiles = apks
            val call = replaceCalls.size
            onReplaceAttempt?.invoke(call, allowDowngrade)
            val result = if (replaceResults.isEmpty()) {
                RootPackageReplaceResult.Success
            } else {
                replaceResults.removeFirst()
            }
            if (result is RootPackageReplaceResult.Success) onReplace?.invoke(call, allowDowngrade)
            return result
        }
        override suspend fun uninstallKeepData(packageName: String, userId: Int) {
            uninstallCalls++
            onUninstall?.invoke()
        }
        override suspend fun restoreSystemRegistration(packageName: String, userId: Int): Boolean {
            restoreRegistrationCalls++
            return true
        }
        override suspend fun replaceRootBackup(path: String, expectedSha256: String, userId: Int): Result<Unit> {
            backupRestoreCalls++
            onBackupRestore?.invoke()
            return backupRestoreResult
        }
        override suspend fun replaceRootBackups(apks: List<RootBackupArtifact>, userId: Int): Result<Unit> {
            restoredBackups = apks
            return replaceRootBackup(apks.first().path, apks.first().sha256, userId)
        }
    }

    private class FakeVerifier(private val reader: FakePackageReader) : RootMountVerification {
        var verifyCalls = 0
        var rootVerifyCalls = 0
        var processVerifyCalls = 0
        var stockProcessVerifyCalls = 0
        var mountCalls = 0
        var removeCalls = 0
        val verifiedProcessPids = mutableListOf<List<Int>>()
        val verifiedStockProcessPids = mutableListOf<List<Int>>()
        val mounts = mutableListOf<MountInfoEntry>()
        val lazyRecoveryCalls = mutableListOf<Boolean>()
        var onRemove: (suspend () -> Unit)? = null
        var verifyFailure: Throwable? = null
        var transientVerifyFailures = 0
        var rootVerifyFailure: Throwable? = null
        var processVerifyFailure: Throwable? = null
        var stockProcessVerifyFailure: Throwable? = null
        var clearFailure: Throwable? = null
        override suspend fun mountEverywhere(expected: RootCommittedState) {
            mountCalls++
        }
        override suspend fun findUrvMounts(packageName: String, extraTargets: Set<String>): List<MountInfoEntry> = mounts.toList()
        override suspend fun verifyTargetsClear(targets: Set<String>) {
            clearFailure?.let { throw it }
        }
        override suspend fun removeAllUrvMounts(
            packageName: String,
            extraTargets: Set<String>,
            allowLazyRecovery: Boolean,
            includeCompeting: Boolean
        ): List<String> {
            removeCalls++
            lazyRecoveryCalls += allowLazyRecovery
            onRemove?.invoke()
            return emptyList()
        }
        override suspend fun verifyRootMounted(expected: RootCommittedState): RootPackageState {
            rootVerifyCalls++
            rootVerifyFailure?.let { throw it }
            return reader.state
        }
        override suspend fun verifyMounted(expected: RootCommittedState): RootPackageState {
            verifyCalls++
            if (transientVerifyFailures > 0) {
                transientVerifyFailures--
                throw IllegalStateException("transient namespace verification failure")
            }
            verifyFailure?.let { throw it }
            return reader.state
        }
        override suspend fun verifyProcessMounted(expected: RootCommittedState, pids: List<Int>) {
            processVerifyCalls++
            verifiedProcessPids += pids
            processVerifyFailure?.let { throw it }
        }
        override suspend fun verifyProcessStock(
            packageName: String,
            userId: Int,
            stockPath: String,
            pids: List<Int>
        ) {
            stockProcessVerifyCalls++
            verifiedStockProcessPids += pids
            stockProcessVerifyFailure?.let { throw it }
        }
    }

    private class FakeLock : RootPackageLocking {
        var releaseCalls = 0
        var acquireFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        var onRelease: (() -> Unit)? = null
        override suspend fun acquire(packageName: String, transactionId: String): RootLockHandle {
            acquireFailure?.let { throw it }
            return RootLockHandle(
                packageName,
                "/lock",
                "/lock/owner",
                123,
                "456",
                transactionId
            )
        }
        override suspend fun release(handle: RootLockHandle) {
            releaseCalls++
            onRelease?.invoke()
            releaseFailure?.let { throw it }
        }
    }

    private class FakeScheduler : RootReconciliationScheduling {
        val scheduledUsers = mutableListOf<Int>()
        val scheduledPackages = mutableListOf<String>()
        val stoppedPackages = mutableListOf<String>()
        val tracked = mutableSetOf<String>()

        override fun ensureScheduled(userId: Int, packageName: String) {
            scheduledUsers += userId
            scheduledPackages += packageName
        }

        override fun stopScheduled(userId: Int, packageName: String) {
            stoppedPackages += packageName
            tracked -= packageName
        }

        override fun trackedPackages(userId: Int): Set<String> = tracked.toSet()
    }

    private companion object {
        const val PACKAGE = "com.example.app"

        fun splitState(): RootPackageState {
            val paths = listOf("/data/app/$PACKAGE/split_config.en.apk", "/data/app/$PACKAGE/split_config.arm64_v8a.apk")
            return defaultState().copy(splitPaths = paths, splitSha256 = paths.associateWith(::testSha256))
        }

        fun defaultState() = RootPackageState(
            packageName = PACKAGE,
            userId = 0,
            installed = true,
            versionName = "2",
            versionCode = 2,
            signerSha256 = testSha256("signer"),
            basePath = "/data/app/$PACKAGE/base.apk",
            baseSha256 = testSha256("stock-2"),
            enabled = true,
            launcherResolvable = true
        )

        fun committed(state: RootPackageState) = RootCommittedState(
            transactionId = "previous",
            packageName = state.packageName,
            userId = state.userId,
            versionName = state.versionName,
            versionCode = requireNotNull(state.versionCode),
            signerSha256 = state.signerSha256,
            stockPath = requireNotNull(state.basePath),
            stockSha256 = requireNotNull(state.baseSha256),
            patchedPath = RootPaths.moduleApk(state.packageName),
            patchedSha256 = testSha256("previous-patched"),
            topology = state.topology,
            enabled = state.enabled,
            launcherResolvable = state.launcherResolvable,
            committedAtEpochMs = 1,
            stockSplits = state.verifiedSplits()
        )

        fun journal(
            phase: RootMountPhase,
            initial: RootPackageState,
            previous: RootCommittedState? = null
        ) = RootMountJournal(
            transactionId = "interrupted",
            packageName = initial.packageName,
            userId = initial.userId,
            operation = RootMountOperation.REPLACE_STOCK_AND_MOUNT,
            phase = phase,
            startedAtEpochMs = 1,
            initialPackageState = initial,
            previousCommitted = previous,
            stockMutationStarted = phase >= RootMountPhase.INSTALLING_STOCK,
            registrationGap = phase >= RootMountPhase.INSTALLING_STOCK
        )

        fun testSha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
    }
}

package app.universal.patch.manager.domain.installer.root

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootMountVerifierTest {
    @Test
    fun `opaque mount ownership batches source probes and refreshes each discovery`() = runBlocking {
        val shell = MountShell(TARGET, opaqueSource = true)
        val verifier = verifier(shell)

        repeat(2) {
            assertEquals(listOf(TARGET), verifier.findUrvMounts(PACKAGE, emptySet()).map { it.mountPoint })
        }

        // One source batch and one fresh target probe per discovery, even though most
        // candidate paths are absent and stat returns a nonzero status for the batch.
        assertEquals(4, shell.commands.count { it.startsWith("stat -c") })
        val batches = shell.commands.filter { it.startsWith("stat -c") && !it.contains(shellQuote(TARGET)) }
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.contains(shellQuote("${RootPaths.LEGACY}/$PACKAGE/$PACKAGE.apk")) })
    }

    @Test
    fun `opaque foreign mount is rejected with batched source probes`() = runBlocking {
        val shell = MountShell(TARGET, source = "/data/adb/modules/other/payload.apk", opaqueSource = true)

        assertTrue(verifier(shell).findUrvMounts(PACKAGE, emptySet()).isEmpty())
        assertEquals(2, shell.commands.count { it.startsWith("stat -c") })
    }

    @Test
    fun `current and legacy payload inodes identify stale mounts without sourceDir`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, source = "/data/adb/revanced/com.example.app/com.example.app.apk")
        val verifier = verifier(shell)

        val mounts = verifier.findUrvMounts(PACKAGE, emptySet())

        assertEquals(listOf(target), mounts.map { it.mountPoint })
    }

    @Test
    fun `unrelated mount at a package path is not treated as UPM owned`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, source = "/data/adb/modules/other-tool/payload.apk")
        val verifier = verifier(shell)

        val mounts = verifier.findUrvMounts(PACKAGE, setOf(target))

        assertTrue(mounts.isEmpty())
        assertFailsWith<IllegalStateException> {
            verifier.verifyTargetsClear(setOf(target))
        }
        Unit
    }

    @Test
    fun `competing ReVanced mount is cleared only when adopting its stock target`() = runBlocking {
        val source = "${RootPaths.competingModule(PACKAGE)}/$PACKAGE.apk"
        val shell = MountShell(TARGET, source = source)
        val verifier = verifier(shell)

        verifier.removeAllUrvMounts(PACKAGE, setOf(TARGET), allowLazyRecovery = true)
        assertTrue(shell.mounted)

        verifier.removeAllUrvMounts(
            PACKAGE, setOf(TARGET), allowLazyRecovery = true, includeCompeting = true
        )
        assertFalse(shell.mounted)
    }

    @Test
    fun `normal unmount is verified without lazy fallback`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target)
        val verifier = verifier(shell)

        val lazy = verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = false)

        assertEquals(emptyList(), lazy)
        assertFalse(shell.commands.any { it.startsWith("umount -l") })
        assertFalse(shell.commands.any { it.startsWith("stat -c") })
        assertFalse(shell.mounted)
    }

    @Test
    fun `stacked UPM layers with filesystem relative roots are discovered and peeled`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, mountLayers = 2, exposeSourcePath = false)
        val verifier = verifier(shell)

        val discovered = verifier.findUrvMounts(PACKAGE, setOf(target))
        val lazy = verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = false)

        assertEquals(2, discovered.size)
        assertEquals(emptyList(), lazy)
        assertEquals(2, shell.commands.count { it.startsWith("umount ") })
        assertFalse(shell.mounted)
    }

    @Test
    fun `foreign layer above a UPM mount is never unmounted`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, includeForeignLayer = true)
        val verifier = verifier(shell)

        assertFailsWith<IllegalStateException> {
            verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = true)
        }

        assertTrue(shell.mounted)
        assertFalse(shell.commands.any { it.startsWith("umount ") })
    }

    @Test
    fun `verification rejects preserved mount without a stock shadow layer`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, mountLayers = 2)
        val verifier = verifier(shell)
        val expected = committedState().copy(stockPath = target)

        val failure = assertFailsWith<IllegalStateException> { verifier.verifyMounted(expected) }

        assertTrue(failure.message.orEmpty().contains("Stock shadow mount layer is missing"))
    }

    @Test
    fun `verification accepts bounded duplicate UPM layers from namespace propagation`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(
            target,
            mountLayerSources = listOf(
                RootPaths.moduleStockApk(PACKAGE),
                RootPaths.moduleApk(PACKAGE),
                RootPaths.moduleApk(PACKAGE)
            )
        )
        val expected = committedState().copy(stockPath = target)
        val verifier = verifier(shell, MatchingPackageReader(expected))

        val verified = verifier.verifyMounted(expected)

        assertEquals(expected.patchedSha256, verified.baseSha256)
    }

    @Test
    fun `launcher visibility is not part of mounted APK identity`() = runBlocking {
        val expected = committedState().copy(launcherResolvable = true)
        val shell = MountShell(
            TARGET,
            mountLayerSources = listOf(RootPaths.moduleStockApk(PACKAGE), RootPaths.moduleApk(PACKAGE))
        )
        val matching = MatchingPackageReader(expected)
        val reader = object : PackageStateReader by matching {
            override suspend fun read(packageName: String, userId: Int) =
                matching.read(packageName, userId).copy(launcherResolvable = false)
        }

        assertFalse(verifier(shell, reader).verifyMounted(expected).launcherResolvable)
    }

    @Test
    fun `full verification hashes the stock shadow after namespace verification`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(
            target,
            mountLayerSources = listOf(
                RootPaths.moduleStockApk(PACKAGE),
                RootPaths.moduleApk(PACKAGE)
            )
        )
        val expected = committedState().copy(stockPath = target)
        val verifier = verifier(shell, MatchingPackageReader(expected))

        verifier.verifyMounted(expected)

        val namespaceVerification = shell.commands.indexOfFirst {
            it.contains("Zygote namespaces did not stabilize during verification")
        }
        val shadowHash = shell.commands.indexOfLast {
            it.startsWith("sha256sum") && it.contains(shellQuote(RootPaths.moduleStockApk(PACKAGE)))
        }
        assertTrue(namespaceVerification >= 0)
        assertTrue(shadowHash > namespaceVerification)
        assertEquals(
            1,
            shell.commands.count {
                it.startsWith("sha256sum") && it.contains(shellQuote(RootPaths.moduleStockApk(PACKAGE)))
            }
        )
    }

    @Test
    fun `normal unmount failure cannot silently become lazy unmount`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, failNormalUnmount = true)
        val verifier = verifier(shell)

        assertFailsWith<RootCommandException> {
            verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = false)
        }
        assertTrue(shell.mounted)
        assertFalse(shell.commands.any { it.startsWith("umount -l") })
    }

    @Test
    fun `failed normal unmount is accepted when the owned layer disappears during recheck`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(
            target,
            failNormalUnmount = true,
            dropUrvMountOnFailedNormalUnmount = true
        )
        val verifier = verifier(shell)

        val lazy = verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = false)

        assertEquals(emptyList(), lazy)
        assertFalse(shell.mounted)
        assertFalse(shell.commands.any { it.startsWith("umount -l") })
    }

    @Test
    fun `lazy unmount is available when the caller has quiesced the package`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, failNormalUnmount = true)
        val verifier = verifier(shell)

        val lazy = verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = true)

        assertEquals(listOf(target), lazy)
        assertTrue(shell.commands.any { it.startsWith("umount -l") })
        assertFalse(shell.mounted)
    }

    @Test
    fun `stale stacks beyond the old peel limit keep using normal unmount while it succeeds`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(target, mountLayers = 10)
        val verifier = verifier(shell)

        val lazy = verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = true)

        assertEquals(emptyList(), lazy)
        assertEquals(10, shell.commands.count { it.startsWith("umount ") && !it.startsWith("umount -l") })
        assertFalse(shell.commands.any { it.startsWith("umount -l") })
        assertFalse(shell.mounted)
    }

    @Test
    fun `lazy fallback rechecks ownership after a normal unmount failure`() = runBlocking {
        val target = "/data/app/com.example.app/base.apk"
        val shell = MountShell(
            target,
            failNormalUnmount = true,
            foreignLayerOnMountTableRead = 3
        )
        val verifier = verifier(shell)

        assertFailsWith<IllegalStateException> {
            verifier.removeAllUrvMounts(PACKAGE, setOf(target), allowLazyRecovery = true)
        }

        assertTrue(shell.mounted)
        assertFalse(shell.commands.any { it.startsWith("umount -l") })
    }

    @Test
    fun `zygote targets are checked before root bind mounts are created`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell).mount(committedState())

        val command = shell.commands.single()
        val preflight = command.indexOf("elif ! namespace_target_clear \"${'$'}pid\"; then")
        val rootReuseCheck = command.indexOf("if ! namespace_matches \"${'$'}self_pid\"; then")
        val rootShadowBind = command.indexOf(
            "mount -o bind ${shellQuote(RootPaths.moduleStockApk(PACKAGE))} ${shellQuote(TARGET)}"
        )
        val rootPrivate = command.indexOf("mount -o private none ${shellQuote(TARGET)}")
        val rootBind = command.indexOf(
            "mount -o bind ${shellQuote(RootPaths.moduleApk(PACKAGE))} ${shellQuote(TARGET)}"
        )
        assertTrue(preflight >= 0)
        assertTrue(rootReuseCheck > preflight)
        assertTrue(rootShadowBind > rootReuseCheck)
        assertTrue(rootPrivate > rootShadowBind)
        assertTrue(rootBind > rootPrivate)
        assertTrue(command.contains("-v patched_root='${RootPaths.moduleApk(PACKAGE).removePrefix("/data")}'"))
        assertTrue(command.contains("-v shadow_root='${RootPaths.moduleStockApk(PACKAGE).removePrefix("/data")}'"))
        assertFalse(command.contains("-m sha256sum"))
        assertEquals(1, Regex("sha256sum").findAll(command).count())
        assertEquals(2, Regex("validate_shadow_hash").findAll(command).count())
        assertTrue(command.contains("live_zygote_pids"))
        assertTrue(command.contains("namespace_matches_shadow"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- awk"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- mount -o bind"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- mount -o private none"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- stat -c"))
        assertFalse(command.contains("nsenter -t"))
        assertTrue(command.contains("awk '/^[0-9]+${'$'}/ && ${'$'}0 > 1'"))
        assertTrue(command.contains("/proc/${'$'}pid/cmdline"))
        assertTrue(command.contains("zygote|zygote64|zygote\\ *|zygote64\\ *"))
        assertFalse(command.contains("/proc/${'$'}1/comm"))
        assertTrue(command.contains("validate_zygote \"${'$'}pid\" || { zygote_changed=1; continue; }"))
        assertTrue(command.contains("Zygote namespaces did not stabilize during mount preflight"))
        assertTrue(command.contains("Zygote namespaces did not stabilize while applying mounts"))
        assertFalse(command.contains("disappeared during mount preflight"))
        assertFalse(command.contains("Zygote set changed during mount"))
        assertEquals(listOf(60L to "root and Zygote namespace mount"), shell.boundedOperations)
    }

    @Test
    fun `zygote verification retries process churn before failing`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell).verify(committedState())

        val command = shell.commands.single()
        assertTrue(command.contains("while [ \"${'$'}verify_attempt\" -lt 20 ]"))
        assertEquals(1, Regex("validate_shadow_hash").findAll(command).count())
        assertTrue(command.contains("validate_shadow_file"))
        assertTrue(command.contains("validate_zygote \"${'$'}pid\" || { zygote_changed=1; continue; }"))
        assertTrue(command.contains("Zygote namespaces did not stabilize during verification"))
        assertFalse(command.contains("disappeared during verification"))
        assertEquals(listOf(60L to "Zygote namespace verification"), shell.boundedOperations)
    }

    @Test
    fun `running target process namespaces are verified against the patched mount`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell).verifyProcesses(committedState(), listOf(1234, 5678))

        val command = shell.commands.single()
        assertTrue(command.contains("package_name=${shellQuote(PACKAGE)}"))
        assertTrue(command.contains("pm list packages -U --user 0"))
        assertTrue(command.contains("process_matches_package"))
        assertTrue(command.contains("/proc/${'$'}pid/status"))
        assertTrue(command.contains("[ \"${'$'}process_uid\" -ge 0 ] && [ \"${'$'}process_uid\" -lt 100000 ] || return 1"))
        assertTrue(command.indexOf("process_uid=\"") < command.indexOf("case \"${'$'}process_name\" in"))
        assertTrue(command.contains("for pid in 1234 5678; do"))
        assertTrue(command.contains("/proc/${'$'}pid/cmdline"))
        assertTrue(command.contains("[ -d \"/proc/${'$'}pid\" ] || continue"))
        assertTrue(command.contains("Could not inspect target process mount namespace"))
        assertTrue(command.contains("namespace_id=\"${'$'}(readlink \"/proc/${'$'}pid/ns/mnt\""))
        assertTrue(command.contains("verified_namespace_id=\"${'$'}(readlink \"/proc/${'$'}pid/ns/mnt\""))
        assertTrue(command.contains("Target process mount namespace changed during verification"))
        assertEquals(2, command.split("if ! process_matches_package \"${'$'}pid\"; then").size - 1)
        assertTrue(command.contains("Target process identity changed during namespace verification"))
        assertTrue(command.contains("\"${'$'}package_name\"|\"${'$'}package_name\":*)"))
        assertTrue(command.contains("if ! namespace_matches \"${'$'}pid\"; then"))
        assertTrue(command.contains("Patched APK is not visible in target process namespace"))
        assertEquals(listOf(60L to "target process namespace verification"), shell.boundedOperations)
    }

    @Test
    fun `process identity verification scopes package names to the requested Android user`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell).verifyProcesses(
            committedState().copy(userId = 10),
            listOf(1234)
        )

        val command = shell.commands.single()
        assertTrue(command.contains("pm list packages -U --user 10"))
        assertTrue(
            command.contains(
                "[ \"${'$'}process_uid\" -ge 1000000 ] && " +
                    "[ \"${'$'}process_uid\" -lt 1100000 ] || return 1"
            )
        )
    }

    @Test
    fun `running target process namespaces are verified against stock during recovery`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell).verifyStockProcesses(PACKAGE, 0, TARGET, listOf(1234))

        val command = shell.commands.single()
        assertTrue(command.contains("package_name=${shellQuote(PACKAGE)}"))
        assertTrue(command.contains("pm list packages -U --user 0"))
        assertTrue(command.contains("process_matches_package"))
        assertTrue(command.contains("/proc/${'$'}pid/status"))
        assertTrue(command.contains("[ \"${'$'}process_uid\" -ge 0 ] && [ \"${'$'}process_uid\" -lt 100000 ] || return 1"))
        assertTrue(command.indexOf("process_uid=\"") < command.indexOf("case \"${'$'}process_name\" in"))
        assertTrue(command.contains("stock_path=${shellQuote(TARGET)}"))
        assertTrue(command.contains("stock_inode=\"${'$'}(stat -c '%d:%i' \"${'$'}stock_path\")\""))
        assertTrue(command.contains("[ -d \"/proc/${'$'}pid\" ] || continue"))
        assertTrue(command.contains("Could not inspect target process mount namespace"))
        assertTrue(command.contains("namespace_id=\"${'$'}(readlink \"/proc/${'$'}pid/ns/mnt\""))
        assertTrue(command.contains("verified_namespace_id=\"${'$'}(readlink \"/proc/${'$'}pid/ns/mnt\""))
        assertTrue(command.contains("Target process mount namespace changed during stock verification"))
        assertEquals(2, command.split("if ! process_matches_package \"${'$'}pid\"; then").size - 1)
        assertTrue(command.contains("Target process identity changed during stock namespace verification"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- stat -c '%d:%i'"))
        assertTrue(command.contains("still sees a non-stock APK"))
        assertEquals(listOf(60L to "target process stock namespace verification"), shell.boundedOperations)
    }

    @Test
    fun `foreign only zygote mounts are left untouched during cleanup`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell).removeOwned(PACKAGE, setOf(TARGET))

        val command = shell.commands.single()
        assertTrue(command.contains("pidof zygote64 2>/dev/null || true"))
        assertTrue(command.contains("pidof zygote 2>/dev/null || true"))
        assertTrue(command.contains("validate_zygote \"${'$'}pid\" || continue"))
        assertTrue(command.contains("namespace_layer_counts"))
        assertTrue(command.contains("if ! visible_layer_is_owned \"${'$'}pid\" \"${'$'}target\" \"${'$'}counts\"; then"))
        assertTrue(command.contains("Foreign ${'$'}namespace_label mount covers a UPM layer"))
        assertTrue(command.contains("Failed to unmount ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- umount"))
        assertFalse(command.contains("nsenter -t"))
        assertTrue(command.contains("if [ 0 = 1 ]; then"))
        assertTrue(command.contains("max_attempts=8"))
        assertEquals(listOf(60L to "Zygote namespace cleanup"), shell.boundedOperations)
    }

    @Test
    fun `cleanup also removes owned mounts inherited by the Manager process`() = runBlocking {
        val shell = CapturingShell()
        RootMountNamespaces(shell, managerPid = 4242).removeOwned(PACKAGE, setOf(TARGET))

        val command = shell.commands.single()
        assertTrue(command.contains("if [ -r /proc/4242/ns/mnt ]; then cleanup_namespace 4242 Manager ${shellQuote(TARGET)}; fi"))
        assertTrue(command.contains("cleanup_namespace \"${'$'}pid\" Zygote"))
    }

    @Test
    fun `explicit recovery permits lazy detach in Zygote namespaces`() = runBlocking {
        val shell = CapturingShell()

        RootMountNamespaces(shell).removeOwned(PACKAGE, setOf(TARGET), allowLazyRecovery = true)

        val command = shell.commands.single()
        assertTrue(command.contains("if [ 1 = 1 ]; then"))
        assertTrue(command.contains("max_attempts=16"))
        assertTrue(command.contains("visible_layer_is_owned \"${'$'}pid\" \"${'$'}target\" \"${'$'}counts\" \"${'$'}@\" ||"))
        assertTrue(command.contains("${'$'}namespace_label mount ownership changed before lazy unmount"))
        assertTrue(command.contains("nsenter --mount=\"/proc/${'$'}pid/ns/mnt\" -- umount -l"))
        assertTrue(command.contains("URV_LAZY_UNMOUNT:%s"))
    }

    private fun committedState() = RootCommittedState(
        transactionId = "tx",
        packageName = PACKAGE,
        userId = 0,
        versionName = "1",
        versionCode = 1,
        signerSha256 = "signer",
        stockPath = TARGET,
        stockSha256 = STOCK_HASH,
        patchedPath = RootPaths.moduleApk(PACKAGE),
        patchedSha256 = PATCHED_HASH,
        stockShadowPath = RootPaths.moduleStockApk(PACKAGE),
        stockShadowSha256 = STOCK_HASH,
        preserveStockAcrossBoot = true,
        topology = "SINGLE",
        committedAtEpochMs = 1
    )

    private fun verifier(
        shell: MountShell,
        packageReader: PackageStateReader = StaticPackageReader()
    ) = RootMountVerifier(
        shell,
        MountTableReader(shell),
        packageReader,
        RootMountNamespaces(shell)
    )

    private class MountShell(
        private val target: String,
        private val source: String = RootPaths.moduleApk(PACKAGE),
        private val failNormalUnmount: Boolean = false,
        private val mountLayers: Int = 1,
        private val includeForeignLayer: Boolean = false,
        private val exposeSourcePath: Boolean = true,
        private val opaqueSource: Boolean = false,
        private val foreignLayerOnMountTableRead: Int? = null,
        private val dropUrvMountOnFailedNormalUnmount: Boolean = false,
        mountLayerSources: List<String>? = null
    ) : RootShellGateway {
        val commands = mutableListOf<String>()
        private val remainingUrvSources =
            (mountLayerSources ?: List(mountLayers) { source }).toMutableList()
        private var foreignLayerMounted = includeForeignLayer
        private var mountTableReads = 0
        val mounted: Boolean get() = remainingUrvSources.isNotEmpty() || foreignLayerMounted

        private fun inodeFor(path: String): String = when (path) {
            RootPaths.moduleStockApk(PACKAGE) -> "6:6"
            RootPaths.moduleApk(PACKAGE),
            "${RootPaths.LEGACY}/$PACKAGE/$PACKAGE.apk",
            "${RootPaths.rollbackModule(PACKAGE)}/$PACKAGE.apk",
            "${RootPaths.rollbackModule(PACKAGE)}/$PACKAGE-stock.apk",
            "${RootPaths.backup(PACKAGE)}/module/$PACKAGE.apk",
            "${RootPaths.backup(PACKAGE)}/module/$PACKAGE-stock.apk" -> "7:7"
            else -> "8:8"
        }

        override suspend fun run(command: String): RootCommandResult {
            commands += command
            return when {
                command == "cat /proc/self/mountinfo" -> {
                    mountTableReads++
                    if (mountTableReads == foreignLayerOnMountTableRead) foreignLayerMounted = true
                    val lines = if (mounted) {
                        val urv = remainingUrvSources.mapIndexed { index, layerSource ->
                            val root = if (opaqueSource || exposeSourcePath) {
                                "/"
                            } else {
                                layerSource.removePrefix("/data")
                            }
                            val mountedSource = if (exposeSourcePath && !opaqueSource) layerSource else "/dev/block/dm-3"
                            "${42 + index} 1 0:1 $root $target rw - ext4 $mountedSource rw"
                        }
                        if (foreignLayerMounted) {
                            urv + "99 1 0:2 / $target rw - none /data/adb/modules/other/payload.apk rw"
                        } else {
                            urv
                        }
                    } else {
                        emptyList()
                    }
                    RootCommandResult(0, lines, emptyList())
                }
                command.startsWith("stat -c") -> {
                    when {
                        command.contains(shellQuote(target)) && foreignLayerMounted ->
                            RootCommandResult(0, listOf("8:8"), emptyList())
                        command.contains(shellQuote(target)) && remainingUrvSources.isNotEmpty() ->
                            RootCommandResult(0, listOf(inodeFor(remainingUrvSources.last())), emptyList())
                        else -> {
                            val existing = remainingUrvSources + RootPaths.moduleApk(PACKAGE) +
                                RootPaths.moduleStockApk(PACKAGE)
                            val requested = Regex("'([^']*)'").findAll(command)
                                .map { it.groupValues[1] }.filter { it.startsWith('/') }.toList()
                            RootCommandResult(
                                if (requested.all { it in existing }) 0 else 1,
                                requested.filter { it in existing }.map(::inodeFor),
                                emptyList()
                            )
                        }
                    }
                }
                command.startsWith("sha256sum") &&
                    command.contains(shellQuote(RootPaths.moduleStockApk(PACKAGE))) ->
                    RootCommandResult(0, listOf(STOCK_HASH), emptyList())
                command.startsWith("umount -l") -> {
                    if (foreignLayerMounted) {
                        foreignLayerMounted = false
                    } else if (remainingUrvSources.isNotEmpty()) {
                        remainingUrvSources.removeAt(remainingUrvSources.lastIndex)
                    }
                    RootCommandResult(0, emptyList(), emptyList())
                }
                command.startsWith("umount ") && failNormalUnmount -> {
                    if (dropUrvMountOnFailedNormalUnmount && remainingUrvSources.isNotEmpty()) {
                        remainingUrvSources.removeAt(remainingUrvSources.lastIndex)
                    }
                    RootCommandResult(1, emptyList(), listOf("busy"))
                }
                command.startsWith("umount ") -> {
                    if (foreignLayerMounted) {
                        foreignLayerMounted = false
                    } else if (remainingUrvSources.isNotEmpty()) {
                        remainingUrvSources.removeAt(remainingUrvSources.lastIndex)
                    }
                    RootCommandResult(0, emptyList(), emptyList())
                }
                else -> RootCommandResult(0, emptyList(), emptyList())
            }
        }
    }

    private class CapturingShell : RootShellGateway {
        val commands = mutableListOf<String>()
        val boundedOperations = mutableListOf<Pair<Long, String>>()

        override suspend fun run(command: String): RootCommandResult {
            commands += command
            return RootCommandResult(0, emptyList(), emptyList())
        }

        override suspend fun runBounded(
            command: String,
            timeoutSeconds: Long,
            operation: String
        ): RootCommandResult {
            boundedOperations += timeoutSeconds to operation
            return run(command)
        }
    }

    private class MatchingPackageReader(
        private val expected: RootCommittedState
    ) : PackageStateReader {
        override suspend fun installedUserIds(packageName: String): Set<Int> = setOf(expected.userId)

        override suspend fun read(packageName: String, userId: Int) = RootPackageState(
            packageName = expected.packageName,
            userId = expected.userId,
            installed = true,
            versionName = expected.versionName,
            versionCode = expected.versionCode,
            signerSha256 = expected.signerSha256,
            basePath = expected.stockPath,
            baseSha256 = expected.patchedSha256,
            enabled = expected.enabled,
            launcherResolvable = expected.launcherResolvable
        )

        override fun inspect(file: File): RootArtifactState = error("not used")
        override suspend fun waitForStable(expected: RootPackageState, consecutiveReads: Int) = expected
        override suspend fun runningPids(packageName: String): List<Int> = emptyList()
        override suspend fun waitUntilStopped(packageName: String, timeoutMs: Long): Boolean = true
    }

    private class StaticPackageReader : PackageStateReader {
        override suspend fun installedUserIds(packageName: String): Set<Int> = setOf(0)
        override suspend fun read(packageName: String, userId: Int) = RootPackageState(packageName, userId, false)
        override fun inspect(file: File): RootArtifactState = error("not used")
        override suspend fun waitForStable(expected: RootPackageState, consecutiveReads: Int) = expected
        override suspend fun runningPids(packageName: String): List<Int> = emptyList()
        override suspend fun waitUntilStopped(packageName: String, timeoutMs: Long): Boolean = true
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val TARGET = "/data/app/com.example.app/base.apk"
        const val STOCK_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val PATCHED_HASH =
            "2222222222222222222222222222222222222222222222222222222222222222"
    }
}

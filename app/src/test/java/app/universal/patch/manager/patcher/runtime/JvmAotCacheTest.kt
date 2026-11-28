package app.universal.patch.manager.patcher.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class JvmAotCacheTest {
    @Test fun separatesRuntimeAndHostUpdatesAndBoundsOldCaches() {
        val root = createTempDirectory("aot-test").toFile()
        try {
            val first = JvmAotCache(root, File("runtime1"), File("host1"))
            first.prepare()
            assertNotEquals(first.cache, JvmAotCache(root, File("runtime2"), File("host1")).cache)
            assertNotEquals(first.cache, JvmAotCache(root, File("runtime1"), File("host2")).cache)
            repeat(12) { JvmAotCache(root, File("runtime$it"), File("host$it")).prepare() }
            assertTrue(root.listFiles()!!.size <= 8)
        } finally { root.deleteRecursively() }
    }
    @Test fun stopsAfterThreeFailuresAndClearingCacheAllowsRetry() {
        val root = createTempDirectory("aot-test").toFile()
        try {
            val entry = JvmAotCache(root, File("runtime"), File("host"))
            entry.prepare()
            repeat(3) { assertTrue(entry.canRecord()); entry.failed(Exception("failed\nreason")) }
            assertFalse(entry.canRecord())
            entry.configuration.writeText("partial")
            entry.staging.writeText("partial")
            entry.discardRecording()
            assertFalse(entry.configuration.exists())
            assertFalse(entry.staging.exists())
            root.deleteRecursively()
            entry.prepare()
            assertTrue(entry.canRecord())
        } finally { root.deleteRecursively() }
    }
}

package app.universal.patch.manager.network.runtime

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeCatalogCacheTest {
    @Test fun concurrentRefreshesPublishWholeFilesAndCleanTemporaryFiles() {
        val directory = Files.createTempDirectory("runtime-catalog-test").toFile()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val cache = directory.resolve("runtime-catalog.json")
            val contents = (1..8).map { "{\"catalog\":\"$it" + "x".repeat(200_000) + "\"}" }
            writeRuntimeCatalogCache(cache, contents.first())
            val start = CountDownLatch(1)
            val writes = contents.map { raw ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(10) { writeRuntimeCatalogCache(cache, raw) }
                }
            }
            start.countDown()
            // Windows locks open readers against replacement; Android uses POSIX rename.
            // Stress competing publishers here, then check the complete published content.
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(Files.readString(cache.toPath()) in contents)
            assertEquals(listOf(cache.name), directory.listFiles()!!.map { it.name })
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun failedPublicationCleansItsTemporaryFile() {
        val directory = Files.createTempDirectory("runtime-catalog-test").toFile()
        try {
            val occupied = directory.resolve("runtime-catalog.json").apply { mkdir() }
            occupied.resolve("keep").writeText("existing")
            assertThrows(java.io.IOException::class.java) { writeRuntimeCatalogCache(occupied, "catalog") }
            assertEquals(listOf(occupied.name), directory.listFiles()!!.map { it.name })
            assertEquals("existing", occupied.resolve("keep").readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}

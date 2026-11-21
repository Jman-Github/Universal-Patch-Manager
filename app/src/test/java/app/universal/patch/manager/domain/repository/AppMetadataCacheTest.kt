package app.universal.patch.manager.domain.repository

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppMetadataCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_000_000L

    @Test fun removesExpiredAndAbandonedFilesWithoutTouchingOtherFiles() {
        val directory = temporary.newFolder()
        val old = directory.resolve("old.json").apply { writeText("{}"); setLastModified(now - 91L * 86_400_000) }
        val fresh = directory.resolve("fresh.json").apply { writeText("{}"); setLastModified(now) }
        val partial = directory.resolve("partial.tmp").apply { writeText("unfinished") }
        val unrelated = directory.resolve("keep.txt").apply { writeText("keep") }
        pruneAppMetadataCache(directory, now)
        assertFalse(old.exists())
        assertFalse(partial.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun entryLimitKeepsMostRecentlyUsedFiles() {
        val directory = temporary.newFolder()
        repeat(1005) { index ->
            directory.resolve("$index.json").apply { writeText("{}"); setLastModified(now + index) }
        }
        pruneAppMetadataCache(directory, now)
        assertEquals(1000, directory.listFiles()!!.size)
        assertFalse(directory.resolve("0.json").exists())
        assertTrue(directory.resolve("1004.json").exists())
    }

    @Test fun enforcesTotalSizeAndRejectsOversizedEntries() {
        val directory = temporary.newFolder()
        repeat(600) { index ->
            directory.resolve("$index.json").apply { writeBytes(ByteArray(16 * 1024)); setLastModified(now + index) }
        }
        val oversized = directory.resolve("oversized.json").apply { writeBytes(ByteArray(16 * 1024 + 1)); setLastModified(now) }
        pruneAppMetadataCache(directory, now)
        assertFalse(oversized.exists())
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 8L * 1024 * 1024)
        assertTrue(directory.resolve("599.json").exists())
    }
}

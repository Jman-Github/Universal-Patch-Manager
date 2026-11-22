package app.universal.patch.manager.patcher.patch

import org.junit.Assert.*
import org.junit.Test

class PatchInfoMetadataTest {
    @Test fun readableNamesPreserveSelectionAndOptionKeys() {
        val metadata = mapOf<String, Any?>(
            "name" to "Change package name [app.reseam.patches.universal.changePackageName]",
            "displayName" to "Change package name",
            "options" to listOf(mapOf("key" to "packageName", "type" to "kotlin.String"))
        )
        val patch = PatchInfo.fromMorpheMetadata(metadata, preserveDouble = true)
        assertEquals(metadata["name"], patch.name)
        assertEquals("Change package name", patch.displayName)
        assertEquals("packageName", patch.options!!.single().key)
    }

    @Test fun selectorUsesOneRowForEachBundlePatchKey() {
        val compatible = PatchInfo.fromMorpheMetadata(mapOf("name" to "Patch"))
        val duplicate = compatible.copy(description = "Duplicate metadata")
        val scoped = PatchBundleInfo.Scoped(
            "Bundle", null, 1, PatchBundleType.MORPHE, true,
            listOf(duplicate, compatible), listOf(compatible), listOf(duplicate), emptyList()
        )

        val selectorRows = scoped.forSelection()
        assertEquals(listOf(compatible), selectorRows.patches)
        assertEquals(listOf(compatible), selectorRows.compatible)
        assertTrue(selectorRows.incompatible.isEmpty())
        assertEquals(listOf(compatible), scoped.patchSequence(allowIncompatible = true).toList())
    }

    @Test fun selectorKeepsDifferentKeysWithTheSameReadableTitle() {
        val first = PatchInfo.fromMorpheMetadata(mapOf("name" to "first", "displayName" to "Patch"))
        val second = first.copy(name = "second")
        val scoped = PatchBundleInfo.Scoped(
            "Bundle", null, 1, PatchBundleType.RESEAM, true,
            listOf(first, second), emptyList(), emptyList(), listOf(first, second)
        ).forSelection()

        assertEquals(listOf(first, second), scoped.universal)
    }

    @Test fun bundleCategoryIsOptionalDisplayMetadata() {
        val metadata = mapOf("name" to "NewX: Remove ads")
        assertEquals(
            "NewX patches",
            PatchInfo.fromMorpheMetadata(metadata + ("category" to " NewX patches ")).category
        )
        assertNull(PatchInfo.fromMorpheMetadata(metadata + ("category" to " ")).category)
        assertNull(PatchInfo.fromMorpheMetadata(metadata).category)
    }

    @Test fun existingRuntimesKeepTheirNames() {
        val patch = PatchInfo.fromMorpheMetadata(mapOf("name" to "GmsCore support"))
        assertEquals("GmsCore support", patch.name)
        assertEquals(patch.name, patch.displayName)
    }

    @Test fun blankReadableNameFallsBackToSelectionName() {
        val patch = PatchInfo.fromMorpheMetadata(mapOf("name" to "Patch", "displayName" to " "))
        assertEquals("Patch", patch.displayName)
    }
}

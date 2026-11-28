package app.universal.patch.manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatchVersionRecommendationTest {
    @Test
    fun numericVersionWinsTiesRegardlessOfMetadataOrder() {
        assertEquals("2.10", selectSuggestedPatchVersion(linkedMapOf("2.10" to 2, "2.9" to 2)))
        assertEquals("2.10", selectSuggestedPatchVersion(linkedMapOf("2.9" to 2, "2.10" to 2)))
    }

    @Test
    fun patchCoverageWinsOverVersionOrder() {
        assertEquals("2.9", selectSuggestedPatchVersion(linkedMapOf("2.10" to 1, "2.9" to 3)))
        assertEquals("2.9", selectSuggestedPatchVersion(linkedMapOf("2.9" to 3, "2.10" to 1)))
    }

    @Test
    fun largeNumericComponentsDoNotOverflow() {
        assertEquals("2.2147483648", selectSuggestedPatchVersion(
            linkedMapOf("2.2147483647" to 1, "2.2147483648" to 1)
        ))
    }

    @Test
    fun emptyAndSingleVersionAreSupported() {
        assertNull(selectSuggestedPatchVersion(emptyMap()))
        assertEquals("2.10", selectSuggestedPatchVersion(mapOf("2.10" to 1)))
    }
}
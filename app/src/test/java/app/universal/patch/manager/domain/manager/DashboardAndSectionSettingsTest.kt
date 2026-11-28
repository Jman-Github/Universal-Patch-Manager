package app.universal.patch.manager.domain.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardAndSectionSettingsTest {
    @Test fun flippingTheCategoryDefaultClearsOnlyCategoryOverrides() {
        val overrides = setOf(
            "com.example.app:1:${PATCH_CATEGORY_SECTION_PREFIX}Ads",
            "com.example.app:1:regular",
            "com.example.app:1:incompatible",
            // Category names can hold colons of their own.
            "com.other.app:2:${PATCH_CATEGORY_SECTION_PREFIX}Layout: extras",
            "com.other.app:2:new"
        )

        assertEquals(
            setOf("com.example.app:1:regular", "com.example.app:1:incompatible", "com.other.app:2:new"),
            overrides.withoutPatchCategoryOverrides()
        )
    }

    @Test fun sectionKeysSplitBackIntoTheirParts() {
        val key = patchSectionKey("com.example.app", 7, "${PATCH_CATEGORY_SECTION_PREFIX}Layout: extras")
        assertEquals("com.example.app:7:category:Layout: extras", key)
        assertEquals(
            PatchSectionKeyParts("com.example.app", 7, "category:Layout: extras"),
            parsePatchSectionKey(key)
        )
        assertEquals(PatchSectionKeyParts("com.example.app", 3, "regular"), parsePatchSectionKey("com.example.app:3:regular"))
        for (malformed in listOf("", "com.example.app", "com.example.app:3", "com.example.app:x:regular",
            ":3:regular", "com.example.app:3:")) {
            assertNull(malformed, parsePatchSectionKey(malformed))
        }
    }

    @Test fun backedUpCategoryStatesCarryOverOnlyWhileTheDefaultMatches() {
        val backup = setOf("com.example.app:1:${PATCH_CATEGORY_SECTION_PREFIX}Ads", "com.example.app:1:regular")

        assertEquals(backup, importedSectionOverrides(backup, backupExpandsCategories = false, expandsCategories = false))
        assertEquals(backup, importedSectionOverrides(backup, backupExpandsCategories = true, expandsCategories = true))
        // A category entry flips its section from the backup's default, which no longer applies.
        assertEquals(
            setOf("com.example.app:1:regular"),
            importedSectionOverrides(backup, backupExpandsCategories = false, expandsCategories = true)
        )
    }

    @Test fun importedSectionStatesReplaceOnlyTheBundlesTheyCover() {
        val current = setOf("com.a:1:regular", "com.a:1:${PATCH_CATEGORY_SECTION_PREFIX}Ads", "com.b:2:universal", "malformed")

        assertEquals(
            setOf("com.a:1:incompatible", "com.b:2:universal", "malformed"),
            mergedSectionOverrides(current, imported = setOf("com.a:1:incompatible"), restoredBundleUids = setOf(1))
        )
        // A backup whose bundles aren't installed here changes nothing.
        assertEquals(current, mergedSectionOverrides(current, imported = emptySet(), restoredBundleUids = emptySet()))
    }

    @Test fun savedLayoutNamesRoundTrip() {
        for (layout in DashboardListLayout.entries) {
            assertEquals(layout, DashboardListLayout.fromName(layout.name))
        }
    }

    @Test fun layoutNamesThisVersionLacksAreSkipped() {
        assertNull(DashboardListLayout.fromName("LARGE_GRID"))
        assertNull(DashboardListLayout.fromName("grid"))
        assertNull(DashboardListLayout.fromName(""))
    }
}

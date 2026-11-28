package app.universal.patch.manager.domain.manager

/** Id prefix of the patch selection sections built from the categories a bundle gives its patches. */
internal const val PATCH_CATEGORY_SECTION_PREFIX = "category:"

/**
 * Key of a patch selection section's remembered open or closed state. Package names can't hold a
 * colon, so the bundle uid ends at the second one and everything after it is the section id.
 */
internal fun patchSectionKey(packageName: String, bundleUid: Int, sectionId: String): String =
    "$packageName:$bundleUid:$sectionId"

/** The parts of a [patchSectionKey]. */
internal data class PatchSectionKeyParts(
    val packageName: String,
    val bundleUid: Int,
    val sectionId: String
)

/** Splits a [patchSectionKey], or null when [key] isn't one. */
internal fun parsePatchSectionKey(key: String): PatchSectionKeyParts? {
    val parts = key.split(':', limit = 3)
    if (parts.size != 3 || parts[0].isEmpty() || parts[2].isEmpty()) return null
    val bundleUid = parts[1].toIntOrNull() ?: return null
    return PatchSectionKeyParts(parts[0], bundleUid, parts[2])
}

/**
 * These section overrides without the ones for category sections. A section's key is
 * `package:bundleUid:sectionId`, so a category section's key holds `:category:`.
 */
internal fun Set<String>.withoutPatchCategoryOverrides(): Set<String> =
    filterNotTo(mutableSetOf()) { it.contains(":$PATCH_CATEGORY_SECTION_PREFIX") }

/**
 * Section overrides restored from a backup. A category entry flips its section from the default
 * the backup was made with, so category entries only carry over while that default still holds.
 */
internal fun importedSectionOverrides(
    overrides: Set<String>,
    backupExpandsCategories: Boolean,
    expandsCategories: Boolean
): Set<String> =
    if (backupExpandsCategories == expandsCategories) overrides
    else overrides.withoutPatchCategoryOverrides()

/**
 * The saved section overrides once [imported] ones are restored for the bundles in
 * [restoredBundleUids]: those bundles take the backup's states, every other bundle keeps its own.
 */
internal fun mergedSectionOverrides(
    current: Set<String>,
    imported: Set<String>,
    restoredBundleUids: Set<Int>
): Set<String> =
    current.filterNotTo(mutableSetOf()) { parsePatchSectionKey(it)?.bundleUid in restoredBundleUids } + imported

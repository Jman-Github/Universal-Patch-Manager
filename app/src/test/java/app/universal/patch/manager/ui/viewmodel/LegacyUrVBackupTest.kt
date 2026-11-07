package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.domain.manager.*
import app.universal.patch.manager.domain.repository.SerializedSelection
import app.universal.patch.manager.ui.theme.Theme
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Uses frozen exports produced by the last URV source, including its former namespace. */
class LegacyUrVBackupTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = File(System.getProperty("upm.legacyImportFixtures")
        ?: "src/test/fixtures/urv-import")
    private fun raw(name: String) = File(fixtures, name).readText()

    // New nullable settings may appear when re-exporting an older backup.
    // Every field present in the frozen URV export must still match exactly.
    private fun assertLegacyValues(expected: JsonElement, actual: JsonElement) {
        when (expected) {
            is JsonObject -> {
                val restored = actual.jsonObject
                expected.forEach { (key, value) ->
                    assertTrue("Missing legacy field: $key", key in restored)
                    assertLegacyValues(value, restored.getValue(key))
                }
                (restored.keys - expected.keys).forEach { key ->
                    assertEquals("Unexpected non-default field: $key", JsonNull, restored.getValue(key))
                }
            }
            is JsonArray -> {
                val restored = actual.jsonArray
                assertEquals(expected.size, restored.size)
                expected.zip(restored).forEach { (before, after) -> assertLegacyValues(before, after) }
            }
            else -> assertEquals(expected, actual)
        }
    }

    @Test fun everythingBackupRetainsAllSectionsAndOptionTypes() {
        val raw = raw("urv_everything.json")
        val backup = json.decodeFromString<EverythingExportFile>(raw)
        assertLegacyValues(Json.parseToJsonElement(raw), json.encodeToJsonElement(backup))
        assertEquals(1, backup.version)
        assertEquals("URV user profile", backup.patchProfiles.profiles.single().name)
        assertEquals(Theme.DARK, backup.managerSettings.settings.theme)
        val options = backup.patchProfiles.profiles.single().payload.bundles.single().options.getValue("Patch")
        assertEquals(9007199254740993L, options.getValue("large").raw.jsonPrimitive.long)
        assertTrue(options.getValue("enabled").raw.jsonPrimitive.boolean)
        assertEquals("app.urv.manager.keep.this", options.getValue("customPackage").raw.jsonPrimitive.content)
        assertEquals(JsonNull, options.getValue("unset").raw)
    }

    @Test fun standaloneSettingsRetainCredentialsAndCustomizations() {
        val raw = raw("urv_manager_settings.json")
        val backup = json.decodeFromString<ManagerSettingsExportFile>(raw)
        assertLegacyValues(Json.parseToJsonElement(raw), json.encodeToJsonElement(backup))
        val settings = normalizeImportedManagerSettings(backup.settings)
        assertEquals("https://api.revanced.app", settings.api)
        assertEquals("fixture-key", settings.keystoreAlias)
        assertEquals("NOT_A_REAL_PASSWORD", settings.keystorePass)
        assertEquals("URV-{app-name}", settings.patchedAppExportFormat)
        assertEquals(setOf("app.universal.patch.manager.reseam.runtime"), settings.acknowledgedPatcherRuntimePlugins)
        assertEquals(SearchForUpdatesBackgroundInterval.HOUR, settings.searchForUpdatesBackgroundInterval)
    }

    @Test fun standaloneBundleAndProfileFilesRoundTripWithoutNamespaceRewrites() {
        val bundles = raw("urv_patch_bundles.json")
        assertEquals(Json.parseToJsonElement(bundles),
            json.encodeToJsonElement(json.decodeFromString<PatchBundleExportFile>(bundles)))
        val profiles = raw("urv_patch_profiles.json")
        assertEquals(Json.parseToJsonElement(profiles),
            json.encodeToJsonElement(json.decodeFromString<PatchProfileExportFile>(profiles)))
    }

    @Test fun bothSelectionBackupVersionsRemainReadable() {
        for (version in 1..2) {
            val file = if (version == 1) "urv_patch_selection_v1.json" else "urv_patch_selection_all.json"
            val raw = raw(file)
            val backup = json.decodeFromString<PatchSelectionExportFile>(raw)
            assertEquals(version, backup.version)
            assertEquals(listOf("Patch"), backup.bundles.single().selection.getValue("com.example.app"))
            assertLegacyValues(Json.parseToJsonElement(raw), json.encodeToJsonElement(backup))
        }
    }

    @Test fun singleBundleAndLegacySelectionOnlyExportsRemainReadable() {
        val bundle = raw("urv_patch_selection_bundle.json")
        val decoded = json.decodeFromString<PatchSelectionBundleExport>(bundle)
        assertEquals(Json.parseToJsonElement(bundle), json.encodeToJsonElement(decoded))
        assertEquals(decoded.selection, json.decodeFromString<SerializedSelection>(raw("urv_legacy_selection_only.json")))
    }

    @Test fun minimalOldSettingsUseDefaultsForMissingFields() {
        val backup = json.decodeFromString<ManagerSettingsExportFile>(raw("urv_minimal_settings.json"))
        assertEquals(1, backup.version)
        assertEquals(true, backup.settings.pureBlackTheme)
        assertNull(backup.settings.useJvmRuntime)
    }

    @Test fun unknownBackupFieldsDoNotBlockOldImports() {
        val original = Json.parseToJsonElement(raw("urv_everything.json")).jsonObject
        val extended = JsonObject(original + ("futureMetadata" to JsonPrimitive("ignored")))
        assertEquals(json.decodeFromJsonElement<EverythingExportFile>(original),
            json.decodeFromJsonElement<EverythingExportFile>(extended))
    }
}

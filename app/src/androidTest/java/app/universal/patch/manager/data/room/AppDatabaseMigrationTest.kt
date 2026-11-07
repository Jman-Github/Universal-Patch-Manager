package app.universal.patch.manager.data.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate16To18CreatesLsposedModulesTable() {
        migrationHelper.createDatabase(DATABASE_16, 16).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_16,
            18,
            true,
            MIGRATION_16_17,
            MIGRATION_17_18,
        )
        try {
            assertEquals(EXPECTED_COLUMNS, migrated.lsposedModuleColumns())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrate17To18PreservesModuleAndRemovesLegacySettingsColumn() {
        migrationHelper.createDatabase(DATABASE_17, 17).apply {
            execSQL(
                """
                INSERT INTO lsposed_modules (
                    package_name,
                    display_name,
                    installed_version,
                    installed_version_code,
                    source_kind,
                    source_reference,
                    release_tag,
                    asset_name,
                    asset_digest,
                    signing_fingerprint,
                    has_settings_activity,
                    latest_version,
                    latest_asset_digest,
                    last_update_check,
                    update_available
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    PACKAGE_NAME,
                    "Test module",
                    "1.0",
                    10L,
                    "GITHUB_REPOSITORY",
                    "https://github.com/example/module",
                    "v1.0",
                    "module.apk",
                    "a".repeat(64),
                    "AA:BB",
                    1,
                    "v1.1",
                    "b".repeat(64),
                    1234L,
                    1,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_17,
            18,
            true,
            MIGRATION_17_18,
        )
        try {
            val columns = migrated.lsposedModuleColumns()
            assertEquals(EXPECTED_COLUMNS, columns)
            assertFalse(columns.contains("has_settings_activity"))

            migrated.query(
                """
                SELECT package_name, display_name, installed_version,
                    installed_version_code, source_kind, source_reference,
                    signing_fingerprint, update_available
                FROM lsposed_modules
                WHERE package_name = ?
                """.trimIndent(),
                arrayOf(PACKAGE_NAME),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(PACKAGE_NAME, cursor.getString(0))
                assertEquals("Test module", cursor.getString(1))
                assertEquals("1.0", cursor.getString(2))
                assertEquals(10L, cursor.getLong(3))
                assertEquals("GITHUB_REPOSITORY", cursor.getString(4))
                assertEquals("https://github.com/example/module", cursor.getString(5))
                assertEquals("AA:BB", cursor.getString(6))
                assertEquals(1, cursor.getInt(7))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrate21To22PreservesSavedMetadataWithUnknownVersionCode() {
        val packageName = "com.example.saved"
        migrationHelper.createDatabase(DATABASE_21, 21).apply {
            execSQL(
                """
                INSERT INTO installed_app (
                    current_package_name, original_package_name, version, install_type,
                    sort_order, selection_payload, created_at, custom_installer_package_name,
                    repatch_source_path, use_mount
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(packageName, packageName, "4.3.2360", "SAVED", 7,
                    "{}", 1234L, null, "/retained/original.apk", 0),
            )
            close()
        }
        val migrated = migrationHelper.runMigrationsAndValidate(
            DATABASE_21, 22, true, MIGRATION_21_22,
        )
        try {
            migrated.query(
                "SELECT version, version_code, sort_order, selection_payload, created_at, " +
                    "repatch_source_path, use_mount FROM installed_app WHERE current_package_name = ?",
                arrayOf(packageName),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("4.3.2360", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals(7, cursor.getInt(2))
                assertEquals("{}", cursor.getString(3))
                assertEquals(1234L, cursor.getLong(4))
                assertEquals("/retained/original.apk", cursor.getString(5))
                assertEquals(0, cursor.getInt(6))
            }
            // Android long version codes must survive without being truncated to Int.
            val longVersionCode = 4_294_969_656L
            migrated.execSQL(
                "UPDATE installed_app SET version_code = ? WHERE current_package_name = ?",
                arrayOf(longVersionCode, packageName),
            )
            migrated.query("SELECT version_code FROM installed_app").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(longVersionCode, cursor.getLong(0))
            }
        } finally {
            migrated.close()
        }
    }

    private fun SupportSQLiteDatabase.lsposedModuleColumns(): List<String> =
        query("PRAGMA table_info(`lsposed_modules`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private companion object {
        const val DATABASE_16 = "lsposed-migration-16"
        const val DATABASE_17 = "lsposed-migration-17"
        const val DATABASE_21 = "saved-app-migration-21"
        const val PACKAGE_NAME = "com.example.module"

        val EXPECTED_COLUMNS = listOf(
            "package_name",
            "display_name",
            "installed_version",
            "installed_version_code",
            "source_kind",
            "source_reference",
            "release_tag",
            "asset_name",
            "asset_digest",
            "signing_fingerprint",
            "latest_version",
            "latest_asset_digest",
            "last_update_check",
            "update_available",
        )
    }
}

package app.universal.patch.manager.data.room.apps.installed

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledAppDao {
    @Query("SELECT * FROM installed_app ORDER BY sort_order ASC, current_package_name ASC")
    fun getAll(): Flow<List<InstalledApp>>

    @Query("SELECT * FROM installed_app")
    suspend fun getAllSnapshot(): List<InstalledApp>

    @Query("SELECT * FROM installed_app WHERE current_package_name = :packageName")
    suspend fun get(packageName: String): InstalledApp?

    @Query("SELECT * FROM installed_app WHERE install_type = :installType")
    suspend fun getByInstallType(installType: InstallType): List<InstalledApp>

    @Query("SELECT sort_order FROM installed_app WHERE current_package_name = :packageName")
    suspend fun getSortOrder(packageName: String): Int?

    @Query("SELECT MAX(sort_order) FROM installed_app")
    suspend fun getMaxSortOrder(): Int?

    @Query("UPDATE installed_app SET sort_order = :sortOrder WHERE current_package_name = :packageName")
    suspend fun updateSortOrder(packageName: String, sortOrder: Int)

    @Query("UPDATE installed_app SET selection_payload = :payload WHERE current_package_name = :packageName")
    suspend fun updateSelectionPayload(packageName: String, payload: PatchProfilePayload)

    @Query("UPDATE installed_app SET version_code = :versionCode WHERE current_package_name = :packageName AND version = :version AND version_code IS NULL")
    suspend fun rememberVersionCode(packageName: String, version: String, versionCode: Long)

    @Query("UPDATE installed_app SET repatch_source_path = NULL WHERE repatch_source_path IS NOT NULL")
    suspend fun clearRepatchSourcePaths()

    @Query(
        "SELECT bundle, patch_name FROM applied_patch" +
                " WHERE package_name = :packageName"
    )
    suspend fun getPatchesSelection(packageName: String): Map<@MapColumn("bundle") Int, List<@MapColumn(
        "patch_name"
    ) String>>

    @Transaction
    suspend fun upsertApp(installedApp: InstalledApp, appliedPatches: List<AppliedPatch>) {
        upsertApp(installedApp)
        deleteAppliedPatches(installedApp.currentPackageName)
        insertAppliedPatches(appliedPatches)
    }

    @Upsert
    suspend fun upsertApp(installedApp: InstalledApp)

    @Insert
    suspend fun insertAppliedPatches(appliedPatches: List<AppliedPatch>)

    @Query("DELETE FROM applied_patch WHERE package_name = :packageName")
    suspend fun deleteAppliedPatches(packageName: String)

    @Query("DELETE FROM installed_app WHERE install_type = :installType")
    suspend fun deleteByInstallType(installType: InstallType)

    @Delete
    suspend fun delete(installedApp: InstalledApp)
}

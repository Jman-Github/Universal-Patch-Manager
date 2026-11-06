package app.universal.patch.manager.data.room.apps.installed

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.R

enum class InstallType(val stringResource: Int) {
    DEFAULT(R.string.install_type_system_installer),
    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
    PLAY_STORE(R.string.install_type_play_store),
    ROOT_PLAY_STORE(R.string.install_type_root_play_store),
    CUSTOM(R.string.install_type_custom_installer),
    MOUNT(R.string.mount_install),
    SAVED(R.string.saved_install),
    SHIZUKU(R.string.install_type_shizuku_label),
    SHIZUKU_PLAY_STORE(R.string.install_type_shizuku_play_store)
}

@Entity(tableName = "installed_app")
data class InstalledApp(
    @PrimaryKey
    @ColumnInfo(name = "current_package_name") val currentPackageName: String,
    @ColumnInfo(name = "original_package_name") val originalPackageName: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "install_type") val installType: InstallType,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "selection_payload") val selectionPayload: PatchProfilePayload? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "custom_installer_package_name")
    val customInstallerPackageName: String? = null,
    @ColumnInfo(name = "repatch_source_path") val repatchSourcePath: String? = null,
    @ColumnInfo(name = "use_mount") val useMount: Boolean? = null,
    @ColumnInfo(name = "version_code") val versionCode: Long? = null
)

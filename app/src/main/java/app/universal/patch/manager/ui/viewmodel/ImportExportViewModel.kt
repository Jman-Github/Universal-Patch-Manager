package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.util.bundleImportLabel
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.manager.AutoClearCacheInterval
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.domain.manager.BundleUpdateDeliveryMode
import app.universal.patch.manager.domain.repository.DownloaderPluginRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchOptionsRepository
import app.universal.patch.manager.domain.repository.PatchProfileExportEntry
import app.universal.patch.manager.domain.repository.PatchProfileRepository
import app.universal.patch.manager.domain.repository.PatcherRuntimePluginRepository
import app.universal.patch.manager.domain.repository.PatchSelectionRepository
import app.universal.patch.manager.domain.repository.SerializedOptions
import app.universal.patch.manager.domain.repository.SerializedSelection
import app.universal.patch.manager.domain.bundles.PatchBundleSource
import app.universal.patch.manager.domain.bundles.JsonPatchBundle
import app.universal.patch.manager.domain.bundles.RepositoryBundleSettings
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.bundles.PatchBundleChangelogEntry
import app.universal.patch.manager.domain.repository.remapLocalBundles
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.patcher.worker.AutoPatchWorker
import app.universal.patch.manager.domain.bundles.bundleEndpointIdentity
import app.universal.patch.manager.domain.bundles.toLegacyAwareBundleIndex
import app.universal.patch.manager.domain.bundles.legacyBundleEndpointsMatch
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.uiSafe
import app.universal.patch.manager.util.permission.hasNotificationPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteExisting
import kotlin.io.path.inputStream

sealed class ResetDialogState(
    @StringRes val titleResId: Int,
    @StringRes val descriptionResId: Int,
    val onConfirm: () -> Unit,
    val dialogOptionName: String? = null
) {
    class Keystore(onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.regenerate_keystore,
        descriptionResId = R.string.regenerate_keystore_dialog_description,
        onConfirm = onConfirm
    )

    class PatchSelectionAll(onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_selection_reset_all,
        descriptionResId = R.string.patch_selection_reset_all_dialog_description,
        onConfirm = onConfirm
    )

    class PatchSelectionPackage(dialogOptionName:String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_selection_reset_package,
        descriptionResId = R.string.patch_selection_reset_package_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )

    class PatchSelectionBundle(dialogOptionName: String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_selection_reset_patches,
        descriptionResId = R.string.patch_selection_reset_patches_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )

    class PatchOptionsAll(onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_options_reset_all,
        descriptionResId = R.string.patch_options_reset_all_dialog_description,
        onConfirm = onConfirm
    )

    class PatchOptionPackage(dialogOptionName:String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_options_reset_package,
        descriptionResId = R.string.patch_options_reset_package_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )

    class PatchOptionBundle(dialogOptionName: String, onConfirm: () -> Unit) : ResetDialogState(
        titleResId = R.string.patch_options_reset_patches,
        descriptionResId = R.string.patch_options_reset_patches_dialog_description,
        onConfirm = onConfirm,
        dialogOptionName = dialogOptionName
    )
}

@Serializable
data class PatchBundleExportFile(
    val bundles: List<PatchBundleSnapshot>
)

@Serializable
data class PatchBundleSnapshot(
    val endpoint: String,
    val name: String,
    val displayName: String? = null,
    val autoUpdate: Boolean = false,
    val searchUpdate: Boolean = true,
    val usePrereleases: Boolean? = null,
    val useLatest: Boolean? = null,
    val useSpecificVersion: Boolean? = null,
    val selectedVersion: app.universal.patch.manager.network.dto.ReVancedAsset? = null,
    val enabled: Boolean = true,
    val officialState: OfficialBundleState? = null,
    val position: Int? = null,
    val officialAutoUpdate: Boolean? = null,
    val officialUsePrereleases: Boolean? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val changelogHistory: List<PatchBundleChangelogEntry> = emptyList()
)

@Serializable
enum class OfficialBundleState {
    PRESENT,
    ABSENT
}

private data class PatchBundleImportSummary(
    val created: Int,
    val updated: Int,
    val skipped: Int
)

private data class PatchBundleExportResult(
    val exportFile: PatchBundleExportFile,
    val exportedCount: Int,
    val hasLocalSources: Boolean
)

private enum class EverythingImportToast {
    PATCH_SELECTION,
    PATCH_BUNDLES,
    PATCH_PROFILES,
}

@Serializable
data class PatchProfileExportFile(
    val profiles: List<PatchProfileExportEntry>
)

@Serializable
data class ManagerSettingsExportFile(
    val version: Int = 1,
    val settings: PreferencesManager.SettingsSnapshot
)

@Serializable
data class EverythingExportFile(
    val version: Int = 1,
    val patchBundles: PatchBundleExportFile,
    val patchProfiles: PatchProfileExportFile,
    val managerSettings: ManagerSettingsExportFile,
    val patchSelection: PatchSelectionExportFile
)

@Serializable
data class PatchSelectionExportFile(
    val version: Int = 2,
    val bundles: List<PatchSelectionBundleExport>
)

@Serializable
data class PatchSelectionBundleExport(
    val bundleUid: Int,
    val name: String,
    val displayName: String?,
    val source: String?,
    val selection: SerializedSelection,
    val options: SerializedOptions? = null
)

private data class DecodedPatchSelectionBundleImport(
    val export: PatchSelectionBundleExport,
    val isLegacySelectionOnly: Boolean
)

internal fun importedPermissionsDeferAutoPatchWork(
    needsNotificationPermission: Boolean,
    needsShizukuPermission: Boolean
): Boolean = needsNotificationPermission || needsShizukuPermission

internal fun importedBackgroundWorkNeedsNotifications(
    bundleInterval: SearchForUpdatesBackgroundInterval,
    managerInterval: SearchForUpdatesBackgroundInterval,
    announcementInterval: SearchForUpdatesBackgroundInterval,
    autoClearCacheInterval: AutoClearCacheInterval,
    deliveryMode: BundleUpdateDeliveryMode,
    autoPatchEnabled: Boolean
): Boolean = autoPatchEnabled ||
    bundleInterval != SearchForUpdatesBackgroundInterval.NEVER ||
    managerInterval != SearchForUpdatesBackgroundInterval.NEVER ||
    announcementInterval != SearchForUpdatesBackgroundInterval.NEVER ||
    autoClearCacheInterval != AutoClearCacheInterval.NEVER ||
    (
        deliveryMode == BundleUpdateDeliveryMode.WEBSOCKET_PREFERRED &&
            (
                bundleInterval != SearchForUpdatesBackgroundInterval.NEVER ||
                    managerInterval != SearchForUpdatesBackgroundInterval.NEVER
                )
        )

@OptIn(ExperimentalSerializationApi::class)
class ImportExportViewModel(
    private val app: Application,
    private val keystoreManager: KeystoreManager,
    private val selectionRepository: PatchSelectionRepository,
    private val optionsRepository: PatchOptionsRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val patchProfileRepository: PatchProfileRepository,
    private val patcherRuntimePluginRepository: PatcherRuntimePluginRepository,
    private val downloaderPluginRepository: DownloaderPluginRepository,
    private val preferencesManager: PreferencesManager,
    private val workerRepository: WorkerRepository,
    private val installerManager: InstallerManager
) : ViewModel() {
    data class ImportedNotificationRollback(
        val bundleInterval: SearchForUpdatesBackgroundInterval,
        val managerInterval: SearchForUpdatesBackgroundInterval,
        val announcementInterval: SearchForUpdatesBackgroundInterval,
        val autoClearCacheInterval: AutoClearCacheInterval,
        val deliveryMode: BundleUpdateDeliveryMode,
        val autoPatchEnabled: Boolean,
        val autoPatchInstallWithShizuku: Boolean,
        val autoPatchUninstallOnConflictWithShizuku: Boolean,
        val autoPatchInterval: SearchForUpdatesBackgroundInterval,
        val autoPatchRequiresCharging: Boolean
    )

    data class ImportedPermissionRequest(
        val needsStoragePermission: Boolean = false,
        val needsNotificationPermission: Boolean = false,
        val needsShizukuPermission: Boolean = false,
        val notificationRollback: ImportedNotificationRollback? = null,
        val shizukuAutoInstallRollback: Boolean? = null,
        val shizukuAutoUninstallRollback: Boolean? = null,
        val shizukuAutoPatchInstallRollback: Boolean? = null,
        val shizukuAutoPatchConflictUninstallRollback: Boolean? = null
    ) {
        val isEmpty: Boolean
            get() = !needsStoragePermission &&
                !needsNotificationPermission &&
                !needsShizukuPermission
    }

    enum class SelectionAction {
        ImportBundle,
        ImportAllBundles,
        ExportBundle,
        ExportAllBundles
    }

    enum class ExportType(@StringRes val failureMessage: Int) {
        PatchBundles(R.string.export_patch_bundles_fail),
        PatchProfiles(R.string.export_patch_profiles_fail),
        SelectionBundle(R.string.export_patch_selection_fail),
        SelectionAllBundles(R.string.export_patch_selection_fail)
    }

    private var preparedPatchBundles: PatchBundleExportResult? = null
    private var preparedPatchProfiles: List<PatchProfileExportEntry>? = null
    private var preparedSelectionBundle: PatchSelectionBundleExport? = null
    private var preparedSelectionAllBundles: PatchSelectionExportFile? = null

    private val contentResolver = app.contentResolver
    private val tolerantJson = Json { ignoreUnknownKeys = true }
    private val settingsImportMutex = Mutex()
    private val importedPermissionResultMutex = Mutex()
    private var deferredImportedAutoPatchSettings: PreferencesManager.SettingsSnapshot? = null
    private var restartAfterSettingsImport by mutableStateOf(false)
    var isImportingManagerSettings by mutableStateOf(false)
        private set
    val settingsImportPending by derivedStateOf {
        isImportingManagerSettings || restartAfterSettingsImport ||
            importedPermissionRequest != null || importedSettingsRestartLanguage != null
    }
    var importedSettingsRestartLanguage by mutableStateOf<String?>(null)
        private set

    fun consumeImportedSettingsRestart() {
        importedSettingsRestartLanguage = null
    }

    private suspend fun completeImportedSettingsRestart() {
        if (restartAfterSettingsImport && importedPermissionRequest == null) {
            val language = preferencesManager.appLanguage.get()
            restartAfterSettingsImport = false
            importedSettingsRestartLanguage = language
        }
    }
    val patchBundles = patchBundleRepository.sources
    val bundleImportProgress = patchBundleRepository.bundleImportProgress
    var selectedBundle by mutableStateOf<PatchBundleSource?>(null)
        private set
    var selectionAction by mutableStateOf<SelectionAction?>(null)
        private set
    private var keystoreImportPath by mutableStateOf<Path?>(null)
    val showCredentialsDialog by derivedStateOf { keystoreImportPath != null }
    var keystoreDiagnostics by mutableStateOf<KeystoreManager.KeystoreDiagnostics?>(null)
        private set

    var resetDialogState by mutableStateOf<ResetDialogState?>(null)
    var importedPermissionRequest by mutableStateOf<ImportedPermissionRequest?>(null)
        private set

    val packagesWithOptions = optionsRepository.getPackagesWithSavedOptions()
    val packagesWithSelection = selectionRepository.getPackagesWithSavedSelection()

    init {
        refreshKeystoreDiagnostics()
    }

    fun refreshKeystoreDiagnostics() = viewModelScope.launch {
        keystoreDiagnostics = keystoreManager.getDiagnostics()
    }

    fun resetOptionsForPackage(packageName: String) = viewModelScope.launch {
        optionsRepository.resetOptionsForPackage(packageName)
        app.toast(app.getString(R.string.patch_options_reset_toast))
    }

    fun resetOptionsForBundle(patchBundle: PatchBundleSource) = viewModelScope.launch {
        optionsRepository.resetOptionsForPatchBundle(patchBundle.uid)
        app.toast(app.getString(R.string.patch_options_reset_toast))
    }

    fun resetOptions() = viewModelScope.launch {
        optionsRepository.reset()
        app.toast(app.getString(R.string.patch_options_reset_toast))
    }

    private fun handleImportedPermissionResult(
        needsPermission: (ImportedPermissionRequest) -> Boolean,
        onResult: suspend (ImportedPermissionRequest) -> ImportedPermissionRequest
    ) = viewModelScope.launch {
        importedPermissionResultMutex.withLock {
            withContext(NonCancellable) {
                val request = importedPermissionRequest?.takeIf(needsPermission)
                    ?: return@withContext
                importedPermissionRequest = onResult(request)
                    .takeUnless(ImportedPermissionRequest::isEmpty)
                completeImportedSettingsRestart()
            }
        }
    }

    fun onImportedStoragePermissionResult(granted: Boolean) = handleImportedPermissionResult(
        needsPermission = { it.needsStoragePermission }
    ) { request ->
        if (!granted) preferencesManager.useCustomFilePicker.update(false)
        request.copy(needsStoragePermission = false)
    }

    fun onImportedNotificationPermissionResult(granted: Boolean) = handleImportedPermissionResult(
        needsPermission = { it.needsNotificationPermission }
    ) { request ->
        val notificationsGranted = granted && app.hasNotificationPermission()
        if (!notificationsGranted) {
            rollbackImportedNotificationSettings(request.notificationRollback)
        } else if (deferredImportedAutoPatchSettings != null) {
            preferencesManager.updateAutoPatchEnabled(true)
        }
        deferredImportedAutoPatchSettings = null
        syncImportedPeriodicWork()
        if (!request.needsShizukuPermission) {
            syncImportedAutoPatchWork()
        }
        request.copy(needsNotificationPermission = false)
    }

    fun onImportedShizukuPermissionResult(granted: Boolean) = handleImportedPermissionResult(
        needsPermission = { it.needsShizukuPermission }
    ) { request ->
        if (!granted) {
            preferencesManager.autoInstallWithShizuku.update(
                request.shizukuAutoInstallRollback ?: false
            )
            preferencesManager.autoUninstallWithShizuku.update(
                request.shizukuAutoUninstallRollback ?: false
            )
            preferencesManager.restoreAutoPatchShizukuSettings(
                installWithShizuku =
                    request.shizukuAutoPatchInstallRollback ?: false,
                uninstallOnConflictWithShizuku =
                    request.shizukuAutoPatchConflictUninstallRollback ?: false
            )
        }
        syncImportedAutoPatchWork()
        request.copy(needsShizukuPermission = false)
    }

    fun startKeystoreImport(content: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.failed_to_import_keystore, "Failed to import keystore") {
            val path = withContext(Dispatchers.IO) {
                File.createTempFile("signing", "ks", app.cacheDir).toPath().also {
                    Files.copy(
                        contentResolver.openInputStream(content)!!,
                        it,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }

            aliases.forEach { alias ->
                knownPasswords.forEach { pass ->
                    if (tryKeystoreImport(alias, pass, pass, path)) {
                        return@launch
                    }
                }
            }

            keystoreImportPath = path
        }
    }

    fun startKeystoreImport(content: Path) = viewModelScope.launch {
        uiSafe(app, R.string.failed_to_import_keystore, "Failed to import keystore") {
            val path = withContext(Dispatchers.IO) {
                File.createTempFile("signing", "ks", app.cacheDir).toPath().also { target ->
                    content.inputStream().use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }

            aliases.forEach { alias ->
                knownPasswords.forEach { pass ->
                    if (tryKeystoreImport(alias, pass, pass, path)) {
                        return@launch
                    }
                }
            }

            keystoreImportPath = path
        }
    }

    fun cancelKeystoreImport() {
        keystoreImportPath?.deleteExisting()
        keystoreImportPath = null
    }

    suspend fun tryKeystoreImport(alias: String, storePass: String, keyPass: String) =
        tryKeystoreImport(alias, storePass, keyPass, keystoreImportPath!!)

    private suspend fun tryKeystoreImport(
        alias: String,
        storePass: String,
        keyPass: String,
        path: Path
    ): Boolean {
        path.inputStream().use { stream ->
            if (keystoreManager.import(alias, storePass, keyPass, stream)) {
                app.toast(app.getString(R.string.import_keystore_success))
                cancelKeystoreImport()
                refreshKeystoreDiagnostics()
                return true
            }
        }

        return false
    }

    override fun onCleared() {
        super.onCleared()

        cancelKeystoreImport()
    }

    fun canExport() = keystoreManager.hasKeystore()

    fun exportKeystore(target: Uri) = viewModelScope.launch {
        keystoreManager.export(contentResolver.openOutputStream(target)!!)
        app.toast(app.getString(R.string.export_keystore_success))
    }

    fun exportKeystore(target: Path) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            target.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(target).use { output ->
                keystoreManager.export(output)
            }
        }
        app.toast(app.getString(R.string.export_keystore_success))
    }

    fun regenerateKeystore() = viewModelScope.launch {
        keystoreManager.regenerate()
        app.toast(app.getString(R.string.regenerate_keystore_success))
        refreshKeystoreDiagnostics()
    }

    fun resetSelection() = viewModelScope.launch {
        withContext(Dispatchers.Default) { selectionRepository.reset() }
        app.toast(app.getString(R.string.reset_patch_selection_success))
    }

    fun resetSelectionForPackage(packageName: String) = viewModelScope.launch {
        selectionRepository.resetSelectionForPackage(packageName)
        app.toast(app.getString(R.string.reset_patch_selection_success))
    }

    fun resetSelectionForPatchBundle(patchBundle: PatchBundleSource) = viewModelScope.launch {
        selectionRepository.resetSelectionForPatchBundle(patchBundle.uid)
        app.toast(app.getString(R.string.reset_patch_selection_success))
    }

    fun executeSelectionImport(target: Path) = viewModelScope.launch {
        val source = selectedBundle ?: return@launch
        clearSelectionAction()

        uiSafe(app, R.string.import_patch_selection_fail, "Failed to restore patch selections and options") {
            val decodedImport = withContext(Dispatchers.IO) {
                target.inputStream().bufferedReader().use { reader ->
                    decodeSelectionBundleImport(
                        reader.readText(),
                        source
                    )
                }
            }

            validateSelectionBundleImportTarget(decodedImport, source)
            selectionRepository.import(source.uid, decodedImport.export.selection)
            decodedImport.export.options?.let { optionsRepository.import(source.uid, it) }
            app.toast(app.getString(R.string.import_patch_selection_success))
        }
    }

    fun executeSelectionImport(target: Uri) = viewModelScope.launch {
        val source = selectedBundle ?: return@launch
        clearSelectionAction()

        uiSafe(app, R.string.import_patch_selection_fail, "Failed to restore patch selections and options") {
            val decodedImport = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(target)!!.bufferedReader().use { reader ->
                    decodeSelectionBundleImport(
                        reader.readText(),
                        source
                    )
                }
            }

            validateSelectionBundleImportTarget(decodedImport, source)
            selectionRepository.import(source.uid, decodedImport.export.selection)
            decodedImport.export.options?.let { optionsRepository.import(source.uid, it) }
            app.toast(app.getString(R.string.import_patch_selection_success))
        }
    }

    fun executeSelectionImportAllBundles(
        target: Path,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = viewModelScope.launch {
        clearSelectionAction()

        uiSafe(app, R.string.import_patch_selection_fail, "Failed to restore patch selections and options") {
            val exportFile = withContext(Dispatchers.IO) {
                target.inputStream().bufferedReader().use { reader ->
                    tolerantJson.decodeFromString<PatchSelectionExportFile>(reader.readText())
                }
            }

            importPatchSelectionExportFile(exportFile, onToast)
        }
    }

    fun executeSelectionImportAllBundles(
        target: Uri,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = viewModelScope.launch {
        clearSelectionAction()

        uiSafe(app, R.string.import_patch_selection_fail, "Failed to restore patch selections and options") {
            val exportFile = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(target)!!.bufferedReader().use { reader ->
                    tolerantJson.decodeFromString<PatchSelectionExportFile>(reader.readText())
                }
            }

            importPatchSelectionExportFile(exportFile, onToast)
        }
    }

    // Prepare before opening either file picker: CreateDocument already creates the file.
    suspend fun prepareExport(type: ExportType): Boolean {
        clearPreparedExport()
        var ready = false
        uiSafe(app, type.failureMessage, "Failed to prepare settings export") {
            ready = when (type) {
                ExportType.PatchBundles -> {
                    preparedPatchBundles = patchBundlesForExport()
                    preparedPatchBundles != null
                }
                ExportType.PatchProfiles -> {
                    preparedPatchProfiles = profilesForExport()
                    preparedPatchProfiles != null
                }
                ExportType.SelectionBundle -> {
                    preparedSelectionBundle = selectionBundleForExport()
                    preparedSelectionBundle != null
                }
                ExportType.SelectionAllBundles -> {
                    preparedSelectionAllBundles = selectionsForExport()
                    preparedSelectionAllBundles != null
                }
            }
        }
        return ready
    }

    fun clearPreparedExport() {
        preparedPatchBundles = null
        preparedPatchProfiles = null
        preparedSelectionBundle = null
        preparedSelectionAllBundles = null
    }

    private suspend fun patchBundlesForExport(): PatchBundleExportResult? {
        val result = preparedPatchBundles ?: buildPatchBundleExportResult()
        preparedPatchBundles = null
        if (result.exportedCount == 0) {
            app.toast(app.getString(R.string.export_patch_bundles_empty))
            return null
        }
        return result
    }

    private suspend fun profilesForExport(): List<PatchProfileExportEntry>? {
        val profiles = preparedPatchProfiles ?: patchProfileRepository.exportProfiles()
        preparedPatchProfiles = null
        if (profiles.isEmpty()) {
            app.toast(app.getString(R.string.export_patch_profiles_empty))
            return null
        }
        return profiles
    }

    private suspend fun selectionBundleForExport(): PatchSelectionBundleExport? {
        val source = selectedBundle ?: return null
        val export = preparedSelectionBundle ?: buildPatchSelectionBundleExport(source)
        preparedSelectionBundle = null
        if (export.selection.isEmpty() && export.options.isNullOrEmpty()) {
            app.toast(app.getString(R.string.export_patch_bundles_empty))
            return null
        }
        return export
    }

    private suspend fun selectionsForExport(): PatchSelectionExportFile? {
        val export = preparedSelectionAllBundles ?: buildPatchSelectionExportFile()
        preparedSelectionAllBundles = null
        if (export.bundles.isEmpty()) {
            app.toast(app.getString(R.string.export_patch_bundles_empty))
            return null
        }
        return export
    }

    fun executeSelectionExport(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_selection_fail, "Failed to backup patch selections and options") {
            val selectionExport = selectionBundleForExport()
            clearSelectionAction()
            if (selectionExport == null) return@uiSafe

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(selectionExport, it)
                }
            }
            app.toast(app.getString(R.string.export_patch_selection_success))
        }
    }

    fun executeSelectionExport(target: Path) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_selection_fail, "Failed to backup patch selections and options") {
            val selectionExport = selectionBundleForExport()
            clearSelectionAction()
            if (selectionExport == null) return@uiSafe

            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use {
                    Json.Default.encodeToStream(selectionExport, it)
                }
            }
            app.toast(app.getString(R.string.export_patch_selection_success))
        }
    }

    fun executeSelectionExportAllBundles(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_selection_fail, "Failed to backup patch selections and options") {
            val exportFile = selectionsForExport()
            clearSelectionAction()
            if (exportFile == null) return@uiSafe

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(exportFile, it)
                }
            }
            app.toast(app.getString(R.string.export_patch_selection_success))
        }
    }

    fun executeSelectionExportAllBundles(target: Path) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_selection_fail, "Failed to backup patch selections and options") {
            val exportFile = selectionsForExport()
            clearSelectionAction()
            if (exportFile == null) return@uiSafe

            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use {
                    Json.Default.encodeToStream(exportFile, it)
                }
            }
            app.toast(app.getString(R.string.export_patch_selection_success))
        }
    }

    private suspend fun importPatchSelectionExportFile(
        exportFile: PatchSelectionExportFile,
        onToast: (String) -> Unit = { app.toast(it) }
    ) {
        require(exportFile.version in 1..2) { "Unsupported patch selection backup version" }
        val bundles = patchBundleRepository.sources.first()
        val byEndpoint =
            bundles.mapNotNull { it.asRemoteOrNull?.endpoint?.let(::bundleEndpointIdentity)?.takeIf(String::isNotBlank)?.let { endpoint -> endpoint to it } }
                .toLegacyAwareBundleIndex()

        var skipped = 0
        for (bundleExport in exportFile.bundles) {
            val endpoint = bundleExport.source?.let(::bundleEndpointIdentity)?.takeIf(String::isNotBlank)
            val source = if (endpoint != null) {
                byEndpoint[endpoint]
            } else {
                bundles.singleOrNull {
                    it.asRemoteOrNull == null && bundleExport.matchesSelectedBundle(it)
                }
            }
            if (source == null) {
                skipped++
                continue
            }
            selectionRepository.import(source.uid, bundleExport.selection)
            bundleExport.options?.let { optionsRepository.import(source.uid, it) }
        }

        val message = app.getString(R.string.import_patch_selection_success)
        onToast(
            if (skipped == 0) message
            else "$message, ${app.getString(R.string.import_result_with_skipped, skipped)}"
        )
    }

    private suspend fun buildPatchSelectionBundleExport(
        bundle: PatchBundleSource
    ): PatchSelectionBundleExport {
        val selection = selectionRepository.export(bundle.uid)
        val options = optionsRepository.export(bundle.uid)

        return PatchSelectionBundleExport(
            bundleUid = bundle.uid,
            name = bundle.name,
            displayName = bundle.displayName,
            source = bundle.asRemoteOrNull?.endpoint,
            selection = selection,
            options = options
        )
    }

    private suspend fun buildPatchSelectionExportFile(): PatchSelectionExportFile {
        val bundles = patchBundleRepository.sources.first()
        val exports = bundles.mapNotNull { bundle ->
            buildPatchSelectionBundleExport(bundle).takeIf { export ->
                export.selection.isNotEmpty() || !export.options.isNullOrEmpty()
            }
        }

        return PatchSelectionExportFile(bundles = exports)
    }

    private fun decodeSelectionBundleImport(
        rawJson: String,
        source: PatchBundleSource
    ): DecodedPatchSelectionBundleImport {
        return runCatching {
            DecodedPatchSelectionBundleImport(
                export = tolerantJson.decodeFromString<PatchSelectionBundleExport>(rawJson),
                isLegacySelectionOnly = false
            )
        }.getOrElse { bundleDecodeError ->
            val selection = runCatching {
                tolerantJson.decodeFromString<SerializedSelection>(rawJson)
            }.getOrElse {
                throw bundleDecodeError
            }

            DecodedPatchSelectionBundleImport(
                export = PatchSelectionBundleExport(
                    bundleUid = source.uid,
                    name = source.name,
                    displayName = source.displayName,
                    source = source.asRemoteOrNull?.endpoint,
                    selection = selection,
                    options = null
                ),
                isLegacySelectionOnly = true
            )
        }
    }

    private fun validateSelectionBundleImportTarget(
        decodedImport: DecodedPatchSelectionBundleImport,
        source: PatchBundleSource
    ) {
        if (decodedImport.isLegacySelectionOnly) return
        if (decodedImport.export.matchesSelectedBundle(source)) return

        throw IllegalArgumentException("Backup file belongs to a different patch bundle")
    }

    private fun PatchSelectionBundleExport.matchesSelectedBundle(
        source: PatchBundleSource
    ): Boolean {
        val exportEndpoint = this.source?.let(::bundleEndpointIdentity)?.takeIf(String::isNotBlank)
        val selectedEndpoint = source.asRemoteOrNull?.endpoint?.let(::bundleEndpointIdentity)?.takeIf(String::isNotBlank)
        if (exportEndpoint != null || selectedEndpoint != null) {
            return exportEndpoint != null && selectedEndpoint != null && legacyBundleEndpointsMatch(this.source.orEmpty(), selectedEndpoint)
        }

        val exportNames = setOfNotNull(name, displayName)
            .map { it.normalizedBundleIdentifier() }
            .filter(String::isNotBlank)
            .toSet()
        val selectedNames = buildSet {
            add(source.name.normalizedBundleIdentifier())
            add(source.displayTitle.normalizedBundleIdentifier())
            source.patchBundle?.manifestAttributes?.name
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { add(it.normalizedBundleIdentifier()) }
        }

        return exportNames.isNotEmpty() && exportNames.any(selectedNames::contains)
    }

    private fun String.normalizedBundleIdentifier() = trim().lowercase()

    fun selectBundle(bundle: PatchBundleSource) {
        selectedBundle = bundle
    }

    fun clearSelectionAction() {
        selectionAction = null
        selectedBundle = null
        clearPreparedExport()
    }

    fun importSelectionForBundle() = clearSelectionAction().also {
        selectionAction = SelectionAction.ImportBundle
    }

    fun importSelectionAllBundles() = clearSelectionAction().also {
        selectionAction = SelectionAction.ImportAllBundles
    }

    fun exportSelectionForBundle() = viewModelScope.launch {
        clearSelectionAction()
        uiSafe(app, R.string.export_patch_selection_fail, "Failed to prepare bundle selection export") {
            if (patchBundleRepository.sources.first().isEmpty()) {
                app.toast(app.getString(R.string.export_patch_bundles_empty))
                return@uiSafe
            }
            selectionAction = SelectionAction.ExportBundle
        }
    }

    fun exportSelectionAllBundles() = clearSelectionAction().also {
        selectionAction = SelectionAction.ExportAllBundles
    }

    private fun buildPatchBundleImportMessage(
        imported: Int,
        updated: Int,
        skipped: Int
    ) = buildImportResultMessage(
        imported = imported,
        updated = updated,
        skipped = skipped,
        importedQuantity = R.plurals.import_patch_bundles_success_quantity,
        importedPartialQuantity = R.plurals.import_patch_bundles_partial_quantity,
        updatedQuantity = R.plurals.import_patch_bundles_updated_quantity,
        updatedPartialQuantity = R.plurals.import_patch_bundles_updated_partial_quantity,
        skippedQuantity = R.plurals.import_patch_bundles_skipped_quantity,
        none = R.string.import_patch_bundles_none
    )

    private fun buildPatchProfileImportMessage(
        imported: Int,
        updated: Int,
        skipped: Int
    ) = buildImportResultMessage(
        imported = imported,
        updated = updated,
        skipped = skipped,
        importedQuantity = R.plurals.import_patch_profiles_success_quantity,
        importedPartialQuantity = R.plurals.import_patch_profiles_partial_quantity,
        updatedQuantity = R.plurals.import_patch_profiles_updated_quantity,
        updatedPartialQuantity = R.plurals.import_patch_profiles_updated_partial_quantity,
        skippedQuantity = R.plurals.import_patch_profiles_skipped_quantity,
        none = R.string.import_patch_profiles_none
    )

    private fun buildImportResultMessage(
        imported: Int,
        updated: Int,
        skipped: Int,
        importedQuantity: Int,
        importedPartialQuantity: Int,
        updatedQuantity: Int,
        updatedPartialQuantity: Int,
        skippedQuantity: Int,
        none: Int
    ): String {
        val resources = app.resources
        val importedText = {
            resources.getQuantityString(importedQuantity, imported, imported)
        }
        val updatedText = {
            resources.getQuantityString(updatedQuantity, updated, updated)
        }
        val skippedText = {
            app.getString(R.string.import_result_with_skipped, skipped)
        }
        return when {
            imported > 0 && updated > 0 && skipped > 0 ->
                "${importedText()}, ${updatedText().replaceFirstChar { it.lowercase() }}, ${skippedText()}"
            imported > 0 && updated > 0 ->
                "${importedText()}, ${updatedText().replaceFirstChar { it.lowercase() }}"
            imported > 0 && skipped > 0 -> resources.getQuantityString(
                importedPartialQuantity,
                imported,
                imported,
                skipped
            )
            imported > 0 -> importedText()
            updated > 0 && skipped > 0 -> resources.getQuantityString(
                updatedPartialQuantity,
                updated,
                updated,
                skipped
            )
            updated > 0 -> updatedText()
            skipped > 0 -> resources.getQuantityString(skippedQuantity, skipped, skipped)
            else -> app.getString(none)
        }
    }

    fun importPatchBundles(
        source: Uri,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = viewModelScope.launch {
        withContext(NonCancellable) {
            uiSafe(app, R.string.import_patch_bundles_fail, "Failed to import patch bundles") {
                val exportFile = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)!!.use {
                        tolerantJson.decodeFromStream<PatchBundleExportFile>(it)
                    }
                }
                importPatchBundleExportFile(exportFile, onToast)
            }
        }
    }

    private suspend fun importPatchBundleExportFile(
        exportFile: PatchBundleExportFile,
        onToast: (String) -> Unit
    ) {
        // Only an explicit legacy bundle entry is imported; settings never add sources.
        val importedBundles = exportFile.bundles.mapNotNull { it.asUserManagedBundle() }
        coroutineScope {
            val importActive = AtomicBoolean(true)
            val progressToast = withContext(Dispatchers.Main) {
                Toast.makeText(
                    app,
                    app.getString(R.string.import_patch_bundles_in_progress),
                    Toast.LENGTH_SHORT
                )
            }

            withContext(Dispatchers.Main) { progressToast.show() }

            val toastRepeater = launch(Dispatchers.Main) {
                try {
                    while (isActive) {
                        delay(1_750)
                        progressToast.show()
                    }
                } catch (_: CancellationException) {
                    // Ignore cancellation.
                }
            }


            val summary = try {
                withContext(Dispatchers.Default) {
                    val initialSources = patchBundleRepository.sources.first()
                        .mapNotNull { it.asRemoteOrNull }
                    val endpointToSource =
                        initialSources.associateBy { it.endpoint }.toMutableMap()
                    val importedEndpoints = importedBundles
                        .map { it.endpoint.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()

                    var createdCount = 0
                    var updatedCount = 0
                    var skippedCount = 0
                    val pendingEnabledUpdates = LinkedHashMap<Int, Boolean>()
                    val pendingReleaseSettings =
                        LinkedHashMap<Int, RepositoryBundleSettings>()
                    val total = importedBundles.size.coerceAtLeast(1)
                    var processed = 0

                    for (snapshot in importedBundles) {
                        val endpoint = snapshot.endpoint.trim()
                        val snapshotEnabled = snapshot.enabled
                        val displayName = snapshot.displayName?.trim().takeUnless { it.isNullOrBlank() }
                        val snapshotName = snapshot.name.trim().takeUnless { it.isBlank() }
                        val bundleLabel = bundleImportLabel(
                            endpoint,
                            (displayName ?: snapshotName)?.takeUnless {
                                it == app.getString(R.string.patches_name_fallback)
                            }
                        )

                        fun setImportProgress(
                            phase: PatchBundleRepository.BundleImportPhase,
                            bytesRead: Long = 0L,
                            bytesTotal: Long? = null,
                        ) {
                            if (!importActive.get()) return
                            patchBundleRepository.setBundleImportProgress(
                                PatchBundleRepository.ImportProgress(
                                    processed = processed,
                                    total = total,
                                    currentBundleName = bundleLabel.takeIf { it.isNotBlank() },
                                    phase = phase,
                                    bytesRead = bytesRead,
                                    bytesTotal = bytesTotal,
                                )
                            )
                        }

                        fun finishImportItem() {
                            if (!importActive.get()) return
                            processed += 1
                            patchBundleRepository.setBundleImportProgress(
                                PatchBundleRepository.ImportProgress(processed, total)
                            )
                        }

                        setImportProgress(PatchBundleRepository.BundleImportPhase.Processing)
                        if (endpoint.isBlank()) {
                            skippedCount += 1
                            finishImportItem()
                            continue
                        }

                        val targetDisplayName =
                            snapshot.displayName?.takeUnless { it.isBlank() }
                        val current = endpointToSource[endpoint]
                        if (current != null) {
                            var changed = false
                            var effectiveCurrent = current
                            val normalizedDisplayName =
                                current.displayName?.takeUnless { it.isBlank() }
                            if (normalizedDisplayName != targetDisplayName) {
                                val result =
                                    patchBundleRepository.setDisplayName(current.uid, targetDisplayName)
                                if (result == PatchBundleRepository.DisplayNameUpdateResult.SUCCESS) {
                                    changed = true
                                }
                            }
                            if (current.autoUpdate != snapshot.autoUpdate) {
                                with(patchBundleRepository) {
                                    current.setAutoUpdate(snapshot.autoUpdate)
                                }
                                changed = true
                            }
                            if (current.searchUpdate != snapshot.searchUpdate) {
                                with(patchBundleRepository) {
                                    current.setSearchUpdate(snapshot.searchUpdate)
                                }
                                changed = true
                            }
                            val repositoryBundle = current as? JsonPatchBundle
                            if (repositoryBundle != null) {
                                val previousSettings = repositoryBundle.repositorySettings
                                val restoredSettings = previousSettings.withImportedSelection(
                                    snapshot.usePrereleases, snapshot.useLatest,
                                    snapshot.useSpecificVersion, snapshot.selectedVersion
                                )
                                if (restoredSettings != previousSettings) {
                                    pendingReleaseSettings[current.uid] = previousSettings
                                    effectiveCurrent = with(patchBundleRepository) {
                                        repositoryBundle.restoreRepositorySettings(restoredSettings)
                                    }
                                    changed = true
                                }
                            }
                            if (current.enabled != snapshotEnabled) {
                                pendingEnabledUpdates[current.uid] = snapshotEnabled
                                changed = true
                            }
                            val needsCreatedAtUpdate = snapshot.createdAt != null && snapshot.createdAt != current.createdAt
                            val needsUpdatedAtUpdate = snapshot.updatedAt != null && snapshot.updatedAt != current.updatedAt
                            if (needsCreatedAtUpdate || needsUpdatedAtUpdate) {
                                patchBundleRepository.updateTimestamps(
                                    current,
                                    snapshot.createdAt,
                                    snapshot.updatedAt
                                )
                                changed = true
                            }
                            if (snapshot.changelogHistory.isNotEmpty()) {
                                val existingHistory =
                                    patchBundleRepository.getChangelogHistory(effectiveCurrent)
                                if (existingHistory != snapshot.changelogHistory) {
                                    patchBundleRepository.setChangelogHistory(
                                        effectiveCurrent,
                                        snapshot.changelogHistory
                                    )
                                    changed = true
                                }
                            }
                            if (changed) {
                                updatedCount += 1
                            } else {
                                skippedCount += 1
                            }
                            finishImportItem()
                            continue
                        }

                        try {
                            setImportProgress(PatchBundleRepository.BundleImportPhase.Downloading)
                            patchBundleRepository.createRemote(
                                endpoint,
                                snapshot.searchUpdate,
                                snapshot.autoUpdate,
                                usePrereleases = snapshot.usePrereleases == true,
                                useLatest = snapshot.useLatest == true,
                                useSpecificVersion = snapshot.useSpecificVersion == true,
                                selectedVersion = snapshot.selectedVersion,
                                createdAt = snapshot.createdAt,
                                updatedAt = snapshot.updatedAt,
                                importLabel = bundleLabel,
                                onProgress = { bytesRead, bytesTotal ->
                                    setImportProgress(
                                        phase = PatchBundleRepository.BundleImportPhase.Downloading,
                                        bytesRead = bytesRead,
                                        bytesTotal = bytesTotal,
                                    )
                                },
                            )
                        } catch (error: Exception) {
                            Log.e(tag, "Failed to import patch bundle $endpoint", error)
                            skippedCount += 1
                            finishImportItem()
                            continue
                        }

                        setImportProgress(PatchBundleRepository.BundleImportPhase.Finalizing)
                        val created = withTimeoutOrNull(15_000) {
                            patchBundleRepository.sources
                                .map { sources -> sources.mapNotNull { it.asRemoteOrNull } }
                                .first { sources -> sources.any { it.endpoint == endpoint } }
                                .first { it.endpoint == endpoint }
                        }
                        if (created == null) {
                            skippedCount += 1
                            finishImportItem()
                            continue
                        }

                        createdCount += 1
                        endpointToSource[endpoint] = created

                        if (created.displayName != targetDisplayName) {
                            patchBundleRepository.setDisplayName(created.uid, targetDisplayName)
                        }
                        if (created.autoUpdate != snapshot.autoUpdate) {
                            with(patchBundleRepository) {
                                created.setAutoUpdate(snapshot.autoUpdate)
                            }
                        }
                        if (created.enabled != snapshotEnabled) {
                            pendingEnabledUpdates[created.uid] = snapshotEnabled
                        }
                        if (snapshot.changelogHistory.isNotEmpty()) {
                            patchBundleRepository.setChangelogHistory(
                                created.uid,
                                snapshot.changelogHistory
                            )
                        }

                        finishImportItem()
                    }

                    if (pendingEnabledUpdates.isNotEmpty()) {
                        patchBundleRepository.setEnabledStates(pendingEnabledUpdates)
                    }

                    val orderedSnapshots = importedBundles
                        .mapIndexed { index, snapshot -> snapshot to index }
                        .sortedWith(
                            compareBy<Pair<PatchBundleSnapshot, Int>> { pair ->
                                pair.first.position ?: Int.MAX_VALUE
                            }.thenBy { pair -> pair.second }
                        )

                    if (orderedSnapshots.isNotEmpty()) {
                        val latestSources = patchBundleRepository.sources.first()
                        val endpointToUid = latestSources
                            .mapNotNull { source ->
                                source.asRemoteOrNull?.let { remote -> remote.endpoint to remote.uid }
                            }
                            .toMap()
                        val desiredOrder = orderedSnapshots.mapNotNull { (snapshot, _) ->
                            endpointToUid[snapshot.endpoint.trim()]
                        }
                        if (desiredOrder.isNotEmpty()) {
                            patchBundleRepository.reorderBundles(desiredOrder)
                        }
                    }

                    val changedReleaseUids = pendingReleaseSettings.keys
                    val downloadableReleaseUids = patchBundleRepository.sources.first()
                        .filterIsInstance<JsonPatchBundle>()
                        .filter { it.uid in changedReleaseUids &&
                            (!it.useSpecificVersion || it.selectedVersion != null) }
                        .map { it.uid }.toSet()
                    if (downloadableReleaseUids.isNotEmpty()) {
                        val updatedChannels = mutableSetOf<Int>()
                        try {
                            val updated = patchBundleRepository.updateNow(
                                force = true,
                                onBundleUpdated = { bundle, _, _ -> updatedChannels += bundle.uid },
                                predicate = { it.uid in downloadableReleaseUids }
                            )
                            check(updated && updatedChannels.containsAll(downloadableReleaseUids)) {
                                "Could not download the imported patch bundle release channels"
                            }
                        } finally {
                            // Restore all release preferences together so a failed import can be retried.
                            val sources = patchBundleRepository.sources.first().associateBy { it.uid }
                            downloadableReleaseUids.forEach { uid ->
                                if (uid in updatedChannels) return@forEach
                                val source = sources[uid] as? JsonPatchBundle ?: return@forEach
                                val previous = pendingReleaseSettings[uid] ?: return@forEach
                                with(patchBundleRepository) {
                                    source.restoreRepositorySettings(previous)
                                }
                            }
                        }
                    }

                    val missingRemotes = patchBundleRepository.sources.first()
                        .mapNotNull { it.asRemoteOrNull }
                        .filter { remote ->
                            remote.endpoint in importedEndpoints &&
                                remote.state is PatchBundleSource.State.Missing
                        }
                    if (missingRemotes.isNotEmpty()) {
                        patchBundleRepository.update(*missingRemotes.toTypedArray())
                    }

                    PatchBundleImportSummary(createdCount, updatedCount, skippedCount)
                }
            } finally {
                toastRepeater.cancel()
                withContext(Dispatchers.Main) { progressToast.cancel() }
                importActive.set(false)
                patchBundleRepository.setBundleImportProgress(null)
            }

            val totalCreated = summary.created
            val totalUpdated = summary.updated

            onToast(
                buildPatchBundleImportMessage(
                    imported = totalCreated,
                    updated = totalUpdated,
                    skipped = summary.skipped
                )
            )
        }
    }

    private suspend fun buildPatchBundleExportResult(): PatchBundleExportResult {
        val sources = patchBundleRepository.sources.first()
        val localSources = sources.filter { it.asRemoteOrNull == null }
        val remoteSources = sources.mapNotNull { it.asRemoteOrNull }
        val exportedCount = remoteSources.size
        val positionLookup = sources.withIndex().associate { it.value.uid to it.index }

        val bundles = buildList {
            remoteSources.mapTo(this) {
                val changelogHistory = patchBundleRepository.getChangelogHistory(it)
                PatchBundleSnapshot(
                    endpoint = it.endpoint,
                    name = it.name,
                    displayName = it.displayName,
                    autoUpdate = it.autoUpdate,
                    searchUpdate = it.searchUpdate,
                    usePrereleases = (it as? JsonPatchBundle)?.usePrereleases ?: false,
                    useLatest = (it as? JsonPatchBundle)?.useLatest ?: false,
                    useSpecificVersion = (it as? JsonPatchBundle)?.useSpecificVersion,
                    selectedVersion = (it as? JsonPatchBundle)?.selectedVersion,
                    enabled = it.enabled,
                    position = positionLookup[it.uid],
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    changelogHistory = changelogHistory
                )
            }

        }

        return PatchBundleExportResult(
            exportFile = PatchBundleExportFile(bundles),
            exportedCount = exportedCount,
            hasLocalSources = localSources.isNotEmpty()
        )
    }

    fun exportPatchBundles(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_bundles_fail, "Failed to export patch bundles") {
            val result = patchBundlesForExport() ?: return@uiSafe

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(result.exportFile, it)
                }
            }

            val message = if (result.hasLocalSources) {
                R.plurals.export_patch_bundles_partial_quantity
            } else {
                R.plurals.export_patch_bundles_success_quantity
            }

            app.toast(app.resources.getQuantityString(message, result.exportedCount, result.exportedCount))
        }
    }

    fun exportPatchBundles(target: Path) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_bundles_fail, "Failed to export patch bundles") {
            val result = patchBundlesForExport() ?: return@uiSafe

            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use {
                    Json.Default.encodeToStream(result.exportFile, it)
                }
            }

            val message = if (result.hasLocalSources) {
                R.plurals.export_patch_bundles_partial_quantity
            } else {
                R.plurals.export_patch_bundles_success_quantity
            }

            app.toast(app.resources.getQuantityString(message, result.exportedCount, result.exportedCount))
        }
    }

    fun importPatchBundles(
        source: Path,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = importPatchBundles(Uri.fromFile(source.toFile()), onToast)

    fun importPatchProfiles(
        source: Uri,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = viewModelScope.launch {
        withContext(NonCancellable) {
            uiSafe(app, R.string.import_patch_profiles_fail, "Failed to import patch profiles") {
                val exportFile = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)!!.use {
                        tolerantJson.decodeFromStream<PatchProfileExportFile>(it)
                    }
                }
                importPatchProfileExportFile(exportFile, onToast)
            }
        }
    }

    private suspend fun importPatchProfileExportFile(
        exportFile: PatchProfileExportFile,
        onToast: (String) -> Unit
    ) {
        val entries = exportFile.profiles.filter { it.name.isNotBlank() && it.packageName.isNotBlank() }
        if (entries.isEmpty()) {
            onToast(app.getString(R.string.import_patch_profiles_none))
            return
        }

        val sourcesSnapshot = patchBundleRepository.sources.first()
        val signatureSnapshot = patchBundleRepository.allBundlesInfoFlow.first()
            .mapValues { (_, info) -> info.patches.map { it.name.trim().lowercase() }.toSet() }
        val remappedEntries = entries.map { entry ->
            val remappedPayload = entry.payload.remapLocalBundles(sourcesSnapshot, signatureSnapshot)
            if (remappedPayload === entry.payload) entry else entry.copy(payload = remappedPayload)
        }

        val result = patchProfileRepository.importProfiles(remappedEntries)
        onToast(
            buildPatchProfileImportMessage(
                imported = result.imported,
                updated = result.updated,
                skipped = result.skipped
            )
        )
    }

    fun importPatchProfiles(
        source: Path,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = importPatchProfiles(Uri.fromFile(source.toFile()), onToast)

    fun exportPatchProfiles(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_profiles_fail, "Failed to export patch profiles") {
            val profiles = profilesForExport() ?: return@uiSafe

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use {
                    Json.Default.encodeToStream(PatchProfileExportFile(profiles), it)
                }
            }

            app.toast(
                app.resources.getQuantityString(
                    R.plurals.export_patch_profiles_success_quantity,
                    profiles.size,
                    profiles.size
                )
            )
        }
    }

    fun exportPatchProfiles(target: Path) = viewModelScope.launch {
        uiSafe(app, R.string.export_patch_profiles_fail, "Failed to export patch profiles") {
            val profiles = profilesForExport() ?: return@uiSafe

            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use {
                    Json.Default.encodeToStream(PatchProfileExportFile(profiles), it)
                }
            }

            app.toast(
                app.resources.getQuantityString(
                    R.plurals.export_patch_profiles_success_quantity,
                    profiles.size,
                    profiles.size
                )
            )
        }
    }

    fun importManagerSettings(
        source: Uri,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = viewModelScope.launch {
        if (settingsImportPending || !settingsImportMutex.tryLock()) return@launch
        isImportingManagerSettings = true
        try {
            withContext(NonCancellable) {
                uiSafe(app, R.string.import_manager_settings_fail, "Failed to import manager settings") {
                    val exportFile = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(source)!!.use {
                            tolerantJson.decodeFromStream<ManagerSettingsExportFile>(it)
                        }
                    }
                    require(exportFile.version == 1) { "Unsupported manager settings backup version" }
                    val previousSettings = preferencesManager.exportSettings()
                    applyImportedManagerSettings(exportFile.settings, previousSettings)
                    try {
                        refreshImportedRepositories(exportFile.settings, previousSettings)
                    } finally {
                        // Settings are already saved even if a later import step fails.
                        finishManagerSettingsImport(previousSettings)
                    }
                    onToast(app.getString(R.string.import_manager_settings_success))
                }
            }
        } finally {
            isImportingManagerSettings = false
            settingsImportMutex.unlock()
        }
    }

    private suspend fun applyImportedManagerSettings(
        settings: PreferencesManager.SettingsSnapshot,
        previousSettings: PreferencesManager.SettingsSnapshot
    ) {
        val autoPatchEnabled = settings.autoPatchEnabled ?: previousSettings.autoPatchEnabled ?: false
        val deferred = if (autoPatchEnabled && !app.hasNotificationPermission()) {
            PreferencesManager.SettingsSnapshot(
                autoPatchEnabled = true,
                autoPatchInstallWithShizuku = settings.autoPatchInstallWithShizuku
                    ?: previousSettings.autoPatchInstallWithShizuku ?: false,
                autoPatchUninstallOnConflictWithShizuku = settings.autoPatchUninstallOnConflictWithShizuku
                    ?: previousSettings.autoPatchUninstallOnConflictWithShizuku ?: false
            )
        } else null
        // Activity resume checks disable automatic patching without notification permission.
        // Save the intended Shizuku settings, but enable automatic patching only after permission.
        preferencesManager.importSettings(
            deferred?.let {
                settings.copy(
                    autoPatchEnabled = false,
                    autoPatchInstallWithShizuku = it.autoPatchInstallWithShizuku,
                    autoPatchUninstallOnConflictWithShizuku = it.autoPatchUninstallOnConflictWithShizuku
                )
            } ?: settings
        )
        deferredImportedAutoPatchSettings = deferred
    }

    private suspend fun finishManagerSettingsImport(
        previousSettings: PreferencesManager.SettingsSnapshot
    ) {
        val permissionRequest = buildImportedPermissionRequest(previousSettings)
        val notificationPermissionPending =
            permissionRequest?.needsNotificationPermission == true
        val autoPatchPermissionPending = importedPermissionsDeferAutoPatchWork(
            needsNotificationPermission = notificationPermissionPending,
            needsShizukuPermission = permissionRequest?.needsShizukuPermission == true
        )
        if (notificationPermissionPending) {
            suspendImportedPeriodicWork()
        } else {
            syncImportedPeriodicWork()
        }
        if (autoPatchPermissionPending) {
            AutoPatchWorker.cancel(app)
        } else {
            syncImportedAutoPatchWork()
        }
        restartAfterSettingsImport = true
        importedPermissionRequest = permissionRequest
        completeImportedSettingsRestart()
    }

    fun importManagerSettings(
        source: Path,
        onToast: (String) -> Unit = { app.toast(it) }
    ) = importManagerSettings(Uri.fromFile(source.toFile()), onToast)

    fun exportManagerSettings(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_manager_settings_fail, "Failed to export manager settings") {
            val snapshot = preferencesManager.exportSettings()

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    Json.Default.encodeToStream(
                        ManagerSettingsExportFile(settings = snapshot),
                        output
                    )
                }
            }

            app.toast(app.getString(R.string.export_manager_settings_success))
        }
    }

    fun exportManagerSettings(target: Path) = viewModelScope.launch {
        uiSafe(app, R.string.export_manager_settings_fail, "Failed to export manager settings") {
            val snapshot = preferencesManager.exportSettings()

            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use { output ->
                    Json.Default.encodeToStream(
                        ManagerSettingsExportFile(settings = snapshot),
                        output
                    )
                }
            }

            app.toast(app.getString(R.string.export_manager_settings_success))
        }
    }

    fun exportEverything(target: Path) = viewModelScope.launch {
        uiSafe(app, R.string.export_everything_fail, "Failed to export all data") {
            val exportFile = buildEverythingExportFile()

            withContext(Dispatchers.IO) {
                target.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(target).use { output ->
                    Json.Default.encodeToStream(exportFile, output)
                }
            }

            app.toast(app.getString(R.string.export_everything_success))
        }
    }

    private suspend fun buildEverythingExportFile(): EverythingExportFile {
        val bundleResult = buildPatchBundleExportResult()
        val profileExport = PatchProfileExportFile(patchProfileRepository.exportProfiles())
        val settingsExport = ManagerSettingsExportFile(settings = preferencesManager.exportSettings())
        val selectionExport = buildPatchSelectionExportFile()
        return EverythingExportFile(
            patchBundles = bundleResult.exportFile,
            patchProfiles = profileExport,
            managerSettings = settingsExport,
            patchSelection = selectionExport
        )
    }

    private suspend fun rollbackImportedNotificationSettings(
        rollback: ImportedNotificationRollback?
    ) {
        if (rollback == null) return

        preferencesManager.importSettings(
            PreferencesManager.SettingsSnapshot(
                announcementPushNotificationInterval = rollback.announcementInterval,
                autoClearCacheInterval = rollback.autoClearCacheInterval,
                searchForUpdatesBackgroundInterval = rollback.bundleInterval,
                searchForManagerUpdatesBackgroundInterval = rollback.managerInterval,
                bundleUpdateDeliveryMode = rollback.deliveryMode,
                autoPatchEnabled = rollback.autoPatchEnabled,
                autoPatchInstallWithShizuku = rollback.autoPatchInstallWithShizuku,
                autoPatchUninstallOnConflictWithShizuku = rollback.autoPatchUninstallOnConflictWithShizuku,
                autoPatchInterval = rollback.autoPatchInterval,
                autoPatchRequiresCharging = rollback.autoPatchRequiresCharging
            )
        )
    }

    private suspend fun syncImportedPeriodicWork() {
        workerRepository.scheduleBundleUpdateNotificationWork(
            preferencesManager.searchForUpdatesBackgroundInterval.get()
        )
        workerRepository.scheduleManagerUpdateNotificationWork(
            preferencesManager.searchForManagerUpdatesBackgroundInterval.get()
        )
        workerRepository.scheduleAnnouncementNotificationWork(
            if (preferencesManager.announcementSystemEnabled.get()) {
                preferencesManager.announcementPushNotificationInterval.get()
            } else {
                SearchForUpdatesBackgroundInterval.NEVER
            }
        )
        workerRepository.scheduleAutoClearCacheWork(
            preferencesManager.autoClearCacheInterval.get()
        )
    }

    private fun suspendImportedPeriodicWork() {
        workerRepository.scheduleBundleUpdateNotificationWork(
            SearchForUpdatesBackgroundInterval.NEVER
        )
        workerRepository.scheduleManagerUpdateNotificationWork(
            SearchForUpdatesBackgroundInterval.NEVER
        )
        workerRepository.scheduleAnnouncementNotificationWork(
            SearchForUpdatesBackgroundInterval.NEVER
        )
        workerRepository.scheduleAutoClearCacheWork(AutoClearCacheInterval.NEVER)
    }

    private suspend fun syncImportedAutoPatchWork() {
        if (preferencesManager.autoPatchEnabled.get()) {
            AutoPatchWorker.schedule(
                app,
                preferencesManager,
                preferencesManager.autoPatchInterval.get(),
                preferencesManager.autoPatchRequiresCharging.get()
            )
        } else {
            AutoPatchWorker.cancel(app)
        }
    }

    private suspend fun refreshImportedRepositories(
        settings: PreferencesManager.SettingsSnapshot,
        previousSettings: PreferencesManager.SettingsSnapshot
    ) {
        if (settings.acknowledgedDownloaderPlugins != null ||
            settings.downloaderPluginSourcesJson != null ||
            settings.trustedApkDownloadHelpersJson != null
        ) {
            downloaderPluginRepository.reload()
            try {
                downloaderPluginRepository.updateCheck()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(tag, "Failed to update imported downloader plugins", error)
            }
        }
        if (settings.acknowledgedPatcherRuntimePlugins != null ||
            settings.trustedPatcherRuntimePluginsJson != null ||
            settings.patcherRuntimePluginSourcesJson != null
        ) {
            patcherRuntimePluginRepository.reload()
            try {
                patcherRuntimePluginRepository.updateCheck()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(tag, "Failed to update imported patcher runtime plugins", error)
            }
        }
        patchBundleRepository.reload()
    }


    private suspend fun buildImportedPermissionRequest(
        previousSettings: PreferencesManager.SettingsSnapshot
    ): ImportedPermissionRequest? {
        val needsStoragePermission = preferencesManager.useCustomFilePicker.get()
        val bundleInterval = preferencesManager.searchForUpdatesBackgroundInterval.get()
        val managerInterval = preferencesManager.searchForManagerUpdatesBackgroundInterval.get()
        val announcementInterval = if (preferencesManager.announcementSystemEnabled.get()) {
            preferencesManager.announcementPushNotificationInterval.get()
        } else {
            SearchForUpdatesBackgroundInterval.NEVER
        }
        val autoClearCacheInterval = preferencesManager.autoClearCacheInterval.get()
        val deliveryMode = preferencesManager.bundleUpdateDeliveryMode.get()
        val deferred = deferredImportedAutoPatchSettings
        val autoPatchEnabled = deferred?.autoPatchEnabled ?: preferencesManager.autoPatchEnabled.get()
        val shizukuAutoInstallEnabled =
            preferencesManager.autoInstallWithShizuku.get()
        val shizukuAutoPatchInstallEnabled = deferred?.autoPatchInstallWithShizuku
            ?: preferencesManager.autoPatchInstallWithShizuku.get()
        val shizukuAutoPatchConflictUninstallEnabled = deferred?.autoPatchUninstallOnConflictWithShizuku
            ?: preferencesManager.autoPatchUninstallOnConflictWithShizuku.get()
        val needsShizukuPermission =
            (shizukuAutoInstallEnabled || shizukuAutoPatchInstallEnabled ||
                preferencesManager.autoUninstallWithShizuku.get() ||
                shizukuAutoPatchConflictUninstallEnabled) &&
            !installerManager.shizukuStatus(
                InstallerManager.InstallTarget.PATCHER
            ).permissionGranted

        val backgroundNotificationsEnabled = importedBackgroundWorkNeedsNotifications(
            bundleInterval = bundleInterval,
            managerInterval = managerInterval,
            announcementInterval = announcementInterval,
            autoClearCacheInterval = autoClearCacheInterval,
            deliveryMode = deliveryMode,
            autoPatchEnabled = autoPatchEnabled
        )

        return ImportedPermissionRequest(
            needsStoragePermission = needsStoragePermission,
            needsNotificationPermission = backgroundNotificationsEnabled,
            needsShizukuPermission = needsShizukuPermission,
            notificationRollback = if (backgroundNotificationsEnabled) {
                ImportedNotificationRollback(
                    bundleInterval = previousSettings.searchForUpdatesBackgroundInterval
                        ?: SearchForUpdatesBackgroundInterval.NEVER,
                    managerInterval = previousSettings.searchForManagerUpdatesBackgroundInterval
                        ?: SearchForUpdatesBackgroundInterval.NEVER,
                    announcementInterval = previousSettings.announcementPushNotificationInterval
                        ?: SearchForUpdatesBackgroundInterval.NEVER,
                    autoClearCacheInterval = previousSettings.autoClearCacheInterval
                        ?: AutoClearCacheInterval.NEVER,
                    deliveryMode = previousSettings.bundleUpdateDeliveryMode
                        ?: BundleUpdateDeliveryMode.AUTO,
                    autoPatchEnabled = previousSettings.autoPatchEnabled ?: false,
                    autoPatchInstallWithShizuku = previousSettings.autoPatchInstallWithShizuku ?: false,
                    autoPatchUninstallOnConflictWithShizuku =
                        previousSettings.autoPatchUninstallOnConflictWithShizuku ?: false,
                    autoPatchInterval = previousSettings.autoPatchInterval
                        ?: SearchForUpdatesBackgroundInterval.DAY,
                    autoPatchRequiresCharging =
                        previousSettings.autoPatchRequiresCharging ?: true
                )
            } else {
                null
            },
            shizukuAutoInstallRollback = if (needsShizukuPermission) {
                previousSettings.autoInstallWithShizuku ?: false
            } else {
                null
            },
            shizukuAutoUninstallRollback = if (needsShizukuPermission) {
                previousSettings.autoUninstallWithShizuku ?: false
            } else {
                null
            },
            shizukuAutoPatchInstallRollback = if (needsShizukuPermission) {
                previousSettings.autoPatchInstallWithShizuku ?: false
            } else {
                null
            },
            shizukuAutoPatchConflictUninstallRollback = if (needsShizukuPermission) {
                previousSettings.autoPatchUninstallOnConflictWithShizuku ?: false
            } else {
                null
            }
        ).takeUnless(ImportedPermissionRequest::isEmpty)
    }

    fun exportEverything(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.export_everything_fail, "Failed to export all data") {
            val exportFile = buildEverythingExportFile()

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    Json.Default.encodeToStream(exportFile, output)
                }
            }

            app.toast(app.getString(R.string.export_everything_success))
        }
    }

    fun importEverything(source: Path) = importEverything(Uri.fromFile(source.toFile()))

    fun importEverything(source: Uri) = viewModelScope.launch {
        if (settingsImportPending || !settingsImportMutex.tryLock()) return@launch
        isImportingManagerSettings = true
        try {
            withContext(NonCancellable) {
                uiSafe(app, R.string.import_everything_fail, "Failed to import all data") {
                    val exportFile = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(source)!!.use {
                            tolerantJson.decodeFromStream<EverythingExportFile>(it)
                        }
                    }
                    require(exportFile.version == 1) { "Unsupported backup version" }
                    require(exportFile.managerSettings.version == 1) {
                        "Unsupported manager settings backup version"
                    }
                    require(exportFile.patchSelection.version in 1..2) {
                        "Unsupported patch selection backup version"
                    }
                    val previousSettings = preferencesManager.exportSettings()
                    val settings = exportFile.managerSettings.settings
                    // Bundles must see imported API and prerelease preferences on the first pass.
                    applyImportedManagerSettings(settings, previousSettings)
                    val importToasts = mutableMapOf<EverythingImportToast, String>()
                    try {
                        refreshImportedRepositories(settings, previousSettings)
                        importPatchBundleExportFile(exportFile.patchBundles) {
                            importToasts[EverythingImportToast.PATCH_BUNDLES] = it
                        }
                        importPatchProfileExportFile(exportFile.patchProfiles) {
                            importToasts[EverythingImportToast.PATCH_PROFILES] = it
                        }
                        importPatchSelectionExportFile(exportFile.patchSelection) {
                            importToasts[EverythingImportToast.PATCH_SELECTION] = it
                        }
                    } finally {
                        // Settings are already saved even if a later import step fails.
                        finishManagerSettingsImport(previousSettings)
                    }
                    importToasts.values.forEach(app::toast)
                    app.toast(app.getString(R.string.import_manager_settings_success))
                }
            }
        } finally {
            isImportingManagerSettings = false
            settingsImportMutex.unlock()
        }
    }

    private companion object {
        val knownPasswords = arrayOf(KeystoreManager.DEFAULT_PASSWORD, KeystoreManager.LEGACY_DEFAULT_ALIAS, KeystoreManager.LEGACY_DEFAULT_PASSWORD)
        val aliases = arrayOf(KeystoreManager.DEFAULT_ALIAS, KeystoreManager.LEGACY_DEFAULT_ALIAS, "ReVanced Key")
    }
}

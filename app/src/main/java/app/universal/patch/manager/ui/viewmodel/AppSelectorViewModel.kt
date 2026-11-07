package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import androidx.documentfile.provider.DocumentFile
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.SupportedVersionInfo
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.patcher.split.SplitApkInspector
import app.universal.patch.manager.patcher.split.SplitApkPreparer
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.APK_FILE_EXTENSIONS
import app.universal.patch.manager.util.resolveSupportedApkExtension
import app.universal.patch.manager.util.resolveAppDisplayLabel
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.saveableVar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

@OptIn(SavedStateHandleSaveableApi::class)
class AppSelectorViewModel(
    private val app: Application,
    private val pm: PM,
    fs: Filesystem,
    private val patchBundleRepository: PatchBundleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var inputFile by savedStateHandle.saveableVar {
        File(fs.uiTempDir, "input.apk").also(File::delete)
    }
    private val splitWorkspace = fs.tempDir
    val appList = pm.appList

    private val storageSelectionChannel = Channel<SelectedApp.Local>(Channel.BUFFERED)
    val storageSelectionFlow = storageSelectionChannel.receiveAsFlow()
    var storageSelectionInProgress by mutableStateOf(false)
        private set

    val suggestedAppVersions = patchBundleRepository.appListSuggestedVersions.flowOn(Dispatchers.Default)
    val bundleSuggestionsByApp =
        combine(
            patchBundleRepository.appListBundleInfoFlow,
            patchBundleRepository.appListSuggestedVersionsByBundle,
            patchBundleRepository.sources
        ) { bundleInfo, bundleVersions, sources ->
            val result = mutableMapOf<String, MutableList<BundleVersionSuggestion>>()
            val displayNames = sources.associate { source ->
                source.uid to source.displayTitle
            }

            bundleInfo.forEach { (bundleUid, info) ->
                val packageSupport = mutableMapOf<String, BundleSupportAccumulator>()

                info.patches.forEach { patch ->
                    patch.compatiblePackages?.forEach { compatible ->
                        val accumulator =
                            packageSupport.getOrPut(compatible.packageName) {
                                BundleSupportAccumulator(
                                    mutableSetOf(),
                                    mutableSetOf(),
                                    mutableSetOf(),
                                    mutableMapOf(),
                                    false
                                )
                            }
                        if (info.bundleType == PatchBundleType.MORPHE || info.bundleType == PatchBundleType.RESEAM) {
                            compatible.versionCodes.orEmpty().forEach { (version, codes) ->
                                accumulator.versionCodes.getOrPut(version) { mutableSetOf() } += codes
                            }
                        }
                        val versions = compatible.versions
                        if (versions.isNullOrEmpty()) {
                            accumulator.supportsAllVersions = true
                        } else {
                            val experimentalVersions = compatible.experimentalVersions.orEmpty().toSet()
                            accumulator.versions += versions
                            accumulator.experimentalVersions += experimentalVersions
                            accumulator.stableVersions += versions.filterNot { it in experimentalVersions }
                        }
                    }
                }

                packageSupport.forEach { (packageName, support) ->
                    val recommended = bundleVersions[bundleUid]?.get(packageName)
                    if (
                        recommended == null &&
                        support.versions.isEmpty() &&
                        !support.supportsAllVersions
                    ) return@forEach

                    val recommendedExperimental = recommended != null &&
                        recommended in support.experimentalVersions &&
                        recommended !in support.stableVersions
                    val otherVersions = support.versions
                        .filterNot { recommended.equals(it, ignoreCase = true) }
                        .sorted()
                        .map { version ->
                            SupportedVersionInfo(
                                version = version,
                                experimental = version in support.experimentalVersions &&
                                    version !in support.stableVersions,
                                versionCodes = support.versionCodes[version].orEmpty()
                            )
                        }

                    val suggestions = result.getOrPut(packageName) { mutableListOf() }
                    suggestions += BundleVersionSuggestion(
                        bundleUid = bundleUid,
                        bundleName = displayNames[bundleUid]
                            ?.takeIf { it.isNotBlank() }
                            ?: info.name,
                        recommendedVersion = recommended,
                        recommendedVersionCodes = recommended?.let(support.versionCodes::get).orEmpty(),
                        recommendedVersionExperimental = recommendedExperimental,
                        otherSupportedVersions = otherVersions,
                        supportsAllVersions = support.supportsAllVersions
                    )
                }
            }

            result.mapValues { (_, values) ->
                values.sortedBy { it.bundleName.lowercase(Locale.ROOT) }
            }
        }
            .flowOn(Dispatchers.Default)

    var nonSuggestedVersionDialogSubject by mutableStateOf<SelectedApp.Local?>(null)
        private set
    var nonSuggestedVersionDialogSuggestedVersion by mutableStateOf<String?>(null)
        private set
    var nonSuggestedVersionDialogSuggestedVersionCodes by mutableStateOf<Set<Long>>(emptySet())
        private set
    var nonSuggestedVersionDialogRequiresUniversalEnabled by mutableStateOf(false)
        private set
    var universalFallbackDialogSubject by mutableStateOf<SelectedApp.Local?>(null)
        private set
    var universalFallbackDialogSuggestedVersion by mutableStateOf<String?>(null)
        private set

    fun loadLabel(app: PackageInfo?): String {
        val fallback = this.app.getString(R.string.not_installed)
        return resolveAppDisplayLabel(this.app, app, app?.packageName ?: fallback, requireMetadataLabel = true)
            ?: app?.packageName ?: fallback
    }

    fun dismissNonSuggestedVersionDialog() {
        nonSuggestedVersionDialogSubject = null
        nonSuggestedVersionDialogSuggestedVersion = null
        nonSuggestedVersionDialogSuggestedVersionCodes = emptySet()
        nonSuggestedVersionDialogRequiresUniversalEnabled = false
    }

    fun dismissUniversalFallbackDialog() {
        universalFallbackDialogSubject = null
        universalFallbackDialogSuggestedVersion = null
    }

    fun continueWithUniversalFallback() = viewModelScope.launch {
        val selectedApp = universalFallbackDialogSubject ?: return@launch
        storageSelectionInProgress = true
        dismissUniversalFallbackDialog()
        dismissNonSuggestedVersionDialog()
        runCatching {
            storageSelectionChannel.send(selectedApp)
        }.onFailure {
            storageSelectionInProgress = false
            app.toast(app.getString(R.string.failed_to_load_apk))
        }
    }

    fun consumeStorageSelectionResult() {
        storageSelectionInProgress = false
    }

    fun handleStorageResult(uri: Uri) = viewModelScope.launch {
        storageSelectionInProgress = true
        val loadResult = runCatching {
            withContext(Dispatchers.IO) {
                loadSelectedFile(uri)
            }
        }.getOrElse {
            storageSelectionInProgress = false
            app.toast(app.getString(R.string.failed_to_load_apk))
            return@launch
        }
        when (loadResult) {
            is StorageApkLoadResult.Success -> handleSelectedStorageApp(loadResult.app)
            StorageApkLoadResult.InvalidType -> {
                storageSelectionInProgress = false
                app.toast(app.getString(R.string.selected_file_not_supported_apk))
            }
            StorageApkLoadResult.Failed -> {
                storageSelectionInProgress = false
                app.toast(app.getString(R.string.failed_to_load_apk))
            }
        }
    }

    fun handleStorageFile(file: File) = viewModelScope.launch {
        storageSelectionInProgress = true
        val loadResult = runCatching {
            withContext(Dispatchers.IO) {
                loadSelectedFile(file)
            }
        }.getOrElse {
            storageSelectionInProgress = false
            app.toast(app.getString(R.string.failed_to_load_apk))
            return@launch
        }
        when (loadResult) {
            is StorageApkLoadResult.Success -> handleSelectedStorageApp(loadResult.app)
            StorageApkLoadResult.InvalidType -> {
                storageSelectionInProgress = false
                app.toast(app.getString(R.string.selected_file_not_supported_apk))
            }
            StorageApkLoadResult.Failed -> {
                storageSelectionInProgress = false
                app.toast(app.getString(R.string.failed_to_load_apk))
            }
        }
    }


    private suspend fun handleSelectedStorageApp(selectedApp: SelectedApp.Local) {
        val assessment =
            patchBundleRepository.assessVersionSelection(
                selectedApp.packageName,
                selectedApp.version,
                selectedApp.versionCode
            )
        if (assessment.isAllowed) {
            dismissUniversalFallbackDialog()
            dismissNonSuggestedVersionDialog()
            storageSelectionChannel.send(selectedApp)
            return
        }

        if (assessment.canContinueWithUniversalFallback) {
            storageSelectionInProgress = false
            universalFallbackDialogSubject = selectedApp
            universalFallbackDialogSuggestedVersion = assessment.suggestedVersion
            dismissNonSuggestedVersionDialog()
        } else {
            storageSelectionInProgress = false
            nonSuggestedVersionDialogSubject = selectedApp
            nonSuggestedVersionDialogSuggestedVersion = assessment.suggestedVersion
            nonSuggestedVersionDialogSuggestedVersionCodes = assessment.suggestedVersionCodes
            nonSuggestedVersionDialogRequiresUniversalEnabled =
                assessment.requiresUniversalPatchesEnabled
            dismissUniversalFallbackDialog()
        }
    }

    private suspend fun loadSelectedFile(uri: Uri): StorageApkLoadResult =
        app.contentResolver.openInputStream(uri)?.use { stream ->
            val extension = resolveExtension(uri)
            if (extension.isBlank()) return@use StorageApkLoadResult.InvalidType
            val destination = prepareInputFile(extension)
            destination.delete()
            Files.copy(stream, destination.toPath())

            val isSplitArchive = SplitApkPreparer.isSplitArchive(destination)
            resolvePackageInfo(destination)?.let { packageInfo ->
                SelectedApp.Local(
                    packageName = packageInfo.packageName,
                    version = packageInfo.versionName
                        ?: if (isSplitArchive) app.getString(R.string.app_version_unspecified) else "",
                    file = destination,
                    temporary = true,
                    resolved = true,
                    versionCode = pm.getVersionCode(packageInfo)
                )
            }?.let(StorageApkLoadResult::Success) ?: StorageApkLoadResult.Failed
        } ?: StorageApkLoadResult.Failed

    private suspend fun loadSelectedFile(file: File): StorageApkLoadResult {
        if (!file.exists()) return StorageApkLoadResult.Failed
        val extension = file.extension.lowercase(Locale.ROOT)
            .takeIf { it in APK_FILE_EXTENSIONS }
            ?: "apk"

        val destination = prepareInputFile(extension)
        destination.delete()
        Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val isSplitArchive = SplitApkPreparer.isSplitArchive(destination)
        return resolvePackageInfo(destination)?.let { packageInfo ->
            SelectedApp.Local(
                packageName = packageInfo.packageName,
                version = packageInfo.versionName
                    ?: if (isSplitArchive) app.getString(R.string.app_version_unspecified) else "",
                file = destination,
                temporary = true,
                resolved = true,
                    versionCode = pm.getVersionCode(packageInfo)
            )
        }?.let(StorageApkLoadResult::Success) ?: StorageApkLoadResult.Failed
    }

    private fun resolveExtension(uri: Uri): String {
        val document = DocumentFile.fromSingleUri(app, uri)
        val mime = app.contentResolver.getType(uri)
        return resolveSupportedApkExtension(document?.name, mime).orEmpty()
    }

    private fun prepareInputFile(extension: String): File {
        val sanitized = extension.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
            ?: "apk"
        val destination = File(inputFile.parentFile, "input.$sanitized")
        if (destination != inputFile) {
            inputFile.delete()
            inputFile = destination
        }
        return destination
    }

    private suspend fun resolvePackageInfo(file: File): PackageInfo? =
        if (SplitApkPreparer.isSplitArchive(file)) {
            val extracted = SplitApkInspector.extractRepresentativeApk(file, splitWorkspace)
                ?: return null
            try {
                pm.getPackageInfo(extracted.file)
            } finally {
                extracted.cleanup()
            }
        } else {
            pm.getPackageInfo(file)
        }
}

private sealed interface StorageApkLoadResult {
    data class Success(val app: SelectedApp.Local) : StorageApkLoadResult
    data object InvalidType : StorageApkLoadResult
    data object Failed : StorageApkLoadResult
}

data class BundleVersionSuggestion(
    val bundleUid: Int,
    val bundleName: String,
    val recommendedVersion: String?,
    val recommendedVersionCodes: Set<Long>,
    val recommendedVersionExperimental: Boolean,
    val otherSupportedVersions: List<SupportedVersionInfo>,
    val supportsAllVersions: Boolean
)

private data class BundleSupportAccumulator(
    val versions: MutableSet<String>,
    val stableVersions: MutableSet<String>,
    val experimentalVersions: MutableSet<String>,
    val versionCodes: MutableMap<String, MutableSet<Long>>,
    var supportsAllVersions: Boolean
)

package app.universal.patch.manager.ui.viewmodel

import app.universal.patch.manager.data.platform.SharedApkFiles
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.universal.patch.manager.domain.manager.KeystoreManager
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.batch.BatchPlanResolver
import app.universal.patch.manager.domain.repository.DownloadedAppRepository
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.repository.PatchSelectionRepository
import app.universal.patch.manager.domain.repository.SerializedSelection
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.receiver.BundleUpdateNotificationDismissReceiver
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.ui.model.navigation.SelectedApplicationInfo
import app.universal.patch.manager.ui.theme.Theme
import app.universal.patch.manager.util.PatchSelection
import app.universal.patch.manager.util.AnnouncementDeepLinkIntent
import app.universal.patch.manager.util.BatchPatchIntents
import app.universal.patch.manager.util.BundleDeepLink
import app.universal.patch.manager.util.BundleDeepLinkIntent
import app.universal.patch.manager.util.ManagerUpdateDeepLinkIntent
import app.universal.patch.manager.util.PatchBundleFileIntent
import app.universal.patch.manager.util.PatchBundleFileIntentParser
import app.universal.patch.manager.util.SplitArchiveIntent
import app.universal.patch.manager.util.SplitArchiveIntentParser
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class BatchPatchRequest(
    val packageNames: List<String>,
    val startImmediately: Boolean,
    val showExistingResult: Boolean = false,
    val scheduled: Boolean = false
)

class MainViewModel(
    private val patchBundleRepository: PatchBundleRepository,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val downloadedAppRepository: DownloadedAppRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val keystoreManager: KeystoreManager,
    private val batchPlanResolver: BatchPlanResolver,
    private val app: Application,
    val prefs: PreferencesManager,
    private val json: Json
) : ViewModel() {
    private val appSelectChannel = Channel<SelectedApplicationInfo.ViewModelParams>(Channel.BUFFERED)
    val appSelectFlow = appSelectChannel.receiveAsFlow()
    private val legacyImportActivityChannel = Channel<Intent>()
    val legacyImportActivityFlow = legacyImportActivityChannel.receiveAsFlow()
    private val bundleDeepLinkChannel = Channel<BundleDeepLink>(Channel.BUFFERED)
    val bundleDeepLinkFlow = bundleDeepLinkChannel.receiveAsFlow()
    private val managerUpdateDeepLinkChannel = Channel<Unit>(Channel.BUFFERED)
    val managerUpdateDeepLinkFlow = managerUpdateDeepLinkChannel.receiveAsFlow()
    private val announcementDeepLinkChannel =
        Channel<app.universal.patch.manager.ui.model.navigation.Announcement.Payload>(Channel.BUFFERED)
    val announcementDeepLinkFlow = announcementDeepLinkChannel.receiveAsFlow()
    private val splitArchiveIntentChannel = Channel<SplitArchiveIntent>(Channel.BUFFERED)
    val splitArchiveIntentFlow = splitArchiveIntentChannel.receiveAsFlow()
    private val patchBundleFileIntentChannel = Channel<PatchBundleFileIntent>(Channel.BUFFERED)
    val patchBundleFileIntentFlow = patchBundleFileIntentChannel.receiveAsFlow()
    private val batchPatchRequestChannel = Channel<BatchPatchRequest>(Channel.BUFFERED)
    val batchPatchRequestFlow = batchPatchRequestChannel.receiveAsFlow()
    private val dashboardRequestChannel = Channel<Unit>(Channel.BUFFERED)
    val dashboardRequestFlow = dashboardRequestChannel.receiveAsFlow()
    private val patchedAppsRequestChannel = Channel<List<String>>(Channel.BUFFERED)
    val patchedAppsRequestFlow = patchedAppsRequestChannel.receiveAsFlow()
    private var initialIntentHandled = false

    private suspend fun suggestedVersion(packageName: String): String? {
        patchBundleRepository.awaitReady()
        return patchBundleRepository.suggestedVersions.first()[packageName]
    }

    private suspend fun findDownloadedApp(app: SelectedApp): SelectedApp.Local? {
        if (app !is SelectedApp.Search) return null

        val suggestedVersion = suggestedVersion(app.packageName)
        val downloadedApp = if (suggestedVersion != null) {
            downloadedAppRepository.get(app.packageName, suggestedVersion, markUsed = true)
        } else {
            downloadedAppRepository.getLatest(app.packageName, markUsed = true)
        } ?: return null

        val file = try {
            downloadedAppRepository.getApkFileForApp(downloadedApp)
        } catch (e: Exception) {
            Log.w(tag, "Downloaded APK file not found for ${downloadedApp.packageName}", e)
            return null
        }
        return SelectedApp.Local(
            downloadedApp.packageName,
            downloadedApp.version,
            file,
            false
        )
    }

    private suspend fun findRememberedRepatchApp(
        packageName: String,
        sourceEntryKey: String?
    ): SelectedApp.Local? {
        val sourceRecord = sourceEntryKey
            ?.let { installedAppRepository.get(it) }
            ?: return null
        val sourcePath = sourceRecord.repatchSourcePath
            ?.takeIf(String::isNotBlank)
            ?: return null
        val file = SharedApkFiles.resolve(File(sourcePath)).takeIf(File::isFile) ?: return null
        return SelectedApp.Local(
            packageName = packageName,
            version = sourceRecord.version,
            file = file,
            temporary = false,
            resolved = false
        )
    }

    fun selectApp(
        app: SelectedApp,
        patches: PatchSelection? = null,
        selectionPayload: PatchProfilePayload? = null,
        persistConfiguration: Boolean = true,
        returnToDashboard: Boolean = false,
        batchQueue: Boolean = false,
        sourceEntryKey: String? = null
    ) = viewModelScope.launch {
        val resolved = findDownloadedApp(app) ?: app
        val selectionPayloadJson = selectionPayload?.let { json.encodeToString(it) }
        val rememberedUseMount = sourceEntryKey
            ?.let { installedAppRepository.get(it)?.useMount }
        appSelectChannel.send(
            SelectedApplicationInfo.ViewModelParams(
                app = resolved,
                patches = patches,
                selectionPayloadJson = selectionPayloadJson,
                persistConfiguration = persistConfiguration,
                returnToDashboard = returnToDashboard,
                batchQueue = batchQueue,
                sourceEntryKey = sourceEntryKey,
                useMount = rememberedUseMount
            )
        )
    }

    fun selectApp(app: SelectedApp) = selectApp(app, null, null, true)

    fun selectApp(
        packageName: String,
        patches: PatchSelection? = null,
        selectionPayload: PatchProfilePayload? = null,
        persistConfiguration: Boolean = true,
        returnToDashboard: Boolean = false,
        batchQueue: Boolean = false,
        sourceEntryKey: String? = null
    ) = viewModelScope.launch {
        val rememberedSourceExists = sourceEntryKey
            ?.let { installedAppRepository.get(it)?.repatchSourcePath }
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.isFile == true
        val rememberedApp = findRememberedRepatchApp(packageName, sourceEntryKey)
        if (rememberedSourceExists && rememberedApp == null) {
            app.toast(app.getString(R.string.repatch_source_missing_description))
        }
        val selectedApp = rememberedApp
            ?: SelectedApp.Search(packageName, suggestedVersion(packageName))
        selectApp(
            selectedApp,
            patches,
            selectionPayload,
            persistConfiguration,
            returnToDashboard,
            batchQueue,
            sourceEntryKey
        )
    }

    fun selectApp(packageName: String) = selectApp(packageName, null, null, true)

    fun handleInitialIntent(intent: Intent?) {
        if (initialIntentHandled) return
        initialIntentHandled = true
        handleIntent(intent)
    }

    fun handleIntent(intent: Intent?) {
        if (handleBatchIntent(intent)) return
        if (ManagerUpdateDeepLinkIntent.shouldOpenManagerUpdate(intent)) {
            managerUpdateDeepLinkChannel.trySend(Unit)
        }
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/blob/6688aa17ea35b5ab398a3c1922be13626290cbf1/app/src/main/java/app/morphe/manager/MainActivity.kt#L102-L118
        PatchBundleFileIntentParser.fromIntent(intent, app.contentResolver)?.let { patchBundleFileIntent ->
            patchBundleFileIntentChannel.trySend(patchBundleFileIntent)
            return
        }
        SplitArchiveIntentParser.fromIntent(intent, app.contentResolver)?.let { splitArchiveIntent ->
            splitArchiveIntentChannel.trySend(splitArchiveIntent)
        }
        AnnouncementDeepLinkIntent.fromIntent(intent)?.let { announcement ->
            if (prefs.announcementSystemEnabled.getBlocking()) {
                announcementDeepLinkChannel.trySend(announcement)
            }
        }
        val deepLink = BundleDeepLinkIntent.fromIntent(intent) ?: return
        BundleUpdateNotificationDismissReceiver.markDismissedMarkers(
            app,
            BundleUpdateNotificationDismissReceiver.dismissalMarkers(intent)
        )
        bundleDeepLinkChannel.trySend(deepLink)
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/795
    private fun handleBatchIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action !in setOf(
                BatchPatchIntents.ACTION_PATCH_APP,
                BatchPatchIntents.ACTION_CHECK_UPDATES,
                BatchPatchIntents.ACTION_SHOW_RESULT,
                BatchPatchIntents.ACTION_SHOW_APPS
            )
        ) return false

        val internal = BatchPatchIntents.isTrustedInternal(app, intent)
        if (!internal && !prefs.allowExternalBatchActions.getBlocking()) {
            app.toast(app.getString(R.string.batch_patch_external_disabled))
            return true
        }

        when (action) {
            BatchPatchIntents.ACTION_SHOW_APPS -> {
                patchedAppsRequestChannel.trySend(BatchPatchIntents.packageNames(intent))
            }
            BatchPatchIntents.ACTION_SHOW_RESULT -> {
                val packages = BatchPatchIntents.packageNames(intent)
                if (packages.isEmpty()) {
                    dashboardRequestChannel.trySend(Unit)
                } else {
                    batchPatchRequestChannel.trySend(
                        BatchPatchRequest(
                            packageNames = packages,
                            startImmediately = false,
                            showExistingResult = true,
                            scheduled = intent.getBooleanExtra(
                                BatchPatchIntents.EXTRA_SCHEDULED,
                                false
                            )
                        )
                    )
                }
            }

            BatchPatchIntents.ACTION_CHECK_UPDATES -> viewModelScope.launch {
                runCatching { patchBundleRepository.updateCheck() }
                    .onFailure { Log.w(tag, "Failed to refresh patch bundles", it) }
                openOutdatedBatch()
            }

            BatchPatchIntents.ACTION_PATCH_APP -> {
                val packageName = intent.getStringExtra(BatchPatchIntents.EXTRA_PACKAGE)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                if (packageName != null) {
                    selectApp(packageName)
                }
            }
        }
        return true
    }

    private suspend fun openOutdatedBatch() {
        val packages = batchPlanResolver.findOutdatedPackages()
        when {
            packages.isEmpty() -> app.toast(app.getString(R.string.batch_patch_no_updates))
            packages.size == 1 -> selectApp(packages.single())
            else -> batchPatchRequestChannel.send(BatchPatchRequest(packages, false))
        }
    }

    init {
        viewModelScope.launch {
            if (!prefs.firstLaunch.get()) return@launch
            legacyImportActivityChannel.send(Intent().apply {
                setClassName(
                    "app.universal.patch.manager.flutter",
                    "app.universal.patch.manager.flutter.ExportSettingsActivity"
                )
            })
        }
    }

    fun applyLegacySettings(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            app.toast(app.getString(R.string.legacy_import_failed))
            Log.e(
                tag,
                "Got unknown result code while importing legacy settings: ${result.resultCode}"
            )
            return
        }

        val jsonStr = result.data?.getStringExtra("data")
        if (jsonStr == null) {
            app.toast(app.getString(R.string.legacy_import_failed))
            Log.e(tag, "Legacy settings data is null")
            return
        }
        val settings = try {
            json.decodeFromString<LegacySettings>(jsonStr)
        } catch (e: SerializationException) {
            app.toast(app.getString(R.string.legacy_import_failed))
            Log.e(tag, "Legacy settings data could not be deserialized", e)
            return
        }

        applyLegacySettings(settings)
    }

    private fun applyLegacySettings(settings: LegacySettings) = viewModelScope.launch {
        val importedTheme = settings.themeMode?.let { theme ->
            when (theme) {
                1 -> Theme.LIGHT
                2 -> Theme.DARK
                else -> Theme.SYSTEM
            }
        }
        importedTheme?.let { prefs.theme.update(it) }
        settings.useDynamicTheme?.let { prefs.dynamicColor.update(it) }
        if (importedTheme != null || settings.useDynamicTheme != null) {
            val resolvedTheme = importedTheme ?: prefs.theme.get()
            val dynamicColor = settings.useDynamicTheme ?: prefs.dynamicColor.get()
            val preset = when {
                dynamicColor && resolvedTheme == Theme.SYSTEM -> ThemePreset.DYNAMIC
                dynamicColor -> null
                resolvedTheme == Theme.SYSTEM -> ThemePreset.DEFAULT
                resolvedTheme == Theme.LIGHT -> ThemePreset.LIGHT
                else -> ThemePreset.DARK
            }
            if (preset != null) {
                prefs.pureBlackTheme.update(false)
                prefs.customAccentColor.update("")
                prefs.customThemeColor.update("")

            }
            prefs.themePresetSelectionName.update(preset?.name.orEmpty())
            prefs.themePresetSelectionEnabled.update(preset != null)
        }
        settings.usePrereleases?.let { prereleases ->
            prefs.useManagerPrereleases.update(prereleases)
        }
        settings.apiUrl?.let { api ->
            prefs.api.update(api.removeSuffix("/"))
        }
        settings.experimentalPatchesEnabled?.let { allowExperimental ->
            prefs.disablePatchVersionCompatCheck.update(allowExperimental)
        }
        settings.patchesAutoUpdate?.let { autoUpdate ->
            with(patchBundleRepository) {
                sources
                    .first()
                    .find { it.uid == 0 }
                    ?.asRemoteOrNull
                    ?.setAutoUpdate(autoUpdate)

                updateCheck()
            }
        }
        settings.patchesChangeEnabled?.let { disableSelectionWarning ->
            prefs.disableSelectionWarning.update(disableSelectionWarning)
        }
        settings.keystore?.let { keystore ->
            val keystoreBytes = Base64.decode(keystore, Base64.DEFAULT)
            val passwordCandidates = listOf(
                settings.keystorePassword,
                KeystoreManager.DEFAULT_PASSWORD,
                KeystoreManager.LEGACY_DEFAULT_ALIAS,
                KeystoreManager.LEGACY_DEFAULT_PASSWORD
            ).filter { it.isNotBlank() }.distinct()
            val aliasCandidates = listOf(
                KeystoreManager.DEFAULT_ALIAS,
                KeystoreManager.LEGACY_DEFAULT_ALIAS,
                "ReVanced Key"
            ).distinct()
            var imported = false
            for (alias in aliasCandidates) {
                for (pass in passwordCandidates) {
                    Log.d(tag, "Trying legacy keystore import alias=$alias")
                    if (keystoreManager.import(alias, pass, pass, keystoreBytes.inputStream())) {
                        Log.i(tag, "Legacy keystore import succeeded alias=$alias")
                        imported = true
                        break
                    }
                }
                if (imported) break
            }
            if (!imported) {
                Log.w(tag, "Legacy keystore import failed for all known aliases/passwords")
            }
        }
        settings.patches?.let { selection ->
            patchSelectionRepository.import(0, selection)
        }
        Log.d(tag, "Imported legacy settings")
    }

    @Serializable
    private data class LegacySettings(
        val keystorePassword: String,
        val themeMode: Int? = null,
        val useDynamicTheme: Boolean? = null,
        val usePrereleases: Boolean? = null,
        val apiUrl: String? = null,
        val experimentalPatchesEnabled: Boolean? = null,
        val patchesAutoUpdate: Boolean? = null,
        val patchesChangeEnabled: Boolean? = null,
        val keystore: String? = null,
        val patches: SerializedSelection? = null,
    )
}

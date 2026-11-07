package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import app.universal.patch.manager.patcher.runtime.jvm.JvmInstallation
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.domain.installer.InstallerManager
import app.universal.patch.manager.domain.installer.RootInstaller
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.hideInstallerComponent
import app.universal.patch.manager.domain.manager.showInstallerComponent
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.apps.installed.InstallType
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.patcher.logger.PatcherLogMode
import app.universal.patch.manager.patcher.runtime.morphe.MorpheBytecodeMode
import app.universal.patch.manager.patcher.worker.AnnouncementNotificationWorker
import app.universal.patch.manager.patcher.worker.AutoPatchWorker
import app.universal.patch.manager.patcher.worker.reconcileAutoPatchNotificationPermission
import app.universal.patch.manager.util.FilenameUtils
import app.universal.patch.manager.util.tag
import app.universal.patch.manager.util.toast
import app.universal.patch.manager.util.simpleMessage
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.universal.patch.manager.ui.model.PatchSelectionActionKey
import app.universal.patch.manager.ui.model.PatchBundleActionKey
import app.universal.patch.manager.ui.model.BatchResultActionKey
import app.universal.patch.manager.ui.model.SavedAppActionKey
import app.universal.patch.manager.ui.model.PatchProfileActionKey
import app.universal.patch.manager.ui.model.LsposedModuleActionKey

class AdvancedSettingsViewModel(
    val prefs: PreferencesManager,
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val filesystem: Filesystem,
    private val workerRepository: WorkerRepository,
    private val installerManager: InstallerManager,
    private val rootInstaller: RootInstaller
) : ViewModel() {
    val jvmRuntimeAvailable = JvmInstallation(app).isAvailable

    fun setJvmRuntime(enabled: Boolean) = viewModelScope.launch {
        prefs.useJvmRuntime.update(enabled)
    }

    val debugLogFileName: String
        get() = FilenameUtils.timestampedLogFileName("debug")

    fun setApiUrl(value: String) = viewModelScope.launch(Dispatchers.Default) {
        if (value == prefs.api.get()) return@launch

        prefs.api.update(value)
        patchBundleRepository.updateCheck()
    }

    // PR #35: https://github.com/Jman-Github/Universal-Patch-Manager/pull/35
    fun setGitHubPat(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.gitHubPat.update(value.trim())
    }

    fun setIncludeGitHubPatInExports(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.includeGitHubPatInExports.update(enabled)
    }

    fun setSearchEngineHost(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.searchEngineHost.update(normalizeSearchEngineHost(value, prefs.searchEngineHost.default))
    }

    fun updateAnnouncementSystemEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.announcementSystemEnabled.update(enabled)

        workerRepository.scheduleAnnouncementNotificationWork(
            if (enabled) {
                prefs.announcementPushNotificationInterval.get()
            } else {
                SearchForUpdatesBackgroundInterval.NEVER
            }
        )
        if (!enabled) {
            app.getSystemService(NotificationManager::class.java)
                ?.cancel(AnnouncementNotificationWorker.ANNOUNCEMENT_NOTIFICATION_ID)
        }
    }

    fun exportDebugLogs(target: Uri) = viewModelScope.launch {
        val exitCode = try {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(target)!!.bufferedWriter().use { writer ->
                    val consumer = Redirect.Consume { flow ->
                        flow.onEach {
                            writer.write("${it}\n")
                        }.flowOn(Dispatchers.IO).collect()
                    }

                    process("logcat", "-d", stdout = consumer).resultCode
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Got exception while exporting logs", e)
            app.toast(app.getString(R.string.debug_logs_export_failed))
            return@launch
        }

        if (exitCode == 0)
            app.toast(app.getString(R.string.debug_logs_export_success))
        else
            app.toast(app.getString(R.string.debug_logs_export_read_failed, exitCode))
    }

    fun setPrimaryInstaller(token: InstallerManager.Token) = viewModelScope.launch(Dispatchers.Default) {
        // Code adapted from Morphe, see third-party/NOTICE for more information
        // https://github.com/MorpheApp/morphe-manager/commit/7e24461c1454b712da4df21440db6f417c94ce58
        if (token == InstallerManager.Token.AutoSaved ||
            token == InstallerManager.Token.RootPlayStore
        ) {
            // Request/verify root in background when user explicitly selects the rooted mount installer.
            runCatching { withContext(Dispatchers.IO) { rootInstaller.hasRootAccess() } }
        }
        installerManager.updatePrimaryToken(token)
        val fallback = installerManager.getFallbackToken()
        if (fallback != InstallerManager.Token.None && tokensEqual(fallback, token)) {
            installerManager.updateFallbackToken(InstallerManager.Token.None)
        }
    }

    fun setFallbackInstaller(token: InstallerManager.Token) = viewModelScope.launch(Dispatchers.Default) {
        if (token == InstallerManager.Token.AutoSaved ||
            token == InstallerManager.Token.RootPlayStore
        ) {
            runCatching { withContext(Dispatchers.IO) { rootInstaller.hasRootAccess() } }
        }
        val primary = installerManager.getPrimaryToken()
        val target = if (token != InstallerManager.Token.None && tokensEqual(primary, token)) {
            InstallerManager.Token.None
        } else token
        installerManager.updateFallbackToken(target)
    }

    fun setChooseInstallerPerInstall(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.chooseInstallerPerInstall.update(enabled)
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    fun setShizukuInstallAsPlayStore(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            installerManager.updateShizukuPlayStoreMode(enabled)
        }

    fun setAutoInstallWithShizuku(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.autoInstallWithShizuku.update(enabled)
        }

    fun setAutoUninstallWithShizuku(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.autoUninstallWithShizuku.update(enabled)
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/795
    fun setAutoPatchEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        if (enabled && !reconcileAutoPatchNotificationPermission(app, prefs)) {
            return@launch
        }
        prefs.updateAutoPatchEnabled(enabled)
        if (enabled) {
            AutoPatchWorker.schedule(
                app,
                prefs,
                prefs.autoPatchInterval.get(),
                prefs.autoPatchRequiresCharging.get()
            )
        } else {
            AutoPatchWorker.cancel(app)
        }
    }

    fun setAutoPatchInstallWithShizuku(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.edit {
                if (!enabled || prefs.autoPatchEnabled.value) {
                    prefs.autoPatchInstallWithShizuku.value = enabled
                }
            }
        }

    fun setAutoPatchUninstallOnConflictWithShizuku(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.edit {
                if (
                    !enabled ||
                    (prefs.autoPatchEnabled.value &&
                        prefs.autoPatchInstallWithShizuku.value)
                ) {
                    prefs.autoPatchUninstallOnConflictWithShizuku.value = enabled
                }
            }
        }

    fun setAutoPatchRequiresCharging(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.autoPatchRequiresCharging.update(enabled)
        if (prefs.autoPatchEnabled.get()) {
            AutoPatchWorker.schedule(
                app,
                prefs,
                prefs.autoPatchInterval.get(),
                enabled
            )
        }
    }

    fun setAutoPatchInterval(interval: SearchForUpdatesBackgroundInterval) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.autoPatchInterval.update(interval)
            if (prefs.autoPatchEnabled.get()) {
                AutoPatchWorker.schedule(
                    app,
                    prefs,
                    interval,
                    prefs.autoPatchRequiresCharging.get()
                )
            }
        }

    fun setAllowExternalBatchActions(enabled: Boolean) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.allowExternalBatchActions.update(enabled)
        }

    fun setPatchedAppExportFormat(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.patchedAppExportFormat.update(value)
    }

    fun setMergedApkExportFormat(value: String) = viewModelScope.launch(Dispatchers.Default) {
        prefs.mergedApkExportFormat.update(value)
    }

    fun setMorpheBytecodeMode(mode: MorpheBytecodeMode) = viewModelScope.launch(Dispatchers.Default) {
        prefs.morpheBytecodeMode.update(mode)
    }

    fun setPatcherLogMode(mode: PatcherLogMode) = viewModelScope.launch(Dispatchers.Default) {
        prefs.patcherLogMode.update(mode)
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/blob/a2c3d31bd7ab42e6bc4b9dd528ed856fc72fb948/app/src/main/java/app/morphe/manager/ui/screen/settings/system/PerformanceSection.kt
    fun resetPatchedAppExportFormat() = viewModelScope.launch(Dispatchers.Default) {
        prefs.patchedAppExportFormat.update(prefs.patchedAppExportFormat.default)
    }

    fun resetMergedApkExportFormat() = viewModelScope.launch(Dispatchers.Default) {
        prefs.mergedApkExportFormat.update(prefs.mergedApkExportFormat.default)
    }

    fun setSavedAppsEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) {
        prefs.enableSavedApps.update(enabled)
        if (enabled) return@launch

        withContext(Dispatchers.IO) {
            val savedApps = installedAppRepository.getByInstallType(InstallType.SAVED)
            savedApps.forEach { app ->
                installedAppRepository.delete(app)
                filesystem.deletePatchedAppFiles(app.currentPackageName)
                if (app.originalPackageName != app.currentPackageName) {
                    filesystem.deletePatchedAppFiles(app.originalPackageName)
                }
            }
        }
    }

    fun setPatchSelectionActionOrder(order: List<PatchSelectionActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.patchSelectionActionOrder.update(serialized)
        }

    fun setPatchBundleActionOrder(order: List<PatchBundleActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.patchBundleActionOrder.update(serialized)
        }

    fun setPatchBundleHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.patchBundleHiddenActions.update(hidden)
        }

    fun setBatchResultActionOrder(order: List<BatchResultActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.batchResultActionOrder.update(serialized)
        }

    fun setBatchResultHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.batchResultHiddenActions.update(hidden)
        }

    fun setSavedAppActionOrder(order: List<SavedAppActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.savedAppActionOrder.update(serialized)
        }

    fun setSavedAppHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.savedAppHiddenActions.update(hidden)
        }

    fun setPatchProfileActionOrder(order: List<PatchProfileActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.patchProfileActionOrder.update(serialized)
        }

    fun setPatchProfileHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.patchProfileHiddenActions.update(hidden)
        }

    fun setLsposedModuleActionOrder(order: List<LsposedModuleActionKey>) =
        viewModelScope.launch(Dispatchers.Default) {
            val serialized = order.joinToString(",") { it.storageId }
            prefs.lsposedModuleActionOrder.update(serialized)
        }

    fun setLsposedModuleHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.lsposedModuleHiddenActions.update(hidden)
        }

    fun setPatchSelectionHiddenActions(hidden: Set<String>) =
        viewModelScope.launch(Dispatchers.Default) {
            prefs.patchSelectionHiddenActions.update(hidden)
        }

    fun addCustomInstaller(component: ComponentName, onResult: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.Default) {
            val added = installerManager.addCustomInstaller(component)
            if (added) {
                prefs.showInstallerComponent(component)
            }
            withContext(Dispatchers.Main) {
                onResult(added)
            }
        }

    fun removeCustomInstaller(component: ComponentName, onResult: (Boolean) -> Unit = {}) =
        viewModelScope.launch(Dispatchers.Default) {
        val removed = installerManager.removeCustomInstaller(component)
        if (removed) {
            prefs.hideInstallerComponent(component)
            val removedPackage = component.packageName
            val currentPrimary = installerManager.getPrimaryToken()
            val currentFallback = installerManager.getFallbackToken()
            val primaryMatchesRemoved =
                currentPrimary is InstallerManager.Token.Component &&
                    currentPrimary.componentName.packageName == removedPackage
            val fallbackMatchesRemoved =
                currentFallback is InstallerManager.Token.Component &&
                    currentFallback.componentName.packageName == removedPackage

            if (primaryMatchesRemoved) {
                installerManager.updatePrimaryToken(InstallerManager.Token.Internal)
            }
            if (fallbackMatchesRemoved) {
                installerManager.updateFallbackToken(InstallerManager.Token.None)
            }

            val componentAvailable = installerManager.isComponentAvailable(component)
            if (!componentAvailable) {
                if (currentPrimary is InstallerManager.Token.Component &&
                    currentPrimary.componentName == component
                ) {
                    installerManager.updatePrimaryToken(InstallerManager.Token.Internal)
                }
                if (currentFallback is InstallerManager.Token.Component &&
                    currentFallback.componentName == component
                ) {
                    installerManager.updateFallbackToken(InstallerManager.Token.None)
                }
            }
        }
            withContext(Dispatchers.Main) {
                onResult(removed)
            }
        }

    fun searchInstallerEntries(
        query: String,
        target: InstallerManager.InstallTarget
    ): List<InstallerManager.Entry> = installerManager.searchInstallerEntries(query, target)
}

private fun normalizeSearchEngineHost(value: String, fallback: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return fallback
    val noScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val noPath = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    return noPath.trim().trimEnd('/').ifBlank { fallback }
}

private fun tokensEqual(a: InstallerManager.Token, b: InstallerManager.Token): Boolean = when {
    a === b -> true
    a is InstallerManager.Token.Component && b is InstallerManager.Token.Component ->
        a.componentName == b.componentName
    a.baseInstallerVariant() == b.baseInstallerVariant() -> true
    else -> false
}

private fun InstallerManager.Token.baseInstallerVariant(): InstallerManager.Token = when (this) {
    InstallerManager.Token.PlayStore -> InstallerManager.Token.Internal
    InstallerManager.Token.RootPlayStore -> InstallerManager.Token.AutoSaved
    InstallerManager.Token.ShizukuGooglePlay -> InstallerManager.Token.Shizuku
    else -> this
}

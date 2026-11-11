package app.universal.patch.manager.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.universal.patch.manager.R
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.repository.AppEntryArchive
import app.universal.patch.manager.domain.repository.InstalledAppRepository
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.util.PM
import app.universal.patch.manager.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal class AppEntryArchiveViewModel(
    private val app: Application,
    filesystem: Filesystem,
    pm: PM,
    apps: InstalledAppRepository,
    bundles: PatchBundleRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val archive = AppEntryArchive(app, filesystem, pm, apps, bundles)
    var exportEntries by mutableStateOf<List<AppEntryArchive.ExportEntry>?>(null)
        private set
    var importPlan by mutableStateOf<AppEntryArchive.ImportPlan?>(null)
        private set
    var progress by mutableStateOf<AppEntryArchive.Progress?>(null)
        private set
    val busy: Boolean get() = progress != null
    private var pendingExport = emptyList<AppEntryArchive.ExportEntry>()

    fun chooseExport() = runArchiveAction(AppEntryArchive.Stage.Preparing, R.string.app_entries_export_failed) {
        exportEntries = archive.exportEntries()
    }

    fun prepareExport(selection: Set<Int>) {
        pendingExport = exportEntries.orEmpty().filterIndexed { index, entry -> index in selection && entry.hasApk }
        // The activity may be recreated while Android's document picker is open.
        // Store compact IDs, not APK metadata or patch payloads, in the saved-state Bundle.
        savedStateHandle[PENDING_EXPORT_IDS] = ArrayList(pendingExport.map { it.exportId() })
        exportEntries = null
    }

    fun finishExport(target: Uri?): Job? {
        if (target == null) {
            dismissExport()
            return null
        }
        if (busy) return null
        val rememberedSelection = pendingExport
        val selectedIds = savedStateHandle.remove<ArrayList<String>>(PENDING_EXPORT_IDS).orEmpty().toSet()
        pendingExport = emptyList()
        return runArchiveAction(AppEntryArchive.Stage.Exporting, R.string.app_entries_export_failed) {
            check(selectedIds.isNotEmpty()) { "The export selection is no longer available" }
            val selection = rememberedSelection.ifEmpty {
                archive.exportEntries().filter { it.exportId() in selectedIds }
            }
            check(selection.size == selectedIds.size && selection.all { it.hasApk }) {
                "Selected app entries changed or their APKs are no longer available"
            }
            val count = archive.export(target, selection, ::updateProgress)
            app.toast(app.resources.getQuantityString(R.plurals.app_entries_exported, count, count))
        }
    }

    fun dismissExport() {
        exportEntries = null
        pendingExport = emptyList()
        savedStateHandle.remove<ArrayList<String>>(PENDING_EXPORT_IDS)
    }

    fun openImport(uri: Uri) = runArchiveAction(AppEntryArchive.Stage.Reading, R.string.app_entries_import_failed) {
        val plan = archive.prepareImport(uri, ::updateProgress)
        importPlan = plan
        if (plan.entries.size == 1) restore(plan, setOf(0))
    }

    fun finishImport(selected: Set<Int>) {
        val plan = importPlan ?: return
        runArchiveAction(AppEntryArchive.Stage.Importing, R.string.app_entries_import_failed) {
            restore(plan, selected)
        }
    }

    fun dismissImport() {
        importPlan?.file?.delete()
        importPlan = null
    }

    private suspend fun restore(plan: AppEntryArchive.ImportPlan, selected: Set<Int>) {
        importPlan = null
        try {
            val (imported, updated, skipped) = archive.restore(plan, selected, ::updateProgress)
            val messages = buildList {
                if (imported > 0) {
                    add(app.resources.getQuantityString(
                        if (skipped > 0) R.plurals.app_entries_imported_skipped else R.plurals.app_entries_imported,
                        imported, imported, skipped
                    ))
                }
                if (updated > 0) {
                    val updatedWithSkipped = imported == 0 && skipped > 0
                    add(app.resources.getQuantityString(
                        if (updatedWithSkipped) R.plurals.app_entries_updated_skipped else R.plurals.app_entries_updated,
                        updated, updated, skipped
                    ))
                }
                if (imported == 0 && updated == 0 && skipped > 0) {
                    add(app.resources.getQuantityString(R.plurals.app_entries_skipped, skipped, skipped))
                }
            }
            if (messages.isNotEmpty()) app.toast(messages.joinToString("\n"))
        } finally {
            plan.file.delete()
        }
    }

    private suspend fun updateProgress(value: AppEntryArchive.Progress) {
        withContext(Dispatchers.Main.immediate) { progress = value }
    }

    private fun runArchiveAction(
        stage: AppEntryArchive.Stage,
        errorMessage: Int,
        action: suspend () -> Unit
    ): Job? {
        if (busy) return null
        progress = AppEntryArchive.Progress(stage)
        return viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("AppEntryArchive", "App entry archive operation failed", error)
                app.toast(app.getString(errorMessage))
            } finally {
                progress = null
            }
        }
    }

    private fun AppEntryArchive.ExportEntry.exportId(): String =
        MessageDigest.getInstance("SHA-256")
            .digest((record.currentPackageName + "\u0000" + record.version).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PENDING_EXPORT_IDS = "app_entries_pending_export_ids"
    }

    override fun onCleared() {
        dismissImport()
    }
}

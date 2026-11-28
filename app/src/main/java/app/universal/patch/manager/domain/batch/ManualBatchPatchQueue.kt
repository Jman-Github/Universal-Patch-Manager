package app.universal.patch.manager.domain.batch

import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.room.profile.PatchProfilePayload
import app.universal.patch.manager.domain.repository.PatchOptionInputManager
import app.universal.patch.manager.ui.model.SelectedApp
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class ManualBatchPatchEntry(
    val input: SelectedApp,
    val selection: PatchSelection,
    val options: Options,
    val useMount: Boolean,
    val signatureWorkflow: PatchProfilePayload.SignatureWorkflow? = null,
    val sourceEntryKey: String? = null,
    // Identifies the entry: the same app can be queued once for each set of patch bundles.
    val key: String = input.packageName,
)

/**
 * The entry an upsert replaces: the one with [key] when given, otherwise the same app patched
 * with the same bundles. Queueing the app with other bundles adds a separate entry.
 */
internal fun manualBatchEntryIndex(
    entries: List<ManualBatchPatchEntry>,
    key: String?,
    packageName: String,
    selection: PatchSelection
): Int = if (key != null) {
    entries.indexOfFirst { it.key == key }
} else {
    entries.indexOfFirst {
        it.input.packageName == packageName && it.selection.keys == selection.keys
    }
}

/** The package name for an app's first entry, then a numbered key for each further entry. */
internal fun nextManualBatchEntryKey(
    entries: List<ManualBatchPatchEntry>,
    packageName: String
): String {
    val used = entries.mapTo(mutableSetOf()) { it.key }
    if (packageName !in used) return packageName
    return generateSequence(2) { it + 1 }
        .map { "$packageName#$it" }
        .first { it !in used }
}

class ManualBatchPatchQueue(
    private val filesystem: Filesystem,
    private val patchOptionInputManager: PatchOptionInputManager
) {
    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<ManualBatchPatchEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val ownedSourcePaths = mutableSetOf<String>()
    private val optionOwnership = patchOptionInputManager.pendingInputOwnership()
    private var queueGeneration = 0L

    suspend fun upsert(
        input: SelectedApp,
        selection: PatchSelection,
        options: Options,
        useMount: Boolean,
        signatureWorkflow: PatchProfilePayload.SignatureWorkflow? = null,
        sourceEntryKey: String? = null,
        key: String? = null,
    ) {
        val generationAtStart = synchronized(lock) { queueGeneration }
        val (preparedInput, ownedPath) = prepareInput(input)
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (generationAtStart != queueGeneration) {
                    ownedPath?.let { path -> runCatching { File(path).delete() } }
                    return@synchronized
                }
                ownedPath?.let(ownedSourcePaths::add)
                val current = mutableEntries.value
                val queuedSelection = selection.filterValues { it.isNotEmpty() }
                val index = manualBatchEntryIndex(
                    current,
                    key,
                    preparedInput.packageName,
                    queuedSelection
                )
                val previous = current.getOrNull(index)
                val replacement = ManualBatchPatchEntry(
                    input = preparedInput,
                    selection = queuedSelection,
                    options = options,
                    useMount = useMount,
                    signatureWorkflow = signatureWorkflow,
                    sourceEntryKey = sourceEntryKey,
                    key = previous?.key
                        ?: key
                        ?: nextManualBatchEntryKey(current, preparedInput.packageName),
                )
                mutableEntries.value = if (previous == null) {
                    current + replacement
                } else {
                    current.toMutableList().also { it[index] = replacement }
                }
                deleteOwnedInput(previous?.input, except = preparedInput)
                reconcileOptions()
            }
        }
    }

    fun updateConfiguration(
        key: String,
        selection: PatchSelection,
        options: Options
    ) {
        synchronized(lock) {
            mutableEntries.value = mutableEntries.value.map { entry ->
                if (entry.key == key) {
                    entry.copy(
                        selection = selection.filterValues { it.isNotEmpty() },
                        options = options
                    )
                } else {
                    entry
                }
            }
            reconcileOptions()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            val previous = mutableEntries.value.firstOrNull { it.key == key }
            mutableEntries.value = mutableEntries.value.filterNot { it.key == key }
            deleteOwnedInput(previous?.input)
            reconcileOptions()
        }
    }

    fun clear() {
        synchronized(lock) {
            queueGeneration++
            mutableEntries.value = emptyList()
            ownedSourcePaths.toList().forEach { path ->
                runCatching { File(path).delete() }
            }
            ownedSourcePaths.clear()
            optionOwnership.reconcile(emptySet())
        }
    }

    fun snapshot(): List<ManualBatchPatchEntry> = synchronized(lock) {
        mutableEntries.value.toList()
    }

    fun reorder(keys: List<String>) {
        synchronized(lock) {
            val order = keys.distinct().withIndex().associate { it.value to it.index }
            mutableEntries.value = mutableEntries.value.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<ManualBatchPatchEntry>> {
                        order[it.value.key] ?: Int.MAX_VALUE
                    }.thenBy { it.index }
                )
                .map { it.value }
        }
    }

    private suspend fun prepareInput(
        input: SelectedApp
    ): Pair<SelectedApp, String?> = withContext(Dispatchers.IO) {
        prepareManualBatchInput(
            input = input,
            targetDirectory = filesystem.uiTempDir
        )
    }

    private fun reconcileOptions() {
        val referenced = mutableEntries.value
            .flatMapTo(mutableSetOf()) { entry ->
                patchOptionInputManager.pendingInputsIn(entry.options)
            }
        optionOwnership.reconcile(referenced)
    }

    private fun deleteOwnedInput(
        input: SelectedApp?,
        except: SelectedApp? = null
    ) {
        val file = (input as? SelectedApp.Local)?.file ?: return
        val path = runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }
        val exceptPath = (except as? SelectedApp.Local)?.file?.let { exceptFile ->
            runCatching(exceptFile::getCanonicalPath).getOrElse { exceptFile.absolutePath }
        }
        if (path == exceptPath || path !in ownedSourcePaths) return
        runCatching { file.delete() }
        ownedSourcePaths.remove(path)
    }
}


internal fun prepareManualBatchInput(
    input: SelectedApp,
    targetDirectory: File,
    uniqueSuffix: Long = System.nanoTime()
): Pair<SelectedApp, String?> {
    if (input !is SelectedApp.Local || !input.temporary || !input.file.isFile) {
        return input to null
    }
    val extension = input.file.extension.ifBlank { "apk" }
    val safePackage = input.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = targetDirectory.resolve(
        "manual_batch_${safePackage}_$uniqueSuffix.$extension"
    )
    target.parentFile?.mkdirs()
    var copied = false
    try {
        input.file.copyTo(target, overwrite = true)
        check(target.isFile && target.length() == input.file.length()) {
            "Failed to verify the manual batch APK copy"
        }
        copied = true
    } finally {
        if (!copied) target.delete()
    }
    val ownedPath = runCatching(target::getCanonicalPath).getOrElse { target.absolutePath }
    return input.copy(file = target, temporary = false) to ownedPath
}

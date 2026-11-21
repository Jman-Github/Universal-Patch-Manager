package app.universal.patch.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.universal.patch.manager.domain.repository.PatcherRuntimePluginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Inputs live in preferences so a settings restart or process death cannot lose the restore. */
class RuntimeRestoreWorker(
    context: Context,
    parameters: WorkerParameters,
    private val runtimes: PatcherRuntimePluginRepository
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (runtimes.restoreImportedRuntimeSources()) Result.success() else Result.retry()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("RuntimeRestore", "Runtime restore will retry in the background", error)
        Result.retry()
    }

    companion object {
        suspend fun enqueue(context: Context) = withContext(Dispatchers.IO) {
            val request = OneTimeWorkRequestBuilder<RuntimeRestoreWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Append also handles a new import arriving while the previous worker finishes.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "runtime-backup-restore", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            ).result.get()
        }
    }
}

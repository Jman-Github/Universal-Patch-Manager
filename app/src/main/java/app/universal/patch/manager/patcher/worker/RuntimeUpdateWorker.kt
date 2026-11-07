package app.universal.patch.manager.patcher.worker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.universal.patch.manager.domain.repository.PatcherRuntimePluginRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RuntimeUpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters), KoinComponent {
    private val prefs: PreferencesManager by inject()
    private val repository: PatcherRuntimePluginRepository by inject()

    override suspend fun doWork(): Result {
        if (prefs.backgroundRuntimeUpdateInterval.get() != SearchForUpdatesBackgroundInterval.NEVER) {
            try {
                repository.updateCheck()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("RuntimeUpdates", "Runtime update check failed", error)
                return Result.retry()
            }
        }
        return Result.success()
    }
}

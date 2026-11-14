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
    private val downloaders: app.universal.patch.manager.domain.repository.DownloaderPluginRepository by inject()
    private val prefs: PreferencesManager by inject()
    private val repository: PatcherRuntimePluginRepository by inject()

    override suspend fun doWork(): Result {
        if (prefs.backgroundRuntimeUpdateInterval.get() != SearchForUpdatesBackgroundInterval.NEVER) {
            var failed = false
            for (check in listOf<suspend () -> Unit>(
                { downloaders.updateCheck() }, { repository.updateCheck() }
            )) {
                try {
                    check()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("RuntimeUpdates", "Plugin update check failed", error)
                    failed = true
                }
            }
            if (failed) return Result.retry()
        }
        return Result.success()
    }
}

package app.universal.patch.manager.di

import app.universal.patch.manager.domain.installer.root.RootReconciliationScheduling
import app.universal.patch.manager.patcher.worker.AnnouncementNotificationWorker
import app.universal.patch.manager.patcher.worker.AutoClearCacheWorker
import app.universal.patch.manager.patcher.worker.AutoPatchWorker
import app.universal.patch.manager.patcher.worker.BundleUpdateNotificationWorker
import app.universal.patch.manager.patcher.worker.ManagerUpdateNotificationWorker
import app.universal.patch.manager.patcher.worker.PatcherWorker
import app.universal.patch.manager.worker.RuntimeRestoreWorker
import app.universal.patch.manager.worker.RootMountReconcileWorker
import app.universal.patch.manager.worker.RootMountReconciliationScheduler
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val workerModule = module {
    workerOf(::PatcherWorker)
    workerOf(::AnnouncementNotificationWorker)
    workerOf(::AutoClearCacheWorker)
    workerOf(::AutoPatchWorker)
    workerOf(::BundleUpdateNotificationWorker)
    workerOf(::ManagerUpdateNotificationWorker)
    workerOf(::RuntimeRestoreWorker)
    workerOf(::RootMountReconcileWorker)
    single<RootReconciliationScheduling> { RootMountReconciliationScheduler(get()) }
}

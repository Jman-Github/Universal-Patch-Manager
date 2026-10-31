package app.universal.patch.manager.di

import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.data.platform.NetworkInfo
import app.universal.patch.manager.domain.lsposed.LsposedRepository
import app.universal.patch.manager.domain.batch.BatchExecutionGate
import app.universal.patch.manager.domain.batch.BatchPatchCoordinator
import app.universal.patch.manager.domain.batch.BatchPlanResolver
import app.universal.patch.manager.domain.batch.ManualBatchPatchQueue
import app.universal.patch.manager.domain.repository.*
import app.universal.patch.manager.domain.storage.RepatchSourceCleanup
import app.universal.patch.manager.domain.worker.BundleUpdateWebSocketCoordinator
import app.universal.patch.manager.domain.worker.WorkerRepository
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.bundles.BundleDiscoveryState
import app.universal.patch.manager.domain.bundles.BundleDiscoveryStateStore
import app.universal.patch.manager.domain.bundles.BundleIconRepository
import app.universal.patch.manager.domain.bundles.BundleReleasePageRepository
import app.universal.patch.manager.network.api.ExternalBundlesApi
import app.universal.patch.manager.network.api.UpmAPI
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val repositoryModule = module {
    singleOf(::AppNameRepository)
    singleOf(::ExternalBundlesApi)
    single {
        val preference = get<PreferencesManager>().patchBundleDiscoveryState
        BundleDiscoveryStateStore(
            initial = BundleDiscoveryState.decode(preference.getBlocking()),
            persist = { preference.update(it.encode()) },
            onWriteError = { android.util.Log.w("BundleDiscovery", "Could not save browsing preferences", it) }
        )
    }
    singleOf(::BundleIconRepository)
    singleOf(::BundleReleasePageRepository)
    singleOf(::UpmAPI)
    singleOf(::Filesystem) {
        createdAtStart()
    }
    singleOf(::NetworkInfo)
    singleOf(::PatchSelectionRepository)
    singleOf(::PatchOptionInputManager) {
        createdAtStart()
    }
    singleOf(::PatchOptionsRepository)
    singleOf(::PatchProfileRepository)
    singleOf(::PatchBundleRepository) {
        // It is best to load patch bundles ASAP
        createdAtStart()
    }
    singleOf(::BundleUpdateWebSocketCoordinator) {
        createdAtStart()
    }
    singleOf(::AnnouncementRepository)
    singleOf(::DownloaderPluginRepository)
    singleOf(::PatcherRuntimePluginRepository)
    singleOf(::WorkerRepository)
    singleOf(::DownloadedAppRepository)
    singleOf(::InstalledAppRepository)
    singleOf(::RepatchSourceCleanup)
    singleOf(::ManualBatchPatchQueue)
    singleOf(::BatchPlanResolver)
    singleOf(::BatchExecutionGate)
    factoryOf(::BatchPatchCoordinator)
    singleOf(::LsposedRepository)
}

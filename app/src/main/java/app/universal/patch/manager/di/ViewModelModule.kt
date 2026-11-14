package app.universal.patch.manager.di

import app.universal.patch.manager.ui.viewmodel.*
import app.universal.patch.manager.ui.model.navigation.SelectedApplicationInfo
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModelOf(::MainViewModel)
    viewModelOf(::DashboardViewModel)
    viewModel { (params: SelectedApplicationInfo.ViewModelParams) ->
        SelectedAppInfoViewModel(params)
    }
    viewModel { (params: SelectedApplicationInfo.PatchesSelector.ViewModelParams) ->
        PatchesSelectorViewModel(params)
    }
    viewModelOf(::GeneralSettingsViewModel)
    viewModelOf(::AdvancedSettingsViewModel)
    viewModelOf(::AppSelectorViewModel)
    viewModelOf(::PatcherViewModel)
    viewModelOf(::BatchPatcherViewModel)
    viewModelOf(::UpdateViewModel)
    viewModelOf(::ImportExportViewModel)
    viewModelOf(::AppEntryArchiveViewModel)
    viewModelOf(::AboutViewModel)
    viewModelOf(::DeveloperOptionsViewModel)
    viewModelOf(::ContributorViewModel)
    viewModelOf(::DownloadsViewModel)
    viewModelOf(::DownloaderCatalogViewModel)
    viewModelOf(::PatcherRuntimePluginsViewModel)
    viewModelOf(::AnnouncementsViewModel)
    viewModelOf(::InstalledAppsViewModel)
    viewModelOf(::InstalledAppInfoViewModel)
    viewModelOf(::SplitApkInstallerViewModel)
    viewModelOf(::UpdatesSettingsViewModel)
    viewModelOf(::BundleListViewModel)
    viewModelOf(::BundleDiscoveryViewModel)
    viewModelOf(::ChangelogsViewModel)
    viewModelOf(::PatchProfilesViewModel)
    viewModelOf(::LsposedViewModel)
    viewModelOf(::SignatureMetadataInjectorViewModel)
}

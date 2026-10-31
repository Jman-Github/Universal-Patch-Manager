package app.universal.patch.manager.di

import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.util.DownloadProgressNotifier
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val serviceModule = module {
    single { HttpService(get(), get()) }
    singleOf(::DownloadProgressNotifier)
}
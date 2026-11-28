package app.universal.patch.manager.di

import android.content.Context
import androidx.room.Room
import app.universal.patch.manager.data.room.AppDatabase
import app.universal.patch.manager.data.room.MIGRATION_1_2
import app.universal.patch.manager.data.room.MIGRATION_21_22
import app.universal.patch.manager.data.room.MIGRATION_2_3
import app.universal.patch.manager.data.room.MIGRATION_3_4
import app.universal.patch.manager.data.room.MIGRATION_4_5
import app.universal.patch.manager.data.room.MIGRATION_5_6
import app.universal.patch.manager.data.room.MIGRATION_6_7
import app.universal.patch.manager.data.room.MIGRATION_7_8
import app.universal.patch.manager.data.room.MIGRATION_8_9
import app.universal.patch.manager.data.room.MIGRATION_9_10
import app.universal.patch.manager.data.room.MIGRATION_10_11
import app.universal.patch.manager.data.room.MIGRATION_11_12
import app.universal.patch.manager.data.room.MIGRATION_12_13
import app.universal.patch.manager.data.room.MIGRATION_13_14
import app.universal.patch.manager.data.room.MIGRATION_14_15
import app.universal.patch.manager.data.room.MIGRATION_15_16
import app.universal.patch.manager.data.room.MIGRATION_16_17
import app.universal.patch.manager.data.room.MIGRATION_17_18
import app.universal.patch.manager.data.room.MIGRATION_18_19
import app.universal.patch.manager.data.room.MIGRATION_19_20
import app.universal.patch.manager.data.room.MIGRATION_20_21
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    fun provideAppDatabase(context: Context) =
        Room.databaseBuilder(context, AppDatabase::class.java, "manager")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
                MIGRATION_21_22
            )
            .build()

    single {
        provideAppDatabase(androidContext())
    }
    single { get<AppDatabase>().lsposedModuleDao() }
}

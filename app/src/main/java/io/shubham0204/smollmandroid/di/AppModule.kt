/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.di

import androidx.room.Room
import io.shubham0204.smollmandroid.data.ModelRepository
import io.shubham0204.smollmandroid.data.ServerDatabase
import io.shubham0204.smollmandroid.data.SettingsRepository
import io.shubham0204.smollmandroid.engine.DeviceMonitor
import io.shubham0204.smollmandroid.engine.InferenceEngine
import io.shubham0204.smollmandroid.jobs.JobManager
import io.shubham0204.smollmandroid.jobs.PartialWakeLock
import io.shubham0204.smollmandroid.http.LocalAiServer
import io.shubham0204.smollmandroid.pairing.PairingRepository
import io.shubham0204.smollmandroid.service.ServerController
import io.shubham0204.smollmandroid.ui.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), ServerDatabase::class.java, "local_ai_server.db").addMigrations(ServerDatabase.MIGRATION_1_2, ServerDatabase.MIGRATION_2_3).build()
    }
    single { get<ServerDatabase>().models() }
    single { get<ServerDatabase>().pairedApps() }
    single { get<ServerDatabase>().requestLog() }
    single { get<ServerDatabase>().jobs() }
    single { SettingsRepository(androidContext()) }
    single { ModelRepository(androidContext(), get(), get()) }
    single { PairingRepository(get()) }
    single { InferenceEngine(get(), get()) }
    single {
        val settings = get<SettingsRepository>()
        JobManager(get<InferenceEngine>(), get(), get(), PartialWakeLock(androidContext())) { settings.current.logBodies }
    }
    single { DeviceMonitor(androidContext()) }
    single {
        val settings = get<SettingsRepository>()
        LocalAiServer(get<InferenceEngine>(), get(), get(), get()) { settings.current }
    }
    single { ServerController(androidContext(), get(), get(), get(), get()) }
    viewModel { MainViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

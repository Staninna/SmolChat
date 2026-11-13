/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.shubham0204.smollmandroid.data.SettingsRepository
import io.shubham0204.smollmandroid.engine.InferenceEngine
import io.shubham0204.smollmandroid.http.LocalAiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts and stops the foreground service and reacts to settings changes while it runs:
 * a new port restarts the HTTP server, and a new model or load setting unloads the model so the
 * next request (or an immediate preload) picks up the change.
 */
class ServerController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val engine: InferenceEngine,
    private val http: LocalAiServer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            settings.settings.map { it.port }.distinctUntilChanged().drop(1).collect { port ->
                if (http.state.value.running) http.start(port)
            }
        }
        scope.launch {
            settings.settings.map { it.loadKey() }.distinctUntilChanged().drop(1).collect {
                engine.unload()
                if (http.state.value.running) engine.preload()
            }
        }
        scope.launch { engine.refreshIdleState() }
    }

    val isRunning: Boolean
        get() = http.state.value.running

    /** Starts the foreground service (must be called while the app is in the foreground). */
    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, LocalAiService::class.java))
    }

    fun stop() {
        context.startService(Intent(context, LocalAiService::class.java).setAction(LocalAiService.ACTION_STOP))
    }

    /** Called by the service. */
    internal fun onServiceStarted() {
        http.start(settings.current.port)
        engine.preload()
    }

    /** Called by the service. */
    internal suspend fun onServiceStopping() {
        http.stop()
        engine.unload()
    }
}

/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.engine.EngineState
import io.shubham0204.smollmandroid.engine.EngineStatus
import io.shubham0204.smollmandroid.engine.InferenceEngine
import io.shubham0204.smollmandroid.http.HttpServerState
import io.shubham0204.smollmandroid.http.LocalAiServer
import io.shubham0204.smollmandroid.jobs.JobManager
import io.shubham0204.smollmandroid.jobs.JobPhase
import io.shubham0204.smollmandroid.jobs.JobSnapshot
import io.shubham0204.smollmandroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service (type `specialUse`) that keeps the HTTP server and the loaded model alive
 * while other apps use them. Its persistent notification shows the model, the status and a Stop
 * action.
 */
class LocalAiService : Service() {
    private val controller by inject<ServerController>()
    private val engine by inject<InferenceEngine>()
    private val http by inject<LocalAiServer>()
    private val jobs by inject<JobManager>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val initial = buildNotification(engine.state.value, http.state.value, jobs.active.value)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initial,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0,
        )
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                controller.onServiceStopping()
                ServiceCompat.stopForeground(this@LocalAiService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        controller.onServiceStarted()
        if (observer == null) {
            val nm = getSystemService(NotificationManager::class.java)
            observer =
                scope.launch {
                    combine(engine.state, http.state, jobs.active) { e, h, j -> Triple(e, h, j) }
                        .distinctUntilChangedBy { (e, h, j) -> buildKey(e, h, j) }
                        .collect { (e, h, j) ->
                            nm.notify(NOTIFICATION_ID, buildNotification(e, h, j))
                            // at most one update per second, so the system does not drop them
                            delay(1_000)
                        }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // only rebuild the notification when something visible changes; progress is rounded so that
    // a running job updates it about once per second at most (collect is conflated by the delay)
    private fun buildKey(e: EngineState, h: HttpServerState, j: List<JobSnapshot>): List<Any?> {
        val current = j.firstOrNull { it.phase != JobPhase.Queued } ?: j.firstOrNull()
        return listOf(
            e.status, e.model?.id, e.error, h.running, h.port, h.error, j.size,
            current?.id, current?.phase, current?.promptProcessed, current?.completionTokens,
        )
    }

    private fun buildNotification(e: EngineState, h: HttpServerState, j: List<JobSnapshot>): Notification {
        val current = j.firstOrNull { it.phase != JobPhase.Queued } ?: j.firstOrNull()
        val waiting = j.count { it.phase == JobPhase.Queued && it.id != current?.id }
        val title = current?.clientLabel ?: e.model?.displayName ?: getString(R.string.notif_no_model)
        var progressMax = 0
        var progress = 0
        var indeterminate = false
        val status =
            when {
                h.error != null -> h.error
                current != null -> {
                    val text =
                        when (current.phase) {
                            JobPhase.Queued -> getString(R.string.job_phase_queued)
                            JobPhase.LoadingModel -> {
                                indeterminate = true
                                getString(R.string.job_phase_loading)
                            }
                            JobPhase.ReadingPrompt -> {
                                progressMax = current.promptTokens
                                progress = current.promptProcessed
                                indeterminate = progressMax == 0
                                getString(R.string.notif_job_reading, current.promptProcessed, current.promptTokens)
                            }
                            else -> {
                                val target = current.maxTokens?.takeIf { current.maxTokensFromClient }
                                if (target != null) {
                                    progressMax = target
                                    progress = current.completionTokens
                                } else {
                                    indeterminate = true
                                }
                                current.tokensPerSecond?.let {
                                    getString(R.string.notif_job_writing_speed, current.completionTokens, it)
                                } ?: getString(R.string.notif_job_writing, current.completionTokens)
                            }
                        }
                    if (waiting > 0) text + " · " + getString(R.string.status_queue, waiting) else text
                }
                e.status == EngineStatus.Loading -> getString(R.string.status_loading)
                e.status == EngineStatus.Generating -> getString(R.string.status_generating)
                e.status == EngineStatus.Ready -> getString(R.string.notif_ready, h.port ?: 0)
                e.status == EngineStatus.Unloaded -> getString(R.string.notif_unloaded)
                e.status == EngineStatus.Error -> e.error ?: getString(R.string.status_error)
                else -> getString(R.string.notif_no_model_text)
            }
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, LocalAiService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(status)
            .setSubText(e.model?.displayName?.takeIf { current != null })
            .setProgress(progressMax, progress, indeterminate && current != null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_stop, getString(R.string.action_stop), stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.notif_channel_desc) }
            )
        }
    }

    companion object {
        const val ACTION_STOP = "io.shubham0204.smollmandroid.action.STOP_SERVER"
        private const val CHANNEL_ID = "local_ai_server"
        private const val NOTIFICATION_ID = 1
    }
}

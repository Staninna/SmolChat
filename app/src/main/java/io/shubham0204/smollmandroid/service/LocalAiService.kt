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
import io.shubham0204.smollmandroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val initial = buildNotification(engine.state.value, http.state.value)
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
                    combine(engine.state, http.state) { e, h -> buildKey(e, h) to (e to h) }
                        .distinctUntilChanged { a, b -> a.first == b.first }
                        .collect { (_, pair) -> nm.notify(NOTIFICATION_ID, buildNotification(pair.first, pair.second)) }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // only rebuild the notification when something visible changes (not on every token)
    private fun buildKey(e: EngineState, h: HttpServerState) =
        listOf(e.status, e.model?.id, e.queueLength, e.error, h.running, h.port, h.error)

    private fun buildNotification(e: EngineState, h: HttpServerState): Notification {
        val title = e.model?.displayName ?: getString(R.string.notif_no_model)
        val status =
            when {
                h.error != null -> h.error
                e.status == EngineStatus.Loading -> getString(R.string.status_loading)
                e.status == EngineStatus.Generating ->
                    if (e.queueLength > 0) getString(R.string.notif_generating_queue, e.queueLength)
                    else getString(R.string.status_generating)
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

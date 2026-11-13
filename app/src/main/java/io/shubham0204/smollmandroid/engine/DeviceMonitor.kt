/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalLevel { Normal, Warm, Hot, Critical }

data class MemoryInfo(val availableBytes: Long, val totalBytes: Long, val low: Boolean)

/**
 * The phone conditions that decide how fast the model runs: heat (the system throttles the CPU),
 * battery saver (it limits the big cores) and free memory (a model that does not fit gets paged).
 */
class DeviceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activity = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _thermal = MutableStateFlow(ThermalLevel.Normal)
    val thermal: StateFlow<ThermalLevel> = _thermal.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _thermal.value = level(power.currentThermalStatus)
            power.addThermalStatusListener(appContext.mainExecutor) { status -> _thermal.value = level(status) }
        }
    }

    val powerSaveMode: Boolean
        get() = power.isPowerSaveMode

    fun memory(): MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(info)
        return MemoryInfo(info.availMem, info.totalMem, info.lowMemory)
    }

    private fun level(status: Int): ThermalLevel =
        when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.Critical
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.Hot
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.Warm
            else -> ThermalLevel.Normal
        }
}

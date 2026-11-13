/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

import android.content.Context
import androidx.core.content.edit
import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.api.SamplingDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All user-tunable server settings. Defaults follow docs/LOCAL_LLM_API.md §6 (Qwen3.5 non-thinking). */
data class ServerSettings(
    val port: Int = LocalApi.DEFAULT_PORT,
    val activeModelId: Long = -1L,
    val contextSize: Int = 8192,
    /** 0 = automatic (number of performance cores). */
    val threads: Int = 0,
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val minP: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val enableThinking: Boolean = false,
    /** 0 = never unload. */
    val idleUnloadMinutes: Int = 10,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val logBodies: Boolean = false,
    val onboardingDone: Boolean = false,
) {
    val samplingDefaults: SamplingDefaults
        get() = SamplingDefaults(temperature, topP, topK, minP, presencePenalty, enableThinking)

    /** Settings that require reloading the model when changed. */
    fun loadKey() = listOf(activeModelId, contextSize, threads, useMmap, useMlock)
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ServerSettings> = _settings.asStateFlow()

    val current: ServerSettings
        get() = _settings.value

    fun update(transform: (ServerSettings) -> ServerSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    /** Resets everything except the model choice and onboarding state. */
    fun restoreDefaults() = update {
        ServerSettings(activeModelId = it.activeModelId, onboardingDone = it.onboardingDone)
    }

    private fun read(): ServerSettings {
        val d = ServerSettings()
        return ServerSettings(
            port = prefs.getInt("port", d.port),
            activeModelId = prefs.getLong("active_model_id", d.activeModelId),
            contextSize = prefs.getInt("context_size", d.contextSize),
            threads = prefs.getInt("threads", d.threads),
            temperature = prefs.getFloat("temperature", d.temperature),
            topP = prefs.getFloat("top_p", d.topP),
            topK = prefs.getInt("top_k", d.topK),
            minP = prefs.getFloat("min_p", d.minP),
            presencePenalty = prefs.getFloat("presence_penalty", d.presencePenalty),
            enableThinking = prefs.getBoolean("enable_thinking", d.enableThinking),
            idleUnloadMinutes = prefs.getInt("idle_unload_minutes", d.idleUnloadMinutes),
            useMmap = prefs.getBoolean("use_mmap", d.useMmap),
            useMlock = prefs.getBoolean("use_mlock", d.useMlock),
            logBodies = prefs.getBoolean("log_bodies", d.logBodies),
            onboardingDone = prefs.getBoolean("onboarding_done", d.onboardingDone),
        )
    }

    private fun write(s: ServerSettings) =
        prefs.edit {
            putInt("port", s.port)
            putLong("active_model_id", s.activeModelId)
            putInt("context_size", s.contextSize)
            putInt("threads", s.threads)
            putFloat("temperature", s.temperature)
            putFloat("top_p", s.topP)
            putInt("top_k", s.topK)
            putFloat("min_p", s.minP)
            putFloat("presence_penalty", s.presencePenalty)
            putBoolean("enable_thinking", s.enableThinking)
            putInt("idle_unload_minutes", s.idleUnloadMinutes)
            putBoolean("use_mmap", s.useMmap)
            putBoolean("use_mlock", s.useMlock)
            putBoolean("log_bodies", s.logBodies)
            putBoolean("onboarding_done", s.onboardingDone)
        }
}

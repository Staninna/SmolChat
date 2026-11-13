/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.ChatCompletionRequest
import io.shubham0204.smollmandroid.api.ChatMessage
import io.shubham0204.smollmandroid.api.RequestValidator
import io.shubham0204.smollmandroid.data.ModelEntity
import io.shubham0204.smollmandroid.data.ModelImportException
import io.shubham0204.smollmandroid.data.ModelRepository
import io.shubham0204.smollmandroid.data.PairedAppEntity
import io.shubham0204.smollmandroid.data.RequestLogDao
import io.shubham0204.smollmandroid.data.RequestLogEntity
import io.shubham0204.smollmandroid.data.ServerSettings
import io.shubham0204.smollmandroid.data.SettingsRepository
import io.shubham0204.smollmandroid.engine.EngineState
import io.shubham0204.smollmandroid.engine.InferenceEngine
import io.shubham0204.smollmandroid.http.HttpServerState
import io.shubham0204.smollmandroid.http.LocalAiServer
import io.shubham0204.smollmandroid.pairing.PairingRepository
import io.shubham0204.smollmandroid.service.ServerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class ImportState(val fileName: String? = null, val progress: Float = 0f, val error: String? = null)

data class PlaygroundState(
    val systemPrompt: String = "You are a helpful assistant. Answer briefly.",
    val prompt: String = "",
    val thinking: Boolean = false,
    val output: String = "",
    val reasoning: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val stats: String? = null,
)

class MainViewModel(
    context: Context,
    private val settingsRepo: SettingsRepository,
    private val modelRepo: ModelRepository,
    private val pairing: PairingRepository,
    private val requestLog: RequestLogDao,
    private val engine: InferenceEngine,
    http: LocalAiServer,
    private val controller: ServerController,
) : ViewModel() {
    private val appContext = context.applicationContext

    val engineState: StateFlow<EngineState> = engine.state
    val httpState: StateFlow<HttpServerState> = http.state
    val settings: StateFlow<ServerSettings> = settingsRepo.settings
    val models: StateFlow<List<ModelEntity>> =
        modelRepo.observeModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pairedApps: StateFlow<List<PairedAppEntity>> =
        pairing.pairedApps.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val log: StateFlow<List<RequestLogEntity>> =
        requestLog.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()
    private var importJob: Job? = null

    private val _playground = MutableStateFlow(PlaygroundState())
    val playground: StateFlow<PlaygroundState> = _playground.asStateFlow()
    private var playgroundJob: Job? = null

    init {
        viewModelScope.launch { engine.refreshIdleState() }
    }

    fun startServer() = controller.start()

    fun stopServer() = controller.stop()

    fun importModel(uri: Uri, fileName: String?) {
        importJob?.cancel()
        _import.value = ImportState(fileName = fileName ?: "model.gguf", progress = 0f)
        importJob =
            viewModelScope.launch {
                try {
                    modelRepo.import(uri) { p -> _import.update { it.copy(progress = p) } }
                    _import.value = ImportState()
                    engine.refreshIdleState()
                } catch (e: ModelImportException) {
                    _import.value = ImportState(error = e.message)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _import.value = ImportState()
                    throw e
                } catch (e: Exception) {
                    _import.value = ImportState(error = e.message ?: "Import failed")
                }
            }
    }

    fun cancelImport() {
        importJob?.cancel()
        _import.value = ImportState()
    }

    fun dismissImportError() = _import.update { it.copy(error = null) }

    fun selectModel(model: ModelEntity) {
        settingsRepo.update { it.copy(activeModelId = model.id) }
        viewModelScope.launch { engine.refreshIdleState() }
    }

    fun deleteModel(model: ModelEntity) {
        viewModelScope.launch {
            if (settings.value.activeModelId == model.id) engine.unload()
            modelRepo.delete(model)
            engine.refreshIdleState()
        }
    }

    fun revoke(app: PairedAppEntity) {
        viewModelScope.launch { pairing.revoke(app.packageName) }
    }

    fun clearLog() {
        viewModelScope.launch { requestLog.clear() }
    }

    fun updateSettings(transform: (ServerSettings) -> ServerSettings) {
        val before = settings.value.logBodies
        settingsRepo.update(transform)
        // turning the debug toggle off deletes stored bodies (docs/LOCAL_LLM_API.md §5)
        if (before && !settings.value.logBodies) viewModelScope.launch { requestLog.clearBodies() }
    }

    fun restoreDefaults() {
        settingsRepo.restoreDefaults()
        viewModelScope.launch { requestLog.clearBodies() }
    }

    fun completeOnboarding() = settingsRepo.update { it.copy(onboardingDone = true) }

    fun updatePlayground(transform: (PlaygroundState) -> PlaygroundState) = _playground.update(transform)

    /** Runs a prompt directly against the engine (same queue as HTTP clients). */
    fun runPlayground() {
        val s = _playground.value
        if (s.prompt.isBlank() || s.running) return
        _playground.update { it.copy(output = "", reasoning = "", running = true, error = null, stats = null) }
        playgroundJob =
            viewModelScope.launch {
                try {
                    val messages = buildList {
                        if (s.systemPrompt.isNotBlank()) add(ChatMessage("system", s.systemPrompt))
                        add(ChatMessage("user", s.prompt))
                    }
                    val request =
                        RequestValidator.resolve(
                            ChatCompletionRequest(
                                messages = messages,
                                chatTemplateKwargs = mapOf("enable_thinking" to JsonPrimitive(s.thinking)),
                                includeThinking = true,
                            ),
                            settings.value.samplingDefaults,
                        )
                    val result =
                        engine.withSlot { session ->
                            session.ensureLoaded()
                            val prepared = session.prepare(request)
                            session.generate(prepared) { content, reasoning ->
                                _playground.update {
                                    it.copy(output = it.output + content, reasoning = it.reasoning + reasoning)
                                }
                            }
                        }
                    _playground.update {
                        it.copy(
                            output = result.content,
                            running = false,
                            stats =
                                "%d prompt + %d generated tokens · %.1f tok/s"
                                    .format(result.promptTokens, result.completionTokens, result.tokensPerSecond),
                        )
                    }
                } catch (e: ApiException) {
                    _playground.update { it.copy(running = false, error = e.message) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _playground.update { it.copy(running = false) }
                    throw e
                } catch (e: Exception) {
                    _playground.update { it.copy(running = false, error = e.message ?: "Something went wrong") }
                }
            }
    }

    fun stopPlayground() {
        playgroundJob?.cancel()
        _playground.update { it.copy(running = false) }
    }
}

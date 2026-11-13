/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.engine

import android.content.Context
import android.os.PowerManager
import android.util.Log
import io.shubham0204.smollm.SmolLM
import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.RequestValidator
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.api.ThinkFilter
import io.shubham0204.smollmandroid.data.ModelDao
import io.shubham0204.smollmandroid.data.ModelEntity
import io.shubham0204.smollmandroid.data.SettingsRepository
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class EngineStatus {
    /** No model imported or selected. */
    NoModel,
    /** A model is selected but not in memory (not started yet, or unloaded after idling). */
    Unloaded,
    Loading,
    Ready,
    Generating,
    /** The last load failed; see [EngineState.error]. */
    Error,
}

data class EngineState(
    val status: EngineStatus = EngineStatus.NoModel,
    val model: ModelEntity? = null,
    val contextSize: Int = 0,
    val threads: Int = 0,
    val ramEstimateBytes: Long = 0,
    val queueLength: Int = 0,
    val lastTokensPerSecond: Double? = null,
    val liveTokensPerSecond: Double? = null,
    val error: String? = null,
)

data class PreparedPrompt(val promptTokens: Int, val maxTokens: Int, val opensThink: Boolean)

data class GenerationResult(
    val content: String,
    val reasoning: String,
    val finishReason: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val promptMs: Double,
    val generationMs: Double,
    val tokensPerSecond: Double,
)

/**
 * Owns the single loaded model and runs one request at a time.
 *
 * Requests wait on a fair [Mutex], which resumes waiters in FIFO order. At most [MAX_WAITING]
 * requests may wait; more get [ApiError.ServerBusy]. All native calls run on one dedicated thread.
 */
class InferenceEngine(
    context: Context,
    private val models: ModelDao,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val nativeDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llm-inference") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + nativeDispatcher)
    private val gate = Mutex()
    private val waiting = AtomicInteger(0)
    private val smolLM = SmolLM()
    private var loadedKey: List<Any>? = null
    private var idleJob: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Refreshes the status shown when nothing is loaded (e.g. after the model choice changed). */
    suspend fun refreshIdleState() {
        if (smolLM.isLoaded) return
        val model = models.get(settings.current.activeModelId) ?: models.getAll().firstOrNull()
        _state.update {
            it.copy(
                status = if (model == null) EngineStatus.NoModel else
                    if (it.status == EngineStatus.Error && it.model?.id == model.id) EngineStatus.Error
                    else EngineStatus.Unloaded,
                model = model,
                ramEstimateBytes = model?.let { m -> RamEstimate.bytes(m, settings.current.contextSize) } ?: 0,
            )
        }
    }

    /**
     * Runs [block] once it is this request's turn.
     *
     * @throws ApiException [ApiError.ServerBusy] when the queue is full.
     */
    suspend fun <T> withSlot(block: suspend (Session) -> T): T {
        if (waiting.incrementAndGet() > MAX_WAITING + 1) {
            waiting.decrementAndGet()
            throw ApiException(ApiError.ServerBusy, "The local AI is busy. Try again in a few seconds.")
        }
        publishQueue()
        try {
            return gate.withLock {
                idleJob?.cancel()
                try {
                    block(Session())
                } finally {
                    scheduleIdleUnload()
                }
            }
        } finally {
            waiting.decrementAndGet()
            publishQueue()
        }
    }

    /** Loads the active model in the background (used when the service starts). */
    fun preload() {
        scope.launch {
            try {
                withSlot { it.ensureLoaded() }
            } catch (e: ApiException) {
                Log.w(TAG, "preload: ${e.message}")
            }
        }
    }

    /** Unloads the model now (Stop action, model switch). */
    suspend fun unload() {
        gate.withLock { unloadLocked() }
        refreshIdleState()
    }

    private suspend fun unloadLocked() =
        withContext(nativeDispatcher) {
            if (smolLM.isLoaded) {
                smolLM.close()
                Log.i(TAG, "model unloaded")
            }
            loadedKey = null
            _state.update { it.copy(status = EngineStatus.Unloaded, liveTokensPerSecond = null) }
        }

    private fun scheduleIdleUnload() {
        val minutes = settings.current.idleUnloadMinutes
        if (minutes <= 0) return
        idleJob?.cancel()
        idleJob =
            scope.launch {
                delay(minutes * 60_000L)
                if (waiting.get() == 0) {
                    gate.withLock { unloadLocked() }
                }
            }
    }

    private fun publishQueue() {
        // the running request is not "waiting"
        val queued = (waiting.get() - if (gate.isLocked) 1 else 0).coerceAtLeast(0)
        _state.update { it.copy(queueLength = queued) }
    }

    /** Operations that are only valid while holding the slot. */
    inner class Session internal constructor() {
        /**
         * Makes sure the active model is loaded with the current settings.
         *
         * @throws ApiException [ApiError.ModelNotLoaded] if no model is selected or loading fails.
         */
        suspend fun ensureLoaded(): ModelEntity =
            withContext(nativeDispatcher) {
                val s = settings.current
                val model =
                    models.get(s.activeModelId)
                        ?: models.getAll().firstOrNull()?.also { first ->
                            settings.update { it.copy(activeModelId = first.id) }
                        }
                        ?: run {
                            _state.update { it.copy(status = EngineStatus.NoModel, model = null) }
                            throw ApiException(
                                ApiError.ModelNotLoaded,
                                "No model is set up. Open the server app and import a GGUF model.",
                            )
                        }
                val key = settings.current.loadKey() + model.id
                if (smolLM.isLoaded && loadedKey == key) return@withContext model
                if (smolLM.isLoaded) smolLM.close()
                if (!File(model.path).exists()) {
                    fail(model, "The model file is missing. Import it again.")
                }
                val threads = s.threads.takeIf { it > 0 } ?: CpuInfo.performanceCoreCount()
                _state.update {
                    it.copy(
                        status = EngineStatus.Loading,
                        model = model,
                        contextSize = s.contextSize,
                        threads = threads,
                        ramEstimateBytes = RamEstimate.bytes(model, s.contextSize),
                        error = null,
                    )
                }
                try {
                    smolLM.load(
                        model.path,
                        SmolLM.InferenceParams(
                            minP = s.minP,
                            temperature = s.temperature,
                            storeChats = false,
                            contextSize = s.contextSize.toLong(),
                            chatTemplate = null,
                            numThreads = threads,
                            useMmap = s.useMmap,
                            useMlock = s.useMlock,
                        ),
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to load ${model.path}", e)
                    smolLM.close()
                    fail(model, "Could not load ${model.displayName}: ${e.message ?: e.javaClass.simpleName}")
                }
                loadedKey = key
                _state.update { it.copy(status = EngineStatus.Ready, error = null) }
                Log.i(TAG, "loaded ${model.displayName} n_ctx=${s.contextSize} threads=$threads")
                model
            }

        private fun fail(model: ModelEntity, message: String): Nothing {
            loadedKey = null
            _state.update { it.copy(status = EngineStatus.Error, model = model, error = message) }
            throw ApiException(ApiError.ModelNotLoaded, message)
        }

        /**
         * Renders the chat template and checks the context window.
         *
         * @throws ApiException [ApiError.ContextLengthExceeded]
         */
        suspend fun prepare(request: ResolvedRequest): PreparedPrompt =
            withContext(nativeDispatcher) {
                smolLM.clearMessages()
                for (m in request.messages) {
                    when (m.role) {
                        "system" -> smolLM.addSystemPrompt(m.content)
                        "assistant" -> smolLM.addAssistantMessage(m.content)
                        else -> smolLM.addUserMessage(m.content)
                    }
                }
                smolLM.setSamplingParams(
                    SmolLM.SamplingParams(
                        temperature = request.temperature,
                        topP = request.topP,
                        topK = request.topK,
                        minP = request.minP,
                        presencePenalty = request.presencePenalty,
                        seed = request.seed,
                    )
                )
                try {
                    smolLM.prepareChatCompletion(request.enableThinking)
                } catch (e: IllegalStateException) {
                    throw ApiException(ApiError.InvalidRequest, "Could not apply the chat template: ${e.message}")
                }
                val promptTokens = smolLM.getPromptTokenCount()
                val ctx = smolLM.getContextSize()
                RequestValidator.checkContext(promptTokens, request.maxTokens, ctx)
                PreparedPrompt(
                    promptTokens = promptTokens,
                    maxTokens = request.maxTokens ?: (ctx - promptTokens),
                    opensThink = smolLM.promptOpensThink(),
                )
            }

        /**
         * Generates the response. [onDelta] receives visible content and reasoning as they are
         * produced (`<think>` blocks are already separated). Stops early when the calling coroutine
         * is cancelled (client went away).
         */
        suspend fun generate(
            prepared: PreparedPrompt,
            onDelta: suspend (content: String, reasoning: String) -> Unit = { _, _ -> },
        ): GenerationResult =
            withContext(nativeDispatcher) {
                val wakeLock =
                    (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmolChat:generate")
                wakeLock.acquire(10 * 60_000L)
                _state.update { it.copy(status = EngineStatus.Generating, liveTokensPerSecond = null) }
                val filter = ThinkFilter(startInThink = prepared.opensThink)
                val content = StringBuilder()
                val reasoning = StringBuilder()
                var tokens = 0
                var finishReason = "length"
                val start = System.nanoTime()
                var firstTokenAt = 0L
                try {
                    while (tokens < prepared.maxTokens) {
                        coroutineContext.ensureActive()
                        val piece =
                            try {
                                smolLM.nextToken()
                            } catch (e: IllegalStateException) {
                                if (e.message?.contains("context size reached") == true) {
                                    finishReason = "length"
                                    break
                                }
                                throw ApiException(ApiError.InternalError, "Inference failed: ${e.message}")
                            }
                        if (piece == null) {
                            finishReason = "stop"
                            break
                        }
                        tokens++
                        if (tokens == 1) firstTokenAt = System.nanoTime()
                        val out = filter.push(piece)
                        emit(out, content, reasoning, onDelta)
                        if (tokens % 8 == 0) {
                            val secs = (System.nanoTime() - firstTokenAt) / 1e9
                            if (secs > 0) _state.update { it.copy(liveTokensPerSecond = (tokens - 1) / secs) }
                        }
                    }
                    emit(filter.finish(), content, reasoning, onDelta)
                } finally {
                    smolLM.finishCompletion()
                    if (wakeLock.isHeld) wakeLock.release()
                    _state.update { it.copy(status = EngineStatus.Ready, liveTokensPerSecond = null) }
                }
                val end = System.nanoTime()
                val promptMs = if (firstTokenAt > 0) (firstTokenAt - start) / 1e6 else (end - start) / 1e6
                val genMs = if (firstTokenAt > 0) (end - firstTokenAt) / 1e6 else 0.0
                val tps = smolLM.getResponseGenerationSpeed().toDouble()
                _state.update { it.copy(lastTokensPerSecond = tps) }
                GenerationResult(
                    content = content.toString().trim(),
                    reasoning = reasoning.toString().trim(),
                    finishReason = finishReason,
                    promptTokens = prepared.promptTokens,
                    completionTokens = tokens,
                    promptMs = promptMs,
                    generationMs = genMs,
                    tokensPerSecond = tps,
                )
            }

        private suspend fun emit(
            out: ThinkFilter.Output,
            content: StringBuilder,
            reasoning: StringBuilder,
            onDelta: suspend (String, String) -> Unit,
        ) {
            if (out.content.isEmpty() && out.reasoning.isEmpty()) return
            content.append(out.content)
            reasoning.append(out.reasoning)
            onDelta(out.content, out.reasoning)
        }
    }

    companion object {
        private const val TAG = "InferenceEngine"
        const val MAX_WAITING = 4
    }
}

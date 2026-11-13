/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.RequestValidator
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.data.ModelEntity
import io.shubham0204.smollmandroid.data.PairedAppEntity
import io.shubham0204.smollmandroid.data.PairedAppDao
import io.shubham0204.smollmandroid.data.RequestLogDao
import io.shubham0204.smollmandroid.data.RequestLogEntity
import io.shubham0204.smollmandroid.data.StatRow
import io.shubham0204.smollmandroid.data.JobDao
import io.shubham0204.smollmandroid.data.JobEntity
import io.shubham0204.smollmandroid.engine.Checkpoint
import io.shubham0204.smollmandroid.engine.EngineState
import io.shubham0204.smollmandroid.engine.EngineStatus
import io.shubham0204.smollmandroid.engine.GenerationBackend
import io.shubham0204.smollmandroid.engine.GenerationProgress
import io.shubham0204.smollmandroid.engine.GenerationResult
import io.shubham0204.smollmandroid.engine.GenerationSession
import io.shubham0204.smollmandroid.engine.PreparedPrompt
import io.shubham0204.smollmandroid.jobs.WakeHold
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

val testModel =
    ModelEntity(
        id = 1,
        displayName = "Test-Model",
        path = "/dev/null",
        sizeBytes = 1,
        architecture = "test",
        trainedContextLength = 4096,
        blockCount = 1,
        headCountKv = 1,
        keyLength = 1,
        valueLength = 1,
        fullAttentionInterval = null,
        addedAt = 0,
    )

/** Stands in for the model: one request at a time, a slow prompt and a slow stream of words. */
class FakeBackend(
    private val words: List<String> = listOf("Good", " morning", ",", " world", "!"),
    private val promptTokens: Int = 48,
    private val promptStepMs: Long = 5,
    private val tokenMs: Long = 5,
    private val contextSize: Int = 4096,
) : GenerationBackend {
    override val state = MutableStateFlow(EngineState(status = EngineStatus.Ready, model = testModel))
    private val gate = Mutex()

    /** Set to make the model load on the next request, as after an idle unload. */
    @Volatile var loadMs: Long = 0
    @Volatile var failure: ApiException? = null
    val started = CopyOnWriteArrayList<String>()
    val cancelled = CopyOnWriteArrayList<String>()

    /** Token counts at which a run continued an interrupted answer. */
    val resumedAt = CopyOnWriteArrayList<Int>()

    override suspend fun <T> withSlot(block: suspend (GenerationSession) -> T): T = gate.withLock { block(Session()) }

    private inner class Session : GenerationSession {
        private var label = ""

        override suspend fun ensureLoaded(onLoading: () -> Unit): ModelEntity {
            failure?.let { throw it }
            if (loadMs > 0) {
                onLoading()
                delay(loadMs)
                loadMs = 0
            }
            return testModel
        }

        override suspend fun prepare(request: ResolvedRequest, resumeFrom: Checkpoint?): PreparedPrompt {
            label = request.messages.last().content
            RequestValidator.checkContext(promptTokens, request.maxTokens, contextSize)
            return PreparedPrompt(
                promptTokens,
                request.maxTokens ?: (contextSize - promptTokens),
                opensThink = false,
                cachedTokens = 16,
                resumed = resumeFrom,
            )
        }

        override suspend fun generate(
            prepared: PreparedPrompt,
            progress: GenerationProgress?,
            onDelta: suspend (content: String, reasoning: String) -> Unit,
        ): GenerationResult {
            started += label
            val text = StringBuilder()
            var count = 0
            try {
                // like the real engine: replay what an interrupted run had written, then carry on
                prepared.resumed?.let {
                    resumedAt += it.tokens
                    count = it.tokens
                    text.append(it.rawText)
                    onDelta(it.rawText, "")
                }
                var processed = prepared.cachedTokens
                while (processed < prepared.promptTokens) {
                    coroutineContext.ensureActive()
                    delay(promptStepMs)
                    processed = (processed + 16).coerceAtMost(prepared.promptTokens)
                    progress?.onPrompt(processed, prepared.promptTokens, prepared.cachedTokens)
                }
                for (word in words.take(prepared.maxTokens).drop(count)) {
                    coroutineContext.ensureActive()
                    delay(tokenMs)
                    count++
                    progress?.onRaw(word)
                    progress?.onTokens(count, 20.0)
                    text.append(word)
                    onDelta(word, "")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += label
                throw e
            }
            return GenerationResult(
                content = text.toString(),
                reasoning = "",
                finishReason = if (count < words.size) "length" else "stop",
                promptTokens = prepared.promptTokens,
                cachedTokens = prepared.cachedTokens,
                completionTokens = count,
                promptMs = 10.0,
                generationMs = 20.0,
                tokensPerSecond = 20.0,
            )
        }
    }
}

class FakeRequestLog : RequestLogDao {
    val rows = CopyOnWriteArrayList<RequestLogEntity>()

    override fun observeRecent(limit: Int): Flow<List<RequestLogEntity>> = flowOf(rows.toList())

    override suspend fun insert(entry: RequestLogEntity) {
        rows += entry
    }

    override suspend fun trim(keep: Int) {}

    override fun observeStatRows(since: Long): Flow<List<StatRow>> = flowOf(emptyList())

    override suspend fun clearBodies() {}

    override suspend fun clear() = rows.clear()
}

class FakePairedApps(private val apps: MutableMap<String, PairedAppEntity> = mutableMapOf()) : PairedAppDao {
    fun add(app: PairedAppEntity) {
        apps[app.packageName] = app
    }

    override fun observeAll(): Flow<List<PairedAppEntity>> = flowOf(apps.values.toList())

    override suspend fun findByTokenHash(hash: String): PairedAppEntity? = apps.values.firstOrNull { it.tokenHash == hash }

    override suspend fun upsert(app: PairedAppEntity) = add(app)

    override suspend fun recordUse(packageName: String, time: Long) {
        apps[packageName]?.let { apps[packageName] = it.copy(lastUsedAt = time, requestCount = it.requestCount + 1) }
    }

    override suspend fun revoke(packageName: String) {
        apps.remove(packageName)
    }
}

class FakeWakeHold : WakeHold {
    @Volatile var held = false
    @Volatile var acquisitions = 0

    override fun acquire() {
        held = true
        acquisitions++
    }

    override fun release() {
        held = false
    }
}

/** The jobs table. Hand the same instance to a second JobManager to play "the process restarted". */
class FakeJobDao : JobDao {
    val rows = java.util.concurrent.ConcurrentHashMap<String, JobEntity>()

    override suspend fun upsert(job: JobEntity) {
        rows[job.id] = job
    }

    override suspend fun getAll(): List<JobEntity> = rows.values.sortedBy { it.createdAt }

    override suspend fun checkpoint(id: String, text: String, tokens: Int, phase: String) {
        rows.computeIfPresent(id) { _, row -> row.copy(checkpointText = text, checkpointTokens = tokens, phase = phase) }
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun deleteFinishedBefore(before: Long) {
        rows.values.filter { (it.finishedAt ?: Long.MAX_VALUE) < before }.forEach { rows.remove(it.id) }
    }
}

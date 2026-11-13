/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.jobs

import android.content.Context
import android.os.PowerManager
import android.util.Log
import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.data.RequestLogDao
import io.shubham0204.smollmandroid.data.RequestLogEntity
import io.shubham0204.smollmandroid.engine.GenerationProgress
import io.shubham0204.smollmandroid.engine.GenerationResult
import io.shubham0204.smollmandroid.engine.GenerationBackend
import io.shubham0204.smollmandroid.engine.InferenceEngine
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps the CPU running while jobs are queued or running. */
interface WakeHold {
    fun acquire()

    fun release()
}

/** A partial wake lock: the processor stays on with the screen off, from queue to result. */
class PartialWakeLock(context: Context) : WakeHold {
    private val lock =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmolChat:jobs")
            .apply { setReferenceCounted(false) }

    // Not reference counted and taken again for every job, so the timeout is only a safety net for
    // a native call that never returns.
    override fun acquire() = lock.acquire(TIMEOUT_MS)

    override fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TIMEOUT_MS = 60 * 60_000L
    }
}

enum class JobPhase(val wire: String, val terminal: Boolean = false) {
    Queued("queued"),
    LoadingModel("loading_model"),
    ReadingPrompt("processing_prompt"),
    Generating("generating"),
    Completed("completed", terminal = true),
    Failed("failed", terminal = true),
    Cancelled("cancelled", terminal = true),
}

/** How the result travels back to the client. */
enum class JobMode(val wire: String) {
    /** The HTTP response is the result; a dropped connection cancels the job. */
    Blocking("blocking"),
    /** Server-sent events; a dropped connection cancels the job. */
    Stream("stream"),
    /** The job keeps running without a connection; the client polls `/v1/jobs/{id}`. */
    Background("background"),
    /** Started from the Playground screen. */
    Playground("playground"),
}

/** Everything the UI, the notification and `/v1/jobs/{id}` show about one generation. */
data class JobSnapshot(
    val id: String,
    val clientPackage: String?,
    val clientLabel: String,
    val mode: JobMode,
    val phase: JobPhase = JobPhase.Queued,
    val createdAt: Long,
    /** When the job got the inference slot (left the queue). */
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val model: String? = null,
    val promptTokens: Int = 0,
    val promptProcessed: Int = 0,
    val cachedTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Upper bound for [completionTokens]: `max_tokens`, or the room left in the context window. */
    val maxTokens: Int? = null,
    /** True when the client sent `max_tokens`, so [maxTokens] is a meaningful target. */
    val maxTokensFromClient: Boolean = false,
    val tokensPerSecond: Double? = null,
    /** Tail of the visible output. Kept in memory only, never stored. */
    val preview: String = "",
    val loadMs: Long? = null,
    val promptStartedAt: Long? = null,
    val generationStartedAt: Long? = null,
    val finishReason: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val queueMs: Long?
        get() = startedAt?.let { it - createdAt }
}

sealed interface JobOutcome {
    data class Success(val result: GenerationResult, val model: String) : JobOutcome

    data class Failure(val error: ApiError, val message: String) : JobOutcome

    data object Cancelled : JobOutcome
}

sealed interface JobEvent {
    /** The prompt fits and generation is about to start: from here on errors are in-band. */
    data class Started(val model: String) : JobEvent

    data class Delta(val content: String, val reasoning: String) : JobEvent

    data class Finished(val outcome: JobOutcome) : JobEvent
}

class ServerJob
internal constructor(
    initial: JobSnapshot,
    val request: ResolvedRequest,
    /** Only for [JobMode.Stream] and [JobMode.Playground]. */
    val events: Channel<JobEvent>?,
    internal val requestBody: String?,
) {
    internal val mutableState = MutableStateFlow(initial)
    val state: StateFlow<JobSnapshot> = mutableState.asStateFlow()
    internal val outcomeDeferred = CompletableDeferred<JobOutcome>()
    internal var coroutine: Job? = null
    internal val text = StringBuilder()

    val id: String
        get() = state.value.id

    /** The finished outcome, or null while the job is queued or running. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val outcome: JobOutcome?
        get() = if (outcomeDeferred.isCompleted) outcomeDeferred.getCompleted() else null

    suspend fun await(): JobOutcome = outcomeDeferred.await()
}

/**
 * Runs every generation as a job that does not depend on the connection that asked for it.
 *
 * The inference engine still runs one request at a time in FIFO order; this class adds what makes
 * long generations survive on a phone: jobs outlive a dropped or frozen client ([JobMode.Background]),
 * the CPU stays awake from queue to result, every phase is observable, and each job writes one
 * request-log row when it ends.
 */
class JobManager(
    private val engine: GenerationBackend,
    private val requestLog: RequestLogDao,
    private val wakeLock: WakeHold,
    private val logBodies: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val jobs = LinkedHashMap<String, ServerJob>()
    private val random = SecureRandom()

    private val _active = MutableStateFlow<List<JobSnapshot>>(emptyList())

    /** Queued and running jobs, oldest first. */
    val active: StateFlow<List<JobSnapshot>> = _active.asStateFlow()

    private val _wakeLockHeld = MutableStateFlow(false)
    val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

    /**
     * Queues a generation and returns at once.
     *
     * @throws ApiException [ApiError.ServerBusy] when the queue is full.
     */
    fun submit(
        clientPackage: String?,
        clientLabel: String,
        request: ResolvedRequest,
        mode: JobMode,
        requestBody: String? = null,
    ): ServerJob {
        val job: ServerJob
        synchronized(lock) {
            purgeLocked()
            val running = jobs.values.count { !it.state.value.phase.terminal }
            if (running > InferenceEngine.MAX_WAITING) {
                throw ApiException(ApiError.ServerBusy, "The local AI is busy. Try again in a few seconds.")
            }
            job =
                ServerJob(
                    JobSnapshot(
                        id = newId(),
                        clientPackage = clientPackage,
                        clientLabel = clientLabel,
                        mode = mode,
                        createdAt = System.currentTimeMillis(),
                        maxTokens = request.maxTokens,
                        maxTokensFromClient = request.maxTokens != null,
                    ),
                    request,
                    if (mode == JobMode.Stream || mode == JobMode.Playground) Channel(Channel.UNLIMITED) else null,
                    requestBody,
                )
            jobs[job.id] = job
            holdWakeLockLocked()
            publishLocked()
        }
        job.coroutine = scope.launch { run(job) }
        return job
    }

    /** The job with this id, if it belongs to [clientPackage]. */
    fun find(id: String, clientPackage: String?): ServerJob? =
        synchronized(lock) {
            purgeLocked()
            jobs[id]?.takeIf { it.state.value.clientPackage == clientPackage }
        }

    /** Position in the waiting line (1 = next), or null when the job is not waiting. */
    fun queuePosition(id: String): Int? =
        synchronized(lock) {
            val waiting = jobs.values.filter { it.state.value.phase == JobPhase.Queued }
            waiting.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
        }

    fun cancel(id: String) {
        synchronized(lock) { jobs[id] }?.let(::cancel)
    }

    fun cancel(job: ServerJob) {
        job.coroutine?.cancel()
    }

    /** Cancels everything and waits until the engine is free (server stop). */
    suspend fun cancelAll() {
        val running = synchronized(lock) { jobs.values.toList() }
        running.forEach { it.coroutine?.cancel() }
        running.forEach { it.coroutine?.join() }
    }

    private suspend fun run(job: ServerJob) {
        val outcome =
            try {
                engine.withSlot { session ->
                    job.update { it.copy(startedAt = System.currentTimeMillis()) }
                    var loadStart = 0L
                    val model =
                        session.ensureLoaded(
                            onLoading = {
                                loadStart = System.currentTimeMillis()
                                job.update { it.copy(phase = JobPhase.LoadingModel) }
                            }
                        )
                    val loadMs = if (loadStart > 0) System.currentTimeMillis() - loadStart else null
                    val prepared = session.prepare(job.request)
                    job.update {
                        it.copy(
                            phase = JobPhase.ReadingPrompt,
                            model = model.displayName,
                            loadMs = loadMs,
                            promptTokens = prepared.promptTokens,
                            promptProcessed = prepared.cachedTokens,
                            cachedTokens = prepared.cachedTokens,
                            maxTokens = prepared.maxTokens,
                            promptStartedAt = System.currentTimeMillis(),
                        )
                    }
                    job.events?.send(JobEvent.Started(model.displayName))
                    val progress =
                        object : GenerationProgress {
                            override fun onPrompt(processed: Int, total: Int, cached: Int) {
                                job.update { it.copy(promptProcessed = processed) }
                            }

                            override fun onTokens(count: Int, tokensPerSecond: Double?) {
                                job.update {
                                    it.copy(
                                        phase = JobPhase.Generating,
                                        promptProcessed = it.promptTokens,
                                        completionTokens = count,
                                        tokensPerSecond = tokensPerSecond ?: it.tokensPerSecond,
                                        generationStartedAt = it.generationStartedAt ?: System.currentTimeMillis(),
                                    )
                                }
                            }
                        }
                    val result =
                        session.generate(prepared, progress) { content, reasoning ->
                            if (content.isNotEmpty()) {
                                job.text.append(content)
                                val preview = job.text.takeLast(PREVIEW_CHARS).toString()
                                job.update { it.copy(preview = preview) }
                            }
                            job.events?.send(JobEvent.Delta(content, reasoning))
                        }
                    JobOutcome.Success(result, model.displayName)
                }
            } catch (e: ApiException) {
                JobOutcome.Failure(e.error, e.message)
            } catch (e: CancellationException) {
                JobOutcome.Cancelled
            } catch (e: Exception) {
                Log.e(TAG, "job ${job.id} failed", e)
                JobOutcome.Failure(ApiError.InternalError, e.message ?: "Internal error")
            }
        withContext(NonCancellable) { finish(job, outcome) }
    }

    private suspend fun finish(job: ServerJob, outcome: JobOutcome) {
        val now = System.currentTimeMillis()
        job.update {
            when (outcome) {
                is JobOutcome.Success ->
                    it.copy(
                        phase = JobPhase.Completed,
                        finishedAt = now,
                        completionTokens = outcome.result.completionTokens,
                        promptProcessed = it.promptTokens,
                        tokensPerSecond = outcome.result.tokensPerSecond.takeIf { v -> v > 0 } ?: it.tokensPerSecond,
                        finishReason = outcome.result.finishReason,
                    )
                is JobOutcome.Failure ->
                    it.copy(
                        phase = JobPhase.Failed,
                        finishedAt = now,
                        errorCode = outcome.error.code,
                        errorMessage = outcome.message,
                    )
                JobOutcome.Cancelled -> it.copy(phase = JobPhase.Cancelled, finishedAt = now, errorCode = CLIENT_CLOSED)
            }
        }
        job.outcomeDeferred.complete(outcome)
        job.events?.let {
            it.trySend(JobEvent.Finished(outcome))
            it.close()
        }
        synchronized(lock) {
            // only background results are fetched later; everything else was delivered just now
            if (job.state.value.mode != JobMode.Background) jobs.remove(job.id)
            if (jobs.values.none { !it.state.value.phase.terminal }) releaseWakeLockLocked()
            publishLocked()
        }
        runCatching {
            requestLog.insert(logEntry(job, outcome))
            requestLog.trim()
        }
            .onFailure { Log.w(TAG, "could not write the request log", it) }
    }

    private fun logEntry(job: ServerJob, outcome: JobOutcome): RequestLogEntity {
        val s = job.state.value
        val result = (outcome as? JobOutcome.Success)?.result
        val logBodies = logBodies()
        return RequestLogEntity(
            timestamp = s.createdAt,
            clientPackage = s.clientPackage,
            clientLabel = s.clientLabel,
            method = "POST",
            path = CHAT_PATH,
            status =
                when (outcome) {
                    is JobOutcome.Success -> 200
                    is JobOutcome.Failure -> outcome.error.httpStatus
                    JobOutcome.Cancelled -> 499
                },
            errorCode = s.errorCode,
            stream = s.mode == JobMode.Stream,
            promptTokens = result?.promptTokens ?: s.promptTokens.takeIf { it > 0 },
            completionTokens = result?.completionTokens ?: s.completionTokens.takeIf { it > 0 },
            durationMs = (s.finishedAt ?: System.currentTimeMillis()) - s.createdAt,
            tokensPerSecond = result?.tokensPerSecond?.takeIf { it > 0 } ?: s.tokensPerSecond,
            requestBody = job.requestBody.takeIf { logBodies },
            responseBody = result?.content.takeIf { logBodies },
            mode = s.mode.wire,
            model = s.model,
            queueMs = s.queueMs,
            loadMs = s.loadMs,
            promptMs =
                result?.promptMs?.toLong()
                    ?: s.promptStartedAt?.let { (s.generationStartedAt ?: s.finishedAt ?: it) - it },
            generationMs =
                result?.generationMs?.toLong()
                    ?: s.generationStartedAt?.let { (s.finishedAt ?: it) - it },
            cachedTokens = s.cachedTokens.takeIf { s.promptTokens > 0 },
            finishReason = s.finishReason,
        )
    }

    private fun ServerJob.update(transform: (JobSnapshot) -> JobSnapshot) {
        mutableState.update(transform)
        synchronized(lock) { publishLocked() }
    }

    private fun publishLocked() {
        _active.value = jobs.values.map { it.state.value }.filter { !it.phase.terminal }
    }

    /** Drops finished background jobs that nobody fetched within [RETAIN_MS], and the oldest beyond [RETAIN_MAX]. */
    private fun purgeLocked() {
        val now = System.currentTimeMillis()
        val finished = jobs.values.filter { it.state.value.phase.terminal }
        val expired = finished.filter { now - (it.state.value.finishedAt ?: now) > RETAIN_MS }
        val overflow = (finished - expired.toSet()).dropLast(RETAIN_MAX)
        (expired + overflow).forEach { jobs.remove(it.id) }
    }

    private fun holdWakeLockLocked() {
        wakeLock.acquire()
        _wakeLockHeld.value = true
    }

    private fun releaseWakeLockLocked() {
        wakeLock.release()
        _wakeLockHeld.value = false
    }

    private fun newId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "chatcmpl-" + bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "JobManager"
        const val CHAT_PATH = "/v1/chat/completions"
        const val CLIENT_CLOSED = "client_closed"
        private const val PREVIEW_CHARS = 600
        private const val RETAIN_MS = 30 * 60_000L
        private const val RETAIN_MAX = 16
    }
}

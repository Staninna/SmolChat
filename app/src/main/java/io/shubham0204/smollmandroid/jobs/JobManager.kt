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
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import io.shubham0204.smollmandroid.engine.Checkpoint
import io.shubham0204.smollmandroid.data.JobEntity
import io.shubham0204.smollmandroid.data.JobDao
import io.shubham0204.smollmandroid.api.LocalApi
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
    /** The client's own id for this request (idempotency key). */
    val requestId: String? = null,
    /** How often the job was picked up again after the server was stopped or killed. */
    val resumes: Int = 0,
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
    /** Where to continue, for a job restored from disk. */
    internal val resumeFrom: Checkpoint? = null,
) {
    internal val mutableState = MutableStateFlow(initial)
    val state: StateFlow<JobSnapshot> = mutableState.asStateFlow()
    internal val outcomeDeferred = CompletableDeferred<JobOutcome>()
    internal var coroutine: Job? = null
    internal val text = StringBuilder()

    // checkpoint bookkeeping, only touched from the generation
    internal val raw = StringBuilder(resumeFrom?.rawText.orEmpty())
    internal var rawTokens = resumeFrom?.tokens ?: 0
    internal var checkpointTokens = resumeFrom?.tokens ?: 0
    internal var checkpointAt = 0L

    /** Set when the server stops: the job is parked on disk and continues at the next start. */
    @Volatile internal var parked = false

    val id: String
        get() = state.value.id

    /** The visible text written so far. */
    val content: String
        get() = synchronized(text) { text.toString() }

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
 * long generations survive on a phone:
 * - a job outlives a dropped or frozen client ([JobMode.Background]);
 * - a client can name its request (`request_id`) and finds exactly that job again, however often it
 *   reconnects or repeats the request;
 * - background jobs are on disk ([JobDao]) with a checkpoint of the answer so far, so a job that
 *   was running when the server was stopped or killed continues where it was at the next start;
 * - the CPU stays awake from queue to result, every phase is observable, and each job writes one
 *   request-log row when it ends.
 */
class JobManager(
    private val engine: GenerationBackend,
    private val requestLog: RequestLogDao,
    private val store: JobDao,
    private val wakeLock: WakeHold,
    private val logBodies: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val jobs = LinkedHashMap<String, ServerJob>()
    private val random = SecureRandom()
    private val restoreGate = Mutex()
    private var restored = false
    private var wakeRenewal: Job? = null

    private val _active = MutableStateFlow<List<JobSnapshot>>(emptyList())

    /** Queued and running jobs, oldest first. */
    val active: StateFlow<List<JobSnapshot>> = _active.asStateFlow()

    private val _wakeLockHeld = MutableStateFlow(false)
    val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

    /**
     * Queues a generation and returns at once. A background request that carries a `request_id`
     * this client used before returns that job (running or finished) instead of a new one.
     *
     * @throws ApiException [ApiError.ServerBusy] when the queue is full.
     */
    suspend fun submit(
        clientPackage: String?,
        clientLabel: String,
        request: ResolvedRequest,
        mode: JobMode,
        requestBody: String? = null,
    ): ServerJob {
        val requestId = request.requestId.takeIf { mode == JobMode.Background }
        if (requestId != null) restore() // the job may be on disk from before a restart
        val job: ServerJob
        synchronized(lock) {
            purgeLocked()
            if (requestId != null) {
                jobs.values
                    .firstOrNull { it.state.value.clientPackage == clientPackage && it.state.value.requestId == requestId }
                    ?.let { return it }
            }
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
                        requestId = requestId,
                    ),
                    request,
                    if (mode == JobMode.Stream || mode == JobMode.Playground) Channel(Channel.UNLIMITED) else null,
                    requestBody,
                )
            jobs[job.id] = job
            holdWakeLockLocked()
            publishLocked()
        }
        // on disk before the client hears about it: from here on the job cannot get lost
        if (mode == JobMode.Background) persist(job, null)
        job.coroutine = scope.launch { run(job) }
        return job
    }

    /**
     * Loads the jobs that were on disk when the process started: finished ones can be fetched again,
     * unfinished ones go back into the queue and continue from their checkpoint. Runs once.
     */
    suspend fun restore() {
        restoreGate.withLock {
            if (restored) return
            val rows =
                try {
                    store.deleteFinishedBefore(System.currentTimeMillis() - RETAIN_MS)
                    store.getAll()
                } catch (e: Exception) {
                    Log.w(TAG, "could not read stored jobs", e)
                    return
                }
            for (row in rows) {
                val request =
                    try {
                        LocalApi.json.decodeFromString<ResolvedRequest>(row.requestJson)
                    } catch (e: Exception) {
                        Log.w(TAG, "dropping unreadable job ${row.id}", e)
                        runCatching { store.delete(row.id) }
                        continue
                    }
                val phase = JobPhase.entries.firstOrNull { it.name == row.phase } ?: JobPhase.Queued
                val unfinished = !phase.terminal
                val snapshot =
                    JobSnapshot(
                        id = row.id,
                        clientPackage = row.clientPackage,
                        clientLabel = row.clientLabel,
                        mode = JobMode.Background,
                        phase = if (unfinished) JobPhase.Queued else phase,
                        createdAt = row.createdAt,
                        finishedAt = row.finishedAt,
                        model = row.model,
                        promptTokens = row.promptTokens,
                        promptProcessed = if (unfinished) 0 else row.promptTokens,
                        cachedTokens = row.cachedTokens,
                        completionTokens = if (unfinished) row.checkpointTokens else row.completionTokens,
                        maxTokens = request.maxTokens,
                        maxTokensFromClient = request.maxTokens != null,
                        tokensPerSecond = row.tokensPerSecond,
                        finishReason = row.finishReason,
                        errorCode = row.errorCode,
                        errorMessage = row.errorMessage,
                        requestId = row.requestId,
                        resumes = row.resumes + if (unfinished) 1 else 0,
                    )
                val checkpoint =
                    row.checkpointText?.takeIf { unfinished && it.isNotEmpty() && row.checkpointTokens > 0 }
                        ?.let { Checkpoint(it, row.checkpointTokens) }
                val job = ServerJob(snapshot, request, null, null, checkpoint)
                if (!unfinished) job.outcomeDeferred.complete(outcomeOf(row, phase))
                synchronized(lock) {
                    if (jobs.containsKey(job.id)) return@synchronized
                    jobs[job.id] = job
                    if (unfinished) {
                        holdWakeLockLocked()
                        job.coroutine = scope.launch { run(job) }
                    }
                    publishLocked()
                }
                if (unfinished) Log.i(TAG, "resuming job ${job.id} at ${row.checkpointTokens} tokens")
            }
            restored = true
        }
    }

    private fun outcomeOf(row: JobEntity, phase: JobPhase): JobOutcome =
        when (phase) {
            JobPhase.Completed ->
                JobOutcome.Success(
                    GenerationResult(
                        content = row.content.orEmpty(),
                        reasoning = row.reasoning.orEmpty(),
                        finishReason = row.finishReason ?: "stop",
                        promptTokens = row.promptTokens,
                        cachedTokens = row.cachedTokens,
                        completionTokens = row.completionTokens,
                        promptMs = row.promptMs ?: 0.0,
                        generationMs = row.generationMs ?: 0.0,
                        tokensPerSecond = row.tokensPerSecond ?: 0.0,
                    ),
                    row.model.orEmpty(),
                )
            JobPhase.Failed ->
                JobOutcome.Failure(
                    ApiError.entries.firstOrNull { it.code == row.errorCode } ?: ApiError.InternalError,
                    row.errorMessage ?: "The request failed.",
                )
            else -> JobOutcome.Cancelled
        }

    /** The job with this id (the server's, or the client's own `request_id`), if it belongs to [clientPackage]. */
    suspend fun find(id: String, clientPackage: String?): ServerJob? {
        restore()
        return synchronized(lock) {
            purgeLocked()
            jobs[id]?.takeIf { it.state.value.clientPackage == clientPackage }
                ?: jobs.values.firstOrNull { it.state.value.clientPackage == clientPackage && it.state.value.requestId == id }
        }
    }

    /** This client's jobs that are known right now, oldest first. */
    suspend fun list(clientPackage: String?): List<ServerJob> {
        restore()
        return synchronized(lock) {
            purgeLocked()
            jobs.values.filter { it.state.value.clientPackage == clientPackage && it.state.value.mode == JobMode.Background }
        }
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

    /** Drops a finished job and its stored result (the client has it, or does not want it). */
    suspend fun forget(job: ServerJob) {
        if (!job.state.value.phase.terminal) return
        synchronized(lock) { jobs.remove(job.id) }
        runCatching { store.delete(job.id) }
    }

    /**
     * The server stops. Background jobs are parked: their checkpoint is on disk and they continue
     * at the next start. Everything else is cancelled. Returns when the engine is free.
     */
    suspend fun stopAll() {
        val running = synchronized(lock) { jobs.values.filter { !it.state.value.phase.terminal } }
        running.forEach {
            if (it.state.value.mode == JobMode.Background) it.parked = true
            it.coroutine?.cancel()
        }
        running.forEach { it.coroutine?.join() }
        // parked jobs are read back from disk at the next start
        restoreGate.withLock { restored = false }
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
                    val prepared = session.prepare(job.request, job.resumeFrom)
                    job.update {
                        it.copy(
                            phase = JobPhase.ReadingPrompt,
                            model = model.displayName,
                            loadMs = loadMs,
                            promptTokens = prepared.promptTokens,
                            promptProcessed = prepared.cachedTokens.coerceAtMost(prepared.promptTokens),
                            cachedTokens = prepared.cachedTokens.coerceAtMost(prepared.promptTokens),
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

                            override fun onRaw(piece: String) {
                                job.raw.append(piece)
                                job.rawTokens++
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
                                val preview =
                                    synchronized(job.text) {
                                        job.text.append(content)
                                        job.text.takeLast(PREVIEW_CHARS).toString()
                                    }
                                job.update { it.copy(preview = preview) }
                            }
                            job.events?.send(JobEvent.Delta(content, reasoning))
                            checkpointIfDue(job)
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
        withContext(NonCancellable) {
            if (outcome == JobOutcome.Cancelled && job.parked) park(job) else finish(job, outcome)
        }
    }

    /** Saves the answer so far every [CHECKPOINT_TOKENS] tokens or [CHECKPOINT_MS], whichever comes first. */
    private suspend fun checkpointIfDue(job: ServerJob) {
        if (job.state.value.mode != JobMode.Background) return
        val now = System.currentTimeMillis()
        if (job.rawTokens - job.checkpointTokens < CHECKPOINT_TOKENS && now - job.checkpointAt < CHECKPOINT_MS) return
        if (job.rawTokens == job.checkpointTokens) return
        writeCheckpoint(job)
    }

    private suspend fun writeCheckpoint(job: ServerJob) {
        job.checkpointTokens = job.rawTokens
        job.checkpointAt = System.currentTimeMillis()
        runCatching { store.checkpoint(job.id, job.raw.toString(), job.rawTokens, job.state.value.phase.name) }
            .onFailure { Log.w(TAG, "could not write a checkpoint", it) }
    }

    /** Takes a job out of memory without ending it; [restore] brings it back. */
    private suspend fun park(job: ServerJob) {
        if (job.rawTokens > 0) writeCheckpoint(job)
        synchronized(lock) {
            jobs.remove(job.id)
            if (jobs.values.none { !it.state.value.phase.terminal }) releaseWakeLockLocked()
            publishLocked()
        }
    }

    private suspend fun persist(job: ServerJob, outcome: JobOutcome?) {
        val s = job.state.value
        val result = (outcome as? JobOutcome.Success)?.result
        val row =
            JobEntity(
                id = s.id,
                requestId = s.requestId,
                clientPackage = s.clientPackage,
                clientLabel = s.clientLabel,
                createdAt = s.createdAt,
                requestJson = LocalApi.json.encodeToString(job.request),
                phase = s.phase.name,
                finishedAt = s.finishedAt,
                model = s.model,
                promptTokens = s.promptTokens,
                cachedTokens = s.cachedTokens,
                completionTokens = s.completionTokens,
                content = result?.content,
                reasoning = result?.reasoning,
                finishReason = s.finishReason,
                promptMs = result?.promptMs,
                generationMs = result?.generationMs,
                tokensPerSecond = s.tokensPerSecond,
                errorCode = s.errorCode,
                errorMessage = s.errorMessage,
                // a finished job needs no checkpoint any more
                checkpointText = if (outcome == null) job.raw.toString().takeIf { it.isNotEmpty() } else null,
                checkpointTokens = if (outcome == null) job.rawTokens else 0,
                resumes = s.resumes,
            )
        runCatching { store.upsert(row) }.onFailure { Log.w(TAG, "could not store job ${s.id}", it) }
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
        // the result is on disk before anybody is told that the job is done
        if (job.state.value.mode == JobMode.Background) persist(job, outcome)
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
        val gone = expired + overflow
        if (gone.isEmpty()) return
        gone.forEach { jobs.remove(it.id) }
        scope.launch { gone.forEach { runCatching { store.delete(it.id) } } }
    }

    private fun holdWakeLockLocked() {
        wakeLock.acquire()
        _wakeLockHeld.value = true
        // The lock has a safety timeout; a generation may run for hours, so it is taken again
        // every few minutes for as long as there is work.
        if (wakeRenewal?.isActive != true) {
            wakeRenewal =
                scope.launch {
                    while (true) {
                        delay(WAKE_RENEW_MS)
                        synchronized(lock) {
                            if (jobs.values.any { !it.state.value.phase.terminal }) wakeLock.acquire()
                        }
                    }
                }
        }
    }

    private fun releaseWakeLockLocked() {
        wakeRenewal?.cancel()
        wakeRenewal = null
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

        /** How long a finished background job and its result are kept for the client. */
        const val RETAIN_MS = 24 * 3_600_000L
        private const val RETAIN_MAX = 50
        private const val CHECKPOINT_TOKENS = 32
        private const val CHECKPOINT_MS = 20_000L
        private const val WAKE_RENEW_MS = 10 * 60_000L
    }
}

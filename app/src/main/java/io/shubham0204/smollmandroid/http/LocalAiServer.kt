/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.http

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.AssistantMessage
import io.shubham0204.smollmandroid.api.ChatCompletionChoice
import io.shubham0204.smollmandroid.api.ChatCompletionChunk
import io.shubham0204.smollmandroid.api.ChatCompletionRequest
import io.shubham0204.smollmandroid.api.ChatCompletionResponse
import io.shubham0204.smollmandroid.api.ChunkChoice
import io.shubham0204.smollmandroid.api.ChunkDelta
import io.shubham0204.smollmandroid.api.HealthResponse
import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.api.ModelInfo
import io.shubham0204.smollmandroid.api.ModelList
import io.shubham0204.smollmandroid.api.RequestValidator
import io.shubham0204.smollmandroid.api.Timings
import io.shubham0204.smollmandroid.api.Usage
import io.shubham0204.smollmandroid.auth.AuthenticatedClient
import io.shubham0204.smollmandroid.auth.TokenAuth
import io.shubham0204.smollmandroid.auth.TokenLookup
import io.shubham0204.smollmandroid.data.PairedAppDao
import io.shubham0204.smollmandroid.data.RequestLogDao
import io.shubham0204.smollmandroid.data.RequestLogEntity
import io.shubham0204.smollmandroid.data.ServerSettings
import io.shubham0204.smollmandroid.engine.EngineStatus
import io.shubham0204.smollmandroid.engine.GenerationBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import io.shubham0204.smollmandroid.jobs.ServerJob
import io.shubham0204.smollmandroid.jobs.JobOutcome
import io.shubham0204.smollmandroid.jobs.JobMode
import io.shubham0204.smollmandroid.jobs.JobManager
import io.shubham0204.smollmandroid.jobs.JobEvent
import io.shubham0204.smollmandroid.api.JobStatus
import io.shubham0204.smollmandroid.api.JobProgress
import io.ktor.server.routing.delete
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.readString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

data class HttpServerState(val running: Boolean = false, val port: Int? = null, val error: String? = null)

/** The OpenAI-compatible HTTP server from docs/LOCAL_LLM_API.md, bound to 127.0.0.1 only. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalAiServer(
    private val inference: GenerationBackend,
    private val jobs: JobManager,
    private val pairedApps: PairedAppDao,
    private val requestLog: RequestLogDao,
    private val settings: () -> ServerSettings,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val json = LocalApi.json
    private val lookup = TokenLookup { hash ->
        pairedApps.findByTokenHash(hash)?.let { AuthenticatedClient(it.packageName, it.label) }
    }

    private val _state = MutableStateFlow(HttpServerState())
    val state: StateFlow<HttpServerState> = _state.asStateFlow()

    @Synchronized
    fun start(port: Int) {
        if (server != null && _state.value.port == port && _state.value.running) return
        stop()
        try {
            val s =
                embeddedServer(CIO, port = port, host = LocalApi.LOOPBACK_HOST) { module(port) }
            s.start(wait = false)
            server = s
            _state.value = HttpServerState(running = true, port = port)
            Log.i(TAG, "listening on ${LocalApi.LOOPBACK_HOST}:$port")
        } catch (e: Exception) {
            Log.e(TAG, "could not start on port $port", e)
            server = null
            _state.value =
                HttpServerState(running = false, port = port, error = "Port $port is not available (${e.message}).")
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
        _state.value = HttpServerState(running = false)
    }

    private fun Application.module(port: Int) {
        // Ktor keeps running a handler whose client is gone unless this is on. Without it a blocking
        // or streaming generation went on after the client gave up, and every retry queued behind it.
        install(HttpRequestLifecycle) { cancelCallOnClose = true }
        routing {
            get("/health") {
                if (!checkHost(call, port)) return@get
                val st = inference.state.value
                val status =
                    when (st.status) {
                        EngineStatus.NoModel -> "no_model"
                        EngineStatus.Unloaded -> "idle"
                        EngineStatus.Loading -> "loading"
                        EngineStatus.Generating -> "busy"
                        EngineStatus.Ready -> "ok"
                        EngineStatus.Error -> "no_model"
                    }
                call.respondJson(
                    HttpStatusCode.OK,
                    json.encodeToString(
                        HealthResponse(status = status, model = st.model?.displayName, queueLength = st.queueLength)
                    ),
                )
            }
            get("/v1/models") {
                handleApi(call, port) { _, log ->
                    val st = inference.state.value
                    val model = st.model
                    val list =
                        ModelList(
                            data =
                                listOfNotNull(
                                    model?.let {
                                        ModelInfo(
                                            id = it.displayName,
                                            created = it.addedAt / 1000,
                                            architecture = it.architecture,
                                            contextLength = settings().contextSize,
                                            loaded = st.status == EngineStatus.Ready || st.status == EngineStatus.Generating,
                                        )
                                    }
                                )
                        )
                    ioScope.launch { pairedApps.recordUse(log.clientPackage ?: return@launch, log.timestamp) }
                    writeLog(log.copy(status = 200), log.timestamp)
                    call.respondJson(HttpStatusCode.OK, json.encodeToString(list))
                }
            }
            post("/v1/chat/completions") { handleApi(call, port) { client, _ -> chatCompletions(call, client) } }
            get("/v1/jobs/{id}") { handleApi(call, port) { client, _ -> getJob(call, client) } }
            delete("/v1/jobs/{id}") { handleApi(call, port) { client, _ -> deleteJob(call, client) } }
            route("{...}") {
                handle {
                    if (!checkHost(call, port)) return@handle
                    call.respondError(ApiError.NotFound, "Unknown endpoint ${call.request.path()}")
                }
            }
        }
    }

    /** Rejects requests whose Host header is not loopback (DNS-rebinding protection). */
    private suspend fun checkHost(call: ApplicationCall, port: Int): Boolean {
        val host = call.request.header(HttpHeaders.Host)?.lowercase() ?: return true
        val ok = host == "127.0.0.1:$port" || host == "localhost:$port" || host == "127.0.0.1" || host == "localhost"
        if (!ok) call.respondError(ApiError.InvalidRequest, "Invalid Host header")
        return ok
    }

    private suspend fun handleApi(
        call: ApplicationCall,
        port: Int,
        block: suspend (AuthenticatedClient, RequestLogEntity) -> Unit,
    ) {
        if (!checkHost(call, port)) return
        val started = System.currentTimeMillis()
        val baseLog =
            RequestLogEntity(
                timestamp = started,
                clientPackage = null,
                clientLabel = null,
                method = call.request.httpMethod.value,
                path = call.request.path(),
                status = 0,
                durationMs = 0,
            )
        val client = TokenAuth.authenticate(call.request.header(HttpHeaders.Authorization), lookup)
        if (client == null) {
            call.respondError(ApiError.Unauthorized, "Missing or invalid token. Pair this app with the server again.")
            writeLog(baseLog.copy(status = 401, errorCode = ApiError.Unauthorized.code), started)
            return
        }
        val log = baseLog.copy(clientPackage = client.packageName, clientLabel = client.label)
        try {
            block(client, log)
        } catch (e: ApiException) {
            runCatching { call.respondError(e.error, e.message) }
            writeLog(log.copy(status = e.error.httpStatus, errorCode = e.error.code), started)
        } catch (e: CancellationException) {
            // a generation that was cut off is logged by its job
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
            // fails harmlessly if the response (e.g. an SSE stream) was already started
            runCatching { call.respondError(ApiError.InternalError, e.message ?: "Internal error") }
            writeLog(log.copy(status = 500, errorCode = ApiError.InternalError.code), started)
        }
    }

    /**
     * Every generation runs as a job ([JobManager]), which also writes its request-log row. This
     * handler only decides how the result reaches the client: a `202` and polling (`background`),
     * server-sent events (`stream`), or the plain response.
     */
    private suspend fun chatCompletions(call: ApplicationCall, client: AuthenticatedClient) {
        val body = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readString()
        if (body.length > MAX_BODY_BYTES) throw ApiException(ApiError.InvalidRequest, "Request body is too large")
        val request =
            try {
                json.decodeFromString<ChatCompletionRequest>(body)
            } catch (e: SerializationException) {
                throw ApiException(ApiError.InvalidRequest, "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            } catch (e: IllegalArgumentException) {
                throw ApiException(ApiError.InvalidRequest, "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        val resolved = RequestValidator.resolve(request, settings().samplingDefaults)
        val mode =
            when {
                resolved.background -> JobMode.Background
                resolved.stream -> JobMode.Stream
                else -> JobMode.Blocking
            }
        val job = jobs.submit(client.packageName, client.label, resolved, mode, body)
        ioScope.launch { pairedApps.recordUse(client.packageName, job.state.value.createdAt) }

        when (mode) {
            JobMode.Background -> {
                call.response.header(HttpHeaders.Location, "/v1/jobs/${job.id}")
                call.respondJson(HttpStatusCode.Accepted, json.encodeToString(jobStatus(job)))
            }
            JobMode.Stream ->
                try {
                    streamJob(call, job, resolved.includeThinking)
                } catch (e: Throwable) {
                    // the client went away (or the stream broke): stop generating (§1.2)
                    jobs.cancel(job)
                    throw e
                }
            else -> {
                val outcome =
                    try {
                        job.await()
                    } catch (e: CancellationException) {
                        jobs.cancel(job)
                        throw e
                    }
                when (outcome) {
                    is JobOutcome.Success ->
                        call.respondJson(HttpStatusCode.OK, json.encodeToString(completion(job, outcome, resolved.includeThinking)))
                    is JobOutcome.Failure -> call.respondError(outcome.error, outcome.message)
                    JobOutcome.Cancelled -> call.respondError(ApiError.ModelNotLoaded, CANCELLED_MESSAGE)
                }
            }
        }
    }

    private suspend fun streamJob(call: ApplicationCall, job: ServerJob, includeThinking: Boolean) {
        val events = checkNotNull(job.events)
        // Until generation is about to start, errors are plain JSON responses (§2.3).
        val model =
            when (val first = events.receive()) {
                is JobEvent.Started -> first.model
                is JobEvent.Finished -> {
                    when (val o = first.outcome) {
                        is JobOutcome.Failure -> call.respondError(o.error, o.message)
                        else -> call.respondError(ApiError.ModelNotLoaded, CANCELLED_MESSAGE)
                    }
                    return
                }
                is JobEvent.Delta -> error("delta before start")
            }
        val created = job.state.value.createdAt / 1000
        fun chunk(delta: ChunkDelta, finish: String? = null, usage: Usage? = null) =
            "data: " +
                json.encodeToString(
                    ChatCompletionChunk(
                        id = job.id,
                        created = created,
                        model = model,
                        choices = listOf(ChunkChoice(delta = delta, finishReason = finish)),
                        usage = usage,
                    )
                ) +
                "\n\n"
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            write(chunk(ChunkDelta(role = "assistant")))
            flush()
            while (true) {
                // select does not lose an event when the timeout wins
                val event =
                    select<JobEvent?> {
                        events.onReceiveCatching { it.getOrNull() ?: JobEvent.Finished(JobOutcome.Cancelled) }
                        onTimeout(KEEP_ALIVE_MS) { null }
                    }
                when (event) {
                    null -> {
                        // an SSE comment: keeps the client's read timeout from firing while the
                        // prompt is processed, and notices a client that went away
                        write(": keep-alive\n\n")
                        flush()
                    }
                    is JobEvent.Started -> Unit
                    is JobEvent.Delta -> {
                        val r = event.reasoning.takeIf { includeThinking && it.isNotEmpty() }
                        val c = event.content.takeIf { it.isNotEmpty() }
                        if (c != null || r != null) {
                            write(chunk(ChunkDelta(content = c, reasoningContent = r)))
                            flush()
                        }
                    }
                    is JobEvent.Finished -> {
                        when (val o = event.outcome) {
                            is JobOutcome.Success -> {
                                val usage =
                                    Usage(
                                        o.result.promptTokens,
                                        o.result.completionTokens,
                                        o.result.promptTokens + o.result.completionTokens,
                                    )
                                write(chunk(ChunkDelta(), finish = o.result.finishReason, usage = usage))
                            }
                            // the stream already started: report in-band (§2.3)
                            is JobOutcome.Failure ->
                                write("data: " + json.encodeToString(o.error.body(o.message)) + "\n\n")
                            JobOutcome.Cancelled ->
                                write("data: " + json.encodeToString(ApiError.Cancelled.body(CANCELLED_MESSAGE)) + "\n\n")
                        }
                        write("data: [DONE]\n\n")
                        flush()
                        return@respondTextWriter
                    }
                }
            }
        }
    }

    private fun completion(job: ServerJob, outcome: JobOutcome.Success, includeThinking: Boolean): ChatCompletionResponse {
        val s = job.state.value
        val result = outcome.result
        return ChatCompletionResponse(
            id = job.id,
            created = s.createdAt / 1000,
            model = outcome.model,
            choices =
                listOf(
                    ChatCompletionChoice(
                        message =
                            AssistantMessage(
                                content = result.content,
                                reasoningContent = result.reasoning.takeIf { includeThinking },
                            ),
                        finishReason = result.finishReason,
                    )
                ),
            usage = Usage(result.promptTokens, result.completionTokens, result.promptTokens + result.completionTokens),
            timings =
                Timings(
                    promptMs = result.promptMs,
                    generationMs = result.generationMs,
                    tokensPerSecond = result.tokensPerSecond,
                    queueMs = s.queueMs,
                    loadMs = s.loadMs,
                    cachedPromptTokens = result.cachedTokens,
                ),
        )
    }

    private fun jobStatus(job: ServerJob): JobStatus {
        val s = job.state.value
        val outcome = job.outcome
        return JobStatus(
            id = s.id,
            status = s.phase.wire,
            created = s.createdAt / 1000,
            model = s.model,
            queuePosition = jobs.queuePosition(s.id),
            progress =
                JobProgress(
                    promptTokens = s.promptTokens,
                    promptTokensProcessed = s.promptProcessed,
                    promptTokensCached = s.cachedTokens,
                    completionTokens = s.completionTokens,
                    maxTokens = s.maxTokens,
                    tokensPerSecond = s.tokensPerSecond,
                    elapsedMs = (s.finishedAt ?: System.currentTimeMillis()) - s.createdAt,
                ),
            response = (outcome as? JobOutcome.Success)?.let { completion(job, it, job.request.includeThinking) },
            error =
                when (outcome) {
                    is JobOutcome.Failure -> outcome.error.body(outcome.message).error
                    JobOutcome.Cancelled -> ApiError.Cancelled.body(CANCELLED_MESSAGE).error
                    else -> null
                },
        )
    }

    /** `GET /v1/jobs/{id}?wait=N`: answers when the job has ended, or after N seconds with its progress. */
    private suspend fun getJob(call: ApplicationCall, client: AuthenticatedClient) {
        val job = findJob(call, client)
        val wait = (call.request.queryParameters["wait"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, MAX_WAIT_SECONDS)
        if (wait > 0 && job.outcome == null) {
            withTimeoutOrNull((wait * 1000).toLong()) { job.await() }
        }
        call.respondJson(HttpStatusCode.OK, json.encodeToString(jobStatus(job)))
    }

    /** `DELETE /v1/jobs/{id}`: stops the job; answers with its final status. */
    private suspend fun deleteJob(call: ApplicationCall, client: AuthenticatedClient) {
        val job = findJob(call, client)
        jobs.cancel(job)
        withTimeoutOrNull(CANCEL_WAIT_MS) { job.await() }
        call.respondJson(HttpStatusCode.OK, json.encodeToString(jobStatus(job)))
    }

    private fun findJob(call: ApplicationCall, client: AuthenticatedClient): ServerJob =
        call.parameters["id"]?.let { jobs.find(it, client.packageName) }
            ?: throw ApiException(ApiError.NotFound, "Unknown job. Results are kept for 30 minutes after a job ends.")

    private fun writeLog(entry: RequestLogEntity, started: Long) {
        val final = entry.copy(durationMs = System.currentTimeMillis() - started)
        ioScope.launch {
            requestLog.insert(final)
            requestLog.trim()
        }
    }

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
        respondText(body, ContentType.Application.Json, status)

    private suspend fun ApplicationCall.respondError(error: ApiError, message: String) {
        if (error == ApiError.ServerBusy) response.header(HttpHeaders.RetryAfter, "5")
        respondJson(HttpStatusCode.fromValue(error.httpStatus), json.encodeToString(error.body(message)))
    }

    companion object {
        private const val TAG = "LocalAiServer"
        private const val MAX_BODY_BYTES = 1_000_000L
        private const val KEEP_ALIVE_MS = 5_000L
        private const val MAX_WAIT_SECONDS = 30.0
        private const val CANCEL_WAIT_MS = 3_000L
        private const val CANCELLED_MESSAGE = "The request was cancelled before it finished."
    }
}

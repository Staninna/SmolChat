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
import io.shubham0204.smollmandroid.data.SettingsRepository
import io.shubham0204.smollmandroid.engine.EngineStatus
import io.shubham0204.smollmandroid.engine.InferenceEngine
import java.util.UUID
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
class LocalAiServer(
    private val inference: InferenceEngine,
    private val pairedApps: PairedAppDao,
    private val requestLog: RequestLogDao,
    private val settings: SettingsRepository,
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
                                            contextLength = settings.current.contextSize,
                                            loaded = st.status == EngineStatus.Ready || st.status == EngineStatus.Generating,
                                        )
                                    }
                                )
                        )
                    writeLog(log.copy(status = 200), log.timestamp)
                    call.respondJson(HttpStatusCode.OK, json.encodeToString(list))
                }
            }
            post("/v1/chat/completions") { handleApi(call, port) { client, log -> chatCompletions(call, client, log) } }
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
        ioScope.launch { pairedApps.recordUse(client.packageName, started) }
        try {
            block(client, log)
        } catch (e: ApiException) {
            runCatching { call.respondError(e.error, e.message) }
            writeLog(log.copy(status = e.error.httpStatus, errorCode = e.error.code), started)
        } catch (e: CancellationException) {
            writeLog(log.copy(status = 499, errorCode = "client_closed"), started)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
            // fails harmlessly if the response (e.g. an SSE stream) was already started
            runCatching { call.respondError(ApiError.InternalError, e.message ?: "Internal error") }
            writeLog(log.copy(status = 500, errorCode = ApiError.InternalError.code), started)
        }
    }

    private suspend fun chatCompletions(
        call: ApplicationCall,
        client: AuthenticatedClient,
        baseLog: RequestLogEntity,
    ) {
        val started = baseLog.timestamp
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
        val resolved = RequestValidator.resolve(request, settings.current.samplingDefaults)
        val logBodies = settings.current.logBodies
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val created = System.currentTimeMillis() / 1000

        inference.withSlot { session ->
            val model = session.ensureLoaded()
            val prepared = session.prepare(resolved)
            val modelName = model.displayName
            if (resolved.stream) {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                    fun chunk(delta: ChunkDelta, finish: String? = null, usage: Usage? = null) =
                        "data: " +
                            json.encodeToString(
                                ChatCompletionChunk(
                                    id = id,
                                    created = created,
                                    model = modelName,
                                    choices = listOf(ChunkChoice(delta = delta, finishReason = finish)),
                                    usage = usage,
                                )
                            ) +
                            "\n\n"
                    write(chunk(ChunkDelta(role = "assistant")))
                    flush()
                    try {
                        val result =
                            session.generate(prepared) { content, reasoning ->
                                val r = reasoning.takeIf { resolved.includeThinking && it.isNotEmpty() }
                                val c = content.takeIf { it.isNotEmpty() }
                                if (c != null || r != null) {
                                    write(chunk(ChunkDelta(content = c, reasoningContent = r)))
                                    flush()
                                }
                            }
                        val usage =
                            Usage(result.promptTokens, result.completionTokens, result.promptTokens + result.completionTokens)
                        write(chunk(ChunkDelta(), finish = result.finishReason, usage = usage))
                        write("data: [DONE]\n\n")
                        flush()
                        writeLog(
                            baseLog.copy(
                                status = 200,
                                stream = true,
                                promptTokens = result.promptTokens,
                                completionTokens = result.completionTokens,
                                tokensPerSecond = result.tokensPerSecond,
                                requestBody = body.takeIf { logBodies },
                                responseBody = result.content.takeIf { logBodies },
                            ),
                            started,
                        )
                    } catch (e: ApiException) {
                        // the stream already started: report in-band (§2.3)
                        write("data: " + json.encodeToString(e.error.body(e.message)) + "\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                        writeLog(baseLog.copy(status = e.error.httpStatus, stream = true, errorCode = e.error.code), started)
                    }
                }
            } else {
                val result = session.generate(prepared)
                val response =
                    ChatCompletionResponse(
                        id = id,
                        created = created,
                        model = modelName,
                        choices =
                            listOf(
                                ChatCompletionChoice(
                                    message =
                                        AssistantMessage(
                                            content = result.content,
                                            reasoningContent = result.reasoning.takeIf { resolved.includeThinking },
                                        ),
                                    finishReason = result.finishReason,
                                )
                            ),
                        usage = Usage(result.promptTokens, result.completionTokens, result.promptTokens + result.completionTokens),
                        timings = Timings(result.promptMs, result.generationMs, result.tokensPerSecond),
                    )
                call.respondJson(HttpStatusCode.OK, json.encodeToString(response))
                writeLog(
                    baseLog.copy(
                        status = 200,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        tokensPerSecond = result.tokensPerSecond,
                        requestBody = body.takeIf { logBodies },
                        responseBody = result.content.takeIf { logBodies },
                    ),
                    started,
                )
            }
        }
    }

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
    }
}

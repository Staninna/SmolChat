/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Wire types for docs/LOCAL_LLM_API.md (contract v1). Keep both repositories in sync. */
object LocalApi {
    const val API_VERSION = 2
    const val DEFAULT_PORT = 8080
    const val LOOPBACK_HOST = "127.0.0.1"

    const val ACTION_PAIR = "io.shubham0204.smollmandroid.action.PAIR"
    const val EXTRA_CLIENT_NAME = "client_name"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BASE_URL = "base_url"
    const val EXTRA_API_VERSION = "api_version"
    const val EXTRA_SERVER_PACKAGE = "server_package"

    /** Shared JSON configuration: lenient on input, compact on output, no nulls on the wire. */
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
    }

    fun baseUrl(port: Int) = "http://$LOOPBACK_HOST:$port"
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("min_p") val minP: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: Map<String, JsonElement>? = null,
    @SerialName("include_thinking") val includeThinking: Boolean = false,
    val seed: Long? = null,
    /** Extension (v2): run as a background job and answer `202` with a [JobStatus] at once. */
    val background: Boolean = false,
) {
    /** `chat_template_kwargs.enable_thinking`, or null when the client did not set it. */
    val enableThinking: Boolean?
        get() = (chatTemplateKwargs?.get("enable_thinking") as? JsonPrimitive)?.booleanOrNull
}

@Serializable
data class AssistantMessage(
    val role: String = "assistant",
    val content: String,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChatCompletionChoice(
    val index: Int = 0,
    val message: AssistantMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class Timings(
    @SerialName("prompt_ms") val promptMs: Double,
    @SerialName("generation_ms") val generationMs: Double,
    @SerialName("tokens_per_second") val tokensPerSecond: Double,
    @SerialName("queue_ms") val queueMs: Long? = null,
    @SerialName("load_ms") val loadMs: Long? = null,
    @SerialName("cached_prompt_tokens") val cachedPromptTokens: Int? = null,
)

@Serializable
data class JobProgress(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("prompt_tokens_processed") val promptTokensProcessed: Int = 0,
    @SerialName("prompt_tokens_cached") val promptTokensCached: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("tokens_per_second") val tokensPerSecond: Double? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
)

/** `GET /v1/jobs/{id}` and the `202` answer to a background chat completion (contract v2, §2.4). */
@Serializable
data class JobStatus(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion.job",
    val status: String,
    val created: Long,
    val model: String? = null,
    @SerialName("queue_position") val queuePosition: Int? = null,
    val progress: JobProgress = JobProgress(),
    val response: ChatCompletionResponse? = null,
    val error: ErrorBody? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoice>,
    val usage: Usage,
    val timings: Timings? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta,
    // always present on the wire, `null` until the last chunk (OpenAI behaviour)
    @SerialName("finish_reason") val finishReason: String?,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null,
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val obj: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "local",
    val architecture: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    val loaded: Boolean = false,
)

@Serializable
data class ModelList(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelInfo>,
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("api_version") val apiVersion: Int = LocalApi.API_VERSION,
    val model: String? = null,
    @SerialName("queue_length") val queueLength: Int = 0,
    /** Optional capabilities of this server (v2). */
    val features: List<String> = listOf("background_jobs", "prompt_cache"),
)

@Serializable
data class ErrorBody(
    val message: String,
    val type: String,
    val code: String,
)

@Serializable
data class ErrorResponse(val error: ErrorBody)

/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.api

import java.util.Locale
import kotlinx.serialization.Serializable

/** Server-side defaults, configurable in settings (docs/LOCAL_LLM_API.md §6). */
data class SamplingDefaults(
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val minP: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val enableThinking: Boolean = false,
)

/** A validated request with all defaults filled in. */
@Serializable
data class ResolvedRequest(
    val messages: List<ChatMessage>,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val presencePenalty: Float,
    val maxTokens: Int?,
    val stream: Boolean,
    val enableThinking: Boolean,
    val includeThinking: Boolean,
    val seed: Long,
    val background: Boolean = false,
    /** The client's own id for this request (idempotency key), or null. */
    val requestId: String? = null,
)

object RequestValidator {
    private val requestIdPattern = Regex("[A-Za-z0-9._:-]{8,128}")
    private val allowedRoles = setOf("system", "developer", "user", "assistant")

    /** @throws ApiException with [ApiError.InvalidRequest] when a field is missing or out of range. */
    fun resolve(request: ChatCompletionRequest, defaults: SamplingDefaults): ResolvedRequest {
        if (request.messages.isEmpty()) invalid("'messages' must contain at least one message")
        val messages =
            request.messages.mapIndexed { i, m ->
                val role = m.role.lowercase(Locale.ROOT)
                if (role !in allowedRoles) {
                    invalid("messages[$i].role must be one of system, user, assistant")
                }
                ChatMessage(role = if (role == "developer") "system" else role, content = m.content)
            }
        val temperature = request.temperature ?: defaults.temperature
        val topP = request.topP ?: defaults.topP
        val topK = request.topK ?: defaults.topK
        val minP = request.minP ?: defaults.minP
        val presencePenalty = request.presencePenalty ?: defaults.presencePenalty
        if (!temperature.isFinite() || temperature !in 0f..2f) invalid("'temperature' must be between 0 and 2")
        if (!topP.isFinite() || topP !in 0f..1f) invalid("'top_p' must be between 0 and 1")
        if (topK < 0) invalid("'top_k' must be 0 or greater")
        if (!minP.isFinite() || minP !in 0f..1f) invalid("'min_p' must be between 0 and 1")
        if (!presencePenalty.isFinite() || presencePenalty !in -2f..2f) {
            invalid("'presence_penalty' must be between -2 and 2")
        }
        val maxTokens = request.maxTokens
        if (maxTokens != null && maxTokens < 1) invalid("'max_tokens' must be 1 or greater")
        return ResolvedRequest(
            messages = messages,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            presencePenalty = presencePenalty,
            maxTokens = maxTokens,
            stream = request.stream,
            enableThinking = request.enableThinking ?: defaults.enableThinking,
            includeThinking = request.includeThinking,
            seed = request.seed?.takeIf { it >= 0 } ?: -1L,
            background = request.background,
            requestId = request.requestId?.also {
                if (!requestIdPattern.matches(it)) {
                    invalid("'request_id' must be 8 to 128 characters: letters, digits, '.', '_', ':' or '-'")
                }
            },
        )
    }

    /**
     * Context check from §2.3: `prompt + max_tokens` must fit, and without `max_tokens` the prompt
     * must leave room for at least one token.
     */
    fun checkContext(promptTokens: Int, maxTokens: Int?, contextLength: Int) {
        val needed = promptTokens.toLong() + (maxTokens ?: 1)
        if (needed > contextLength) {
            val detail =
                if (maxTokens != null) "$promptTokens prompt tokens + $maxTokens max_tokens"
                else "$promptTokens prompt tokens"
            throw ApiException(
                ApiError.ContextLengthExceeded,
                "This request needs $detail, but the context window is $contextLength tokens.",
            )
        }
    }

    private fun invalid(message: String): Nothing = throw ApiException(ApiError.InvalidRequest, message)
}

/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.ChatCompletionRequest
import io.shubham0204.smollmandroid.api.ChatMessage
import io.shubham0204.smollmandroid.api.RequestValidator
import io.shubham0204.smollmandroid.api.SamplingDefaults
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequestValidatorTest {
    private val defaults = SamplingDefaults()
    private val user = listOf(ChatMessage("user", "hi"))

    private fun expect(error: ApiError, block: () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: ApiException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun `fills Qwen3_5 non-thinking defaults`() {
        val r = RequestValidator.resolve(ChatCompletionRequest(messages = user), defaults)
        assertEquals(0.7f, r.temperature)
        assertEquals(0.8f, r.topP)
        assertEquals(20, r.topK)
        assertEquals(0.0f, r.minP)
        assertEquals(0.0f, r.presencePenalty)
        assertFalse(r.enableThinking)
        assertEquals(-1L, r.seed)
    }

    @Test
    fun `request values override defaults`() {
        val r =
            RequestValidator.resolve(
                ChatCompletionRequest(
                    messages = user,
                    temperature = 0.2f,
                    topK = 0,
                    chatTemplateKwargs = mapOf("enable_thinking" to JsonPrimitive(true)),
                    seed = 7,
                ),
                defaults,
            )
        assertEquals(0.2f, r.temperature)
        assertEquals(0, r.topK)
        assertTrue(r.enableThinking)
        assertEquals(7L, r.seed)
    }

    @Test
    fun `developer role becomes system and unknown roles are rejected`() {
        val r = RequestValidator.resolve(ChatCompletionRequest(messages = listOf(ChatMessage("Developer", "x"), ChatMessage("user", "y"))), defaults)
        assertEquals("system", r.messages[0].role)
        expect(ApiError.InvalidRequest) {
            RequestValidator.resolve(ChatCompletionRequest(messages = listOf(ChatMessage("tool", "x"))), defaults)
        }
    }

    @Test
    fun `rejects empty messages and out of range values`() {
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, temperature = 3f), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, topP = 1.5f), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, topK = -1), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, minP = -0.1f), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, presencePenalty = 2.5f), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, maxTokens = 0), defaults) }
        expect(ApiError.InvalidRequest) { RequestValidator.resolve(ChatCompletionRequest(messages = user, temperature = Float.NaN), defaults) }
    }

    @Test
    fun `context check`() {
        RequestValidator.checkContext(promptTokens = 8000, maxTokens = 192, contextLength = 8192)
        RequestValidator.checkContext(promptTokens = 8191, maxTokens = null, contextLength = 8192)
        expect(ApiError.ContextLengthExceeded) { RequestValidator.checkContext(8000, 193, 8192) }
        expect(ApiError.ContextLengthExceeded) { RequestValidator.checkContext(8192, null, 8192) }
    }
}

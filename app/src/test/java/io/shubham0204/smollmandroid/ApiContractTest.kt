/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.api.ApiError
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
import io.shubham0204.smollmandroid.api.Usage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the JSON shapes of docs/LOCAL_LLM_API.md (contract v2). */
class ApiContractTest {
    private val json = LocalApi.json

    @Test
    fun `request from the contract example parses`() {
        val body =
            """
            {
              "model": "Qwen3.5-4B-Q4_K_M",
              "messages": [
                {"role": "system", "content": "You rewrite my voice-dictated notes"},
                {"role": "user",   "content": "so uh today i went to albert hine"}
              ],
              "temperature": 0.7, "top_p": 0.8, "top_k": 20, "min_p": 0.0,
              "presence_penalty": 0.0, "max_tokens": 2048, "stream": true,
              "chat_template_kwargs": {"enable_thinking": false},
              "include_thinking": false, "seed": 42,
              "some_future_field": {"ignored": true}
            }
            """.trimIndent()
        val r = json.decodeFromString<ChatCompletionRequest>(body)
        assertEquals(2, r.messages.size)
        assertEquals("system", r.messages[0].role)
        assertEquals(0.8f, r.topP)
        assertEquals(20, r.topK)
        assertEquals(0.0f, r.minP)
        assertEquals(2048, r.maxTokens)
        assertTrue(r.stream)
        assertEquals(false, r.enableThinking)
        assertEquals(42L, r.seed)
    }

    @Test
    fun `minimal request uses defaults`() {
        val r = json.decodeFromString<ChatCompletionRequest>("""{"messages":[{"role":"user","content":"hi"}]}""")
        assertFalse(r.stream)
        assertNull(r.temperature)
        assertNull(r.enableThinking)
        assertFalse(r.includeThinking)
    }

    @Test
    fun `non-stream response has the OpenAI shape`() {
        val response =
            ChatCompletionResponse(
                id = "chatcmpl-1",
                created = 1763000000,
                model = "Qwen3.5-4B-Q4_K_M",
                choices = listOf(ChatCompletionChoice(message = AssistantMessage(content = "Hello"), finishReason = "stop")),
                usage = Usage(10, 2, 12),
            )
        val obj = json.parseToJsonElement(json.encodeToString(response)).jsonObject
        assertEquals("chat.completion", obj["object"]!!.jsonPrimitive.content)
        val choice = obj["choices"]!!.jsonArray[0].jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("assistant", choice["message"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content must be omitted when null", choice["message"]!!.jsonObject.containsKey("reasoning_content"))
        assertEquals(12, obj["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
        assertFalse(obj.containsKey("timings"))
    }

    @Test
    fun `stream chunk keeps finish_reason null until the end`() {
        val chunk =
            ChatCompletionChunk(
                id = "chatcmpl-1",
                created = 1,
                model = "m",
                choices = listOf(ChunkChoice(delta = ChunkDelta(content = "Hi"), finishReason = null)),
            )
        val obj = json.parseToJsonElement(json.encodeToString(chunk)).jsonObject
        assertEquals("chat.completion.chunk", obj["object"]!!.jsonPrimitive.content)
        val choice = obj["choices"]!!.jsonArray[0].jsonObject
        // explicitNulls=false drops nulls; OpenAI clients treat a missing finish_reason like null
        assertTrue(choice["finish_reason"] == null || choice["finish_reason"].toString() == "null")
        assertEquals("Hi", choice["delta"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(choice["delta"]!!.jsonObject.containsKey("role"))
    }

    @Test
    fun `error envelope matches section 4`() {
        val obj = json.parseToJsonElement(json.encodeToString(ApiError.ModelNotLoaded.body("No model"))).jsonObject
        val err = obj["error"] as JsonObject
        assertEquals("model_not_loaded", err["code"]!!.jsonPrimitive.content)
        assertEquals("server_error", err["type"]!!.jsonPrimitive.content)
        assertEquals("No model", err["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `error codes and statuses match the contract`() {
        assertEquals(503, ApiError.ModelNotLoaded.httpStatus)
        assertEquals(429, ApiError.ServerBusy.httpStatus)
        assertEquals("server_busy", ApiError.ServerBusy.code)
        assertEquals(401, ApiError.Unauthorized.httpStatus)
        assertEquals("unauthorized", ApiError.Unauthorized.code)
        assertEquals(400, ApiError.ContextLengthExceeded.httpStatus)
        assertEquals("context_length_exceeded", ApiError.ContextLengthExceeded.code)
    }

    @Test
    fun `health and models match the contract`() {
        val h = json.parseToJsonElement(json.encodeToString(HealthResponse(status = "ok", model = "m"))).jsonObject
        assertEquals("ok", h["status"]!!.jsonPrimitive.content)
        assertEquals(2, h["api_version"]!!.jsonPrimitive.int)
        assertEquals(0, h["queue_length"]!!.jsonPrimitive.int)

        val list = ModelList(data = listOf(ModelInfo(id = "m", created = 1, architecture = "qwen35", contextLength = 8192, loaded = true)))
        val o = json.parseToJsonElement(json.encodeToString(list)).jsonObject
        assertEquals("list", o["object"]!!.jsonPrimitive.content)
        val m = o["data"]!!.jsonArray[0].jsonObject
        assertEquals("model", m["object"]!!.jsonPrimitive.content)
        assertEquals("local", m["owned_by"]!!.jsonPrimitive.content)
        assertEquals("qwen35", m["architecture"]!!.jsonPrimitive.content)
        assertEquals(8192, m["context_length"]!!.jsonPrimitive.int)
        assertTrue(m["loaded"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pairing constants match the contract`() {
        assertEquals("io.shubham0204.smollmandroid.action.PAIR", LocalApi.ACTION_PAIR)
        assertEquals("client_name", LocalApi.EXTRA_CLIENT_NAME)
        assertEquals("token", LocalApi.EXTRA_TOKEN)
        assertEquals("base_url", LocalApi.EXTRA_BASE_URL)
        assertEquals("http://127.0.0.1:8080", LocalApi.baseUrl(8080))
    }
}

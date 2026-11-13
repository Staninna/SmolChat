/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.auth.TokenAuth
import io.shubham0204.smollmandroid.data.PairedAppEntity
import io.shubham0204.smollmandroid.data.ServerSettings
import io.shubham0204.smollmandroid.http.LocalAiServer
import io.shubham0204.smollmandroid.jobs.JobManager
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real HTTP server (Ktor on a loopback port) with a fake model behind it: what a paired client
 * sees for contract v2, including a client that disappears in the middle of a request.
 */
class ServerIntegrationTest {
    private val log = FakeRequestLog()
    private val wake = FakeWakeHold()
    private val apps = FakePairedApps()
    private val token = TokenAuth.generateToken()
    private val otherToken = TokenAuth.generateToken()
    private var server: LocalAiServer? = null
    private var port = 0

    private fun start(backend: FakeBackend): FakeBackend {
        apps.add(PairedAppEntity("com.denser.june", "June", TokenAuth.hash(token), pairedAt = 0))
        apps.add(PairedAppEntity("com.other", "Other", TokenAuth.hash(otherToken), pairedAt = 0))
        port = ServerSocket(0).use { it.localPort }
        val jobs = JobManager(backend, log, wake) { false }
        server = LocalAiServer(backend, jobs, apps, log) { ServerSettings() }.also { it.start(port) }
        assertTrue(server!!.state.value.running)
        return backend
    }

    @After
    fun stop() {
        server?.stop()
    }

    private data class Reply(val status: Int, val body: String) {
        val json: JsonObject
            get() = Json.parseToJsonElement(body).jsonObject
    }

    private fun call(method: String, path: String, body: String? = null, auth: String? = token, readTimeoutMs: Int = 10_000): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 2_000
        c.readTimeout = readTimeoutMs
        auth?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return Reply(status, text)
    }

    private fun chat(extra: String = "") =
        """{"messages":[{"role":"system","content":"Rewrite my notes."},{"role":"user","content":"good morning world"}]$extra}"""

    private fun awaitJob(id: String): JsonObject {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val job = call("GET", "/v1/jobs/$id?wait=1").json
            if (job["status"]!!.jsonPrimitive.content in setOf("completed", "failed", "cancelled")) return job
        }
        error("job $id did not finish")
    }

    @Test
    fun `health reports contract v2 without a token`() {
        start(FakeBackend())
        val health = call("GET", "/health", auth = null)
        assertEquals(200, health.status)
        assertEquals(2, health.json["api_version"]!!.jsonPrimitive.int)
        assertEquals("ok", health.json["status"]!!.jsonPrimitive.content)
        assertTrue(health.json["features"]!!.jsonArray.any { it.jsonPrimitive.content == "background_jobs" })
    }

    @Test
    fun `a background request answers at once and the result is fetched later`() {
        start(FakeBackend(promptStepMs = 30, tokenMs = 30))
        val before = System.currentTimeMillis()
        val accepted = call("POST", "/v1/chat/completions", chat(""","background":true,"max_tokens":64"""))
        assertEquals(202, accepted.status)
        assertTrue("the answer must not wait for the model", System.currentTimeMillis() - before < 2_000)
        assertEquals("chat.completion.job", accepted.json["object"]!!.jsonPrimitive.content)
        val id = accepted.json["id"]!!.jsonPrimitive.content

        // a status call without waiting shows progress
        val running = call("GET", "/v1/jobs/$id").json
        assertNotNull(running["progress"])

        val done = awaitJob(id)
        assertEquals("completed", done["status"]!!.jsonPrimitive.content)
        val response = done["response"]!!.jsonObject
        assertEquals("chat.completion", response["object"]!!.jsonPrimitive.content)
        val choice = response["choices"]!!.jsonArray[0].jsonObject
        assertEquals("Good morning, world!", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(5, response["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.int)
        assertEquals(16, response["timings"]!!.jsonObject["cached_prompt_tokens"]!!.jsonPrimitive.int)
        val progress = done["progress"]!!.jsonObject
        assertEquals(48, progress["prompt_tokens_processed"]!!.jsonPrimitive.int)

        // the result can be fetched again, but only by the app that asked
        assertEquals("completed", call("GET", "/v1/jobs/$id").json["status"]!!.jsonPrimitive.content)
        assertEquals(404, call("GET", "/v1/jobs/$id", auth = otherToken).status)
        assertEquals(401, call("GET", "/v1/jobs/$id", auth = null).status)
        assertEquals(404, call("GET", "/v1/jobs/chatcmpl-unknown").status)

        // one log row for the generation; status calls are not logged
        Thread.sleep(100)
        val chatRows = log.rows.filter { it.path == "/v1/chat/completions" }
        assertEquals(1, chatRows.size)
        assertEquals(200, chatRows[0].status)
        assertEquals("background", chatRows[0].mode)
        assertTrue(log.rows.none { it.path.startsWith("/v1/jobs") && it.status == 200 })
        assertFalse(wake.held)
    }

    @Test
    fun `a background job survives a client that disconnects`() {
        val backend = start(FakeBackend(promptStepMs = 40, tokenMs = 80))
        val body = chat(""","background":true""")
        val id: String
        // a raw socket that is closed right after the 202, like an app that gets frozen or killed
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                ("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()
            )
            socket.getOutputStream().flush()
            val head = socket.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { !it.startsWith("{") || true }.take(12).toList() }
            assertTrue(head.first().contains("202"))
            id = Regex("chatcmpl-[0-9a-f]+").find(head.joinToString("\n"))!!.value
        }
        val done = awaitJob(id)
        assertEquals("completed", done["status"]!!.jsonPrimitive.content)
        assertTrue(backend.cancelled.isEmpty())
    }

    @Test
    fun `a blocking request stops generating when its client disconnects`() {
        val backend = start(FakeBackend(promptStepMs = 40, tokenMs = 400))
        val body = chat()
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                ("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()
            )
            socket.getOutputStream().flush()
            Thread.sleep(400) // the model is working now
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (backend.cancelled.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("good morning world"), backend.cancelled.toList())
        Thread.sleep(100)
        assertEquals(499, log.rows.single { it.path == "/v1/chat/completions" }.status)
    }

    @Test
    fun `delete stops a running job`() {
        val backend = start(FakeBackend(tokenMs = 500))
        val id = call("POST", "/v1/chat/completions", chat(""","background":true""")).json["id"]!!.jsonPrimitive.content
        Thread.sleep(200)
        val stopped = call("DELETE", "/v1/jobs/$id")
        assertEquals(200, stopped.status)
        assertEquals("cancelled", stopped.json["status"]!!.jsonPrimitive.content)
        assertEquals("cancelled", stopped.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(1, backend.cancelled.size)
    }

    @Test
    fun `the plain blocking request still works`() {
        start(FakeBackend())
        val r = call("POST", "/v1/chat/completions", chat())
        assertEquals(200, r.status)
        assertEquals("chat.completion", r.json["object"]!!.jsonPrimitive.content)
        assertEquals(
            "Good morning, world!",
            r.json["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a stream sends keep-alives while the prompt is processed and ends with DONE`() {
        // a prompt that takes longer than the keep-alive interval (5 s)
        start(FakeBackend(promptTokens = 16 + 16 * 3, promptStepMs = 2_200, tokenMs = 10))
        val r = call("POST", "/v1/chat/completions", chat(""","stream":true"""), readTimeoutMs = 20_000)
        assertEquals(200, r.status)
        val lines = r.body.lines().filter { it.isNotBlank() }
        assertTrue("expected a keep-alive comment in:\n${r.body}", lines.any { it == ": keep-alive" })
        val data = lines.filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", data.last())
        val text =
            data.dropLast(1).joinToString("") {
                Json.parseToJsonElement(it).jsonObject["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["content"]
                    ?.jsonPrimitive?.content.orEmpty()
            }
        assertEquals("Good morning, world!", text)
        val last = Json.parseToJsonElement(data[data.size - 2]).jsonObject
        assertEquals("stop", last["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(5, last["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `errors before generation are plain json, also for background and stream`() {
        start(FakeBackend(contextSize = 64))
        // a background job is accepted first; the error is in the job
        val id = call("POST", "/v1/chat/completions", chat(""","background":true,"max_tokens":1000""")).json["id"]!!.jsonPrimitive.content
        val failed = awaitJob(id)
        assertEquals("failed", failed["status"]!!.jsonPrimitive.content)
        assertEquals("context_length_exceeded", failed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val stream = call("POST", "/v1/chat/completions", chat(""","stream":true,"max_tokens":1000"""))
        assertEquals(400, stream.status)
        assertEquals("context_length_exceeded", stream.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val blocking = call("POST", "/v1/chat/completions", chat(""","max_tokens":1000"""))
        assertEquals(400, blocking.status)

        assertEquals(400, call("POST", "/v1/chat/completions", """{"messages":[]}""").status)
        assertEquals(401, call("POST", "/v1/chat/completions", chat(), auth = null).status)
    }
}

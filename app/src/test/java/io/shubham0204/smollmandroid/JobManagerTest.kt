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
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.api.SamplingDefaults
import io.shubham0204.smollmandroid.jobs.JobManager
import io.shubham0204.smollmandroid.jobs.JobMode
import io.shubham0204.smollmandroid.jobs.JobOutcome
import io.shubham0204.smollmandroid.jobs.JobPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JobManagerTest {
    private val log = FakeRequestLog()
    private val wake = FakeWakeHold()

    private fun manager(backend: FakeBackend) = JobManager(backend, log, wake) { false }

    private fun request(text: String, maxTokens: Int? = null): ResolvedRequest =
        RequestValidator.resolve(
            ChatCompletionRequest(messages = listOf(ChatMessage("user", text)), maxTokens = maxTokens),
            SamplingDefaults(),
        )

    private suspend fun awaitLog(count: Int) = withTimeout(5_000) { while (log.rows.size < count) delay(5) }

    @Test
    fun `a background job finishes with nobody waiting for it and keeps its result`() = runBlocking {
        val jobs = manager(FakeBackend())
        val job = jobs.submit("com.example", "Example", request("hi"), JobMode.Background)
        assertTrue(wake.held)
        // nobody awaits the job: only its state is observed, like a client that polls later
        withTimeout(5_000) { job.state.first { it.phase == JobPhase.Completed } }
        awaitLog(1)

        val found = jobs.find(job.id, "com.example")
        assertNotNull("the result must still be there for the client to fetch", found)
        val outcome = found!!.outcome as JobOutcome.Success
        assertEquals("Good morning, world!", outcome.result.content)
        assertEquals("stop", outcome.result.finishReason)
        assertTrue(jobs.active.value.isEmpty())
        assertFalse("the wake lock is released when the last job ends", wake.held)

        val row = log.rows.single()
        assertEquals(200, row.status)
        assertEquals("background", row.mode)
        assertEquals("Example", row.clientLabel)
        assertEquals(48, row.promptTokens)
        assertEquals(16, row.cachedTokens)
        assertEquals(5, row.completionTokens)
        assertNull("bodies are only stored with the debug setting", row.requestBody)
    }

    @Test
    fun `a job reports every phase with its progress`() = runBlocking {
        val backend = FakeBackend(promptStepMs = 20, tokenMs = 20)
        backend.loadMs = 40
        val jobs = manager(backend)
        val job = jobs.submit("com.example", "Example", request("hi", maxTokens = 3), JobMode.Background)
        val seen = mutableListOf<JobPhase>()
        withTimeout(5_000) {
            job.state.first {
                if (seen.lastOrNull() != it.phase) seen += it.phase
                it.phase.terminal
            }
        }
        assertTrue("phases were $seen", seen.containsAll(listOf(JobPhase.LoadingModel, JobPhase.ReadingPrompt, JobPhase.Generating)))
        assertEquals(JobPhase.Completed, seen.last())
        val s = job.state.value
        assertEquals(48, s.promptProcessed)
        assertEquals(3, s.completionTokens)
        assertEquals(3, s.maxTokens)
        assertTrue(s.maxTokensFromClient)
        assertEquals("length", s.finishReason)
        assertNotNull(s.loadMs)
        assertEquals("Good morning,", s.preview)
    }

    @Test
    fun `jobs run one at a time in the order they arrived`() = runBlocking {
        val backend = FakeBackend()
        val jobs = manager(backend)
        val submitted = (1..4).map { jobs.submit("com.example", "Example", request("job $it"), JobMode.Background) }
        submitted.forEach { withTimeout(5_000) { it.await() } }
        assertEquals(listOf("job 1", "job 2", "job 3", "job 4"), backend.started.toList())
        assertFalse(wake.held)
    }

    @Test
    fun `a full queue answers server_busy`() = runBlocking {
        val jobs = manager(FakeBackend(tokenMs = 200))
        repeat(5) { jobs.submit("com.example", "Example", request("job $it"), JobMode.Background) }
        try {
            jobs.submit("com.example", "Example", request("one too many"), JobMode.Background)
            fail("expected server_busy")
        } catch (e: ApiException) {
            assertEquals(ApiError.ServerBusy, e.error)
        }
        jobs.cancelAll()
    }

    @Test
    fun `cancelling stops the model and lets the next job run`() = runBlocking {
        val backend = FakeBackend(tokenMs = 300)
        val jobs = manager(backend)
        val first = jobs.submit("com.example", "Example", request("first"), JobMode.Background)
        val second = jobs.submit("com.example", "Example", request("second"), JobMode.Background)
        withTimeout(5_000) { first.state.first { it.phase == JobPhase.Generating } }
        jobs.cancel(first.id)
        assertEquals(JobOutcome.Cancelled, withTimeout(5_000) { first.await() })
        assertTrue(backend.cancelled.contains("first"))
        jobs.cancel(second.id) // while it is still writing
        assertEquals(JobOutcome.Cancelled, withTimeout(5_000) { second.await() })
        awaitLog(2)
        assertTrue(log.rows.all { it.status == 499 && it.errorCode == JobManager.CLIENT_CLOSED })
        assertFalse(wake.held)
        assertEquals(JobPhase.Cancelled, jobs.find(first.id, "com.example")!!.state.value.phase)
    }

    @Test
    fun `a request that does not fit fails before generating`() = runBlocking {
        val jobs = manager(FakeBackend(contextSize = 64))
        val job = jobs.submit("com.example", "Example", request("hi", maxTokens = 1000), JobMode.Background)
        val outcome = withTimeout(5_000) { job.await() } as JobOutcome.Failure
        assertEquals(ApiError.ContextLengthExceeded, outcome.error)
        awaitLog(1)
        assertEquals(400, log.rows.single().status)
        assertEquals("context_length_exceeded", job.state.value.errorCode)
    }

    @Test
    fun `jobs are private to the app that started them and only background results are kept`() = runBlocking {
        val jobs = manager(FakeBackend())
        val background = jobs.submit("com.example", "Example", request("a"), JobMode.Background)
        val blocking = jobs.submit("com.example", "Example", request("b"), JobMode.Blocking)
        withTimeout(5_000) {
            background.await()
            blocking.await()
        }
        awaitLog(2)
        assertNull(jobs.find(background.id, "com.other"))
        assertNotNull(jobs.find(background.id, "com.example"))
        assertNull("a delivered result is not kept", jobs.find(blocking.id, "com.example"))
    }

    @Test
    fun `a streaming job delivers start, deltas and the end in order`() = runBlocking {
        val jobs = manager(FakeBackend())
        val job = jobs.submit("com.example", "Example", request("hi"), JobMode.Stream)
        val events = mutableListOf<io.shubham0204.smollmandroid.jobs.JobEvent>()
        withTimeout(5_000) { for (e in job.events!!) events += e }
        assertTrue(events.first() is io.shubham0204.smollmandroid.jobs.JobEvent.Started)
        assertTrue(events.last() is io.shubham0204.smollmandroid.jobs.JobEvent.Finished)
        val text =
            events.filterIsInstance<io.shubham0204.smollmandroid.jobs.JobEvent.Delta>().joinToString("") { it.content }
        assertEquals("Good morning, world!", text)
    }
}

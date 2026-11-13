/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.data.StatRow
import io.shubham0204.smollmandroid.data.StatsCalculator
import io.shubham0204.smollmandroid.data.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsCalculatorTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun row(
        agoMs: Long,
        status: Int = 200,
        client: String? = "com.denser.june",
        label: String? = "June",
        path: String = StatsCalculator.CHAT_PATH,
        errorCode: String? = null,
        prompt: Int? = 900,
        completion: Int? = 301,
        durationMs: Long = 100_000,
        queueMs: Long? = 0,
        loadMs: Long? = null,
        promptMs: Long? = 40_000,
        generationMs: Long? = 60_000,
        cached: Int? = 500,
    ) =
        StatRow(
            timestamp = now - agoMs,
            clientPackage = client,
            clientLabel = label,
            path = path,
            status = status,
            errorCode = errorCode,
            promptTokens = prompt,
            completionTokens = completion,
            durationMs = durationMs,
            tokensPerSecond = null,
            queueMs = queueMs,
            loadMs = loadMs,
            promptMs = promptMs,
            generationMs = generationMs,
            cachedTokens = cached,
        )

    @Test
    fun `an empty log gives empty buckets and no averages`() {
        val s = StatsCalculator.compute(emptyList(), StatsPeriod.Day, now)
        assertEquals(0, s.requests)
        assertEquals(24, s.requestBuckets.size)
        assertNull(s.successRate)
        assertNull(s.tokensPerSecond)
    }

    @Test
    fun `counts, speeds and tokens`() {
        val rows =
            listOf(
                row(agoMs = 2 * hour),
                row(agoMs = 1 * hour, loadMs = 5_000, queueMs = 4_000),
                row(agoMs = hour / 2, status = 499, errorCode = "client_closed", completion = null, generationMs = null, promptMs = 10_000, prompt = 900, cached = 0),
                row(agoMs = hour / 4, status = 400, errorCode = "context_length_exceeded", prompt = null, completion = null, promptMs = null, generationMs = null, cached = null),
                row(agoMs = 10, status = 401, client = null, label = null, errorCode = "unauthorized", prompt = null, completion = null, promptMs = null, generationMs = null, cached = null),
                row(agoMs = 20, path = "/v1/models", prompt = null, completion = null, promptMs = null, generationMs = null, cached = null),
            )
        val s = StatsCalculator.compute(rows, StatsPeriod.Day, now)
        assertEquals(4, s.requests)
        assertEquals(2, s.succeeded)
        assertEquals(1, s.failed)
        assertEquals(1, s.cancelled)
        assertEquals(1, s.rejected)
        assertEquals(0.5f, s.successRate)
        assertEquals(602L, s.completionTokens)
        assertEquals(2700L, s.promptTokens)
        assertEquals(1000L, s.cachedTokens)
        // 2 x 300 tokens after the first one, in 2 x 60 s
        assertEquals(5.0, s.tokensPerSecond!!, 0.001)
        // (400 + 400 + 900) processed prompt tokens in 40 + 40 + 10 s
        assertEquals(1700 / 90.0, s.promptTokensPerSecond!!, 0.001)
        assertEquals(100_000L, s.averageDurationMs)
        assertEquals(1, s.modelLoads)
        assertEquals(1_000L, s.averageQueueMs)
        assertEquals(210_000L, s.busyMs)
        assertEquals(listOf("client_closed" to 1, "context_length_exceeded" to 1, "unauthorized" to 1), s.errors.sortedBy { it.first })
        assertEquals(4, s.requestBuckets.sum())
        assertEquals(2, s.failureBuckets.sum())
        assertEquals(1, s.requestBuckets[22]) // two hours ago
        assertEquals(3, s.requestBuckets[23]) // the last hour
    }

    @Test
    fun `groups by app and ignores rows outside the period`() {
        val rows =
            listOf(
                row(agoMs = 40 * hour),
                row(agoMs = 3 * hour),
                row(agoMs = 2 * hour, client = null, label = "Playground", completion = 11, generationMs = 1_000),
                row(agoMs = 1 * hour, status = 500, errorCode = "internal_error"),
            )
        val day = StatsCalculator.compute(rows, StatsPeriod.Day, now)
        assertEquals(3, day.requests)
        assertEquals(listOf("June", "Playground"), day.apps.map { it.label })
        assertEquals(2, day.apps[0].requests)
        assertEquals(1, day.apps[0].failed)
        assertEquals(10.0, day.apps[1].tokensPerSecond!!, 0.001)
        val week = StatsCalculator.compute(rows, StatsPeriod.Week, now)
        assertEquals(4, week.requests)
        assertEquals(7, week.requestBuckets.size)
    }
}

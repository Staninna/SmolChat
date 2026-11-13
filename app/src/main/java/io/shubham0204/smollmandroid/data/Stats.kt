/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

/** The request-log columns that statistics need (no prompt or response text). */
data class StatRow(
    val timestamp: Long,
    val clientPackage: String?,
    val clientLabel: String?,
    val path: String,
    val status: Int,
    val errorCode: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val durationMs: Long,
    val tokensPerSecond: Double?,
    val queueMs: Long?,
    val loadMs: Long?,
    val promptMs: Long?,
    val generationMs: Long?,
    val cachedTokens: Int?,
)

enum class StatsPeriod(val spanMs: Long, val buckets: Int) {
    Day(24 * 3_600_000L, 24),
    Week(7 * 86_400_000L, 7),
    Month(30 * 86_400_000L, 30),
}

data class AppUsage(
    val clientPackage: String?,
    val label: String,
    val requests: Int,
    val failed: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val tokensPerSecond: Double?,
    val lastUsedAt: Long,
)

data class UsageStats(
    /** Generations (chat completions) that reached the queue. */
    val requests: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    /** The client went away or the user stopped it. */
    val cancelled: Int = 0,
    /** Calls with a missing, unknown or revoked token. */
    val rejected: Int = 0,
    val promptTokens: Long = 0,
    val cachedTokens: Long = 0,
    val completionTokens: Long = 0,
    /** Generated tokens per second, weighted by generation time. */
    val tokensPerSecond: Double? = null,
    /** Prompt tokens actually processed per second (cached tokens excluded). */
    val promptTokensPerSecond: Double? = null,
    val averageDurationMs: Long? = null,
    val longestDurationMs: Long? = null,
    val averageQueueMs: Long? = null,
    /** Total time the model spent on prompts and generation. */
    val busyMs: Long = 0,
    val modelLoads: Int = 0,
    val apps: List<AppUsage> = emptyList(),
    val errors: List<Pair<String, Int>> = emptyList(),
    /** Generations per time bucket, oldest first. */
    val requestBuckets: List<Int> = emptyList(),
    /** Failed and cancelled generations per time bucket. */
    val failureBuckets: List<Int> = emptyList(),
) {
    val successRate: Float?
        get() = if (requests == 0) null else succeeded.toFloat() / requests
}

object StatsCalculator {
    const val CHAT_PATH = "/v1/chat/completions"
    private const val CLIENT_CLOSED_STATUS = 499

    fun compute(rows: List<StatRow>, period: StatsPeriod, now: Long): UsageStats {
        val since = now - period.spanMs
        val window = rows.filter { it.timestamp in since..now }
        val generations = window.filter { it.path == CHAT_PATH && it.status != 401 }
        if (window.isEmpty()) return UsageStats(requestBuckets = List(period.buckets) { 0 }, failureBuckets = List(period.buckets) { 0 })

        val ok = generations.filter { it.status in 200..299 }
        val cancelled = generations.count { it.status == CLIENT_CLOSED_STATUS }
        val bucketMs = period.spanMs / period.buckets
        val requestBuckets = IntArray(period.buckets)
        val failureBuckets = IntArray(period.buckets)
        for (g in generations) {
            val i = ((g.timestamp - since) / bucketMs).toInt().coerceIn(0, period.buckets - 1)
            requestBuckets[i]++
            if (g.status !in 200..299) failureBuckets[i]++
        }

        return UsageStats(
            requests = generations.size,
            succeeded = ok.size,
            failed = generations.size - ok.size - cancelled,
            cancelled = cancelled,
            rejected = window.count { it.status == 401 },
            promptTokens = generations.sumOf { (it.promptTokens ?: 0).toLong() },
            cachedTokens = generations.sumOf { (it.cachedTokens ?: 0).toLong() },
            completionTokens = generations.sumOf { (it.completionTokens ?: 0).toLong() },
            tokensPerSecond = generationSpeed(generations),
            promptTokensPerSecond = promptSpeed(generations),
            averageDurationMs = ok.takeIf { it.isNotEmpty() }?.map { it.durationMs }?.average()?.toLong(),
            longestDurationMs = generations.maxOfOrNull { it.durationMs },
            averageQueueMs = generations.mapNotNull { it.queueMs }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            busyMs = generations.sumOf { (it.promptMs ?: 0) + (it.generationMs ?: 0) },
            modelLoads = generations.count { it.loadMs != null },
            apps =
                generations
                    .groupBy { it.clientPackage ?: it.clientLabel }
                    .map { (_, list) ->
                        AppUsage(
                            clientPackage = list.last().clientPackage,
                            label = list.last().clientLabel ?: list.last().clientPackage ?: "?",
                            requests = list.size,
                            failed = list.count { it.status !in 200..299 },
                            promptTokens = list.sumOf { (it.promptTokens ?: 0).toLong() },
                            completionTokens = list.sumOf { (it.completionTokens ?: 0).toLong() },
                            tokensPerSecond = generationSpeed(list),
                            lastUsedAt = list.maxOf { it.timestamp },
                        )
                    }
                    .sortedByDescending { it.requests },
            errors =
                window
                    .filter { it.status !in 200..299 }
                    .groupingBy { it.errorCode ?: it.status.toString() }
                    .eachCount()
                    .toList()
                    .sortedByDescending { it.second },
            requestBuckets = requestBuckets.toList(),
            failureBuckets = failureBuckets.toList(),
        )
    }

    /** Total generated tokens over total generation time; falls back to the mean of the logged speeds. */
    private fun generationSpeed(rows: List<StatRow>): Double? {
        val timed = rows.filter { (it.generationMs ?: 0) > 0 && (it.completionTokens ?: 0) > 1 }
        if (timed.isNotEmpty()) {
            return timed.sumOf { it.completionTokens!!.toLong() - 1 } * 1000.0 / timed.sumOf { it.generationMs!! }
        }
        return rows.mapNotNull { it.tokensPerSecond?.takeIf { v -> v > 0 } }.takeIf { it.isNotEmpty() }?.average()
    }

    private fun promptSpeed(rows: List<StatRow>): Double? {
        val timed = rows.filter { (it.promptMs ?: 0) > 0 && (it.promptTokens ?: 0) > (it.cachedTokens ?: 0) }
        if (timed.isEmpty()) return null
        return timed.sumOf { (it.promptTokens!! - (it.cachedTokens ?: 0)).toLong() } * 1000.0 / timed.sumOf { it.promptMs!! }
    }
}

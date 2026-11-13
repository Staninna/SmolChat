/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An imported GGUF file in app-private storage. */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val architecture: String?,
    /** Context length the model was trained with (`<arch>.context_length`). */
    val trainedContextLength: Int?,
    val blockCount: Int?,
    val headCountKv: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
    /** Hybrid models (e.g. qwen35) only have full attention every N layers. */
    val fullAttentionInterval: Int?,
    val addedAt: Long,
)

/** A client app that was granted access through pairing. */
@Entity(tableName = "paired_apps")
data class PairedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val tokenHash: String,
    val pairedAt: Long,
    val lastUsedAt: Long? = null,
    val requestCount: Long = 0,
)

/** Request metadata. Bodies are only filled while the debug toggle is on. */
@Entity(tableName = "request_log", indices = [Index("timestamp")])
data class RequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val clientPackage: String?,
    val clientLabel: String?,
    val method: String,
    val path: String,
    val status: Int,
    val errorCode: String? = null,
    val stream: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val durationMs: Long,
    val tokensPerSecond: Double? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    // added in schema version 2; all nullable so old rows stay valid
    /** `blocking`, `stream`, `background` or `playground`. */
    val mode: String? = null,
    val model: String? = null,
    /** Time spent waiting for the inference slot. */
    val queueMs: Long? = null,
    /** Time spent loading the model, when this request had to load it. */
    val loadMs: Long? = null,
    val promptMs: Long? = null,
    val generationMs: Long? = null,
    /** Prompt tokens taken from the KV cache instead of being processed again. */
    val cachedTokens: Int? = null,
    val finishReason: String? = null,
)

/**
 * A background job on disk, so it outlives the server process: the request (to run or resume it),
 * a checkpoint of the answer so far, and the result until the client has fetched it.
 */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val requestId: String?,
    val clientPackage: String?,
    val clientLabel: String,
    val createdAt: Long,
    /** The resolved request as JSON. */
    val requestJson: String,
    /** A [io.shubham0204.smollmandroid.jobs.JobPhase] name; anything not terminal is resumed at start. */
    val phase: String,
    val finishedAt: Long? = null,
    val model: String? = null,
    val promptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val completionTokens: Int = 0,
    val content: String? = null,
    val reasoning: String? = null,
    val finishReason: String? = null,
    val promptMs: Double? = null,
    val generationMs: Double? = null,
    val tokensPerSecond: Double? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** The model's raw output so far. */
    val checkpointText: String? = null,
    val checkpointTokens: Int = 0,
    val resumes: Int = 0,
)

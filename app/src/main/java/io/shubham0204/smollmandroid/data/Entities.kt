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
)

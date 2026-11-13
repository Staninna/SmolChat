/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.engine

import io.shubham0204.smollmandroid.data.ModelEntity

object RamEstimate {
    /** Fixed overhead for compute buffers, the tokenizer and the runtime. */
    private const val OVERHEAD_BYTES = 350L * 1024 * 1024

    /**
     * Rough resident memory for running [model] with an n_ctx of [contextSize]: weights (file
     * size) + an f16 KV cache for the attention layers + a fixed overhead. Hybrid models such as
     * qwen35 only keep a KV cache for every `full_attention_interval`-th layer.
     */
    fun bytes(model: ModelEntity, contextSize: Int): Long {
        val layers = model.blockCount ?: return model.sizeBytes + OVERHEAD_BYTES
        val interval = model.fullAttentionInterval?.takeIf { it > 0 } ?: 1
        val attentionLayers = (layers + interval - 1) / interval
        val kvHeads = model.headCountKv ?: return model.sizeBytes + OVERHEAD_BYTES
        val k = model.keyLength ?: 128
        val v = model.valueLength ?: k
        val kvBytes = contextSize.toLong() * attentionLayers * kvHeads * (k + v) * 2L
        return model.sizeBytes + kvBytes + OVERHEAD_BYTES
    }
}

fun formatBytes(bytes: Long): String {
    val gib = bytes / (1024.0 * 1024 * 1024)
    return if (gib >= 1) "%.1f GB".format(gib) else "%.0f MB".format(bytes / (1024.0 * 1024))
}

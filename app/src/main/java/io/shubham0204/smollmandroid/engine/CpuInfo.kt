/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.engine

import java.io.File

object CpuInfo {
    /**
     * Number of "performance" cores: all cores except the slowest cluster, detected from each
     * core's maximum frequency. On a Pixel 10 (1x X4, 5x A725, 2x A520) this gives 6. Falls back
     * to half the cores (at least 1) if cpufreq is not readable.
     */
    fun performanceCoreCount(): Int {
        val freqs =
            (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                runCatching {
                        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                            .readText()
                            .trim()
                            .toLong()
                    }
                    .getOrNull()
            }
        return performanceCoresFromFrequencies(freqs, Runtime.getRuntime().availableProcessors())
    }

    internal fun performanceCoresFromFrequencies(maxFreqs: List<Long>, totalCores: Int): Int {
        if (maxFreqs.isEmpty()) return (totalCores / 2).coerceAtLeast(1)
        val slowest = maxFreqs.min()
        val fast = maxFreqs.count { it > slowest }
        // homogeneous CPU: use all cores but one, so the UI stays responsive
        return if (fast == 0) (maxFreqs.size - 1).coerceAtLeast(1) else fast
    }
}

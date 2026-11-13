/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.components

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.jobs.JobMode
import io.shubham0204.smollmandroid.jobs.JobPhase
import io.shubham0204.smollmandroid.jobs.JobSnapshot
import java.util.Locale
import kotlinx.coroutines.delay

/** Wall-clock time that ticks every [periodMs] while the caller is on screen. */
@Composable
fun rememberNow(periodMs: Long = 1_000L): Long {
    val now by
        produceState(System.currentTimeMillis(), periodMs) {
            while (true) {
                value = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    return now
}

/** `850 ms`, `12.4 s`, `3:05`, `1:02:03`. */
fun formatDuration(ms: Long): String {
    val v = ms.coerceAtLeast(0)
    return when {
        v < 1_000 -> "$v ms"
        v < 60_000 -> String.format(Locale.getDefault(), "%.1f s", v / 1000.0)
        else -> formatClock(v)
    }
}

/** `0:07`, `3:05`, `1:02:03`. */
fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** `950`, `1.2k`, `34k`, `1.2M`. */
fun formatCount(n: Long): String =
    when {
        n < 1_000 -> n.toString()
        n < 10_000 -> String.format(Locale.getDefault(), "%.1fk", n / 1000.0)
        n < 1_000_000 -> "${n / 1000}k"
        else -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
    }

@Composable
fun AppIcon(packageName: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon =
        remember(packageName) {
            packageName?.let {
                try {
                    context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap()
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = modifier)
    } else {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (packageName == null) Icons.Rounded.Science else Icons.Rounded.Android,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String?,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val container =
        if (highlight) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlight) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container, modifier = modifier) { inner() }
    } else {
        Surface(shape = shape, color = container, modifier = modifier) { inner() }
    }
}

/** The running generation first, then everything that waits behind it. */
@Composable
fun LiveJobs(jobs: List<JobSnapshot>, onCancel: (String) -> Unit, modifier: Modifier = Modifier) {
    if (jobs.isEmpty()) return
    val now = rememberNow()
    val running = jobs.firstOrNull { it.phase != JobPhase.Queued }
    val waiting = jobs.filter { it.id != running?.id }
    Column(modifier) {
        SectionHeader(stringResource(R.string.live_section))
        if (running != null) {
            LiveJobCard(running, now, onCancel = { onCancel(running.id) })
        }
        if (waiting.isNotEmpty()) {
            if (running != null) SectionHeader(stringResource(R.string.live_queue_section))
            ListGroup {
                waiting.forEachIndexed { i, job ->
                    GroupItem(i, waiting.size) { QueuedJobRow(job, i + 1, now, onCancel = { onCancel(job.id) }) }
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: JobMode): String =
    stringResource(
        when (mode) {
            JobMode.Background -> R.string.job_mode_background
            JobMode.Stream -> R.string.job_mode_stream
            JobMode.Blocking -> R.string.job_mode_blocking
            JobMode.Playground -> R.string.job_mode_playground
        }
    )

@Composable
private fun LiveJobCard(job: JobSnapshot, now: Long, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showPreview by rememberSaveable(job.id) { mutableStateOf(false) }
    val step =
        when (job.phase) {
            JobPhase.Queued -> 0
            JobPhase.LoadingModel -> 1
            JobPhase.ReadingPrompt -> 2
            else -> 3
        }
    val phaseTitle =
        stringResource(
            when (step) {
                0 -> R.string.job_phase_queued
                1 -> R.string.job_phase_loading
                2 -> R.string.job_phase_reading
                else -> R.string.job_phase_writing
            }
        )
    val detail =
        when (step) {
            2 -> stringResource(R.string.job_reading_detail, job.promptProcessed, job.promptTokens)
            3 ->
                if (job.maxTokensFromClient && job.maxTokens != null) {
                    stringResource(R.string.job_writing_detail_max, job.completionTokens, job.maxTokens)
                } else {
                    stringResource(R.string.job_writing_detail, job.completionTokens)
                }
            else -> null
        }
    // Reading has a real total; writing only has an upper bound, and only when the client set one.
    val fraction: Float? =
        when (step) {
            2 -> if (job.promptTokens > 0) job.promptProcessed.toFloat() / job.promptTokens else null
            3 ->
                job.maxTokens?.takeIf { job.maxTokensFromClient && it > 0 }?.let {
                    (job.completionTokens.toFloat() / it).coerceIn(0f, 1f)
                }
            else -> null
        }
    val promptRate: Double? =
        job.promptStartedAt?.let { start ->
            val done = job.promptProcessed - job.cachedTokens
            val secs = ((job.generationStartedAt ?: now) - start) / 1000.0
            if (done > 0 && secs > 0.5) done / secs else null
        }
    val left: Long? =
        if (step == 2 && promptRate != null) ((job.promptTokens - job.promptProcessed) / promptRate * 1000).toLong() else null

    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(job.clientPackage, Modifier.size(44.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.clientLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(modeLabel(job.mode), job.model).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onTertiaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val stopLabel = stringResource(R.string.job_stop_cd, job.clientLabel)
                FilledTonalIconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = stopLabel }) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                }
            }

            PhaseSteps(step, scheme.onTertiaryContainer)

            Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedContent(phaseTitle, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { title ->
                    Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
                }
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyLarge, color = scheme.onTertiaryContainer.copy(alpha = 0.8f))
                }
                if (step == 2 && job.cachedTokens > 0) {
                    Text(
                        stringResource(R.string.job_reading_cached, job.cachedTokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onTertiaryContainer.copy(alpha = 0.75f),
                    )
                }
            }

            if (fraction != null) {
                val animated by animateFloatAsState(fraction, label = "progress")
                LinearWavyProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.tertiary,
                    trackColor = scheme.onTertiaryContainer.copy(alpha = 0.16f),
                )
            } else {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.tertiary,
                    trackColor = scheme.onTertiaryContainer.copy(alpha = 0.16f),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cell = Modifier.weight(1f)
                JobMetric(stringResource(R.string.job_metric_elapsed), formatClock(now - job.createdAt), cell)
                when {
                    step == 3 ->
                        JobMetric(
                            stringResource(R.string.job_metric_speed),
                            job.tokensPerSecond?.let { String.format(Locale.getDefault(), "%.1f tok/s", it) } ?: "—",
                            cell,
                        )
                    else ->
                        JobMetric(
                            stringResource(R.string.job_metric_speed),
                            promptRate?.let { String.format(Locale.getDefault(), "%.0f tok/s", it) } ?: "—",
                            cell,
                        )
                }
                if (left != null) {
                    JobMetric(stringResource(R.string.job_metric_left), formatClock(left), cell)
                } else {
                    JobMetric(
                        stringResource(R.string.job_metric_prompt),
                        stringResource(R.string.job_tokens_short, formatCount(job.promptTokens.toLong())),
                        cell,
                    )
                }
            }

            if (job.preview.isNotBlank()) {
                Column {
                    TextButton(
                        onClick = { showPreview = !showPreview },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            stringResource(if (showPreview) R.string.job_preview_hide else R.string.job_preview_show),
                            color = scheme.onTertiaryContainer,
                        )
                    }
                    AnimatedVisibility(showPreview) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = scheme.onTertiaryContainer.copy(alpha = 0.08f),
                            contentColor = scheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.job_preview_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onTertiaryContainer.copy(alpha = 0.7f),
                                )
                                // the newest text matters: show the tail
                                Text(
                                    "…" + job.preview.takeLast(320).trimStart(),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            if (job.mode == JobMode.Background) {
                Text(
                    stringResource(R.string.job_keeps_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun JobMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, fontFamily = FontFamily.Default)
    }
}

/** Queue, Load, Read, Write: the steps a request goes through, with the current one filled in. */
@Composable
private fun PhaseSteps(current: Int, content: Color) {
    val labels =
        listOf(
            stringResource(R.string.job_step_queue),
            stringResource(R.string.job_step_load),
            stringResource(R.string.job_step_read),
            stringResource(R.string.job_step_write),
        )
    val description = stringResource(R.string.job_step_cd, current + 1, labels[current])
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val color by
                animateColorAsState(
                    when {
                        i < current -> content.copy(alpha = 0.55f)
                        i == current -> content
                        else -> content.copy(alpha = 0.16f)
                    },
                    label = "step",
                )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(color))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i == current) content else content.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun QueuedJobRow(job: JobSnapshot, position: Int, now: Long, onCancel: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(job.clientPackage, Modifier.size(40.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.clientLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.job_queue_position, position) + " · " + formatClock(now - job.createdAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val stopLabel = stringResource(R.string.job_stop_cd, job.clientLabel)
        IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = stopLabel }) {
            Icon(Icons.Rounded.Close, contentDescription = null)
        }
    }
}

/** Requests per time bucket; the failed part of each bar is drawn in the error colour. */
@Composable
fun RequestsChart(
    requests: List<Int>,
    failures: List<Int>,
    startLabel: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val peak = (requests.maxOrNull() ?: 0).coerceAtLeast(1)
    val description = stringResource(R.string.stats_chart_cd, requests.sum(), failures.sum())
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.stats_chart_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.stats_chart_peak, requests.maxOrNull() ?: 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            val ok = scheme.primary
            val bad = scheme.error
            val empty = scheme.surfaceContainerHighest
            Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
                val n = requests.size.coerceAtLeast(1)
                val gap = if (n > 12) 3.dp.toPx() else 6.dp.toPx()
                val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                val radius = CornerRadius((barWidth / 2).coerceAtMost(6.dp.toPx()))
                val floor = 4.dp.toPx()
                requests.forEachIndexed { i, count ->
                    val x = i * (barWidth + gap)
                    if (count == 0) {
                        drawRoundRect(empty, Offset(x, size.height - floor), Size(barWidth, floor), radius)
                    } else {
                        val h = (size.height * count / peak).coerceAtLeast(floor * 2)
                        drawRoundRect(ok, Offset(x, size.height - h), Size(barWidth, h), radius)
                        val failed = failures.getOrElse(i) { 0 }
                        if (failed > 0) {
                            val fh = (h * failed / count).coerceAtLeast(floor)
                            drawRoundRect(bad, Offset(x, size.height - fh), Size(barWidth, fh), radius)
                        }
                    }
                }
            }
            Row {
                Text(startLabel, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.stats_chart_now), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(ok, stringResource(R.string.stats_chart_ok))
                if (failures.any { it > 0 }) LegendDot(bad, stringResource(R.string.stats_chart_failed))
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One horizontal bar split by where a request spent its time. */
@Composable
fun TimingBar(queueMs: Long, loadMs: Long, promptMs: Long, generationMs: Long, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val parts =
        listOf(
            Triple(stringResource(R.string.log_timing_queue), queueMs, scheme.outline),
            Triple(stringResource(R.string.log_timing_load), loadMs, scheme.secondary),
            Triple(stringResource(R.string.log_timing_prompt), promptMs, scheme.tertiary),
            Triple(stringResource(R.string.log_timing_generation), generationMs, scheme.primary),
        )
            .filter { it.second > 0 }
    val total = parts.sumOf { it.second }
    if (total <= 0) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (_, ms, color) ->
                Box(Modifier.weight((ms.toFloat() / total).coerceAtLeast(0.02f)).height(10.dp).background(color))
            }
        }
        parts.forEach { (label, ms, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(color, label, Modifier.weight(1f))
                Text(formatDuration(ms), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import io.shubham0204.smollmandroid.data.StatsPeriod
import io.shubham0204.smollmandroid.data.UsageStats
import io.shubham0204.smollmandroid.ui.components.AppIcon
import io.shubham0204.smollmandroid.ui.components.ListGroup
import io.shubham0204.smollmandroid.ui.components.LiveJobs
import io.shubham0204.smollmandroid.ui.components.MetricTile
import io.shubham0204.smollmandroid.ui.components.RequestsChart
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.components.TimingBar
import io.shubham0204.smollmandroid.ui.components.formatCount
import io.shubham0204.smollmandroid.ui.components.formatDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.RequestLogEntity
import io.shubham0204.smollmandroid.ui.MainViewModel
import io.shubham0204.smollmandroid.ui.components.ContentColumn
import io.shubham0204.smollmandroid.ui.components.EmptyState
import io.shubham0204.smollmandroid.ui.components.GroupItem
import io.shubham0204.smollmandroid.ui.components.ListBottomPadding
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val log by vm.log.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val jobs by vm.activeJobs.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val period by vm.statsPeriod.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                subtitle = {
                    Text(
                        stringResource(if (settings.logBodies) R.string.log_subtitle_debug else R.string.log_subtitle)
                    )
                },
                actions = {
                    if (log.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.log_clear))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ContentColumn(Modifier.padding(padding)) {
            LazyColumn(contentPadding = ListBottomPadding) {
                if (settings.logBodies) {
                    item { DebugBanner(onOpenSettings) }
                }
                if (jobs.isNotEmpty()) {
                    item(key = "live") { LiveJobs(jobs, onCancel = vm::cancelJob, modifier = Modifier.animateItem()) }
                }
                item(key = "stats") { StatsSection(stats, period, vm::setStatsPeriod) }
                if (log.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.ReceiptLong,
                            title = stringResource(R.string.log_empty_title),
                            body = stringResource(R.string.log_empty_body),
                        )
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.log_section)) }
                    itemsIndexed(log, key = { _, e -> e.id }) { i, entry ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 1.dp)) {
                            GroupItem(i, log.size) { LogRow(entry) }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.log_clear_title)) },
            text = { Text(stringResource(R.string.log_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearLog()
                    confirmClear = false
                }) { Text(stringResource(R.string.log_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DebugBanner(onOpenSettings: () -> Unit) {
    Card(
        onClick = onOpenSettings,
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BugReport, contentDescription = null)
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.log_debug_banner), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatsSection(stats: UsageStats, period: StatsPeriod, onPeriod: (StatsPeriod) -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.stats_section))
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            StatsPeriod.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == period,
                    onClick = { onPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, StatsPeriod.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (p) {
                                StatsPeriod.Day -> R.string.stats_period_day
                                StatsPeriod.Week -> R.string.stats_period_week
                                StatsPeriod.Month -> R.string.stats_period_month
                            }
                        )
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            val tile = Modifier.weight(1f).widthIn(min = 150.dp)
            MetricTile(
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.stats_requests),
                value = stats.requests.toString(),
                detail =
                    when {
                        stats.requests == 0 -> stringResource(R.string.stats_requests_none)
                        stats.failed + stats.cancelled > 0 ->
                            stringResource(R.string.stats_requests_breakdown, stats.failed, stats.cancelled)
                        else -> stringResource(R.string.stats_requests_ok, 100)
                    },
                highlight = stats.failed > 0 && stats.failed * 2 >= stats.requests,
                modifier = tile,
            )
            MetricTile(
                icon = Icons.AutoMirrored.Rounded.Notes,
                label = stringResource(R.string.stats_tokens),
                value = formatCount(stats.completionTokens),
                detail =
                    if (stats.cachedTokens > 0 && stats.promptTokens > 0) {
                        stringResource(
                            R.string.stats_tokens_detail_cached,
                            formatCount(stats.promptTokens),
                            (stats.cachedTokens * 100 / stats.promptTokens).toInt(),
                        )
                    } else {
                        stringResource(R.string.stats_tokens_detail, formatCount(stats.promptTokens))
                    },
                modifier = tile,
            )
            MetricTile(
                icon = Icons.Rounded.Speed,
                label = stringResource(R.string.stats_speed),
                value = stats.tokensPerSecond?.let { "%.1f tok/s".format(it) } ?: "—",
                detail =
                    stats.promptTokensPerSecond?.let { stringResource(R.string.stats_speed_detail, it) }
                        ?: stringResource(R.string.stats_speed_none),
                modifier = tile,
            )
            MetricTile(
                icon = Icons.Rounded.Timer,
                label = stringResource(R.string.stats_duration),
                value = stats.averageDurationMs?.let { formatDuration(it) } ?: "—",
                detail = stats.longestDurationMs?.let { stringResource(R.string.stats_duration_detail, formatDuration(it)) },
                modifier = tile,
            )
            MetricTile(
                icon = Icons.Rounded.Memory,
                label = stringResource(R.string.stats_busy),
                value = formatDuration(stats.busyMs),
                detail = stringResource(R.string.stats_busy_detail, stats.modelLoads),
                modifier = tile,
            )
            MetricTile(
                icon = Icons.Rounded.HourglassTop,
                label = stringResource(R.string.stats_queue),
                value = stats.averageQueueMs?.let { formatDuration(it) } ?: "—",
                detail = stringResource(R.string.stats_queue_detail),
                modifier = tile,
            )
        }
        Spacer(Modifier.size(8.dp))
        RequestsChart(
            requests = stats.requestBuckets,
            failures = stats.failureBuckets,
            startLabel =
                stringResource(
                    when (period) {
                        StatsPeriod.Day -> R.string.stats_chart_start_day
                        StatsPeriod.Week -> R.string.stats_chart_start_week
                        StatsPeriod.Month -> R.string.stats_chart_start_month
                    }
                ),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (stats.apps.isNotEmpty()) {
            SectionHeader(stringResource(R.string.stats_apps_section))
            val most = stats.apps.maxOf { it.requests }.coerceAtLeast(1)
            ListGroup {
                stats.apps.forEachIndexed { i, app ->
                    GroupItem(i, stats.apps.size) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.clientPackage, Modifier.size(40.dp))
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    app.tokensPerSecond?.let {
                                        Text(
                                            "%.1f tok/s".format(it),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { app.requests.toFloat() / most },
                                    modifier = Modifier.fillMaxWidth(),
                                    drawStopIndicator = {},
                                )
                                Text(
                                    listOfNotNull(
                                        stringResource(
                                            R.string.stats_app_line,
                                            pluralStringResource(R.plurals.apps_requests, app.requests, app.requests),
                                            formatCount(app.completionTokens),
                                        ),
                                        app.failed.takeIf { it > 0 }?.let { stringResource(R.string.stats_app_failed, it) },
                                    )
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (stats.errors.isNotEmpty()) {
            SectionHeader(stringResource(R.string.stats_errors_section))
            ListGroup {
                stats.errors.forEachIndexed { i, (code, count) ->
                    GroupItem(i, stats.errors.size) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(errorLabel(code), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                stringResource(R.string.stats_error_count, count),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Plain words for the error codes of docs/LOCAL_LLM_API.md §4. */
@Composable
private fun errorLabel(code: String): String =
    when (code) {
        "client_closed" -> stringResource(R.string.error_client_closed)
        "context_length_exceeded" -> stringResource(R.string.error_context_length_exceeded)
        "server_busy" -> stringResource(R.string.error_server_busy)
        "model_not_loaded" -> stringResource(R.string.error_model_not_loaded)
        "internal_error" -> stringResource(R.string.error_internal_error)
        "invalid_request" -> stringResource(R.string.error_invalid_request)
        "unauthorized" -> stringResource(R.string.error_unauthorized)
        else -> code
    }

@Composable
private fun LogRow(entry: RequestLogEntity) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val hasBody = entry.requestBody != null || entry.responseBody != null
    val hasTimings = listOfNotNull(entry.queueMs, entry.loadMs, entry.promptMs, entry.generationMs).any { it > 0 }
    val ok = entry.status in 200..299
    val time = remember(entry.timestamp) { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timestamp)) }
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    entry.status.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                entry.clientLabel ?: stringResource(R.string.log_unknown_client),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${entry.method} ${entry.path}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val details =
            listOfNotNull(
                entry.errorCode?.let { errorLabel(it) },
                entry.mode,
                entry.promptTokens?.let { stringResource(R.string.log_tokens, it, entry.completionTokens ?: 0) },
                entry.tokensPerSecond?.let { "%.1f tok/s".format(it) },
                formatDuration(entry.durationMs),
            )
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hasBody || hasTimings) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(if (expanded) R.string.log_details_hide else R.string.log_details_show))
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimingBar(
                        queueMs = entry.queueMs ?: 0,
                        loadMs = entry.loadMs ?: 0,
                        promptMs = entry.promptMs ?: 0,
                        generationMs = entry.generationMs ?: 0,
                    )
                    val notes =
                        listOfNotNull(
                            entry.model?.let { stringResource(R.string.log_model, it) },
                            entry.cachedTokens?.takeIf { it > 0 }?.let {
                                stringResource(R.string.log_cached, it, entry.promptTokens ?: it)
                            },
                            stringResource(R.string.log_finish_length).takeIf { entry.finishReason == "length" },
                        )
                    notes.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (hasBody) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                entry.requestBody?.let { BodyBlock(stringResource(R.string.log_request), it) }
                                entry.responseBody?.let { BodyBlock(stringResource(R.string.log_response), it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(text.take(8_000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
        }
    }
}

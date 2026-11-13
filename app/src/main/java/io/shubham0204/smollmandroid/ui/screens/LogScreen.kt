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
                if (log.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.ReceiptLong,
                            title = stringResource(R.string.log_empty_title),
                            body = stringResource(R.string.log_empty_body),
                        )
                    }
                } else {
                    item { Spacer(Modifier.size(8.dp)) }
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
private fun LogRow(entry: RequestLogEntity) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val hasBody = entry.requestBody != null || entry.responseBody != null
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
                "${entry.method} ${entry.path}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val details =
            listOfNotNull(
                entry.clientLabel ?: stringResource(R.string.log_unknown_client),
                entry.errorCode,
                if (entry.stream) "stream" else null,
                entry.promptTokens?.let { stringResource(R.string.log_tokens, it, entry.completionTokens ?: 0) },
                entry.tokensPerSecond?.let { "%.1f tok/s".format(it) },
                "${entry.durationMs} ms",
            )
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hasBody) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(if (expanded) R.string.log_hide_bodies else R.string.log_show_bodies))
            }
            AnimatedVisibility(expanded) {
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

@Composable
private fun BodyBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(text.take(8_000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
        }
    }
}

/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.ServerSettings
import io.shubham0204.smollmandroid.engine.CpuInfo
import io.shubham0204.smollmandroid.ui.MainViewModel
import io.shubham0204.smollmandroid.ui.components.ContentColumn
import io.shubham0204.smollmandroid.ui.components.GroupItem
import io.shubham0204.smollmandroid.ui.components.ListGroup
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import kotlin.math.roundToInt

private data class IntEdit(val title: Int, val value: Int, val range: IntRange, val help: Int?, val apply: (ServerSettings, Int) -> ServerSettings)

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var intEdit by remember { mutableStateOf<IntEdit?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val autoThreads = remember { CpuInfo.performanceCoreCount() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ContentColumn(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                SectionHeader(stringResource(R.string.settings_section_server))
                ListGroup {
                    ValueRow(0, 1, stringResource(R.string.settings_port), "127.0.0.1:${s.port}", stringResource(R.string.settings_port_help)) {
                        intEdit = IntEdit(R.string.settings_port, s.port, 1024..65535, R.string.settings_port_help) { st, v -> st.copy(port = v) }
                    }
                }

                SectionHeader(stringResource(R.string.settings_section_runtime))
                ListGroup {
                    ValueRow(0, 5, stringResource(R.string.settings_context), stringResource(R.string.settings_tokens, s.contextSize), stringResource(R.string.settings_context_help)) {
                        intEdit = IntEdit(R.string.settings_context, s.contextSize, 512..131072, R.string.settings_context_help) { st, v -> st.copy(contextSize = v) }
                    }
                    ValueRow(
                        1, 5,
                        stringResource(R.string.settings_threads),
                        if (s.threads > 0) s.threads.toString() else stringResource(R.string.settings_threads_auto, autoThreads),
                        stringResource(R.string.settings_threads_help),
                    ) {
                        intEdit = IntEdit(R.string.settings_threads, s.threads, 0..32, R.string.settings_threads_help) { st, v -> st.copy(threads = v) }
                    }
                    ValueRow(
                        2, 5,
                        stringResource(R.string.settings_idle),
                        if (s.idleUnloadMinutes > 0) stringResource(R.string.metric_idle_value, s.idleUnloadMinutes) else stringResource(R.string.metric_idle_never),
                        stringResource(R.string.settings_idle_help),
                    ) {
                        intEdit = IntEdit(R.string.settings_idle, s.idleUnloadMinutes, 0..1440, R.string.settings_idle_help) { st, v -> st.copy(idleUnloadMinutes = v) }
                    }
                    SwitchRow(3, 5, stringResource(R.string.settings_mmap), stringResource(R.string.settings_mmap_help), s.useMmap) { v -> vm.updateSettings { it.copy(useMmap = v) } }
                    SwitchRow(4, 5, stringResource(R.string.settings_mlock), stringResource(R.string.settings_mlock_help), s.useMlock) { v -> vm.updateSettings { it.copy(useMlock = v) } }
                }

                SectionHeader(stringResource(R.string.settings_section_sampling))
                ListGroup {
                    SliderRow(0, 6, stringResource(R.string.settings_temperature), s.temperature, 0f..2f, 40) { v -> vm.updateSettings { it.copy(temperature = v) } }
                    SliderRow(1, 6, stringResource(R.string.settings_top_p), s.topP, 0f..1f, 20) { v -> vm.updateSettings { it.copy(topP = v) } }
                    ValueRow(2, 6, stringResource(R.string.settings_top_k), if (s.topK == 0) stringResource(R.string.settings_off) else s.topK.toString(), null) {
                        intEdit = IntEdit(R.string.settings_top_k, s.topK, 0..200, null) { st, v -> st.copy(topK = v) }
                    }
                    SliderRow(3, 6, stringResource(R.string.settings_min_p), s.minP, 0f..1f, 20) { v -> vm.updateSettings { it.copy(minP = v) } }
                    SliderRow(4, 6, stringResource(R.string.settings_presence), s.presencePenalty, -2f..2f, 40) { v -> vm.updateSettings { it.copy(presencePenalty = v) } }
                    SwitchRow(5, 6, stringResource(R.string.settings_thinking), stringResource(R.string.settings_thinking_help), s.enableThinking) { v -> vm.updateSettings { it.copy(enableThinking = v) } }
                }

                SectionHeader(stringResource(R.string.settings_section_privacy))
                ListGroup {
                    SwitchRow(0, 1, stringResource(R.string.settings_log_bodies), stringResource(R.string.settings_log_bodies_help), s.logBodies) { v -> vm.updateSettings { it.copy(logBodies = v) } }
                }

                Row(Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                    OutlinedButton(onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_restore))
                    }
                }

                SectionHeader(stringResource(R.string.settings_section_about))
                Text(
                    stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }

    intEdit?.let { edit ->
        IntDialog(edit, onDismiss = { intEdit = null }) { v ->
            vm.updateSettings { edit.apply(it, v) }
            intEdit = null
        }
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_restore)) },
            text = { Text(stringResource(R.string.settings_restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.restoreDefaults()
                    confirmRestore = false
                }) { Text(stringResource(R.string.settings_restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ValueRow(index: Int, count: Int, title: String, value: String, help: String?, onClick: () -> Unit) {
    GroupItem(index, count, onClick = onClick) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(index: Int, count: Int, title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    GroupItem(index, count) {
        Row(
            Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (help != null) {
                    Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(16.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun SliderRow(
    index: Int,
    count: Int,
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    val shown = "%.2f".format(local)
    GroupItem(index, count) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(shown, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = local,
                onValueChange = { local = ((it * 100).roundToInt() / 100f) },
                onValueChangeFinished = { onChange(local) },
                valueRange = range,
                steps = steps - 1,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}

@Composable
private fun IntDialog(edit: IntEdit, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(edit.value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in edit.range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(edit.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                edit.help?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { Text(stringResource(R.string.settings_range, edit.range.first, edit.range.last)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

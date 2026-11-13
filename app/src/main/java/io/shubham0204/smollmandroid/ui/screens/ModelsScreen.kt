/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.ModelEntity
import io.shubham0204.smollmandroid.engine.RamEstimate
import io.shubham0204.smollmandroid.engine.formatBytes
import io.shubham0204.smollmandroid.ui.MainViewModel
import io.shubham0204.smollmandroid.ui.components.ContentColumn
import io.shubham0204.smollmandroid.ui.components.EmptyState
import io.shubham0204.smollmandroid.ui.components.ListBottomPadding
import io.shubham0204.smollmandroid.ui.components.Pill
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.components.groupShape

@Composable
fun ModelsScreen(vm: MainViewModel) {
    val models by vm.models.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val import by vm.import.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var toDelete by remember { mutableStateOf<ModelEntity?>(null) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val name =
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                vm.importModel(uri, name)
            }
        }

    LaunchedEffect(import.error) {
        import.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissImportError()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                subtitle = { Text(stringResource(R.string.models_subtitle)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (import.fileName == null) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_import_model)) },
                )
            }
        },
    ) { padding ->
        ContentColumn(Modifier.padding(padding)) {
            LazyColumn(contentPadding = ListBottomPadding) {
                item {
                    AnimatedVisibility(import.fileName != null) {
                        ImportProgressCard(import.fileName.orEmpty(), import.progress, vm::cancelImport)
                    }
                }
                if (models.isEmpty() && import.fileName == null) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Inventory2,
                            title = stringResource(R.string.models_empty_title),
                            body = stringResource(R.string.models_empty_body),
                        )
                    }
                } else if (models.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.models_section)) }
                    itemsIndexed(models, key = { _, m -> m.id }) { i, model ->
                        ModelRow(
                            model = model,
                            active = model.id == settings.activeModelId,
                            ramBytes = RamEstimate.bytes(model, settings.contextSize),
                            contextSize = settings.contextSize,
                            shape = groupShape(i, models.size),
                            onSelect = { vm.selectModel(model) },
                            onDelete = { toDelete = model },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    item {
                        Text(
                            stringResource(R.string.models_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(stringResource(R.string.models_delete_body, model.displayName, formatBytes(model.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(model)
                    toDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ImportProgressCard(fileName: String, progress: Float, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.models_importing), style = MaterialTheme.typography.titleMedium)
            Text(fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelEntity,
    active: Boolean,
    ramBytes: Long,
    contextSize: Int,
    shape: RoundedCornerShape,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Card(
        modifier =
            modifier
                .padding(horizontal = 16.dp, vertical = 1.dp)
                .fillMaxWidth()
                .selectable(selected = active, onClick = onSelect, role = Role.RadioButton),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.architecture?.let { Pill(it) }
                    Pill(formatBytes(model.sizeBytes))
                }
                Text(
                    stringResource(R.string.models_ram, formatBytes(ramBytes), contextSize) +
                        (model.trainedContextLength?.let { "\n" + stringResource(R.string.models_trained_ctx, it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.models_delete_cd, model.displayName))
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

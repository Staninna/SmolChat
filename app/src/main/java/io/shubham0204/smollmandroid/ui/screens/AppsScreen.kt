/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.PairedAppEntity
import io.shubham0204.smollmandroid.ui.MainViewModel
import io.shubham0204.smollmandroid.ui.components.ContentColumn
import io.shubham0204.smollmandroid.ui.components.EmptyState
import io.shubham0204.smollmandroid.ui.components.GroupItem
import io.shubham0204.smollmandroid.ui.components.ListBottomPadding
import io.shubham0204.smollmandroid.ui.components.Pill
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.components.relativeTime

@Composable
fun AppsScreen(vm: MainViewModel) {
    val apps by vm.pairedApps.collectAsStateWithLifecycle()
    val jobs by vm.activeJobs.collectAsStateWithLifecycle()
    var toRevoke by remember { mutableStateOf<PairedAppEntity?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.apps_title)) },
                subtitle = { Text(stringResource(R.string.apps_subtitle)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ContentColumn(Modifier.padding(padding)) {
            LazyColumn(contentPadding = ListBottomPadding) {
                if (apps.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Apps,
                            title = stringResource(R.string.apps_empty_title),
                            body = stringResource(R.string.apps_empty_body),
                        )
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.apps_section)) }
                    itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                        GroupItem(i, apps.size, modifier = Modifier.animateItem()) {
                            AppRow(app, active = jobs.any { it.clientPackage == app.packageName }, onRevoke = { toRevoke = app })
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.apps_network_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    toRevoke?.let { app ->
        AlertDialog(
            onDismissRequest = { toRevoke = null },
            icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
            title = { Text(stringResource(R.string.apps_revoke_title, app.label)) },
            text = { Text(stringResource(R.string.apps_revoke_body, app.label)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.revoke(app)
                    toRevoke = null
                }) { Text(stringResource(R.string.action_revoke)) }
            },
            dismissButton = { TextButton(onClick = { toRevoke = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AppRow(app: PairedAppEntity, active: Boolean, onRevoke: () -> Unit) {
    val context = LocalContext.current
    val icon =
        remember(app.packageName) {
            try {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
        } else {
            Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                if (active) Pill(stringResource(R.string.apps_active_now))
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (app.lastUsedAt?.let { stringResource(R.string.apps_last_used, relativeTime(it)) }
                    ?: stringResource(R.string.apps_never_used)) +
                    " · " + pluralStringResource(R.plurals.apps_requests, app.requestCount.toInt(), app.requestCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = onRevoke) { Text(stringResource(R.string.action_revoke)) }
    }
}

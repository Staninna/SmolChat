/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.ui.MainViewModel
import io.shubham0204.smollmandroid.ui.components.ContentColumn
import io.shubham0204.smollmandroid.ui.components.GroupItem
import io.shubham0204.smollmandroid.ui.components.ListGroup
import io.shubham0204.smollmandroid.ui.components.MaterialShapesCookie
import io.shubham0204.smollmandroid.ui.components.SetupChecks

/** First-run checks: notifications, battery optimisation, the GrapheneOS Network note and a model. */
@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit, onImportModel: () -> Unit) {
    val context = LocalContext.current
    val models by vm.models.collectAsStateWithLifecycle()
    var version by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose {}
    }
    val notificationsOk = remember(version) { SetupChecks.notificationsAllowed(context) }
    val batteryOk = remember(version) { SetupChecks.batteryUnrestricted(context) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }

    Scaffold { padding ->
        ContentColumn(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialShapesCookie, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text(
                        stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ListGroup {
                    CheckRow(
                        0, 4, Icons.Rounded.Notifications,
                        stringResource(R.string.onboarding_notifications),
                        stringResource(R.string.onboarding_notifications_body),
                        done = notificationsOk,
                        action = stringResource(R.string.action_allow),
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    CheckRow(
                        1, 4, Icons.Rounded.BatteryChargingFull,
                        stringResource(R.string.onboarding_battery),
                        stringResource(R.string.onboarding_battery_body),
                        done = batteryOk,
                        action = stringResource(R.string.action_allow),
                    ) { context.startActivity(SetupChecks.batteryExemptionIntent(context)) }
                    CheckRow(
                        2, 4, Icons.Rounded.Wifi,
                        stringResource(R.string.onboarding_network),
                        stringResource(R.string.onboarding_network_body),
                        done = null,
                        action = null,
                    ) {}
                    CheckRow(
                        3, 4, Icons.Rounded.Inventory2,
                        stringResource(R.string.onboarding_model),
                        stringResource(R.string.onboarding_model_body),
                        done = models.isNotEmpty(),
                        action = stringResource(R.string.action_import_model),
                        onAction = onImportModel,
                    )
                }
                Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.End) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.onboarding_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    body: String,
    done: Boolean?,
    action: String?,
    onAction: () -> Unit,
) {
    GroupItem(index, count) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    done == true ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.onboarding_ok), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    action != null -> FilledTonalButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}


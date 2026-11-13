/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.engine.EngineStatus
import io.shubham0204.smollmandroid.engine.formatBytes
import io.shubham0204.smollmandroid.ui.MainViewModel
import io.shubham0204.smollmandroid.ui.components.ContentColumn
import io.shubham0204.smollmandroid.ui.components.GroupItem
import io.shubham0204.smollmandroid.ui.components.ListGroup
import io.shubham0204.smollmandroid.ui.components.MaterialShapesCookie
import io.shubham0204.smollmandroid.ui.components.SectionHeader
import io.shubham0204.smollmandroid.ui.components.SetupChecks
import io.shubham0204.smollmandroid.ui.components.relativeTime

private enum class Headline { Stopped, NoModel, Loading, Ready, Generating, Unloaded, Error }

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val http by vm.httpState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.pairedApps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var checksVersion by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checksVersion++
        onPauseOrDispose {}
    }
    val missingSetup =
        remember(checksVersion) {
            !SetupChecks.notificationsAllowed(context) || !SetupChecks.batteryUnrestricted(context)
        }

    val headline =
        when {
            http.error != null -> Headline.Error
            engine.status == EngineStatus.NoModel -> Headline.NoModel
            !http.running -> Headline.Stopped
            engine.status == EngineStatus.Loading -> Headline.Loading
            engine.status == EngineStatus.Generating -> Headline.Generating
            engine.status == EngineStatus.Ready -> Headline.Ready
            engine.status == EngineStatus.Error -> Headline.Error
            else -> Headline.Unloaded
        }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                subtitle = { Text(stringResource(R.string.home_subtitle)) },
                actions = {
                    IconWithTooltip(Icons.Rounded.Science, stringResource(R.string.playground_title), onOpenPlayground)
                    IconWithTooltip(Icons.Outlined.Settings, stringResource(R.string.settings_title), onOpenSettings)
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ContentColumn(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                StatusCard(
                    headline = headline,
                    port = http.port ?: settings.port,
                    error = http.error ?: engine.error,
                    queue = engine.queueLength,
                    liveTps = engine.liveTokensPerSecond,
                    running = http.running,
                    hasModel = engine.status != EngineStatus.NoModel,
                    onStart = vm::startServer,
                    onStop = vm::stopServer,
                    onImport = onOpenModels,
                )

                AnimatedVisibility(missingSetup, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SetupBanner(onOpenSetup)
                }

                SectionHeader(stringResource(R.string.home_section_overview))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                ) {
                    val tile = Modifier.weight(1f).widthIn(min = 150.dp)
                    MetricTile(
                        icon = Icons.Rounded.Inventory2,
                        label = stringResource(R.string.metric_model),
                        value = engine.model?.displayName ?: stringResource(R.string.metric_none),
                        detail =
                            engine.model?.let { m ->
                                listOfNotNull(m.architecture, formatBytes(m.sizeBytes)).joinToString(" · ")
                            },
                        modifier = tile,
                        onClick = onOpenModels,
                    )
                    MetricTile(
                        icon = Icons.Rounded.Memory,
                        label = stringResource(R.string.metric_ram),
                        value = if (engine.ramEstimateBytes > 0) "≈ " + formatBytes(engine.ramEstimateBytes) else "—",
                        detail = stringResource(R.string.metric_ram_detail, settings.contextSize),
                        modifier = tile,
                    )
                    MetricTile(
                        icon = Icons.Rounded.Speed,
                        label = stringResource(R.string.metric_speed),
                        value =
                            (engine.liveTokensPerSecond ?: engine.lastTokensPerSecond)?.let { "%.1f tok/s".format(it) }
                                ?: "—",
                        detail =
                            if (engine.liveTokensPerSecond != null) stringResource(R.string.metric_speed_live)
                            else stringResource(R.string.metric_speed_last),
                        modifier = tile,
                    )
                    MetricTile(
                        icon = Icons.Rounded.Apps,
                        label = stringResource(R.string.metric_apps),
                        value = apps.size.toString(),
                        detail =
                            apps.firstOrNull()?.lastUsedAt?.let { stringResource(R.string.metric_apps_last, relativeTime(it)) }
                                ?: stringResource(R.string.metric_apps_none),
                        modifier = tile,
                        onClick = onOpenApps,
                    )
                    MetricTile(
                        icon = Icons.Rounded.Tune,
                        label = stringResource(R.string.metric_runtime),
                        value = stringResource(R.string.metric_runtime_value, settings.contextSize),
                        detail =
                            if (engine.threads > 0) stringResource(R.string.metric_threads, engine.threads)
                            else stringResource(R.string.metric_threads_auto),
                        modifier = tile,
                        onClick = onOpenSettings,
                    )
                    MetricTile(
                        icon = Icons.Rounded.Bedtime,
                        label = stringResource(R.string.metric_idle),
                        value =
                            if (settings.idleUnloadMinutes > 0)
                                stringResource(R.string.metric_idle_value, settings.idleUnloadMinutes)
                            else stringResource(R.string.metric_idle_never),
                        detail = stringResource(R.string.metric_idle_detail),
                        modifier = tile,
                        onClick = onOpenSettings,
                    )
                }

                if (apps.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.home_section_apps))
                    ListGroup {
                        val shown = apps.take(3)
                        shown.forEachIndexed { i, app ->
                            GroupItem(i, shown.size, onClick = onOpenApps) {
                                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            app.lastUsedAt?.let { stringResource(R.string.apps_last_used, relativeTime(it)) }
                                                ?: stringResource(R.string.apps_never_used),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        pluralStringResource(R.plurals.apps_requests, app.requestCount.toInt(), app.requestCount),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(16.dp))
                Text(
                    stringResource(R.string.home_privacy_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun pluralStringResource(id: Int, count: Int, vararg args: Any): String =
    androidx.compose.ui.res.pluralStringResource(id, count, *args)

@Composable
private fun IconWithTooltip(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun StatusCard(
    headline: Headline,
    port: Int,
    error: String?,
    queue: Int,
    liveTps: Double?,
    running: Boolean,
    hasModel: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) =
        when (headline) {
            Headline.Ready -> scheme.primaryContainer to scheme.onPrimaryContainer
            Headline.Generating -> scheme.tertiaryContainer to scheme.onTertiaryContainer
            Headline.Loading -> scheme.secondaryContainer to scheme.onSecondaryContainer
            Headline.Error -> scheme.errorContainer to scheme.onErrorContainer
            else -> scheme.surfaceContainerHigh to scheme.onSurface
        }
    val containerColor by animateColorAsState(container, MaterialTheme.motionScheme.slowEffectsSpec(), label = "status")
    val contentColor by animateColorAsState(content, MaterialTheme.motionScheme.slowEffectsSpec(), label = "statusContent")

    val title =
        stringResource(
            when (headline) {
                Headline.Stopped -> R.string.status_stopped
                Headline.NoModel -> R.string.status_no_model
                Headline.Loading -> R.string.status_loading
                Headline.Ready -> R.string.status_ready
                Headline.Generating -> R.string.status_generating
                Headline.Unloaded -> R.string.status_unloaded
                Headline.Error -> R.string.status_error
            }
        )
    val body =
        when (headline) {
            Headline.Stopped -> stringResource(R.string.status_stopped_body)
            Headline.NoModel -> stringResource(R.string.status_no_model_body)
            Headline.Loading -> stringResource(R.string.status_loading_body)
            Headline.Ready -> stringResource(R.string.status_ready_body, port)
            Headline.Generating ->
                buildString {
                    append(liveTps?.let { "%.1f tok/s".format(it) } ?: stringResource(R.string.status_generating_body))
                    if (queue > 0) append(" · ").append(stringResource(R.string.status_queue, queue))
                }
            Headline.Unloaded -> stringResource(R.string.status_unloaded_body, port)
            Headline.Error -> error ?: stringResource(R.string.status_error_body)
        }

    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = headline,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "statusIcon",
                    ) { h ->
                        when (h) {
                            Headline.Loading, Headline.Generating ->
                                LoadingIndicator(Modifier.size(72.dp), color = contentColor)
                            else ->
                                Surface(shape = MaterialShapesCookie, color = contentColor.copy(alpha = 0.12f), modifier = Modifier.size(72.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            when (h) {
                                                Headline.Ready -> Icons.Rounded.CheckCircle
                                                Headline.Error -> Icons.Rounded.ErrorOutline
                                                Headline.Unloaded -> Icons.Rounded.Bedtime
                                                Headline.NoModel -> Icons.Rounded.Inventory2
                                                else -> Icons.Rounded.CloudOff
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }
                                }
                        }
                    }
                }
                Spacer(Modifier.size(20.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(20.dp))
            when {
                !hasModel ->
                    Button(onClick = onImport, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.Inventory2, contentDescription = null)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_import_model))
                    }
                running ->
                    FilledTonalButton(onClick = onStop, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_stop_server))
                    }
                else ->
                    Button(onClick = onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_start_server))
                    }
            }
        }
    }
}

@Composable
private fun SetupBanner(onOpenSetup: () -> Unit) {
    Card(
        onClick = onOpenSetup,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_banner_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.setup_banner_body), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
        Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) { inner() }
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) { inner() }
    }
}


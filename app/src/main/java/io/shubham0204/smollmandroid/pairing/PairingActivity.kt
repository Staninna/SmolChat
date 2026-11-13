/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.pairing

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.data.SettingsRepository
import io.shubham0204.smollmandroid.ui.theme.ServerTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Handles `io.shubham0204.smollmandroid.action.PAIR` (docs/LOCAL_LLM_API.md §3). The caller is
 * identified by [getCallingPackage]; its label and icon come from the PackageManager so an app
 * cannot impersonate another one through `client_name`.
 */
class PairingActivity : ComponentActivity() {
    private val pairing by inject<PairingRepository>()
    private val settings by inject<SettingsRepository>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        if (caller == null || caller == packageName) {
            finish()
            return
        }
        val requestedName = intent.getStringExtra(LocalApi.EXTRA_CLIENT_NAME)?.take(60)
        val (label, icon) = appInfo(caller)
        val displayName = label ?: requestedName ?: caller
        setContent {
            ServerTheme {
                PairingDialog(
                    appName = displayName,
                    packageName = caller,
                    icon = icon,
                    onAllow = {
                        lifecycleScope.launch {
                            val token = pairing.pair(caller, displayName)
                            val port = settings.current.port
                            setResult(
                                Activity.RESULT_OK,
                                Intent()
                                    .putExtra(LocalApi.EXTRA_TOKEN, token)
                                    .putExtra(LocalApi.EXTRA_BASE_URL, LocalApi.baseUrl(port))
                                    .putExtra(LocalApi.EXTRA_API_VERSION, LocalApi.API_VERSION)
                                    .putExtra(LocalApi.EXTRA_SERVER_PACKAGE, packageName),
                            )
                            finish()
                        }
                    },
                    onDeny = { finish() },
                )
            }
        }
    }

    private fun appInfo(pkg: String): Pair<String?, Drawable?> =
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString() to packageManager.getApplicationIcon(info)
        } catch (_: PackageManager.NameNotFoundException) {
            null to null
        }
}

@Composable
private fun PairingDialog(
    appName: String,
    packageName: String,
    icon: Drawable?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val bitmap = remember(icon) { icon?.toBitmap(144, 144)?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Icon(Icons.Rounded.Android, contentDescription = null)
            }
        },
        title = {
            Text(
                stringResource(R.string.pair_title, appName),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text(
                stringResource(R.string.pair_body, appName, packageName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.pair_allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.pair_deny)) } },
    )
}

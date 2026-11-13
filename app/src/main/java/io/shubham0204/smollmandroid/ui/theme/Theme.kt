/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (seed: calm teal) for devices without dynamic color.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF006A65),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF9DF2EA),
        onPrimaryContainer = Color(0xFF00201E),
        secondary = Color(0xFF4A6361),
        secondaryContainer = Color(0xFFCCE8E5),
        onSecondaryContainer = Color(0xFF051F1E),
        tertiary = Color(0xFF48607B),
        tertiaryContainer = Color(0xFFD0E4FF),
        onTertiaryContainer = Color(0xFF001D34),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        surface = Color(0xFFF4FBF9),
        surfaceContainerLow = Color(0xFFEFF5F3),
        surfaceContainer = Color(0xFFE9EFED),
        surfaceContainerHigh = Color(0xFFE3EAE8),
        surfaceContainerHighest = Color(0xFFDDE4E2),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF81D5CE),
        onPrimary = Color(0xFF003734),
        primaryContainer = Color(0xFF00504C),
        onPrimaryContainer = Color(0xFF9DF2EA),
        secondary = Color(0xFFB0CCC9),
        secondaryContainer = Color(0xFF324B49),
        onSecondaryContainer = Color(0xFFCCE8E5),
        tertiary = Color(0xFFB0C9E8),
        tertiaryContainer = Color(0xFF304862),
        onTertiaryContainer = Color(0xFFD0E4FF),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        surface = Color(0xFF0E1514),
        surfaceContainerLow = Color(0xFF161D1C),
        surfaceContainer = Color(0xFF1A2120),
        surfaceContainerHigh = Color(0xFF252B2A),
        surfaceContainerHighest = Color(0xFF2F3635),
    )

@Composable
fun ServerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}

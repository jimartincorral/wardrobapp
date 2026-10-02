package com.wardrobapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.usesDarkColors

/**
 * Material 3, in the colours the app has been asked for.
 *
 * Dynamic colour where the platform offers it (Android 12+), which is the native
 * behaviour a user expects and something the React Native app could not do.
 * Everywhere else -- older phones, the browser -- Material's own baseline
 * scheme, which is what an Android 11 phone has always been shown.
 *
 * [choice] decides light or dark and defaults to following the device, so a caller
 * that has nothing stored yet needs to pass nothing. Which of the two the device
 * is set to is read here rather than passed in -- `isSystemInDarkTheme()` is a
 * composition-local read that recomposes when the setting changes, and the pure
 * decision it feeds sits in [usesDarkColors]. In the browser it is the page's
 * `prefers-color-scheme`, so "follow the device" means the same thing there.
 */
@Composable
fun WardrobappTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = choice.usesDarkColors(isSystemInDarkTheme())

    val colors = platformColorScheme(darkTheme)
        ?: if (darkTheme) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * The platform's own colours, where it has any, or null for Material's baseline.
 *
 * An expect because the one platform that has them -- Android 12's dynamic
 * colour, drawn from the wallpaper -- needs a Context and an API-level check,
 * and neither exists in common code.
 */
@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme?

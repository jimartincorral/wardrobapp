package com.wardrobapp.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wardrobapp.presentation.ThemeChoice
import com.wardrobapp.presentation.usesDarkColors

/**
 * Material 3, in the colours the app has been asked for.
 *
 * Dynamic colour where the platform offers it (Android 12+), which is the native
 * behaviour a user expects and something the React Native app could not do.
 *
 * [choice] decides light or dark and defaults to following the device, so a caller
 * that has nothing stored yet needs to pass nothing. Which of the two the device
 * is set to is read here rather than passed in -- `isSystemInDarkTheme()` is a
 * composition-local read that recomposes when the setting changes, and the pure
 * decision it feeds sits in [usesDarkColors].
 */
@Composable
fun WardrobappTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = choice.usesDarkColors(isSystemInDarkTheme())

    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors, content = content)
}

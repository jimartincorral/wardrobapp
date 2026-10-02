package com.wardrobapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/**
 * The window's own height, which in the browser is the page's.
 *
 * containerSize is marked experimental in Compose 1.7,
 * and it is the only common way to ask. The sheet it sizes is cosmetic, so if it
 * changes shape the cost is a recompile here, not a behaviour anyone relies on.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun windowHeight(): Dp =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

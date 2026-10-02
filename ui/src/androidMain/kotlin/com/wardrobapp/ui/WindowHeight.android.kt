package com.wardrobapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What the filter sheet was always sized from on the phone. */
@Composable
internal actual fun windowHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp

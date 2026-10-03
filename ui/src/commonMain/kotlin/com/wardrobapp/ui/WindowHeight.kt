package com.wardrobapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * How tall the app's window is, for sizing a sheet as a share of it.
 *
 * An expect because the phone's answer is Android's: the wardrobe's filter sheet
 * was sized from LocalConfiguration's screenHeightDp, which exists only there,
 * and the Android actual keeps exactly that, so the sheet the phone shows does
 * not change by a pixel. Elsewhere it is the window's own size, which in the
 * browser is the page.
 */
@Composable
internal expect fun windowHeight(): Dp

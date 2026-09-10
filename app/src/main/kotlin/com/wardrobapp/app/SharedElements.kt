package com.wardrobapp.app

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Key used for the garment photo shared element transition. */
fun garmentSharedElementKey(garmentId: String): String = "garment-photo-$garmentId"

/**
 * Shared element modifier for garment photos.
 *
 * When composed inside [androidx.compose.animation.SharedTransitionLayout] and a navigation
 * [AnimatedVisibilityScope], coordinates the bounds transformation between the wardrobe cell/row
 * and the detail screen. When either scope is null (e.g., in unit tests), behaves as a no-op [Modifier].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.garmentSharedElement(garmentId: String): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current ?: return this
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    if (garmentId.isBlank()) return this
    return with(sharedTransitionScope) {
        sharedElement(
            state = rememberSharedContentState(key = garmentSharedElementKey(garmentId)),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = { _, _ -> springGentle() },
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(8.dp)),
        )
    }
}

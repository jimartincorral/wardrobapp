package com.wardrobapp.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * One curve for the whole app.
 *
 * The design hands over a single cubic bezier -- `.34, 1.5, .64, 1`, an overshoot
 * of roughly twelve percent -- and asks that everything that moves moves on it,
 * so that a chip settling and a sheet arriving read as the same app rather than
 * as two people's work. A spring is the Compose equivalent and is the better
 * fit for the same reason it is the more expensive one: it is interruptible, so
 * a second tap during the first tap's animation continues from where the thing
 * actually is instead of snapping back to the start of a fresh tween.
 *
 * Three of them rather than one, because the overshoot is the part being tuned
 * and the design tunes it per element: a nav icon and a star are asked to pop
 * harder than a card is asked to settle. Damping is what carries that -- lower
 * is bouncier -- and the durations in the handoff fall out of the stiffness
 * rather than being set directly, which is the trade a spring makes.
 */
private const val GENTLE_DAMPING = 0.55f
private const val POP_DAMPING = 0.42f
private const val PRESS_DAMPING = 0.75f

/** The app's default: cards, sheets, menus, anything settling into place. */
internal fun <T> springGentle(): SpringSpec<T> =
    spring(dampingRatio = GENTLE_DAMPING, stiffness = Spring.StiffnessMediumLow)

/** A harder overshoot, for the small things that are meant to be felt: stars, nav icons, chips. */
internal fun <T> springPop(): SpringSpec<T> =
    spring(dampingRatio = POP_DAMPING, stiffness = Spring.StiffnessMedium)

/** Barely any overshoot. A button under a thumb should not wobble. */
internal fun <T> springPress(): SpringSpec<T> =
    spring(dampingRatio = PRESS_DAMPING, stiffness = Spring.StiffnessMedium)

/**
 * Scale with the press, and spring back on release.
 *
 * Takes the [InteractionSource] rather than owning one so that it can be handed
 * the same source the control itself is using: Material's own buttons emit their
 * presses into the source they are given, and a second source made here would
 * scale on a ripple nobody started.
 *
 * The default of 0.97 is the design's, and is deliberately small. The point is
 * to say the tap landed, not to animate.
 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = springPress(),
        label = "press-scale",
    )

    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * A nudge sideways under the press, for a row that leads somewhere.
 *
 * The home screen's navigation rows move three dp right rather than shrinking:
 * a full-width row that scales pulls its own edges away from the screen's, which
 * looks like the row got smaller rather than like it was pushed.
 */
@Composable
internal fun Modifier.pressNudge(interactionSource: InteractionSource, distance: Float = 3f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val offset by animateFloatAsState(
        targetValue = if (pressed) distance else 0f,
        animationSpec = springPress(),
        label = "press-nudge",
    )

    return graphicsLayer { translationX = offset * density }
}

/**
 * A lift under the press: up rather than in.
 *
 * The stat cards use it. They sit side by side, so shrinking one opens a gap
 * between the pair that reads as the layout moving.
 */
@Composable
internal fun Modifier.pressLift(interactionSource: InteractionSource, distance: Float = 3f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val offset by animateFloatAsState(
        targetValue = if (pressed) -distance else 0f,
        animationSpec = springPress(),
        label = "press-lift",
    )

    return graphicsLayer { translationY = offset * density }
}

/**
 * Scale a surface up from one of its own corners.
 *
 * The grid-size menu grows out of the button that opened it, which means growing
 * from its own top-right rather than from its middle -- a menu that scales from
 * the centre appears to come from nowhere in particular.
 *
 * [progress] is a plain 0..1 so the caller decides what drives it; passing an
 * animated float keeps the spring's overshoot, which a `scaleIn` transition on a
 * spring would give as well but without the transform origin.
 */
internal fun Modifier.growFrom(origin: TransformOrigin, progress: Float): Modifier =
    graphicsLayer {
        transformOrigin = origin
        scaleX = progress
        scaleY = progress
        alpha = progress.coerceIn(0f, 1f)
    }

/**
 * What a garment photo sits on.
 *
 * Not one colour in both schemes. In light a photo sits on `surfaceVariant`, the
 * usual placeholder grey. In dark it sits on `surfaceContainerHigh` and never on
 * anything paler, so that a cream shirt reads as brighter than its own frame --
 * on a white frame the garment is the dark part of the cell, which is exactly
 * backwards from what dark mode is for.
 */
@Composable
internal fun photoSurface(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

/**
 * Rough perceptual brightness, enough to tell a dark scheme from a light one.
 *
 * `isSystemInDarkTheme()` would answer a different question -- what the *device*
 * is set to -- and this app lets the theme be overridden in Settings, so the
 * scheme in force is the only honest thing to ask.
 */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

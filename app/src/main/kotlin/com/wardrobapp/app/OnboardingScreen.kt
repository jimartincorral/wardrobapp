package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wardrobapp.presentation.LanguageChoice
import com.wardrobapp.presentation.MAX_RATING
import com.wardrobapp.presentation.OnboardingStep
import com.wardrobapp.presentation.ThemeChoice

/**
 * The first three screens, before there is a wardrobe to show.
 *
 * One composable per step and one shell round all three, because the shell is the
 * whole of what they have in common: no top bar and no bottom bar -- this is not a
 * tab, and `MainActivity` only draws the bar for routes in `TABS` -- 24dp sides,
 * and one weighted spacer holding the forward button at the bottom wherever the
 * text above it ends.
 *
 * No progress indicator, decided deliberately: three screens, each ending in one
 * forward button. A "1 of 3" would be a promise about how long this takes, made to
 * somebody who can already see the button that ends it.
 *
 * Layout only, as every other screen in this module is. Which step comes next is
 * [OnboardingStep]'s business, and what "seen" means on disk is
 * [OnboardingPreference]'s.
 */
@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    /** The colours in force, and the way to change them -- the same path Settings uses. */
    theme: ThemeChoice,
    onThemeSelected: (ThemeChoice) -> Unit,
    /** The language in force, read from AppCompat rather than held here. */
    language: LanguageChoice,
    onLanguageSelected: (LanguageChoice) -> Unit,
    /** Forward: on the last screen this is what dismisses the flow. */
    onContinue: () -> Unit,
    /** "Not now" -- the flow is marked seen and Home arrives, card and all. */
    onSkip: () -> Unit,
    /** Opens the document picker directly. No screen of ours in between. */
    onRestoreRequested: () -> Unit,
) {
    when (step) {
        OnboardingStep.WELCOME -> Welcome(
            theme = theme,
            onThemeSelected = onThemeSelected,
            language = language,
            onLanguageSelected = onLanguageSelected,
            onStart = onContinue,
            onSkip = onSkip,
            onRestoreRequested = onRestoreRequested,
        )

        OnboardingStep.ADDING -> AddingAGarment(onNext = onContinue)

        OnboardingStep.LEARNING -> HowItLearns(onDone = onContinue)
    }
}

/**
 * The shell every step shares: the padding, and the way the button stays put.
 *
 * Scrollable, which the design's frames are not -- they are drawn at 412x908 with
 * room to spare, and the same text at the largest font scale on the shortest
 * supported phone is not. A screen whose only button has been pushed off the
 * bottom is a screen with no way out of it.
 *
 * The `heightIn(min = maxHeight)` is what makes both true at once. Each step holds
 * its forward button down with `Spacer(weight)`, and a weight inside a scrollable
 * column is measured against an infinite height and collapses to nothing -- which
 * would tuck the button under the text on every screen, tall or short. A minimum
 * of one viewport gives the weight something to divide, and content taller than
 * that scrolls as normal.
 */
@Composable
private fun OnboardingShell(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp)),
            content = content,
        )
    }
}

@Composable
private fun Welcome(
    theme: ThemeChoice,
    onThemeSelected: (ThemeChoice) -> Unit,
    language: LanguageChoice,
    onLanguageSelected: (LanguageChoice) -> Unit,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    onRestoreRequested: () -> Unit,
) {
    OnboardingShell {
        Text(
            stringResource(R.string.w_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.w_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )

        // The two settings that change everything read after them, offered before
        // anything is read. The hints Settings puts under each heading are
        // deliberately not here: they explain a settings screen, and this one is
        // asking a question rather than documenting an option.
        Column(modifier = Modifier.padding(top = 32.dp)) {
            Text(
                stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                for (choice in ThemeChoice.entries) {
                    FilterChip(
                        selected = choice == theme,
                        onClick = { onThemeSelected(choice) },
                        label = { Text(stringResource(choice.labelRes)) },
                    )
                }
            }

            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 32.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                for (choice in LanguageChoice.entries) {
                    FilterChip(
                        selected = choice == language,
                        onClick = { onLanguageSelected(choice) },
                        label = { Text(stringResource(choice.labelRes)) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        ForwardButton(label = stringResource(R.string.w_start), onClick = onStart)

        // The way in for somebody arriving from another phone, offered here
        // rather than left in Settings: a restored wardrobe replaces everything,
        // and the one moment when there is nothing to lose is before anything has
        // been added.
        OutlinedButton(
            onClick = onRestoreRequested,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(CTA_HEIGHT),
        ) {
            Text(stringResource(R.string.w_restore), style = ctaLabel())
        }
        Text(
            stringResource(R.string.w_restore_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            // Narrower than the screen's own 24dp sides, which is what keeps this
            // one line from running the full width and reading as body text.
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp),
        )

        // Skipping is offered on this screen only. The two after it are one button
        // each, and a second way past a single button is a second thing to read.
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 12.dp)
                .height(40.dp),
        ) {
            Text(stringResource(R.string.w_skip), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AddingAGarment(onNext: () -> Unit) {
    OnboardingShell {
        Text(
            stringResource(R.string.a_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.a_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(
                glyph = Glyph.PhotoCamera,
                headline = stringResource(R.string.a_s1),
                detail = stringResource(R.string.a_s1_detail),
            )
            Step(
                glyph = Glyph.AutoAwesome,
                headline = stringResource(R.string.a_s2),
                detail = stringResource(R.string.a_s2_detail),
            )
            Step(
                glyph = Glyph.Apps,
                headline = stringResource(R.string.a_s3),
                detail = stringResource(R.string.a_s3_detail),
            )
        }

        // The drawerful, mentioned rather than made a fourth step: it is the same
        // three things done thirty times, and numbering it would make it sound
        // like something else to learn.
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 24.dp)) {
            Text(
                stringResource(R.string.a_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        ForwardButton(label = stringResource(R.string.a_next), onClick = onNext)
    }
}

/**
 * One of the three things adding a garment involves.
 *
 * The same leading-circle construction as `Action` on the home screen, without the
 * card and the chevron: this row does not lead anywhere, so it should not look
 * like it does. The glyph is decorative -- the headline beside it says the same
 * thing in words.
 */
@Composable
private fun Step(glyph: Painter, headline: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                glyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HowItLearns(onDone: () -> Unit) {
    OnboardingShell {
        Text(
            stringResource(R.string.l_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.l_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )

        DemoSuggestionCard()

        Text(
            stringResource(R.string.l_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        ForwardButton(label = stringResource(R.string.l_next), onClick = onDone)
    }
}

/**
 * The card that will ask for a rating, shown before there is one to ask about.
 *
 * A picture of `SuggestionCard` rather than the card itself: the flow runs before
 * there is a wardrobe to build a suggestion from, so there is no outfit, no photos
 * and nothing to rate. Everything here is inert -- the stars do not answer a tap,
 * because a tap on them would be a rating of nothing.
 *
 * Kept beside the flow rather than shared with `OutfitsScreen`: making that card
 * render a fake would mean the real one carrying a mode it never uses, and the
 * copy that matters -- the layout -- is what a reader compares. The thumbnails are
 * placeholders; three real 3:4 garment shots would drop straight in.
 */
@Composable
private fun DemoSuggestionCard() {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.onboarding_demo_name),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // The shape of the save button, not a button: a 40dp box round a
                // 22dp glyph, as `IconButton` produces, with nothing to press.
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Glyph.BookmarkBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Text(
                stringResource(R.string.onboarding_demo_reasons),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            // Four across for an outfit of three, exactly as the real card is: a
            // three-garment card there does not draw wider photos than a
            // four-garment one above it, and this is a picture of that card.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                repeat(DEMO_GARMENTS) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(photoSurface()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Glyph.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = PLACEHOLDER_ALPHA,
                            ),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                repeat(THUMBNAILS_ACROSS - DEMO_GARMENTS) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                DemoStars()

                Box(modifier = Modifier.weight(1f))

                Text(
                    stringResource(R.string.outfit_rate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The star row, at four, and inert.
 *
 * Not `Stars`: that one is a control, and every one of its five targets answers a
 * tap by recording a rating. Here the row is the thing being explained, so it is
 * drawn at the same 36dp targets round the same 26dp glyphs and does nothing --
 * and it announces nothing either, because the sentence above it is what says what
 * these are for.
 */
@Composable
private fun DemoStars() {
    Row {
        for (star in 1..MAX_RATING) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                val filled = star <= DEMO_RATING

                if (filled) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                } else {
                    Icon(
                        Glyph.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/**
 * The filled call to action every step ends with.
 *
 * One definition rather than three, so the three screens cannot drift apart on the
 * one control they all have.
 */
@Composable
private fun ForwardButton(label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }

    Button(
        onClick = onClick,
        interactionSource = press,
        modifier = Modifier
            .fillMaxWidth()
            .height(CTA_HEIGHT)
            .pressScale(press),
    ) {
        Text(label, style = ctaLabel())
    }
}

/**
 * Four of five, which is a rating somebody gave rather than a full mark.
 *
 * Five would read as a demonstration of the app being right; four reads as an
 * opinion, which is what the row collects.
 */
private const val DEMO_RATING = 4

/** Three garments, as a real suggestion usually holds. */
private const val DEMO_GARMENTS = 3

/** Material's own disabled-content alpha, for a thumbnail that is a stand-in. */
private const val PLACEHOLDER_ALPHA = 0.38f

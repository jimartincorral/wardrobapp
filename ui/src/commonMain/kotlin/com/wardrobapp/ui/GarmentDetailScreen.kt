package com.wardrobapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.BackgroundAction
import com.wardrobapp.presentation.GalleryEntry
import com.wardrobapp.presentation.GarmentDetailScreenState
import com.wardrobapp.presentation.GarmentDetailView
import com.wardrobapp.presentation.PaletteEntry
import com.wardrobapp.presentation.formatStoredDateForReader
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_back
import com.wardrobapp.ui.resources.action_cancel
import com.wardrobapp.ui.resources.action_close
import com.wardrobapp.ui.resources.action_delete
import com.wardrobapp.ui.resources.action_edit
import com.wardrobapp.ui.resources.action_keep
import com.wardrobapp.ui.resources.action_retry
import com.wardrobapp.ui.resources.background_remove
import com.wardrobapp.ui.resources.background_removing
import com.wardrobapp.ui.resources.background_undo
import com.wardrobapp.ui.resources.error_action_failed
import com.wardrobapp.ui.resources.garment_build_outfit
import com.wardrobapp.ui.resources.garment_delete
import com.wardrobapp.ui.resources.garment_delete_body
import com.wardrobapp.ui.resources.garment_delete_confirm
import com.wardrobapp.ui.resources.garment_missing
import com.wardrobapp.ui.resources.garment_no_photo
import com.wardrobapp.ui.resources.garment_retire
import com.wardrobapp.ui.resources.garment_retire_action
import com.wardrobapp.ui.resources.garment_retire_body
import com.wardrobapp.ui.resources.garment_retire_confirm
import com.wardrobapp.ui.resources.garment_retired
import com.wardrobapp.ui.resources.garment_retired_since
import com.wardrobapp.ui.resources.garment_unreadable
import com.wardrobapp.ui.resources.garment_unretire
import com.wardrobapp.ui.resources.garment_untitled
import com.wardrobapp.ui.resources.property_added
import com.wardrobapp.ui.resources.property_colours
import com.wardrobapp.ui.resources.property_occasions
import com.wardrobapp.ui.resources.property_style
import com.wardrobapp.ui.resources.attribute_statement
import com.wardrobapp.ui.resources.property_seasons
import com.wardrobapp.ui.resources.property_size
import com.wardrobapp.ui.resources.property_tags
import org.jetbrains.compose.resources.stringResource

/**
 * One garment, in full.
 *
 * Layout only. Which photo is shown, what the palette means, which properties
 * have anything to say and whether the garment is still in use were all decided
 * in :presentation before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarmentDetailScreen(
    state: GarmentDetailScreenState,
    onBack: () -> Unit,
    onPhotoSelected: (Int) -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    onBuildOutfit: () -> Unit,
    onRetire: () -> Unit,
    onReturnToWardrobe: () -> Unit,
    onDelete: () -> Unit,
    onConfirmed: () -> Unit,
    onConfirmationDismissed: () -> Unit,
    onActionErrorDismissed: () -> Unit,
) {
    state.confirming?.let { confirming ->
        ConfirmationDialog(confirming, onConfirmed, onConfirmationDismissed)
    }
    state.actionErrorText()?.let { message ->
        AlertDialog(
            onDismissRequest = onActionErrorDismissed,
            title = { Text(stringResource(Res.string.error_action_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onActionErrorDismissed) { Text(stringResource(Res.string.action_close)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.view?.let { titleOf(it) } ?: stringResource(Res.string.garment_untitled)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    // Only once there is something to edit: a garment that failed
                    // to load or is not there has nothing to open.
                    if (state.view != null) {
                        IconButton(onClick = onEdit) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(Res.string.action_edit),
                            )
                        }
                    }
                },
            )
        },
    ) { insets ->
        val view = state.view

        when {
            state.loading && view == null -> Centered(insets) { CircularProgressIndicator() }

            // Nothing to retry: the garment is not there.
            state.missing -> Centered(insets) {
                Text(stringResource(Res.string.garment_missing))
            }

            // A read that failed is not an empty garment, and must not look like
            // one. Same rule as the wardrobe list.
            view == null -> Centered(insets) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(Res.string.garment_unreadable), style = MaterialTheme.typography.titleMedium)
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                }
            }

            else -> GarmentBody(
                garmentId = state.garmentId,
                view = view,
                insets = insets,
                working = state.working,
                onPhotoSelected = onPhotoSelected,
                onRemoveBackground = onRemoveBackground,
                onUndoBackground = onUndoBackground,
                onBuildOutfit = onBuildOutfit,
                onRetire = onRetire,
                onReturnToWardrobe = onReturnToWardrobe,
                onDelete = onDelete,
            )
        }
    }
}

/**
 * One garment, as the pane beside the desktop's wardrobe grid.
 *
 * The same state and the same callbacks as [GarmentDetailScreen], and the same
 * body: only the frame differs. A 64dp header where the screen has a top bar,
 * with a close button where the screen has a back arrow -- closing the pane is
 * not going back anywhere, the grid never went away -- and the edit button
 * beside it, which opens the full-page form as it does from the screen.
 */
@Composable
fun GarmentDetailPane(
    state: GarmentDetailScreenState,
    onClose: () -> Unit,
    onPhotoSelected: (Int) -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    onBuildOutfit: () -> Unit,
    onRetire: () -> Unit,
    onReturnToWardrobe: () -> Unit,
    onDelete: () -> Unit,
    onConfirmed: () -> Unit,
    onConfirmationDismissed: () -> Unit,
    onActionErrorDismissed: () -> Unit,
) {
    state.confirming?.let { confirming ->
        ConfirmationDialog(confirming, onConfirmed, onConfirmationDismissed)
    }
    state.actionErrorText()?.let { message ->
        AlertDialog(
            onDismissRequest = onActionErrorDismissed,
            title = { Text(stringResource(Res.string.error_action_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onActionErrorDismissed) { Text(stringResource(Res.string.action_close)) }
            },
        )
    }

    val view = state.view

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                view?.let { titleOf(it) } ?: stringResource(Res.string.garment_untitled),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (view != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.action_edit))
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
            }
        }

        val none = PaddingValues(0.dp)

        when {
            state.loading && view == null -> Centered(none) { CircularProgressIndicator() }

            state.missing -> Centered(none) {
                Text(stringResource(Res.string.garment_missing), modifier = Modifier.padding(20.dp))
            }

            view == null -> Centered(none) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(Res.string.garment_unreadable), style = MaterialTheme.typography.titleMedium)
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                }
            }

            else -> GarmentBody(
                garmentId = state.garmentId,
                view = view,
                insets = none,
                working = state.working,
                pane = true,
                onPhotoSelected = onPhotoSelected,
                onRemoveBackground = onRemoveBackground,
                onUndoBackground = onUndoBackground,
                onBuildOutfit = onBuildOutfit,
                onRetire = onRetire,
                onReturnToWardrobe = onReturnToWardrobe,
                onDelete = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GarmentBody(
    garmentId: String,
    view: GarmentDetailView,
    insets: PaddingValues,
    working: Boolean,
    /**
     * Drawn in the desktop's detail pane rather than as a screen: inset from the
     * pane's edges, the photo rounded and capped in height, smaller thumbnails.
     * Everything else -- what is shown, in what order, and what the buttons do --
     * is the same body, so the pane cannot become a second, slightly different
     * garment screen.
     */
    pane: Boolean = false,
    onPhotoSelected: (Int) -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    onBuildOutfit: () -> Unit,
    onRetire: () -> Unit,
    onReturnToWardrobe: () -> Unit,
    onDelete: () -> Unit,
) {
    val edge = if (pane) 20.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(insets)
            .verticalScroll(rememberScrollState()),
    ) {
        // Whether the large photo is a cut-out is what the selected gallery
        // entry knows; the view does not say it twice.
        val cutout = view.gallery.getOrNull(view.selectedIndex)?.hasCutout == true
        if (pane) PanePhoto(view.displayedImage, cutout) else Photo(garmentId, view.displayedImage, cutout)

        if (view.showsGallery) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = edge, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(view.gallery) { index, entry ->
                    Thumbnail(entry, width = if (pane) 56.dp else 72.dp) { onPhotoSelected(index) }
                }
            }
        }

        BackgroundControl(
            action = view.backgroundAction,
            working = working,
            onRemove = onRemoveBackground,
            onUndo = onUndoBackground,
            edge = edge,
        )

        if (!view.isAvailable) {
            UnavailableBanner(
                view.unavailableDate,
                modifier = if (pane) {
                    Modifier.padding(horizontal = edge, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
        }

        Column(modifier = Modifier.padding(edge)) {
            Text(headingOf(view), style = MaterialTheme.typography.headlineSmall)

            view.brand?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(modifier = Modifier.padding(top = 16.dp)) {
                if (view.palette.isNotEmpty()) {
                    Property(stringResource(Res.string.property_colours)) { Palette(view.palette) }
                }
                view.size?.let { Property(stringResource(Res.string.property_size)) { Value(it) } }
                if (view.seasons.isNotEmpty()) {
                    Property(stringResource(Res.string.property_seasons)) {
                        Value(view.seasons.map { stringResource(it.labelRes) }.joinToString(", "))
                    }
                }
                if (view.occasions.isNotEmpty()) {
                    Property(stringResource(Res.string.property_occasions)) {
                        Value(view.occasions.map { stringResource(it.labelRes) }.joinToString(", "))
                    }
                }
                // What the engine takes the garment to be like, whether set or
                // implied by its type: the form is where the two are told apart.
                Property(stringResource(Res.string.property_style)) {
                    Value(
                        listOfNotNull(
                            view.attributes.formality?.let { stringResource(it.labelRes) },
                            view.attributes.pattern?.let { stringResource(it.labelRes) },
                            view.attributes.fit?.let { stringResource(it.labelRes) },
                            view.attributes.weight?.let { stringResource(it.labelRes) },
                            if (view.attributes.statement == true) stringResource(Res.string.attribute_statement) else null,
                        ).joinToString(" \u00b7 "),
                    )
                }
                view.purchaseDate?.let { Property(stringResource(Res.string.property_added)) { Value(displayDate(it)) } }
            }

            if (view.tags.isNotEmpty()) {
                Text(
                    stringResource(Res.string.property_tags),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (tag in view.tags) Tag(tag)
                }
            }

            Actions(
                isAvailable = view.isAvailable,
                working = working,
                onBuildOutfit = onBuildOutfit,
                onRetire = onRetire,
                onReturnToWardrobe = onReturnToWardrobe,
                onDelete = onDelete,
            )
        }
    }
}

/**
 * Remove or restore the selected photo's background.
 *
 * Driven entirely by `view.backgroundAction`, which until now was computed on
 * every load of this screen and thrown away. Nothing is shown when it is null --
 * a photo whose cut-out already replaced it has neither move available, and an
 * always-visible disabled button would ask the reader to work out why.
 */
@Composable
private fun BackgroundControl(
    action: BackgroundAction?,
    working: Boolean,
    onRemove: () -> Unit,
    onUndo: () -> Unit,
    edge: Dp = 16.dp,
) {
    if (action == null) return
    if (action == BackgroundAction.REMOVE && !LocalPhotoTools.current.removesBackgrounds) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.background_removing),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            TextButton(onClick = if (action == BackgroundAction.REMOVE) onRemove else onUndo) {
                Text(
                    when (action) {
                        BackgroundAction.REMOVE -> stringResource(Res.string.background_remove)
                        BackgroundAction.UNDO -> stringResource(Res.string.background_undo)
                    }
                )
            }
        }
    }
}

/**
 * What can be done to a garment.
 *
 * At the bottom, below everything describing it, because these are the two
 * actions worth reaching deliberately rather than by accident -- and one of them
 * cannot be undone.
 *
 * Retiring is offered as its opposite once a garment is already retired, rather
 * than shown greyed out: there is only ever one sensible move, and a disabled
 * button asks the reader to work out why.
 */
@Composable
private fun Actions(
    isAvailable: Boolean,
    working: Boolean,
    onBuildOutfit: () -> Unit,
    onRetire: () -> Unit,
    onReturnToWardrobe: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Only while the garment is in use: an outfit is something to wear, and
        // suggestions are drawn from the available wardrobe, so offering this on a
        // retired garment would be offering a button that answers with nothing.
        if (isAvailable) {
            val press = remember { MutableInteractionSource() }

            Button(
                onClick = onBuildOutfit,
                enabled = !working,
                interactionSource = press,
                modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT).pressScale(press),
            ) {
                Icon(Glyph.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(Res.string.garment_build_outfit),
                    style = ctaLabel(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        val retirePress = remember { MutableInteractionSource() }

        OutlinedButton(
            onClick = if (isAvailable) onRetire else onReturnToWardrobe,
            enabled = !working,
            interactionSource = retirePress,
            modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT).pressScale(retirePress),
        ) {
            Icon(Glyph.Archive, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(
                    if (isAvailable) Res.string.garment_retire else Res.string.garment_unretire
                ),
                style = ctaLabel(),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        TextButton(
            onClick = onDelete,
            enabled = !working,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth().height(CTA_HEIGHT),
        ) {
            Icon(Glyph.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(Res.string.garment_delete),
                style = ctaLabel(),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // Room to scroll clear of the gesture area.
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * The prompt before something that changes the wardrobe.
 *
 * Deleting says what goes with the garment, because the answer is not obvious:
 * its photos, its learned pairings, and its place in any saved outfit. Retiring
 * says what *stays*, for the same reason -- it reads like a delete until you know
 * it is not one.
 */
@Composable
private fun ConfirmationDialog(
    confirming: GarmentDetailScreenState.Confirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = when (confirming) {
    GarmentDetailScreenState.Confirm.RETIRE -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.garment_retire_confirm)) },
        text = {
            Text(
                stringResource(Res.string.garment_retire_body)
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.garment_retire_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )

    GarmentDetailScreenState.Confirm.DELETE -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.garment_delete_confirm)) },
        text = {
            Text(
                stringResource(Res.string.garment_delete_body)
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_keep)) } },
    )
}

/**
 * The garment, large.
 *
 * Three to four, which is the shape every garment photo in this app already is:
 * they are cropped to it on the way in, and every other frame that holds one --
 * a grid cell, an outfit thumb, the bulk-add filmstrip -- is 3:4 as well. A
 * fixed height was the old rule and it was wrong in both directions: 55% of a
 * tall phone letterboxes a 3:4 photo, and on a short one it crops the garment.
 *
 * Fit rather than Crop inside that frame. The design asks for Crop on the
 * grounds that the photos are 3:4 already, which is true of every photo this app
 * took -- but not of one that arrived with an imported garment, and cropping the
 * hem off a coat on the one screen whose job is to show the whole garment is the
 * worse failure of the two. On an in-app photo the two are identical.
 */
@Composable
private fun Photo(garmentId: String, uri: String?, cutout: Boolean) {
    if (uri == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(photoSurface()),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.garment_no_photo), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // The same frame as every thumbnail, so the cut-out that flew in from the
    // list lands on the same white it left, only larger. Square-cornered: this
    // one runs edge to edge under the app bar rather than sitting in a card.
    // No breathing room either -- the photo is the whole point of the screen,
    // and it arrived cropped to 3:4 with its own margins.
    GarmentPhoto(
        uri = uri,
        cutout = cutout,
        shape = RectangleShape,
        inset = 0.dp,
        photoScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .garmentSharedElement(garmentId),
    )
}

/**
 * The garment, large, in the desktop's detail pane.
 *
 * The same 3:4 frame and the same Fit as [Photo], for the same reasons, but inset
 * and rounded like everything else in the pane, and no taller than 440dp: at the
 * pane's full width a 3:4 frame is over five hundred tall, which on a laptop
 * screen pushes the garment's name below the fold of the pane it is the subject
 * of. Narrower rather than cropped, so the whole garment still shows.
 *
 * No shared-element key: the pane opens beside the cell rather than in place of
 * it, so there is no transition for the photo to fly through.
 */
@Composable
private fun PanePhoto(uri: String?, cutout: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        val frame = Modifier
            .heightIn(max = 440.dp)
            .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)

        if (uri == null) {
            Box(
                modifier = frame.clip(RoundedCornerShape(12.dp)).background(photoSurface()),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(Res.string.garment_no_photo), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // The thumbnail's frame at the pane's size; see [Photo] for why there
            // is no breathing room.
            GarmentPhoto(
                uri = uri,
                cutout = cutout,
                shape = RoundedCornerShape(12.dp),
                inset = 0.dp,
                photoScale = ContentScale.Fit,
                modifier = frame,
            )
        }
    }
}

@Composable
private fun Thumbnail(entry: GalleryEntry, width: Dp = 72.dp, onClick: () -> Unit) {
    val border = if (entry.selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    // The selection border is drawn on the frame's outside, before its clip, so
    // it neither eats into the garment nor gets rounded away.
    GarmentPhoto(
        uri = entry.uri,
        cutout = entry.hasCutout,
        inset = 3.dp,
        modifier = Modifier
            .width(width)
            .aspectRatio(0.75f)
            .border(2.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun UnavailableBanner(since: String?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            if (since == null) {
                stringResource(Res.string.garment_retired)
            } else {
                stringResource(Res.string.garment_retired_since, displayDate(since))
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Property(label: String, value: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            value()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Palette(palette: List<PaletteEntry>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (entry in palette) {
                val color = entry.hex.toComposeColor()
                if (color != null) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
        }
        Text(
            palette.map { it.label() }.joinToString(", "),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun Tag(tag: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            tag,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Centered(insets: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(insets),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The bar title: the type if the garment has one, else its category. */
@Composable
private fun titleOf(view: GarmentDetailView): String =
    view.subcategories.firstOrNull()?.let { garmentTypeLabel(it) }
        ?: categoryLabel(view.category)

/** The heading on the page itself, which has room for both. */
@Composable
private fun headingOf(view: GarmentDetailView): String {
    val category = categoryLabel(view.category)
    if (view.subcategories.isEmpty()) return category

    val types = view.subcategories.map { garmentTypeLabel(it) }.joinToString(", ")
    return "$category \u2022 $types"
}

/**
 * A stored date in the device's own language and format.
 *
 * Deliberately not the React Native app's `MMM d, yyyy`, which is English
 * whatever the phone is set to.
 */
private fun displayDate(value: String): String =
    formatStoredDateForReader(value)

/**
 * A colour's name, or its hex if it was not picked from the palette.
 *
 * This used to turn `lightBlue` into "Light blue" by hand, with a note saying a
 * real translation table was what it should become. It now is one.
 */
@Composable
private fun PaletteEntry.label(): String = colorKey?.let { paletteLabel(it) } ?: hex

/**
 * What to show when an action on this garment failed.
 *
 * The exception's own words if it had any, and otherwise what the app was doing.
 * The same rule every screen here follows.
 */
@Composable
private fun GarmentDetailScreenState.actionErrorText(): String? =
    actionError ?: actionErrorFallback?.let { stringResource(it.messageRes) }

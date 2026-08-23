package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.presentation.GalleryEntry
import com.wardrobapp.presentation.GarmentDetailView
import com.wardrobapp.presentation.PaletteEntry
import com.wardrobapp.presentation.formatStoredDate
import java.util.Locale
import java.util.TimeZone

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
    state: GarmentDetailViewModel.State,
    onBack: () -> Unit,
    onPhotoSelected: (Int) -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
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
    state.actionError?.let { message ->
        AlertDialog(
            onDismissRequest = onActionErrorDismissed,
            title = { Text("That didn't work") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onActionErrorDismissed) { Text("Close") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.view?.let { titleOf(it) } ?: "Garment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only once there is something to edit: a garment that failed
                    // to load or is not there has nothing to open.
                    if (state.view != null) {
                        TextButton(onClick = onEdit) { Text("Edit") }
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
                Text("That garment is no longer in your wardrobe.")
            }

            // A read that failed is not an empty garment, and must not look like
            // one. Same rule as the wardrobe list.
            view == null -> Centered(insets) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't read this garment", style = MaterialTheme.typography.titleMedium)
                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }

            else -> GarmentBody(
                view = view,
                insets = insets,
                working = state.working,
                onPhotoSelected = onPhotoSelected,
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
    view: GarmentDetailView,
    insets: PaddingValues,
    working: Boolean,
    onPhotoSelected: (Int) -> Unit,
    onRetire: () -> Unit,
    onReturnToWardrobe: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(insets)
            .verticalScroll(rememberScrollState()),
    ) {
        Photo(view.displayedImage)

        if (view.showsGallery) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(view.gallery) { index, entry ->
                    Thumbnail(entry) { onPhotoSelected(index) }
                }
            }
        }

        if (!view.isAvailable) {
            UnavailableBanner(view.unavailableDate)
        }

        Column(modifier = Modifier.padding(16.dp)) {
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
                    Property("Colours") { Palette(view.palette) }
                }
                view.size?.let { Property("Size") { Value(it) } }
                if (view.seasons.isNotEmpty()) {
                    Property("Seasons") { Value(view.seasons.joinToString(", ") { it.label() }) }
                }
                if (view.occasions.isNotEmpty()) {
                    Property("Occasions") { Value(view.occasions.joinToString(", ") { it.label() }) }
                }
                view.purchaseDate?.let { Property("Added") { Value(displayDate(it)) } }
            }

            if (view.tags.isNotEmpty()) {
                Text(
                    "Tags",
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
                onRetire = onRetire,
                onReturnToWardrobe = onReturnToWardrobe,
                onDelete = onDelete,
            )
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
    onRetire: () -> Unit,
    onReturnToWardrobe: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 32.dp)) {
        OutlinedButton(
            onClick = if (isAvailable) onRetire else onReturnToWardrobe,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isAvailable) "No longer wearing this" else "Wearing this again")
        }

        TextButton(
            onClick = onDelete,
            enabled = !working,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Delete this garment")
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
    confirming: GarmentDetailViewModel.Confirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = when (confirming) {
    GarmentDetailViewModel.Confirm.RETIRE -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop wearing this?") },
        text = {
            Text(
                "It leaves your wardrobe and stops appearing in outfits, but it is " +
                    "kept -- with its photos -- and counts towards how long things last. " +
                    "You can put it back at any time."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Stop wearing it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    GarmentDetailViewModel.Confirm.DELETE -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this garment?") },
        text = {
            Text(
                "Its photos go too, along with what the app has learned about which " +
                    "garments it goes with. Any saved outfit using it loses it, and an " +
                    "outfit left with nothing is deleted. This cannot be undone."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}

@Composable
private fun Photo(uri: String?) {
    // Two-thirds of the screen height, so a garment fills the view the way it
    // does in the React Native app without a fixed dp that crops on a small
    // phone and floats on a tablet.
    val height = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (uri == null) {
            Text("No photo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                // Fit, not Crop: this is the one place the whole garment should
                // be visible, whatever shape the photo is.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Thumbnail(entry: GalleryEntry, onClick: () -> Unit) {
    val border = if (entry.selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    AsyncImage(
        model = entry.uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(72.dp)
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, border, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun UnavailableBanner(since: String?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (since == null) {
                "Not in use"
            } else {
                "Not in use since ${displayDate(since)}"
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
        HorizontalDivider()
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
            palette.joinToString(", ") { it.label() },
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
private fun titleOf(view: GarmentDetailView): String =
    view.subcategories.firstOrNull() ?: view.category.sentenceCase()

/** The heading on the page itself, which has room for both. */
private fun headingOf(view: GarmentDetailView): String =
    if (view.subcategories.isEmpty()) {
        view.category.sentenceCase()
    } else {
        "${view.category.sentenceCase()} \u2022 ${view.subcategories.joinToString(", ")}"
    }

/**
 * A stored date in the device's own language and format.
 *
 * Deliberately not the React Native app's `MMM d, yyyy`, which is English
 * whatever the phone is set to.
 */
private fun displayDate(value: String): String =
    formatStoredDate(value, TimeZone.getDefault(), Locale.getDefault())

/**
 * A colour's name, or its hex if it was not picked from the palette.
 *
 * The keys are camelCase identifiers rather than text meant for reading, so
 * `lightBlue` becomes "Light blue". A real translation table is what this
 * becomes once the port has one; until then it is better than showing an
 * identifier.
 */
private fun PaletteEntry.label(): String = colorKey?.humanised() ?: hex

private fun String.humanised(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().sentenceCase()

private fun String.sentenceCase(): String =
    replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun Season.label(): String = tag.replace('-', ' ').sentenceCase()

private fun Occasion.label(): String = id.sentenceCase()

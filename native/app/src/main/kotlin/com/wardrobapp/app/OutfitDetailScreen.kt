package com.wardrobapp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord

/**
 * One saved outfit: what is in it, what you thought of it, and getting rid of it.
 *
 * Deliberately without the React Native screen's "average rating" block. Rating
 * an outfit replaces any previous rating -- `rateOutfit` deletes the old rows
 * before inserting, in both apps -- so that average is over a single value. It
 * always equals the rating, always reads "(1 ratings)", and sits directly above
 * an editable star row showing the same number. Nothing is lost by showing the
 * rating once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitDetailScreen(
    state: OutfitDetailViewModel.State,
    onBack: () -> Unit,
    onGarmentOpened: (String) -> Unit,
    onRate: (Int) -> Unit,
    onDelete: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text("Delete this outfit?") },
            text = {
                Text(
                    "The outfit and its rating are deleted. The garments in it stay " +
                        "in your wardrobe."
                )
            },
            confirmButton = { TextButton(onClick = onDeleteConfirmed) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDeleteDismissed) { Text("Keep it") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.outfit?.name ?: "Outfit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        val outfit = state.outfit

        when {
            state.loading && outfit == null -> Centered(insets) { CircularProgressIndicator() }

            // Nothing to retry: the outfit is not there.
            state.missing -> Centered(insets) {
                Text("That outfit is no longer saved.")
            }

            // A read that failed is not an empty outfit, and must not look like
            // one. Same rule as every other screen.
            outfit == null -> Centered(insets) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't read this outfit", style = MaterialTheme.typography.titleMedium)
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

            else -> Body(
                state = state,
                insets = insets,
                onGarmentOpened = onGarmentOpened,
                onRate = onRate,
                onDelete = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Body(
    state: OutfitDetailViewModel.State,
    insets: PaddingValues,
    onGarmentOpened: (String) -> Unit,
    onRate: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(insets)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (garment in state.garments) {
                OutfitGarment(garment) { onGarmentOpened(garment.id) }
            }
        }

        // Only when the outfit lists garments this app cannot find. Deleting a
        // garment drops it from every outfit, so this means a row from a restored
        // backup or an older build.
        val missing = state.outfit?.garmentIds?.size?.minus(state.garments.size) ?: 0
        if (missing > 0) {
            Text(
                if (missing == 1) {
                    "One garment in this outfit is no longer in your wardrobe."
                } else {
                    "$missing garments in this outfit are no longer in your wardrobe."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(
            "How was it?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )
        Stars(state.rating.stars.takeIf { state.rating.showsAverage }, onRate)

        TextButton(
            onClick = onDelete,
            enabled = !state.working,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        ) {
            Text("Delete this outfit")
        }
    }
}

@Composable
private fun OutfitGarment(garment: GarmentRecord, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(100.dp).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = garment.displayImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            garment.category.replace('-', ' ').replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        garment.brand?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
private fun Centered(insets: PaddingValues, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(insets),
        contentAlignment = Alignment.Center,
    ) { content() }
}

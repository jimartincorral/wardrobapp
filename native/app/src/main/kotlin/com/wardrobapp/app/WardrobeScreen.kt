package com.wardrobapp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.presentation.GarmentSort

/**
 * The wardrobe list.
 *
 * Layout only. What the list contains, in what order, and what its colours mean
 * were all decided before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardrobeScreen(
    state: WardrobeViewModel.State,
    onSearchChanged: (String) -> Unit,
    onSortToggled: () -> Unit,
    onRetry: () -> Unit,
    onRestoreRequested: () -> Unit,
    onRestoreConfirmed: () -> Unit,
    onRestoreDismissed: () -> Unit,
) {
    state.restore?.let { restore ->
        RestoreDialog(restore, onRestoreConfirmed, onRestoreDismissed)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wardrobe") },
                actions = {
                    TextButton(onClick = onRestoreRequested) { Text("Restore") }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = onSearchChanged,
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSortToggled) {
                    Text(if (state.sort == GarmentSort.NEWEST) "Newest" else "Oldest")
                }
            }

            when {
                state.loading && state.garments.isEmpty() -> Centered {
                    CircularProgressIndicator()
                }

                // Reported, not swallowed. A read that failed must not look like
                // a wardrobe with nothing in it.
                state.error != null -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Couldn't read the wardrobe",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                        )
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }

                state.isEmpty -> Centered {
                    Text(
                        if (state.search.isBlank()) {
                            "No garments yet."
                        } else {
                            "Nothing matches \"${state.search}\"."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.garments, key = { it.id }) { garment ->
                        GarmentRow(garment)
                    }
                }
            }
        }
    }
}

/**
 * What a restore has to say, at each of its four points.
 *
 * The confirmation is not a formality: it is the last moment before the
 * wardrobe on this device stops existing, so it says that plainly. The running
 * dialog cannot be dismissed, because there is nothing useful to do with a
 * half-finished restore except wait for it -- and the work continues whether
 * the dialog is there or not.
 */
@Composable
private fun RestoreDialog(
    restore: WardrobeViewModel.Restore,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = when (restore) {
    is WardrobeViewModel.Restore.Confirming -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from a backup?") },
        text = {
            Text(
                "Everything in this app's wardrobe will be replaced by the contents " +
                    "of the backup you pick. If the backup turns out not to be usable, " +
                    "nothing is changed."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pick a backup") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    is WardrobeViewModel.Restore.Running -> AlertDialog(
        onDismissRequest = {},
        title = { Text("Restoring") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Unpacking and checking the backup.", modifier = Modifier.padding(start = 16.dp))
            }
        },
        confirmButton = {},
    )

    is WardrobeViewModel.Restore.Done -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wardrobe restored") },
        text = {
            Text(
                when (restore.garments) {
                    null -> "The backup was restored."
                    1 -> "The backup was restored: 1 garment."
                    else -> "The backup was restored: ${restore.garments} garments."
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    is WardrobeViewModel.Restore.Failed -> AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't restore that backup") },
        // The message is the whole point: it says whether to update the app,
        // find a different file, or that nothing was lost.
        text = { Text(restore.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun GarmentRow(garment: GarmentRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = garment.displayImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    garment.subcategory ?: garment.category,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                garment.brand?.takeIf { it.isNotBlank() }?.let { brand ->
                    Text(
                        brand,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                garment.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                    Text(
                        tags.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The palette, as swatches. An unparseable colour is left blank
            // rather than guessed at.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (hex in garment.palette.take(3)) {
                    val color = hex.toComposeColor()
                    if (color != null) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
            }

            garment.size?.takeIf { it.isNotBlank() }?.let { size ->
                Text(
                    size,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/**
 * A stored hex colour as a Compose colour, or null if it cannot be read.
 *
 * Parsing goes through the domain's parser rather than a second implementation:
 * it is the one that knows `#RGB` shorthand, the multi-colour sentinel, and how
 * to refuse malformed input instead of returning something wrong.
 */
private fun String.toComposeColor(): Color? =
    com.wardrobapp.domain.parseHexColor(this)?.let { Color(it.r, it.g, it.b) }

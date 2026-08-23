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
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Wardrobe") }) },
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

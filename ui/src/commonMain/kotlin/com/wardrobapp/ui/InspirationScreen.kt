package com.wardrobapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wardrobapp.presentation.InspirationScreenState
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_back
import com.wardrobapp.ui.resources.inspiration_add
import com.wardrobapp.ui.resources.inspiration_adding
import com.wardrobapp.ui.resources.inspiration_empty
import com.wardrobapp.ui.resources.inspiration_intro
import com.wardrobapp.ui.resources.inspiration_remove
import com.wardrobapp.ui.resources.inspiration_retry
import com.wardrobapp.ui.resources.inspiration_title
import org.jetbrains.compose.resources.stringResource

/**
 * The looks the reader likes: a grid of photos, a button to add one, a
 * cross on each to take it away.
 *
 * Photos from anywhere -- a shop's page, a magazine, a screenshot -- and
 * nothing else about them: no name, no tags, no rating. What they are for
 * is said once at the top, because a grid of other people's outfits in a
 * wardrobe app needs a line of explanation and no more. The photos are drawn
 * as photos, not cut-outs, since they are whole looks and the style model
 * saw them whole.
 *
 * A wide window gets more columns, not a second pane: there is nothing to
 * say about a look beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspirationScreen(
    state: InspirationScreenState,
    onBack: () -> Unit,
    onAddRequested: () -> Unit,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.inspiration_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.adding) onAddRequested() },
                icon = {
                    if (state.adding) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
                text = { Text(stringResource(if (state.adding) Res.string.inspiration_adding else Res.string.inspiration_add)) },
                modifier = Modifier.testTag(INSPIRATION_ADD),
            )
        },
    ) { insets ->
        if (state.loading && state.looks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize().padding(insets).testTag(INSPIRATION_GRID),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        stringResource(Res.string.inspiration_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    state.error?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text(stringResource(Res.string.inspiration_retry)) }
                    }
                }
            }

            if (state.looks.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(Res.string.inspiration_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }

            items(state.looks, key = { it.id }) { look ->
                Box {
                    GarmentPhoto(
                        uri = look.imageUri,
                        cutout = false,
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    )
                    FilledTonalIconButton(
                        onClick = { onRemove(look.id) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).testTag(inspirationRemoveTag(look.id)),
                    ) {
                        Icon(
                            Glyph.DeleteOutline,
                            contentDescription = stringResource(Res.string.inspiration_remove),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

const val INSPIRATION_GRID = "inspiration-grid"
const val INSPIRATION_ADD = "inspiration-add"

/** The remove button on one look, for a test to tap. */
fun inspirationRemoveTag(id: String) = "inspiration-remove-$id"

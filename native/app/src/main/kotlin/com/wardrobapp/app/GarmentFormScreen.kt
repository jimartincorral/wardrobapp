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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.domain.DuplicateReason
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.SIZE_CHIPS
import com.wardrobapp.domain.COMMON_SIZES
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.garmentCategory
import com.wardrobapp.presentation.BackgroundAction
import com.wardrobapp.presentation.GARMENT_COLORS
import com.wardrobapp.presentation.backgroundActionFor

/**
 * Adding or editing a garment.
 *
 * Layout only. Which types a category offers, what a tag is, whether the palette
 * may be emptied and which seasons a type implies were all decided in :domain and
 * :presentation before anything reached here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GarmentFormScreen(
    state: GarmentFormViewModel.State,
    isEditing: Boolean,
    brandSuggestions: (String) -> List<String>,
    onBack: () -> Unit,
    onAddPhoto: () -> Unit,
    onPhotoSelected: (Int) -> Unit,
    onPhotoRemoved: (Int) -> Unit,
    onRemoveBackground: () -> Unit,
    onUndoBackground: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onSubcategoryToggled: (String) -> Unit,
    onSeasonToggled: (Season) -> Unit,
    onColorToggled: (String) -> Unit,
    onBrandChanged: (String) -> Unit,
    onSizeChanged: (String) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    onSave: () -> Unit,
    onSaveAnyway: () -> Unit,
    onDuplicatesDismissed: () -> Unit,
    onErrorDismissed: () -> Unit,
) {
    val form = state.form

    if (state.duplicates.isNotEmpty()) {
        DuplicateWarning(state.duplicates, onSaveAnyway, onDuplicatesDismissed)
    }

    state.errorText()?.let { error ->
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text(stringResource(R.string.form_error_title)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = onErrorDismissed) { Text(stringResource(R.string.action_close)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.form_title_edit else R.string.form_title_add
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { insets ->
        if (state.missing) {
            Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.garment_missing)) }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Section(stringResource(R.string.form_section_photos)) {
                    Column {
                        Photos(
                            uris = form.galleryItems().map { it.uri },
                            selected = form.selectedImageIndex,
                            busy = state.saving,
                            onAdd = onAddPhoto,
                            onSelect = onPhotoSelected,
                            onRemove = onPhotoRemoved,
                        )

                        // What to offer is :presentation's call, from the same
                        // function the detail screen asks -- "is there an original"
                        // is true for a photo with no cut-out at all, so asking it
                        // directly offered undo where there was nothing to undo.
                        BackgroundControl(
                            action = backgroundActionFor(
                                form.imageUris.getOrNull(form.selectedImageIndex),
                                form.bgRemovedUris.getOrNull(form.selectedImageIndex),
                            ),
                            running = state.removingBackground,
                            onRemove = onRemoveBackground,
                            onUndo = onUndoBackground,
                        )
                    }
                }
            }

            item {
                Section(stringResource(R.string.filter_section_category)) {
                    Chips(GARMENT_CATEGORIES.map { it.id }, setOf(form.category), { categoryLabel(it) }) {
                        onCategorySelected(it)
                    }
                }
            }

            garmentCategory(form.category)?.let { category ->
                item {
                    Section(stringResource(R.string.filter_section_type)) {
                        Chips(category.subcategories, form.subcategories.toSet(), { garmentTypeLabel(it) }) {
                            onSubcategoryToggled(it)
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.filter_section_season)) {
                    Chips(Season.entries.toList(), form.seasons.toSet(), { stringResource(it.labelRes) }) {
                        onSeasonToggled(it)
                    }
                }
            }

            item {
                Section(stringResource(R.string.property_colours)) {
                    Colors(form.colorPalette.toSet(), onColorToggled)
                }
            }

            item {
                Section(stringResource(R.string.form_section_tags)) {
                    Tags(form.tags, onTagsChanged)
                }
            }

            item {
                Section(stringResource(R.string.filter_brand)) {
                    Brand(form.brand, brandSuggestions(form.brand), onBrandChanged)
                }
            }

            item {
                Section(stringResource(R.string.filter_size)) {
                    Column {
                        Chips(
                            COMMON_SIZES.take(SIZE_CHIPS),
                            setOf(form.size),
                            { it },
                        ) { onSizeChanged(if (form.size == it) "" else it) }

                        OutlinedTextField(
                            value = form.size,
                            onValueChange = onSizeChanged,
                            label = { Text(stringResource(R.string.form_size_custom)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = onSave,
                    enabled = !state.saving && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.saving -> stringResource(R.string.form_saving)
                            isEditing -> stringResource(R.string.form_save_edit)
                            else -> stringResource(R.string.form_save_add)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Remove or restore the selected photo's background.
 *
 * Runs a model on the device, which takes seconds rather than milliseconds, so it
 * says what it is doing rather than just disabling itself. Undo appears only while
 * there is a separate original to go back to: after a garment is saved the cut-out
 * *is* the photo, and offering undo then would be a button that destroys the only
 * copy.
 */
@Composable
private fun BackgroundControl(
    action: BackgroundAction?,
    running: Boolean,
    onRemove: () -> Unit,
    onUndo: () -> Unit,
) {
    // Nothing to offer once a cut-out has replaced the photo it came from: there
    // is no original left, and a button there would destroy the only copy.
    if (action == null && !running) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        when {
            running -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.background_cutting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            action == BackgroundAction.REMOVE ->
                TextButton(onClick = onRemove) { Text(stringResource(R.string.background_remove)) }
            action == BackgroundAction.UNDO ->
                TextButton(onClick = onUndo) { Text(stringResource(R.string.background_undo)) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onTap: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            FilterChip(
                selected = option in selected,
                onClick = { onTap(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun Photos(
    uris: List<String>,
    selected: Int,
    busy: Boolean,
    onAdd: () -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(uris) { index, uri ->
            Box {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            2.dp,
                            if (index == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                            RoundedCornerShape(8.dp),
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelect(index) },
                )

                // On the photo rather than beside it, so the row stays a row of
                // photos however many there are.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.form_remove_photo),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !busy, onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.form_add_photo))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Colors(selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((_, hex) in GARMENT_COLORS) {
            val color = hex.toComposeColor()
            // The multi-colour sentinel has no colour of its own to draw, so it
            // is left out rather than shown as a blank swatch.
            if (color == null) continue

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        if (hex in selected) 3.dp else 1.dp,
                        if (hex in selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        CircleShape,
                    )
                    .clickable { onToggle(hex) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Tags(tags: List<String>, onChange: (List<String>) -> Unit) {
    Column {
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                for (tag in tags) {
                    FilterChip(
                        selected = true,
                        onClick = { onChange(tags - tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.form_remove_tag),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        // One text field rather than a chip editor: a smaller thing to get right
        // than an input that has to manage its own keyboard. A comma commits,
        // which also means pasting a list of tags works.
        var draft by remember { mutableStateOf("") }

        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                val entered = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

                if (text.endsWith(',') && entered.isNotEmpty()) {
                    // Lowercased on the way in, as mergeStructuredTags will do
                    // anyway, so the chip reads the way the stored tag will.
                    onChange((tags + entered.map { it.lowercase() }).distinct())
                    draft = ""
                } else {
                    draft = text
                }
            },
            label = { Text(stringResource(R.string.form_tag_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Brand(brand: String, suggestions: List<String>, onChange: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = brand,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.filter_brand)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (suggestions.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                itemsIndexed(suggestions) { _, suggestion ->
                    TextButton(onClick = { onChange(suggestion) }) { Text(suggestion) }
                }
            }
        }
    }
}

/**
 * What is already in the wardrobe that this might be.
 *
 * Shown before anything is written, and dismissable both ways: the check is a
 * question, not a refusal, since two similar garments are a perfectly ordinary
 * thing to own.
 */
@Composable
private fun DuplicateWarning(
    matches: List<DuplicateGarment>,
    onSaveAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_title)) },
        text = {
            Column {
                for (match in matches.take(3)) {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = match.garment.displayImage,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    match.garment.subcategory?.let { garmentTypeLabel(it) }
                                        ?: categoryLabel(match.garment.category),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    match.reasons.joinToString(", ") { it.label() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSaveAnyway) { Text(stringResource(R.string.duplicate_add_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.duplicate_review)) } },
    )
}

@Composable
private fun DuplicateReason.label(): String = stringResource(
    when (this) {
        DuplicateReason.SIMILAR_TAGS -> R.string.duplicate_reason_tags
        DuplicateReason.SIMILAR_COLOR -> R.string.duplicate_reason_colour
        DuplicateReason.SAME_SIZE -> R.string.duplicate_reason_size
        DuplicateReason.OVERALL_SIMILARITY -> R.string.duplicate_reason_overall
    }
)

/**
 * What to show when saving or importing failed.
 *
 * The exception's own words if it had any, and otherwise what the app was doing.
 * The same rule every screen here follows.
 */
@Composable
private fun GarmentFormViewModel.State.errorText(): String? =
    error ?: errorFallback?.let { stringResource(it) }

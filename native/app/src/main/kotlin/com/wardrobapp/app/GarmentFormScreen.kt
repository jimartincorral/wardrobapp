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
import com.wardrobapp.presentation.GARMENT_COLORS

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

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text("Couldn't save") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = onErrorDismissed) { Text("Close") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit garment" else "Add garment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        if (state.missing) {
            Box(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentAlignment = Alignment.Center,
            ) { Text("That garment is no longer in your wardrobe.") }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Section("Photos") {
                    Photos(
                        uris = form.galleryItems().map { it.uri },
                        selected = form.selectedImageIndex,
                        busy = state.saving,
                        onAdd = onAddPhoto,
                        onSelect = onPhotoSelected,
                        onRemove = onPhotoRemoved,
                    )
                }
            }

            item {
                Section("Category") {
                    Chips(GARMENT_CATEGORIES.map { it.id }, setOf(form.category), { it.sentence() }) {
                        onCategorySelected(it)
                    }
                }
            }

            garmentCategory(form.category)?.let { category ->
                item {
                    Section("Type") {
                        Chips(category.subcategories, form.subcategories.toSet(), { it }) {
                            onSubcategoryToggled(it)
                        }
                    }
                }
            }

            item {
                Section("Season") {
                    Chips(Season.entries.toList(), form.seasons.toSet(), { it.label() }) {
                        onSeasonToggled(it)
                    }
                }
            }

            item {
                Section("Colours") {
                    Colors(form.colorPalette.toSet(), onColorToggled)
                }
            }

            item {
                Section("Tags") {
                    Tags(form.tags, onTagsChanged)
                }
            }

            item {
                Section("Brand") {
                    Brand(form.brand, brandSuggestions(form.brand), onBrandChanged)
                }
            }

            item {
                Section("Size") {
                    Column {
                        Chips(
                            COMMON_SIZES.take(SIZE_CHIPS),
                            setOf(form.size),
                            { it },
                        ) { onSizeChanged(if (form.size == it) "" else it) }

                        OutlinedTextField(
                            value = form.size,
                            onValueChange = onSizeChanged,
                            label = { Text("Or type a size") },
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
                            state.saving -> "Saving…"
                            isEditing -> "Save changes"
                            else -> "Add to wardrobe"
                        }
                    )
                }
            }
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
                            contentDescription = "Remove photo",
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
                    Icon(Icons.Filled.Add, contentDescription = "Add a photo")
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
                                contentDescription = "Remove tag",
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
            label = { Text("Add a tag, then a comma") },
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
            label = { Text("Brand") },
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
        title = { Text("You may already own this") },
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
                                    match.garment.subcategory ?: match.garment.category,
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
        confirmButton = { TextButton(onClick = onSaveAnyway) { Text("Add it anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Let me look") } },
    )
}

private fun DuplicateReason.label(): String = when (this) {
    DuplicateReason.SIMILAR_TAGS -> "similar tags"
    DuplicateReason.SIMILAR_COLOR -> "similar colour"
    DuplicateReason.SAME_SIZE -> "same size"
    DuplicateReason.OVERALL_SIMILARITY -> "overall similarity"
}

private fun Season.label(): String = tag.replace('-', ' ').sentence()

private fun String.sentence(): String = replaceFirstChar { it.uppercase() }

package com.wardrobapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.presentation.LookReader
import com.wardrobapp.presentation.LookRow
import com.wardrobapp.presentation.LookScreenState
import com.wardrobapp.presentation.paletteColorFor
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_back
import com.wardrobapp.ui.resources.gap_add
import com.wardrobapp.ui.resources.gap_want
import com.wardrobapp.ui.resources.look_closest_owned
import com.wardrobapp.ui.resources.look_gone
import com.wardrobapp.ui.resources.look_intro
import com.wardrobapp.ui.resources.look_none_owned
import com.wardrobapp.ui.resources.look_read_again
import com.wardrobapp.ui.resources.look_reader_download
import com.wardrobapp.ui.resources.look_reader_downloadable
import com.wardrobapp.ui.resources.look_reader_downloading
import com.wardrobapp.ui.resources.look_reader_unavailable
import com.wardrobapp.ui.resources.look_reading
import com.wardrobapp.ui.resources.look_save
import com.wardrobapp.ui.resources.look_saving
import com.wardrobapp.ui.resources.look_title
import com.wardrobapp.ui.resources.look_unread
import org.jetbrains.compose.resources.stringResource

/**
 * A saved look, taken apart: its photo, then one row per piece worn in it --
 * named as a gap is ("Navy blazer"), beside the garment in the wardrobe that
 * comes closest, or a line saying there is none and a button to add one.
 * Under them, a button to keep what the wardrobe has of the look as an outfit.
 *
 * Phone only, since Gemini Nano is what reads the pieces; where it is not
 * ready the screen says why (ReaderNotice) instead of the rows. The rows are not editable -- swapping a garment is the saved
 * outfit's own screen's job, and doing it here would be a second outfit
 * editor -- and a piece read wrongly is answered by reading the look again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookScreen(
    state: LookScreenState,
    onBack: () -> Unit,
    onReadAgain: () -> Unit,
    onAddMissing: (PhantomGarment) -> Unit,
    onGarmentOpened: (String) -> Unit,
    onSave: () -> Unit,
    /** Ask Android to download Gemini Nano, when the screen says it can. */
    onDownloadReader: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.look_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { insets ->
        if (state.missing) {
            Box(modifier = Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.look_gone), style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).testTag(LOOK_ROWS),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.look?.let { look ->
                item {
                    GarmentPhoto(
                        uri = look.imageUri,
                        cutout = false,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).aspectRatio(3f / 4f),
                    )
                }
            }

            when {
                state.loading -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        if (state.reading) {
                            Text(
                                stringResource(Res.string.look_reading),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }

                state.reader != LookReader.READY -> item {
                    ReaderNotice(state.reader, onDownloadReader)
                }

                state.unread || state.error != null -> item {
                    Column {
                        Text(
                            state.error ?: stringResource(Res.string.look_unread),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onReadAgain) { Text(stringResource(Res.string.look_read_again)) }
                    }
                }

                else -> {
                    item {
                        Text(
                            stringResource(Res.string.look_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(state.rows) { index, row ->
                        // A rule between pieces, not around them: each row is a
                        // name, a photo and a button, and without one the eye
                        // cannot tell which photo answers which name.
                        Column {
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                            PieceRow(row, onAddMissing = onAddMissing, onGarmentOpened = onGarmentOpened)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = onSave,
                                enabled = state.canSave,
                                modifier = Modifier.fillMaxWidth().testTag(LOOK_SAVE),
                            ) {
                                Text(stringResource(if (state.saving) Res.string.look_saving else Res.string.look_save))
                            }
                            TextButton(onClick = onReadAgain) { Text(stringResource(Res.string.look_read_again)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Why the look has not been taken apart, when the reason is Gemini Nano:
 * downloadable, with the button that asks Android for it; downloading, with
 * a wait; or not on this phone, said plainly. Said rather than hidden,
 * because a look that did nothing when tapped told nobody anything.
 */
@Composable
private fun ReaderNotice(reader: LookReader, onDownload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(LOOK_READER)) {
        Text(
            stringResource(
                when (reader) {
                    LookReader.DOWNLOADABLE -> Res.string.look_reader_downloadable
                    LookReader.DOWNLOADING -> Res.string.look_reader_downloading
                    else -> Res.string.look_reader_unavailable
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (reader) {
            LookReader.DOWNLOADABLE -> Button(onClick = onDownload) { Text(stringResource(Res.string.look_reader_download)) }
            LookReader.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
            else -> Unit
        }
    }
}

/**
 * One piece: what the look has, and what the wardrobe has for it. Named the
 * way a gap names what it wants, because a piece the wardrobe lacks is one --
 * which is why its button opens the same add form a gap does.
 */
@Composable
private fun PieceRow(row: LookRow, onAddMissing: (PhantomGarment) -> Unit, onGarmentOpened: (String) -> Unit) {
    val piece = row.piece
    val type = typeLabel(piece.category, piece.subcategory)
    val colourName = piece.color?.let { paletteColorFor(it) }?.first?.let { paletteLabel(it) }
    val title = if (colourName != null) stringResource(Res.string.gap_want, colourName, type) else type

    // What to show beside the name: the garment that stands in for the piece,
    // else the nearest thing there is, marked as such.
    val shown = row.garment ?: row.closest
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (shown != null) {
            GarmentPhoto(
                garment = shown,
                modifier = Modifier.size(72.dp).clickable { onGarmentOpened(shown.id) },
                contentDescription = title,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (row.garment == null) {
                Text(
                    stringResource(if (row.closest != null) Res.string.look_closest_owned else Res.string.look_none_owned),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.garment == null) {
            TextButton(onClick = { onAddMissing(piece.asWanted()) }) { Text(stringResource(Res.string.gap_add)) }
        }
    }
}

const val LOOK_ROWS = "look-rows"
const val LOOK_SAVE = "look-save"
const val LOOK_READER = "look-reader"

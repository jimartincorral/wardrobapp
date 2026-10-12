package com.wardrobapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.presentation.ShoppingCheckState
import com.wardrobapp.presentation.paletteColorFor
import com.wardrobapp.ui.resources.Res
import com.wardrobapp.ui.resources.action_back
import com.wardrobapp.ui.resources.gap_want
import com.wardrobapp.ui.resources.photo_choose
import com.wardrobapp.ui.resources.photo_take
import com.wardrobapp.ui.resources.shopping_another
import com.wardrobapp.ui.resources.shopping_bought
import com.wardrobapp.ui.resources.shopping_checking
import com.wardrobapp.ui.resources.shopping_goes_with
import com.wardrobapp.ui.resources.shopping_goes_with_nothing
import com.wardrobapp.ui.resources.shopping_intro
import com.wardrobapp.ui.resources.shopping_none_owned
import com.wardrobapp.ui.resources.shopping_other_colours
import com.wardrobapp.ui.resources.shopping_same
import com.wardrobapp.ui.resources.shopping_same_kind
import com.wardrobapp.ui.resources.shopping_title
import com.wardrobapp.ui.resources.shopping_unread
import org.jetbrains.compose.resources.stringResource

/**
 * Before you buy: the photo of something in a shop, what it was read as, and
 * the wardrobe's two answers -- what is already owned like it, and the outfits
 * it would be worn in. See ShoppingCheckScreenModel.
 *
 * The outfits are drawn as the gap cards draw theirs, a row of thumbnails, with
 * the shop's own photo first where a gap has its empty frame: the one thing in
 * them the reader does not own yet. Nothing on the screen is saved; the two
 * ways off it are another photo, and the add form filled in from this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingCheckScreen(
    state: ShoppingCheckState,
    onBack: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    /** Open the add form filled in as the photo was read. */
    onBought: (PhantomGarment) -> Unit,
    onGarmentOpened: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.shopping_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).testTag(SHOPPING_CHECK),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val wanted = state.wanted

            if (state.photo == null && !state.checking) {
                item {
                    Text(
                        stringResource(Res.string.shopping_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.photo?.let { photo ->
                item {
                    GarmentPhoto(
                        uri = photo,
                        cutout = false,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).aspectRatio(3f / 4f),
                    )
                }
            }

            when {
                state.checking -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(Res.string.shopping_checking),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                state.unread || state.error != null -> item {
                    Text(
                        state.error ?: stringResource(Res.string.shopping_unread),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                wanted != null -> {
                    item {
                        Text(wantedTitle(wanted), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    item { Owned(state, wanted, onGarmentOpened) }
                    item { HorizontalDivider() }
                    item { GoesWith(state, onGarmentOpened) }
                    item {
                        Button(onClick = { onBought(wanted) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.shopping_bought))
                        }
                    }
                }
            }

            if (!state.checking) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.photo != null) {
                            Text(
                                stringResource(Res.string.shopping_another),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onTakePhoto, modifier = Modifier.testTag(SHOPPING_TAKE)) {
                                Text(stringResource(Res.string.photo_take))
                            }
                            TextButton(onClick = onChoosePhoto) { Text(stringResource(Res.string.photo_choose)) }
                        }
                    }
                }
            }
        }
    }
}

/** "Navy blazer", or just "Blazer" when the colour has no name in the palette or was not read. */
@Composable
private fun wantedTitle(wanted: PhantomGarment): String {
    val type = typeLabel(wanted.category, wanted.subcategory)
    val colourName = paletteColorFor(wanted.colorPrimary)?.first?.let { paletteLabel(it) }
    return if (colourName != null) stringResource(Res.string.gap_want, colourName, type) else type
}

/**
 * What the reader already has like it: the same colour said first and
 * plainly, since that is the answer that saves the money; the same kind in
 * other colours after, as the context for deciding.
 */
@Composable
private fun Owned(state: ShoppingCheckState, wanted: PhantomGarment, onGarmentOpened: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.same.isEmpty() && state.otherColours.isEmpty()) {
            Text(stringResource(Res.string.shopping_none_owned), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (state.same.isNotEmpty()) {
            Text(
                stringResource(Res.string.shopping_same),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Thumbnails(state.same, onGarmentOpened)
        }
        if (state.otherColours.isNotEmpty()) {
            Text(
                stringResource(
                    if (wanted.colorPrimary.isBlank()) Res.string.shopping_same_kind else Res.string.shopping_other_colours,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Thumbnails(state.otherColours, onGarmentOpened)
        }
    }
}

/** The outfits it would be worn in: the shop's photo, then what is owned. */
@Composable
private fun GoesWith(state: ShoppingCheckState, onGarmentOpened: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.outfits.isEmpty()) {
            Text(stringResource(Res.string.shopping_goes_with_nothing), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(stringResource(Res.string.shopping_goes_with), style = MaterialTheme.typography.bodyMedium)
        for (outfit in state.outfits) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                GarmentPhoto(
                    uri = state.photo,
                    cutout = false,
                    shape = RoundedCornerShape(6.dp),
                    inset = 2.dp,
                    modifier = Modifier.size(56.dp),
                )
                for (garment in outfit.garments) {
                    GarmentPhoto(
                        garment,
                        shape = RoundedCornerShape(6.dp),
                        inset = 2.dp,
                        modifier = Modifier.size(56.dp).clickable { onGarmentOpened(garment.id) },
                    )
                }
            }
        }
    }
}

/** A row of the reader's own garments, each opening its own screen. */
@Composable
private fun Thumbnails(garments: List<GarmentRecord>, onGarmentOpened: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (garment in garments) {
            GarmentPhoto(
                garment,
                shape = RoundedCornerShape(6.dp),
                inset = 2.dp,
                modifier = Modifier.size(72.dp).clickable { onGarmentOpened(garment.id) },
            )
        }
    }
}

const val SHOPPING_CHECK = "shopping-check"
const val SHOPPING_TAKE = "shopping-take"

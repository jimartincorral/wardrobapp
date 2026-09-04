package com.wardrobapp.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.presentation.GarmentFormState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the garment form shows, and what it refuses to show.
 *
 * Mostly about URL import, which is the part of this screen with a decision in it:
 * whether the section is there at all, and whether a link that arrived from
 * outside is fetched or asked about first. The rest of the form -- chips, photos,
 * tags -- is layout over state that :presentation already decides and tests.
 *
 * Like [SettingsScreenTest], no [AppContainer]: the screen takes a state object
 * and lambdas, so it can be asked what it renders without a database anywhere near
 * it.
 */
@RunWith(RobolectricTestRunner::class)
class GarmentFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: GarmentFormViewModel.State = GarmentFormViewModel.State(),
        isEditing: Boolean = false,
        onColorToggled: (String) -> Unit = {},
    ) {
        compose.setContent {
            GarmentFormScreen(
                state = state,
                isEditing = isEditing,
                brandSuggestions = { emptyList() },
                onBack = {},
                onAddPhoto = {},
                onTakePhoto = {},
                onPhotoSelected = {},
                onPhotoRemoved = {},
                onRemoveBackground = {},
                onUndoBackground = {},
                onCategorySelected = {},
                onSubcategoryToggled = {},
                onSeasonToggled = {},
                onColorToggled = onColorToggled,
                onBrandChanged = {},
                onSizeChanged = {},
                onTagsChanged = {},
                onSave = {},
                onSaveAnyway = {},
                onDuplicatesDismissed = {},
                onErrorDismissed = {},
                onImportUrlChanged = {},
                onImportRequested = {},
                onSharedLinkConfirmed = {},
                onSharedLinkDismissed = {},
                onImportProblemDismissed = {},
            )
        }
    }

    @Test
    fun `adding a garment offers to import one from a link`() {
        show(isEditing = false)

        compose.onNodeWithText("Import from a link").assertIsDisplayed()
        // The field, and the fetch beside it. The fetch is a glyph now, so it is
        // asked for by what it announces rather than by a label it no longer
        // draws -- which is the assertion that matters either way: an icon button
        // with no description is a button a screen reader cannot name.
        compose.onNodeWithText("Product link").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import URL").assertIsDisplayed()
    }

    @Test
    fun `editing one does not`() {
        // An import replaces every photo in the form. Offering that on an edit
        // would be offering to discard the garment's own photos, which is not what
        // anybody means by importing.
        show(isEditing = true)

        compose.onNodeWithText("Import from a link").assertDoesNotExist()
    }

    @Test
    fun `both ways of adding a photo are offered`() {
        show()

        // Scrolled to first, because it is below the fold on a small screen and a
        // LazyColumn has not composed it yet -- which is a different failure from
        // the button being absent, and reads identically in the assertion. This is
        // what the first run of this test found.
        compose.onNodeWithTag(GARMENT_FORM_LIST).performScrollToNode(hasText("Take Photo"))

        compose.onNodeWithText("Take Photo").assertIsDisplayed()
    }

    @Test
    fun `a colour is offered as a name, not only a swatch`() {
        // Twenty-four circles that differ only by shade is not a picker anyone
        // can use from memory. Every other choice on this form -- category,
        // season, occasion -- reads as a word, and now so does this one.
        var toggled: String? = null
        show(onColorToggled = { toggled = it })

        compose.onNodeWithTag(GARMENT_FORM_LIST).performScrollToNode(hasText("Navy"))
        compose.onNodeWithText("Navy").performClick()

        assertEquals("#000080", toggled)
    }

    @Test
    fun `a link from outside names its host and waits`() {
        // The confirmation is the whole safety story for a shared link: it says
        // where the app is about to go, and nothing has been fetched yet.
        show(
            GarmentFormViewModel.State(
                urlImport = GarmentFormViewModel.UrlImport(
                    awaitingConfirmation = "https://shop.example.com/product/shirt",
                ),
            )
        )

        compose.onNodeWithText("shop.example.com", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nothing has been fetched yet", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Load it").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun `a refused address says which host it refused`() {
        // Naming the host is what makes the refusal worth reading: "that link
        // points somewhere I will not go" is not actionable, and the host is.
        show(
            GarmentFormViewModel.State(
                urlImport = GarmentFormViewModel.UrlImport(
                    problem = GarmentFormViewModel.ImportProblem.Unsafe(
                        UnsafeUrlReason.HostIsLocal("192.168.1.1")
                    ),
                ),
            )
        )

        compose.onNodeWithText("192.168.1.1", substring = true).assertIsDisplayed()
        compose.onNodeWithText("local network", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failed import says what went wrong with the page`() {
        show(
            GarmentFormViewModel.State(
                urlImport = GarmentFormViewModel.UrlImport(
                    problem = GarmentFormViewModel.ImportProblem.Failed(
                        ImportFailureReason.PageNotLoaded(404)
                    ),
                ),
            )
        )

        compose.onNodeWithText("(404)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the network's own words are shown when there is nothing better`() {
        show(
            GarmentFormViewModel.State(
                urlImport = GarmentFormViewModel.UrlImport(
                    problem = GarmentFormViewModel.ImportProblem.Foreign("Unable to resolve host"),
                ),
            )
        )

        compose.onNodeWithText("Unable to resolve host", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a finished import says how many photos arrived and where from`() {
        show(
            GarmentFormViewModel.State(
                form = GarmentFormState(imageUris = listOf("file:///a.jpg", "file:///b.jpg")),
                urlImport = GarmentFormViewModel.UrlImport(
                    imported = 2,
                    source = "Zara",
                ),
            )
        )

        compose.onNodeWithText("2 images imported", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Imported from Zara").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "es")
    fun `a Spanish phone is asked about a link in Spanish`() {
        show(
            GarmentFormViewModel.State(
                urlImport = GarmentFormViewModel.UrlImport(
                    awaitingConfirmation = "https://shop.example.com/p",
                ),
            )
        )

        compose.onNodeWithText("¿Importar desde un enlace?").assertIsDisplayed()
        compose.onNodeWithText("Cargarlo").assertIsDisplayed()
    }
}

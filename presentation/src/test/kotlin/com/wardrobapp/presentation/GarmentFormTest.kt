package com.wardrobapp.presentation

import com.wardrobapp.domain.Season
import com.wardrobapp.domain.seasonsForSubcategories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules of the add/edit form.
 *
 * Two lists have to stay aligned -- the photos and their cut-outs, entry for
 * entry -- and almost every wrong answer here loses a photo or keeps a file
 * forever. The other half is about not overwriting what somebody typed: a
 * detected colour, an implied season and an imported brand are all suggestions,
 * and a suggestion that overwrites a choice is a bug the user watches happen.
 *
 * These cases replace the recorded transition scripts, which came from the app
 * this was ported from.
 */
class GarmentFormTest {

    private fun form(
        images: List<String> = emptyList(),
        cutouts: List<String> = emptyList(),
        selected: Int = 0,
    ) = GarmentFormState(
        imageUris = images,
        bgRemovedUris = cutouts,
        selectedImageIndex = selected,
    ).normalized()

    @Test
    fun `normalizing aligns the cut-outs with the photos and keeps a colour`() {
        val state = GarmentFormState(
            imageUris = listOf("a.jpg", "b.jpg", "c.jpg"),
            bgRemovedUris = listOf("a-cut.png"),
            colorPalette = emptyList(),
        ).normalized()

        assertEquals(3, state.bgRemovedUris.size)
        assertEquals(listOf("a-cut.png", "", ""), state.bgRemovedUris)
        assertEquals(listOf(GarmentFormState.DEFAULT_COLOR), state.colorPalette)

        // Extra cut-outs are dropped rather than left dangling past the end.
        val trimmed = GarmentFormState(
            imageUris = listOf("a.jpg"),
            bgRemovedUris = listOf("a-cut.png", "orphan.png"),
        ).normalized()
        assertEquals(listOf("a-cut.png"), trimmed.bgRemovedUris)
    }

    @Test
    fun `adding a photo selects it, and both lists grow together`() {
        val state = form(listOf("a.jpg")).withImage("b.jpg")

        assertEquals(listOf("a.jpg", "b.jpg"), state.imageUris)
        assertEquals(listOf("", ""), state.bgRemovedUris)
        assertEquals(1, state.selectedImageIndex)
    }

    @Test
    fun `replacing a photo drops the cut-out that was made of it`() {
        // The cut-out was of the old photo; keeping it would show one garment's
        // outline over another's picture.
        val state = form(listOf("a.jpg", "b.jpg"), listOf("a-cut.png", "b-cut.png"), selected = 0)
            .withImage("new.jpg", replaceCurrent = true)

        assertEquals(listOf("new.jpg", "b.jpg"), state.imageUris)
        assertEquals(listOf("", "b-cut.png"), state.bgRemovedUris)
    }

    @Test
    fun `replacing when there is nothing there adds instead`() {
        val state = form().withImage("first.jpg", replaceCurrent = true)

        assertEquals(listOf("first.jpg"), state.imageUris)
        assertEquals(listOf(""), state.bgRemovedUris)
    }

    @Test
    fun `removing a photo keeps the selection pointing at something`() {
        val three = form(listOf("a.jpg", "b.jpg", "c.jpg"), listOf("", "b-cut.png", ""), selected = 2)

        // Removing something before the selection shifts it back with the list.
        val removedFirst = three.withoutImageAt(0)
        assertEquals(listOf("b.jpg", "c.jpg"), removedFirst.imageUris)
        assertEquals(listOf("b-cut.png", ""), removedFirst.bgRemovedUris)
        assertEquals(1, removedFirst.selectedImageIndex)

        // Removing the selection itself lands on what is now in that place, or
        // the end of the list.
        val removedLast = three.withoutImageAt(2)
        assertEquals(1, removedLast.selectedImageIndex)

        // Removing the only photo leaves a valid, empty form.
        val emptied = form(listOf("a.jpg")).withoutImageAt(0)
        assertEquals(emptyList(), emptied.imageUris)
        assertEquals(0, emptied.selectedImageIndex)
    }

    @Test
    fun `reordering carries the cut-out and follows with the selection`() {
        val state = form(listOf("a.jpg", "b.jpg", "c.jpg"), listOf("", "b-cut.png", ""))
            .withImagesReordered(fromIndex = 1, toIndex = 0)

        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), state.imageUris)
        assertEquals(listOf("b-cut.png", "", ""), state.bgRemovedUris)
        assertEquals(0, state.selectedImageIndex)
    }

    @Test
    fun `a reorder that goes nowhere changes nothing`() {
        val state = form(listOf("a.jpg", "b.jpg"))

        assertEquals(state, state.withImagesReordered(1, 1))
        assertEquals(state, state.withImagesReordered(0, 5))
        assertEquals(state, state.withImagesReordered(-1, 0))
    }

    @Test
    fun `a cut-out is recorded against the photo it was made from`() {
        val state = form(listOf("a.jpg", "b.jpg"), selected = 1).withBackgroundRemoved("b-cut.png")

        assertEquals(listOf("", "b-cut.png"), state.bgRemovedUris)

        // And clearing it is the same call with nothing.
        assertEquals(listOf("", ""), state.withBackgroundRemoved("").bgRemovedUris)
    }

    @Test
    fun `collapsing stores the cut-out and hands back the original to delete`() {
        // Saving space is the point of removing a background, so keeping both
        // files would make every removal cost storage rather than save it.
        val stored = form(listOf("a.jpg", "b.jpg"), listOf("a-cut.png", "")).imagesToStore()

        assertEquals(listOf("a-cut.png", "b.jpg"), stored.imageUris)
        assertEquals(listOf("a-cut.png", ""), stored.bgRemovedUris)
        assertEquals(listOf("a.jpg"), stored.discardable)
    }

    @Test
    fun `collapsing an already-collapsed garment finds nothing to discard`() {
        // Stated directly because it needs the collapse fed back in, which is
        // what editing a saved garment does. Getting it wrong deletes the photo
        // the garment is showing.
        val once = GarmentFormState(imageUris = listOf("a.jpg"), bgRemovedUris = listOf("a-cut.png"))
            .normalized()
            .imagesToStore()

        val twice = GarmentFormState(
            imageUris = once.imageUris,
            bgRemovedUris = once.bgRemovedUris,
        ).normalized().imagesToStore()

        assertEquals(listOf("a-cut.png"), twice.imageUris)
        assertEquals(listOf("a-cut.png"), twice.bgRemovedUris)
        assertEquals(emptyList(), twice.discardable)
    }

    @Test
    fun `a garment type fills the seasons in, but never over a choice`() {
        val implied = form().withSubcategories(listOf("Sandals"), ::seasonsForSubcategories)
        assertEquals(listOf(Season.SUMMER), implied.seasons)

        val chosen = form().copy(seasons = listOf(Season.WINTER))
            .withSubcategories(listOf("Sandals"), ::seasonsForSubcategories)
        assertEquals(listOf(Season.WINTER), chosen.seasons, "an explicit choice was overwritten")
    }

    @Test
    fun `a garment always has a colour`() {
        val one = form().copy(colorPalette = listOf("#FF0000"))

        // Toggling the last colour off puts the default back rather than leaving
        // the palette empty.
        assertEquals(
            listOf(GarmentFormState.DEFAULT_COLOR),
            one.withColorToggled("#FF0000").colorPalette,
        )
        // Toggling is add-if-absent, remove-if-present.
        assertEquals(listOf("#FF0000", "#00FF00"), one.withColorToggled("#00FF00").colorPalette)
    }

    @Test
    fun `a detected colour goes first and keeps what was already picked`() {
        // A detection is a suggestion, not a correction: replacing the palette
        // would discard a deliberate choice.
        val state = form().copy(colorPalette = listOf("#FF0000", "#00FF00"))
            .withDetectedColor("#0000FF")

        assertEquals(listOf("#0000FF", "#FF0000", "#00FF00"), state.colorPalette)

        // Detecting a colour that is already there moves it up rather than
        // listing it twice.
        val again = state.withDetectedColor("#FF0000")
        assertEquals(listOf("#FF0000", "#0000FF", "#00FF00"), again.colorPalette)
    }

    @Test
    fun `a suggested type joins the ones already picked in the same category`() {
        val state = form().copy(category = "tops", subcategories = listOf("Shirt"))
            .withSuggestedType("tops", "Polo", ::seasonsForSubcategories)

        assertEquals(listOf("Polo", "Shirt"), state.subcategories)
        assertEquals("tops", state.category)

        // And suggesting one that is already picked moves it up rather than
        // listing it twice, exactly as a detected colour does.
        val again = state.withSuggestedType("tops", "Shirt", ::seasonsForSubcategories)
        assertEquals(listOf("Shirt", "Polo"), again.subcategories)
    }

    @Test
    fun `a suggestion in another category replaces the types rather than mixing them`() {
        // "Sneakers, Shirt" is not a garment. The types belonged to a category this
        // one is not, which is why tapping a category chip clears them too.
        val state = form().copy(category = "tops", subcategories = listOf("Shirt"))
            .withSuggestedType("shoes", "Sneakers", ::seasonsForSubcategories)

        assertEquals("shoes", state.category)
        assertEquals(listOf("Sneakers"), state.subcategories)
    }

    @Test
    fun `a suggestion with no type still moves the form to the category`() {
        // What the labeller reported was "Footwear", and narrowing the chips from
        // seventy types to seven is the whole value of that.
        val state = form().copy(category = "tops", subcategories = listOf("Shirt"))
            .withSuggestedType("shoes", null, ::seasonsForSubcategories)

        assertEquals("shoes", state.category)
        assertEquals(emptyList(), state.subcategories)
    }

    @Test
    fun `a suggested type fills the seasons in, but never over a choice`() {
        val implied = form().withSuggestedType("shoes", "Sandals", ::seasonsForSubcategories)
        assertEquals(listOf(Season.SUMMER), implied.seasons)

        val chosen = form().copy(seasons = listOf(Season.WINTER))
            .withSuggestedType("shoes", "Sandals", ::seasonsForSubcategories)
        assertEquals(listOf(Season.WINTER), chosen.seasons, "an explicit choice was overwritten")
    }

    @Test
    fun `an import fills an empty brand and leaves a typed one alone`() {
        val empty = form().withImportedPreview(listOf("i1.jpg", "i2.jpg"), "Imported")
        assertEquals(listOf("i1.jpg", "i2.jpg"), empty.imageUris)
        assertEquals(listOf("", ""), empty.bgRemovedUris)
        assertEquals(0, empty.selectedImageIndex)
        assertEquals("Imported", empty.brand)

        val typed = form().copy(brand = "Mine").withImportedPreview(listOf("i1.jpg"), "Imported")
        assertEquals("Mine", typed.brand, "an import overwrote work in progress")

        // An import with no brand does not blank a typed one either.
        assertEquals("Mine", form().copy(brand = "Mine").withImportedPreview(listOf("i1.jpg"), null).brand)
    }

    @Test
    fun `the gallery shows the cut-out and remembers the original`() {
        val items = form(listOf("a.jpg", "b.jpg"), listOf("a-cut.png", "")).galleryItems()

        assertEquals(listOf("a-cut.png", "b.jpg"), items.map { it.uri })
        assertEquals(listOf("a.jpg", "b.jpg"), items.map { it.original })
    }

    @Test
    fun `the preview shows whatever the selected slot has`() {
        assertEquals("a-cut.png", form(listOf("a.jpg"), listOf("a-cut.png")).displayedPreviewUri())
        assertEquals("a.jpg", form(listOf("a.jpg")).displayedPreviewUri())
        assertEquals(null, form().displayedPreviewUri())
    }

    @Test
    fun `a garment imported as a cut-out has nothing to undo to`() {
        // Both slots hold the same path, so there is no with-background original
        // to restore and nothing to re-run removal against.
        val imported = form(listOf("cut.png"), listOf("cut.png"))
        assertTrue(!imported.selectedHasOriginal())

        assertTrue(form(listOf("a.jpg"), listOf("a-cut.png")).selectedHasOriginal())
        assertTrue(!form().selectedHasOriginal())
    }

    @Test
    fun `brand suggestions are what the list is for`() {
        val known = listOf("Adidas", "Arket", "Nike", "Uniqlo")

        // An empty field offers everything: the list doubles as a picker.
        assertEquals(known, brandSuggestions(known, ""))
        // Matching is case-insensitive and anywhere in the name.
        assertEquals(listOf("Adidas", "Arket"), brandSuggestions(known, "a"))
        assertEquals(listOf("Uniqlo"), brandSuggestions(known, "QLO"))
        // An exact match is dropped: it has already been typed.
        assertEquals(emptyList(), brandSuggestions(listOf("Nike"), "nike"))
        // And the list is capped, so it cannot cover the form.
        assertEquals(
            GarmentFormState.BRAND_SUGGESTION_LIMIT,
            brandSuggestions((1..20).map { "Brand $it" }, "").size,
        )
    }
}

package com.wardrobapp.presentation

import com.wardrobapp.domain.GARMENT_CATEGORIES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The style model's type anchors are written by scripts/export-style-model.py
 * and read back by StyleAnchors under the catalogue's labels, so the two
 * lists have to be the same list. Nothing else would notice if they drifted:
 * a type the script leaves out is one the model never suggests, and one it
 * spells differently ("T-shirt") is ignored without a word. Here, in a module
 * whose tests already read files from across the repository, because the
 * script is Python and the catalogue is Kotlin and only a test sees both.
 */
class StyleAnchorTypesTest {

    @Test
    fun `the export script has an anchor for every garment type, under its stored label`() {
        val script = File(
            System.getProperty("styleExportScript")
                ?: error("styleExportScript was not set; see presentation/build.gradle.kts"),
        ).readText()

        val table = script.substringAfter("\"type\": {").substringBefore("\n    },")
        val keys = Regex("""^\s+"([^"]+)": \[""", RegexOption.MULTILINE).findAll(table).map { it.groupValues[1] }.toSet()
        val labels = GARMENT_CATEGORIES.flatMap { it.subcategories }.toSet()

        assertEquals(labels, keys, "the export's type anchors and the catalogue's types differ")
    }
}

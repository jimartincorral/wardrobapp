package com.wardrobapp.data

import com.wardrobapp.domain.Fit
import com.wardrobapp.domain.Formality
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.GarmentAttributes
import com.wardrobapp.domain.GarmentReading
import com.wardrobapp.domain.ReadType
import com.wardrobapp.domain.Pattern
import com.wardrobapp.domain.Weight
import com.wardrobapp.domain.mergeStructuredTags
import com.wardrobapp.domain.splitStructuredTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The parts of the style model that are the same wherever it runs.
 *
 * The model itself -- the image half of CLIP ViT-B/32 at 8 bits, made by
 * scripts/export-style-model.py -- runs in two places: the Home Assistant app,
 * through ONNX Runtime's desktop jar, and the phone, through its Android one.
 * What is around it is not platform code at all: the anchors that turn a
 * vector into attributes, the arithmetic that turns a picture into the
 * model's input, and the pass that keeps a wardrobe's vectors up to date.
 * Those live here, once, so the two sides cannot drift apart -- which they
 * must not, since a vector the phone computed and one the server computed
 * for the same photo are compared with each other after every sync.
 *
 * What stays with each side is what is genuinely theirs: decoding and
 * scaling a photo (ImageIO there, BitmapFactory here), the thread the model
 * runs on, and when a pass is asked for.
 */

/**
 * The anchors the export wrote: for each attribute, a unit vector per value,
 * plus how the model wants its pictures prepared.
 *
 * The text half of CLIP embedded a few sentences per value -- "a photo of
 * formal evening wear" -- and a garment's attribute is the value whose anchor
 * its photo's vector sits nearest. The text model never runs on either side;
 * a few hundred numbers in a JSON file need no tokenizer.
 */
class StyleAnchors(
    val model: String,
    val imageSize: Int,
    val mean: FloatArray,
    val deviation: FloatArray,
    val dimensions: Int,
    val formality: Map<Formality, FloatArray>,
    val pattern: Map<Pattern, FloatArray>,
    val fit: Map<Fit, FloatArray>,
    val weight: Map<Weight, FloatArray>,
    val statement: Map<Boolean, FloatArray>,
    /**
     * An anchor per garment type, keyed by the label a garment row stores
     * ("Chinos", "Tank Top"). Empty in an anchors file written before types
     * were asked for, which then simply reads no type.
     */
    val types: Map<String, FloatArray> = emptyMap(),
) {
    /**
     * What [vector] suggests, attribute by attribute: the nearest anchor,
     * where it beats the runner-up by [LEAST_MARGIN] of cosine; unset where
     * the model cannot tell. Cosines between CLIP's text and image embeddings
     * sit in a narrow band -- a few hundredths separate a clear call from a
     * toss-up -- which is what the margin is sized to.
     */
    fun attributesFor(vector: FloatArray): GarmentAttributes = GarmentAttributes(
        formality = nearest(formality, vector),
        pattern = nearest(pattern, vector),
        fit = nearest(fit, vector),
        weight = nearest(weight, vector),
        statement = nearest(statement, vector),
    )

    /**
     * The garment type [vector] is a photo of, as far as the model can tell.
     *
     * Seventy-odd types are a much harder question than five formalities, and
     * the model is not equally sure of all of it: it tells a shoe from a shirt
     * at a glance and a Midi from a Maxi barely at all. So it is asked twice.
     * The nearest type is the answer when it beats the runner-up by the same
     * margin the attributes need; when it does not, the category alone is,
     * provided the best anchor in it beats the best in every other category by
     * that margin -- "some kind of top" is still a field filled in. Failing
     * both, nothing: the form keeps whatever it was going to show.
     */
    fun typeFor(vector: FloatArray): ReadType? {
        if (types.size < 2) return null
        val ranked = types.entries.map { (label, anchor) -> label to cosine(anchor, vector) }.sortedByDescending { it.second }
        val (best, bestScore) = ranked[0]
        val category = CATEGORY_OF[best] ?: return null
        if (bestScore - ranked[1].second >= LEAST_MARGIN) return ReadType(category, best)
        val bestElsewhere = ranked.firstOrNull { CATEGORY_OF[it.first] != category }?.second ?: return ReadType(category, null)
        return if (bestScore - bestElsewhere >= LEAST_MARGIN) ReadType(category, null) else null
    }

    /** Everything the model reads off [vector] at once: the type and the attributes. */
    fun readingFor(vector: FloatArray): GarmentReading =
        GarmentReading(type = typeFor(vector), attributes = attributesFor(vector))

    private fun <T> nearest(anchors: Map<T, FloatArray>, vector: FloatArray): T? {
        if (anchors.size < 2) return null
        val ranked = anchors.entries.map { (value, anchor) -> value to cosine(anchor, vector) }.sortedByDescending { it.second }
        val (best, bestScore) = ranked[0]
        return if (bestScore - ranked[1].second >= LEAST_MARGIN) best else null
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var total = 0f
        for (i in a.indices) total += a[i] * b[i]
        return total
    }

    @Serializable
    private class Document(
        val model: String,
        val imageSize: Int,
        val mean: List<Float>,
        val deviation: List<Float>,
        val dimensions: Int,
        val anchors: Map<String, Map<String, List<Float>>>,
    )

    companion object {
        /** By how much the nearest anchor must beat the next for the attribute to be written. */
        const val LEAST_MARGIN = 0.01f

        private val json = Json { ignoreUnknownKeys = true }

        /** Each stored type label's category, for a type the model named. */
        private val CATEGORY_OF: Map<String, String> =
            GARMENT_CATEGORIES.flatMap { category -> category.subcategories.map { it to category.id } }.toMap()

        fun parse(text: String): StyleAnchors {
            val document = json.decodeFromString(Document.serializer(), text)
            fun <T> table(name: String, values: List<T>, tag: (T) -> String): Map<T, FloatArray> {
                val anchors = document.anchors[name].orEmpty()
                return values.mapNotNull { value -> anchors[tag(value)]?.let { value to it.toFloatArray() } }.toMap()
            }
            return StyleAnchors(
                model = document.model,
                imageSize = document.imageSize,
                mean = document.mean.toFloatArray(),
                deviation = document.deviation.toFloatArray(),
                dimensions = document.dimensions,
                formality = table("formality", Formality.entries) { it.tag },
                pattern = table("pattern", Pattern.entries) { it.tag },
                fit = table("fit", Fit.entries) { it.tag },
                weight = table("weight", Weight.entries) { it.tag },
                statement = table("statement", listOf(true, false)) { if (it) "yes" else "no" },
                // Only the types this build knows: a type a newer export has and
                // this catalogue does not would be an answer the form cannot show.
                types = table("type", CATEGORY_OF.keys.toList()) { it },
            )
        }
    }
}

/** A rectangle of a picture, in pixels: where it starts and how big it is. */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * How a [width] by [height] picture is brought to the model's [size] by
 * [size] square, the way CLIP was trained: the shorter side scaled to [size],
 * the longer one with it, and the middle square cut out. The first rectangle
 * is the scaled picture's size; the second is the square to keep, inside it.
 *
 * Shared so the phone and the server cut the same square out of the same
 * photo. Rounded rather than truncated, and never below [size], so a photo a
 * pixel from square does not lose a row to arithmetic.
 */
fun centreSquare(width: Int, height: Int, size: Int): Pair<PixelRect, PixelRect> {
    val scale = size.toDouble() / minOf(width, height)
    val scaledWidth = maxOf(size, kotlin.math.round(width * scale).toInt())
    val scaledHeight = maxOf(size, kotlin.math.round(height * scale).toInt())
    return PixelRect(0, 0, scaledWidth, scaledHeight) to
        PixelRect((scaledWidth - size) / 2, (scaledHeight - size) / 2, size, size)
}

/**
 * [argb], the model's square of pixels as ARGB ints row by row, as the model
 * reads it: three planes, red, green and blue, each normalised with the mean
 * and deviation the model was trained with (carried in the anchors file, so
 * the two cannot drift apart).
 *
 * Transparency is laid over black. That is not a choice made for the model --
 * a cut-out on white would be nearer the product photos CLIP learned from --
 * but what the server has always done, since it draws a photo onto an RGB
 * image whose pixels start black, and every vector it has stored was made
 * that way. The phone has to see a cut-out as the server does, or the two
 * would disagree about the same garment.
 */
fun clipInput(argb: IntArray, size: Int, anchors: StyleAnchors): FloatArray {
    val plane = size * size
    require(argb.size == plane) { "Expected $plane pixels, got ${argb.size}." }
    val input = FloatArray(3 * plane)
    for ((index, pixel) in argb.withIndex()) {
        val alpha = (pixel ushr 24 and 0xFF) / 255f
        for (channel in 0..2) {
            val value = (pixel shr (16 - 8 * channel) and 0xFF) / 255f * alpha
            input[channel * plane + index] = (value - anchors.mean[channel]) / anchors.deviation[channel]
        }
    }
    return input
}

/**
 * One pass over a wardrobe: embed every garment whose photo has no vector
 * yet, or a vector of an older photo, and every look with none; how many it
 * embedded.
 *
 * A garment with no attributes of its own gets the ones the model reads off
 * its photo, written as the tags GarmentAttributes describes and stamped as
 * an edit, so the sync carries them to the other side. One somebody has set
 * anything on is left entirely alone: a person's word beats the model's,
 * and half a guess beside a choice would read as the person's. Both sides
 * run this, and both follow that rule, so whichever reads a garment first
 * writes its attributes, and the other then finds them set.
 *
 * [embedPhoto] turns a stored photo's name into a unit vector, or null when
 * it cannot -- a file not there yet, a photo no decoder reads. The photo is
 * then left for the next pass; a vector of nothing would be worse than none.
 * It is the caller's to say why in its log, since only it knows.
 *
 * Not threaded: each side runs passes one at a time on a thread of its own,
 * and asks for them when something may have left a photo without a vector.
 */
class StyleIndexer(
    private val style: StyleQueries,
    private val garments: GarmentQueries,
    private val garmentWrites: GarmentWrites,
    private val model: String,
    private val anchors: StyleAnchors,
    private val embedPhoto: (photo: String) -> FloatArray?,
    private val now: () -> String,
) {
    fun pass(): Int {
        var embedded = 0
        val computed = style.embeddedPhotos()
        for (garment in garments.allGarments(GarmentQueries.Filters(availableOnly = false))) {
            val photo = toStoredImageRef(garment.displayImage)
            if (photo.isEmpty() || computed[garment.id] == photo) continue
            val vector = embedPhoto(photo) ?: continue
            style.putEmbedding(garment.id, photo, model, vector, now())
            embedded++

            val (customTags, seasons, set) = splitStructuredTags(garment.tags)
            if (set.isEmpty) {
                val read = anchors.attributesFor(vector)
                if (!read.isEmpty) {
                    garmentWrites.update(
                        garment.id,
                        GarmentWrites.GarmentEdit(tags = mergeStructuredTags(customTags, seasons, read)),
                        now = now(),
                    )
                }
            }
        }
        for (look in style.inspirationsToEmbed()) {
            val vector = embedPhoto(toStoredImageRef(look.imageUri)) ?: continue
            style.putInspirationVector(look.id, model, vector)
            embedded++
        }
        return embedded
    }
}

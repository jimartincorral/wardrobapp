package com.wardrobapp.server

import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.toStoredImageRef
import com.wardrobapp.domain.GARMENT_CATEGORIES
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.occasions
import com.wardrobapp.presentation.ArchivedGarments
import com.wardrobapp.presentation.GARMENT_COLORS
import com.wardrobapp.presentation.GarmentSort
import com.wardrobapp.presentation.OutfitDraft
import com.wardrobapp.presentation.OutfitFilters
import com.wardrobapp.presentation.SuggestionRequest
import com.wardrobapp.presentation.WardrobeQuery
import com.wardrobapp.presentation.nearestGarmentColor
import com.wardrobapp.presentation.paletteColorFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * What an agent can do with a wardrobe: the tools the agent port lists.
 *
 * Each is one or two calls to the sources the browser's routes call (see
 * WardrobeApi) -- the Database sources the phone's screens use -- so the rules
 * about what a garment, an outfit or a suggestion is are the ones the app
 * already keeps, and nothing here can decide them differently. What is here
 * is translation: a tool's arguments into a source's question, and its answer
 * into JSON a model reads well.
 *
 * That JSON is not the wire types the browser gets. A GarmentRecord carries
 * photo paths, hex colours and parallel lists of cut-outs, which are what a
 * screen needs and noise to a model -- or worse, a hex it will try to name
 * itself. So a garment goes out as [summary]: its id, what it is, in the
 * catalogue's own words, its colours by the palette's names, and the seasons
 * and occasions the app already derives for it. The words are the app's
 * stored ones, in English, because those are what the tools take back as
 * arguments; a brand is as its owner typed it.
 *
 * The tools that write are the ones whose effect the app can undo from its
 * own screens -- an outfit saved can be deleted, a rating changed, an archived
 * garment unarchived -- and nothing that deletes. An agent that misunderstood
 * should cost somebody a tap, not a garment.
 */

/** Every tool, in the order they are listed: reading first, then the three that write. */
internal fun wardrobeTools(wardrobe: ServerWardrobe): List<McpTool> = listOf(
    searchWardrobe(wardrobe),
    getGarment(wardrobe),
    suggestOutfits(wardrobe),
    listOutfits(wardrobe),
    getOutfit(wardrobe),
    wardrobeStatistics(wardrobe),
    wardrobeGaps(wardrobe),
    saveOutfit(wardrobe),
    rateOutfit(wardrobe),
    setGarmentArchived(wardrobe),
)

// ---- The vocabulary a tool's arguments are checked against ----------------------

private val CATEGORY_IDS = GARMENT_CATEGORIES.map { it.id }
private val TYPES = GARMENT_CATEGORIES.flatMap { it.subcategories }.distinct()
private val COLOR_NAMES = GARMENT_COLORS.map { it.first }
private val SEASON_TAGS = Season.entries.map { it.tag }
private val OCCASION_IDS = Occasion.entries.map { it.id }
private val ARCHIVED_CHOICES = mapOf(
    "hidden" to ArchivedGarments.HIDDEN,
    "included" to ArchivedGarments.INCLUDED,
    "only" to ArchivedGarments.ONLY,
)

/** How many garments a search hands back unless asked for fewer: enough to answer, few enough to read. */
private const val DEFAULT_SEARCH_LIMIT = 50
private const val MAX_SEARCH_LIMIT = 200

/** The longest side of a photo sent to an agent: plenty to see a garment by, a fraction of what is stored. */
private const val PHOTO_SIDE = 768

// ---- Reading --------------------------------------------------------------------

private fun searchWardrobe(wardrobe: ServerWardrobe) = McpTool(
    name = "search_wardrobe",
    title = "Search the wardrobe",
    description = "Find garments in the wardrobe. Every filter is optional and they combine with AND; with none, " +
        "this lists the garments in use, newest first. Returns compact summaries with each garment's id; " +
        "use get_garment to see one garment's photo. Archived garments (no longer worn) are left out unless " +
        "`archived` asks for them.",
    inputSchema = objectSchema(
        "category" to stringSchema("One of the app's categories.", CATEGORY_IDS),
        "type" to stringSchema(
            "A garment type within a category, as the app names it, for example \"Jeans\", \"Sneakers\" or \"Blazer\". " +
                "One of: ${TYPES.joinToString()}.",
        ),
        "color" to stringSchema("A colour from the app's palette.", COLOR_NAMES),
        "season" to stringSchema("Garments tagged for this season.", SEASON_TAGS),
        "occasion" to stringSchema("Garments that suit this kind of day.", OCCASION_IDS),
        "brand" to stringSchema("A brand, matched whole and ignoring case."),
        "size" to stringSchema("A size, matched whole and ignoring case, for example \"M\" or \"42\"."),
        "text" to stringSchema("Words to look for in brand, type, size, category and tags."),
        "archived" to stringSchema(
            "Whether archived garments are hidden (the default), included among the rest, or the only ones listed.",
            ARCHIVED_CHOICES.keys.toList(),
        ),
        "sort" to stringSchema("By when each garment was added: newest first (the default) or oldest first.", listOf("newest", "oldest")),
        "limit" to integerSchema("How many garments to return at most.", 1, MAX_SEARCH_LIMIT, DEFAULT_SEARCH_LIMIT),
    ),
    readOnly = true,
) { arguments ->
    val color = arguments.choice("color", COLOR_NAMES)
    val limit = arguments.integer("limit", 1..MAX_SEARCH_LIMIT) ?: DEFAULT_SEARCH_LIMIT
    val query = WardrobeQuery(
        search = arguments.text("text").orEmpty(),
        sort = if (arguments.choice("sort", listOf("newest", "oldest")) == "oldest") GarmentSort.OLDEST else GarmentSort.NEWEST,
        category = arguments.choice("category", CATEGORY_IDS),
        subcategory = arguments.text("type")?.let(::catalogueType),
        season = arguments.choice("season", SEASON_TAGS)?.let { tag -> Season.entries.first { it.tag == tag } },
        occasion = arguments.choice("occasion", OCCASION_IDS)?.let { id -> Occasion.entries.first { it.id == id } },
        brand = arguments.text("brand").orEmpty(),
        size = arguments.text("size").orEmpty(),
        archived = arguments.choice("archived", ARCHIVED_CHOICES.keys)?.let(ARCHIVED_CHOICES::getValue) ?: ArchivedGarments.HIDDEN,
    )

    // The colour by name rather than through the query's hex: a garment whose
    // stored colour is off the palette is shown under its nearest name, and
    // searching for that name should find it.
    val found = wardrobe.wardrobe.garments(query)
        .filter { garment -> color == null || garment.palette.any { colorName(it) == color } }

    ToolResult.json(
        buildJsonObject {
            put("total", found.size)
            putJsonArray("garments") { found.take(limit).forEach { add(it.summary()) } }
            if (found.size > limit) put("note", "Showing the first $limit of ${found.size}; narrow the search or raise limit for more.")
        },
    )
}

private fun getGarment(wardrobe: ServerWardrobe) = McpTool(
    name = "get_garment",
    title = "Show a garment",
    description = "One garment by id: what search_wardrobe says about it, and its first photo, scaled down, " +
        "for clients that can show images.",
    inputSchema = objectSchema(
        "garment_id" to stringSchema("The garment's id, from search_wardrobe or another tool."),
        "include_photo" to booleanSchema("Whether to attach the photo.", default = true),
        required = listOf("garment_id"),
    ),
    readOnly = true,
) { arguments ->
    val id = arguments.requiredText("garment_id")
    val garment = wardrobe.garmentDetail.garment(id)
        ?: return@McpTool ToolResult.error("There is no garment with id $id. search_wardrobe lists the ids there are.")

    val photo = if (arguments.flag("include_photo") != false) firstPhoto(wardrobe, garment) else null
    ToolResult.json(garment.summary(), listOfNotNull(photo))
}

private fun suggestOutfits(wardrobe: ServerWardrobe) = McpTool(
    name = "suggest_outfits",
    title = "Suggest outfits",
    description = "Outfits put together by the app's own suggestion engine from the garments in use, scored by " +
        "colour, occasion, season and what it has learned from this person's ratings. Each suggestion lists its " +
        "garments and why it was picked. Nothing is saved: use save_outfit to keep one.",
    inputSchema = objectSchema(
        "occasion" to stringSchema("The kind of day to dress for.", OCCASION_IDS),
        "season" to stringSchema("The season to dress for; by default, the season it is now.", SEASON_TAGS - Season.ALL_SEASON.tag),
        "garment_id" to stringSchema("Build every suggestion around this garment."),
        "count" to integerSchema("How many suggestions.", 1, 5, 3),
    ),
    readOnly = true,
    // A suggestion is drawn with some chance in it, so asking twice can answer twice differently.
    idempotent = false,
) { arguments ->
    val occasion = arguments.choice("occasion", OCCASION_IDS)?.let { id -> Occasion.entries.first { it.id == id } }
    val season = arguments.choice("season", SEASON_TAGS - Season.ALL_SEASON.tag)?.let { tag -> Season.entries.first { it.tag == tag } }
    val seed = arguments.text("garment_id")
    val count = arguments.integer("count", 1..5) ?: 3

    if (seed != null) {
        val garment = wardrobe.garmentDetail.garment(seed)
            ?: return@McpTool ToolResult.error("There is no garment with id $seed.")
        if (!garment.isAvailable) {
            return@McpTool ToolResult.error("That garment is archived, and suggestions are made from the garments in use.")
        }
    }

    val suggestions = wardrobe.outfitList.suggest(
        SuggestionRequest(
            filters = OutfitFilters(seasons = listOfNotNull(season), occasion = occasion),
            alreadySeen = emptyList(),
            seedGarmentId = seed,
            count = count,
        ),
    )

    ToolResult.json(
        buildJsonObject {
            putJsonArray("suggestions") {
                for (suggestion in suggestions) {
                    addJsonObject {
                        put("name", suggestion.outfit.name)
                        put("score", (suggestion.outfit.score * 100).roundToInt() / 100.0)
                        putJsonArray("reasons") { suggestion.outfit.reasons.forEach { add(it.name.lowercase()) } }
                        putJsonArray("garments") { suggestion.outfit.garments.forEach { add(it.summary()) } }
                    }
                }
            }
            if (suggestions.isEmpty()) {
                put("note", "The wardrobe has nothing that makes a whole outfit for that. Try fewer filters, or see wardrobe_gaps.")
            }
        },
    )
}

private fun listOutfits(wardrobe: ServerWardrobe) = McpTool(
    name = "list_outfits",
    title = "List saved outfits",
    description = "The outfits this person has saved, pinned ones first. Archived outfits -- ones that were rated " +
        "to teach the suggestions but never kept -- are left out unless asked for. Use get_outfit for one " +
        "outfit's garments and rating.",
    inputSchema = objectSchema(
        "include_archived" to booleanSchema("Whether to list archived outfits too.", default = false),
    ),
    readOnly = true,
) { arguments ->
    val saved = wardrobe.outfitList.saved(includeArchived = arguments.flag("include_archived") == true)
    ToolResult.json(
        buildJsonObject {
            putJsonArray("outfits") { saved.outfits.forEach { add(it.summary()) } }
            put("archived_count", saved.archivedCount)
        },
    )
}

private fun getOutfit(wardrobe: ServerWardrobe) = McpTool(
    name = "get_outfit",
    title = "Show an outfit",
    description = "One saved outfit by id, with its garments and its rating from 1 to 5, if it has one.",
    inputSchema = objectSchema(
        "outfit_id" to stringSchema("The outfit's id, from list_outfits or save_outfit."),
        required = listOf("outfit_id"),
    ),
    readOnly = true,
) { arguments ->
    val id = arguments.requiredText("outfit_id")
    val detail = wardrobe.outfitDetail.outfit(id)
        ?: return@McpTool ToolResult.error("There is no outfit with id $id. list_outfits lists the ids there are.")

    ToolResult.json(
        JsonObject(
            detail.outfit.summary() + buildJsonObject {
                put("rating", detail.rating?.let { JsonPrimitive(it) } ?: JsonNull)
                putJsonArray("garments") { detail.garments.forEach { add(it.summary()) } }
                // An outfit outlives a garment deleted from under it; say so
                // rather than leave the model to wonder at a short list.
                val missing = detail.outfit.garmentIds.size - detail.garments.size
                if (missing > 0) put("missing_garments", missing)
            },
        ),
    )
}

private fun wardrobeStatistics(wardrobe: ServerWardrobe) = McpTool(
    name = "wardrobe_statistics",
    title = "Wardrobe statistics",
    description = "What the wardrobe is made of: how many garments are in use and archived, and how they divide " +
        "by category, type, colour and brand.",
    inputSchema = objectSchema(),
    readOnly = true,
) { _ ->
    val counts = wardrobe.statistics.counts()
    ToolResult.json(
        buildJsonObject {
            put("in_use", counts.inUse)
            put("archived", counts.retired)
            putJsonArray("categories") {
                counts.categories.forEach { addJsonObject { put("category", it.key); put("count", it.count) } }
            }
            putJsonObject("types") {
                for ((category, types) in counts.subcategories) {
                    putJsonArray(category) {
                        types.forEach { addJsonObject { put("type", it.key); put("count", it.count) } }
                    }
                }
            }
            // By name, summed: two hexes that both read as "navy" are one colour to a reader.
            putJsonArray("colors") {
                counts.colors
                    .groupBy({ colorName(it.key) }, { it.count })
                    .mapValues { (_, each) -> each.sum() }
                    .entries.sortedByDescending { it.value }
                    .forEach { (name, count) -> addJsonObject { put("color", name); put("count", count) } }
            }
            putJsonArray("brands") {
                counts.brands.forEach { addJsonObject { put("brand", it.key); put("count", it.count) } }
            }
        },
    )
}

private fun wardrobeGaps(wardrobe: ServerWardrobe) = McpTool(
    name = "wardrobe_gaps",
    title = "What the wardrobe is missing",
    description = "Garments worth adding, as the app works them out: each names what to look for, the kind of " +
        "day and season it is for, how many new outfits it would make possible with what is already there, and " +
        "why -- nothing fits that slot, an archived garment was never replaced, or it would beat what the " +
        "suggestions can offer now. Best first.",
    inputSchema = objectSchema(),
    readOnly = true,
) { _ ->
    val gaps = wardrobe.statistics.gaps()
    ToolResult.json(
        buildJsonObject {
            putJsonArray("gaps") {
                for (found in gaps) {
                    val gap = found.gap
                    addJsonObject {
                        putJsonObject("want") {
                            put("category", gap.want.category)
                            gap.want.subcategory?.let { put("type", it) }
                            put("color", colorName(gap.want.colorPrimary))
                        }
                        put("occasion", gap.occasion.id)
                        put("season", gap.season.tag)
                        put("outfits_unlocked", gap.outfitsUnlocked)
                        put("reason", gap.evidence.name.lowercase())
                        found.replaces?.let { put("replaces", it.summary()) }
                    }
                }
            }
        },
    )
}

// ---- Writing --------------------------------------------------------------------

private fun saveOutfit(wardrobe: ServerWardrobe) = McpTool(
    name = "save_outfit",
    title = "Save an outfit",
    description = "Save garments as a named outfit in the person's saved outfits, as the outfit editor in the app " +
        "would. Answers the new outfit's id. It can be deleted from the app.",
    inputSchema = objectSchema(
        "name" to stringSchema("What to call the outfit."),
        "garment_ids" to buildJsonObject {
            put("type", "array")
            put("description", "The garments in it, by id, in the order to show them.")
            putJsonObject("items") { put("type", "string") }
            put("minItems", 1)
            put("maxItems", MAX_OUTFIT_GARMENTS)
            put("uniqueItems", true)
        },
        "occasion" to stringSchema("The kind of day it is for.", OCCASION_IDS),
        "season" to stringSchema("The season it is for.", SEASON_TAGS),
        required = listOf("name", "garment_ids"),
    ),
    readOnly = false,
) { arguments ->
    val name = arguments.requiredText("name")
    if (name.length > MAX_OUTFIT_NAME) throw ToolArgumentException("An outfit's name is at most $MAX_OUTFIT_NAME characters.")
    val ids = arguments.textList("garment_ids").distinct()
    if (ids.isEmpty()) throw ToolArgumentException("An outfit needs at least one garment in garment_ids.")
    if (ids.size > MAX_OUTFIT_GARMENTS) throw ToolArgumentException("An outfit holds at most $MAX_OUTFIT_GARMENTS garments.")

    val unknown = ids.filter { wardrobe.garmentDetail.garment(it) == null }
    if (unknown.isNotEmpty()) {
        return@McpTool ToolResult.error("No garment has the id ${unknown.joinToString()}. Nothing was saved.")
    }

    val occasion = arguments.choice("occasion", OCCASION_IDS)?.let { id -> Occasion.entries.first { it.id == id } }
    val season = arguments.choice("season", SEASON_TAGS)?.let { tag -> Season.entries.first { it.tag == tag } }
    wardrobe.outfitEdit.create(OutfitDraft(name = name, garmentIds = ids, occasion = occasion, season = season))

    // The editor's create answers nothing, since its screen goes back to the
    // list; an agent wants the id to talk about what it saved. The newest
    // outfit of that name and those garments is the one just made.
    val saved = wardrobe.outfitList.saved(includeArchived = false).outfits
        .filter { it.name == name && it.garmentIds == ids }
        .maxByOrNull { it.createdAt.orEmpty() }

    ToolResult.json(
        buildJsonObject {
            put("saved", true)
            saved?.let { put("outfit_id", it.id) }
        },
    )
}

private fun rateOutfit(wardrobe: ServerWardrobe) = McpTool(
    name = "rate_outfit",
    title = "Rate an outfit",
    description = "Rate a saved outfit from 1 (would not wear it) to 5 (love it), replacing any rating it had. " +
        "Ratings are how the suggestion engine learns this person's taste, so only rate what they said they think.",
    inputSchema = objectSchema(
        "outfit_id" to stringSchema("The outfit's id."),
        "rating" to integerSchema("From 1 to 5.", 1, 5, null),
        required = listOf("outfit_id", "rating"),
    ),
    readOnly = false,
    idempotent = true,
) { arguments ->
    val id = arguments.requiredText("outfit_id")
    val rating = arguments.integer("rating", 1..5) ?: throw ToolArgumentException("rating is required, from 1 to 5.")
    if (wardrobe.outfitDetail.outfit(id) == null) {
        return@McpTool ToolResult.error("There is no outfit with id $id. Nothing was rated.")
    }

    wardrobe.outfitDetail.rate(id, rating)
    ToolResult.json(buildJsonObject { put("outfit_id", id); put("rating", rating) })
}

private fun setGarmentArchived(wardrobe: ServerWardrobe) = McpTool(
    name = "set_garment_archived",
    title = "Archive or unarchive a garment",
    description = "Archive a garment the person no longer wears, or bring an archived one back. An archived garment " +
        "is kept, with its photos and history, but leaves the wardrobe list and the outfit suggestions.",
    inputSchema = objectSchema(
        "garment_id" to stringSchema("The garment's id."),
        "archived" to booleanSchema("True to archive it, false to bring it back.", default = null),
        required = listOf("garment_id", "archived"),
    ),
    readOnly = false,
    idempotent = true,
) { arguments ->
    val id = arguments.requiredText("garment_id")
    val archived = arguments.flag("archived") ?: throw ToolArgumentException("archived is required: true or false.")
    if (wardrobe.garmentDetail.garment(id) == null) {
        return@McpTool ToolResult.error("There is no garment with id $id. Nothing was changed.")
    }

    wardrobe.garmentDetail.setInUse(id, !archived)
    ToolResult.json(buildJsonObject { put("garment_id", id); put("archived", archived) })
}

/** As many garments as an outfit sensibly holds, with room for accessories: a guard on a model's mistake, not a style rule. */
private const val MAX_OUTFIT_GARMENTS = 12
private const val MAX_OUTFIT_NAME = 100

// ---- What goes out --------------------------------------------------------------

/**
 * A garment as a model should read it. Snake case, as the tools' own arguments
 * are, and without anything that is only a screen's concern.
 */
internal fun GarmentRecord.summary(): JsonObject = buildJsonObject {
    put("id", id)
    put("category", category)
    effectiveSubcategories.takeIf { it.isNotEmpty() }?.let { types -> putJsonArray("types") { types.forEach { add(it) } } }
    brand?.trim()?.takeIf { it.isNotEmpty() }?.let { put("brand", it) }
    size?.trim()?.takeIf { it.isNotEmpty() }?.let { put("size", it) }
    putJsonArray("colors") { palette.map(::colorName).distinct().forEach { add(it) } }

    val lowered = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val seasons = lowered.filter { it in SEASON_TAGS }.distinct()
    if (seasons.isNotEmpty()) putJsonArray("seasons") { seasons.forEach { add(it) } }
    putJsonArray("occasions") { toDomain().occasions().forEach { add(it.id) } }
    val otherTags = tags.filter { it.trim().lowercase() !in SEASON_TAGS && it.isNotBlank() }
    if (otherTags.isNotEmpty()) putJsonArray("tags") { otherTags.forEach { add(it) } }

    put("archived", !isAvailable)
    if (!isAvailable) unavailableDate?.let { put("archived_at", it) }
    createdAt?.let { put("added_at", it) }
    put("photos", displayImageUris.count { it.isNotEmpty() })
}

private fun OutfitRecord.summary(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    putJsonArray("garment_ids") { garmentIds.forEach { add(it) } }
    occasion?.let { put("occasion", it) }
    season?.let { put("season", it) }
    put("pinned", isPinned)
    put("archived", isArchived)
    createdAt?.let { put("created_at", it) }
}

/**
 * A stored colour by the palette's name: its own if it is one of the palette's,
 * else the nearest. A hex means nothing to a model, and a model left to name
 * one names it differently from the app.
 */
private fun colorName(hex: String): String =
    paletteColorFor(hex)?.first ?: paletteColorFor(nearestGarmentColor(hex))?.first ?: hex

/** A type as the catalogue spells it, whatever case it was asked for in; as given if the catalogue has no such type. */
private fun catalogueType(asked: String): String = TYPES.firstOrNull { it.equals(asked, ignoreCase = true) } ?: asked

/**
 * The garment's first photo, scaled so its longest side is [PHOTO_SIDE] and
 * sent as a JPEG; null when there is none, or it cannot be read.
 *
 * Scaled because a stored photo is a phone camera's, and a model gains
 * nothing from megapixels it is about to scale down itself except a request
 * the size of a few of them. Drawn on white, so a photo that is a cut-out --
 * transparent where its background was -- comes out as the garment on a page
 * rather than on black. A WebP is not something the JVM reads without a
 * plugin, and is left out rather than failing the whole answer: the garment's
 * description stands without it.
 */
private suspend fun firstPhoto(wardrobe: ServerWardrobe, garment: GarmentRecord): ToolContent.Image? {
    val reference = garment.displayImageUris.firstOrNull { it.isNotEmpty() } ?: return null
    val file = wardrobe.photos.file(toStoredImageRef(reference)) ?: return null
    return withContext(Dispatchers.IO) { scaledJpeg(file) }
}

private fun scaledJpeg(file: File): ToolContent.Image? {
    val source = try {
        ImageIO.read(file)
    } catch (_: IOException) {
        null
    } ?: return null

    val scale = min(1.0, PHOTO_SIDE.toDouble() / max(source.width, source.height))
    val width = max(1, (source.width * scale).roundToInt())
    val height = max(1, (source.height * scale).roundToInt())

    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }

    val bytes = ByteArrayOutputStream().use { out ->
        if (!ImageIO.write(scaled, "jpg", out)) return null
        out.toByteArray()
    }
    return ToolContent.Image(Base64.getEncoder().encodeToString(bytes), "image/jpeg")
}

// ---- Schemas, and reading arguments against them -----------------------------------

/**
 * An object schema. `additionalProperties` is false so a client that checks
 * arguments before sending them catches a misspelt one; the tools below do not
 * rely on that, and ignore anything they did not ask for.
 */
private fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }

private fun stringSchema(description: String, choices: Collection<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    choices?.let { putJsonArray("enum") { it.forEach { choice -> add(choice) } } }
}

private fun integerSchema(description: String, minimum: Int, maximum: Int, default: Int?): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", minimum)
    put("maximum", maximum)
    default?.let { put("default", it) }
}

private fun booleanSchema(description: String, default: Boolean?): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    default?.let { put("default", it) }
}

/*
 * Arguments are read forgivingly where the meaning is plain -- a number sent as
 * "3", a choice in the wrong case -- and refused in words where it is not,
 * since the refusal goes to the model, which can send them again.
 */

private fun JsonObject.present(name: String): JsonPrimitive? = when (val value = this[name]) {
    null, JsonNull -> null
    is JsonPrimitive -> value
    else -> throw ToolArgumentException("$name should be a single value, not ${if (value is JsonArray) "a list" else "an object"}.")
}

/** A string argument, trimmed; null when absent or blank. */
private fun JsonObject.text(name: String): String? = present(name)?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.requiredText(name: String): String =
    text(name) ?: throw ToolArgumentException("$name is required.")

/** One of [allowed], matched ignoring case and answered as [allowed] spells it. */
private fun JsonObject.choice(name: String, allowed: Collection<String>): String? {
    val given = text(name) ?: return null
    return allowed.firstOrNull { it.equals(given, ignoreCase = true) }
        ?: throw ToolArgumentException("$name must be one of: ${allowed.joinToString()}. It was \"$given\".")
}

private fun JsonObject.integer(name: String, range: IntRange): Int? {
    val given = present(name) ?: return null
    val number = given.content.trim().toDoubleOrNull() ?: given.doubleOrNull
    val whole = number?.takeIf { it == kotlin.math.floor(it) }?.toInt()
        ?: throw ToolArgumentException("$name must be a whole number from ${range.first} to ${range.last}.")
    if (whole !in range) throw ToolArgumentException("$name must be from ${range.first} to ${range.last}, not $whole.")
    return whole
}

private fun JsonObject.flag(name: String): Boolean? {
    val given = present(name) ?: return null
    return given.booleanOrNull ?: given.content.trim().lowercase().toBooleanStrictOrNull()
        ?: throw ToolArgumentException("$name must be true or false.")
}

private fun JsonObject.textList(name: String): List<String> = when (val value = this[name]) {
    null, JsonNull -> emptyList()
    is JsonArray -> value.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolArgumentException("$name must be a list of ids, as strings.")
    }
    else -> throw ToolArgumentException("$name must be a list of ids.")
}

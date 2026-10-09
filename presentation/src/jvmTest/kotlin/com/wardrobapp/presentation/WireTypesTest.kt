package com.wardrobapp.presentation

import com.wardrobapp.data.Deletion
import com.wardrobapp.data.DeletionKind
import com.wardrobapp.data.DuplicateGarment
import com.wardrobapp.data.DuplicateGarmentGroup
import com.wardrobapp.data.GapOutfit
import com.wardrobapp.data.GapWithPhotos
import com.wardrobapp.data.GarmentRecord
import com.wardrobapp.data.MaintenanceSummary
import com.wardrobapp.data.OutfitRecord
import com.wardrobapp.data.SuggestedOutfit
import com.wardrobapp.data.SyncOutfit
import com.wardrobapp.data.SyncRating
import com.wardrobapp.data.WardrobeSnapshot
import com.wardrobapp.domain.DuplicateCandidate
import com.wardrobapp.domain.GapEvidence
import com.wardrobapp.domain.Garment
import com.wardrobapp.domain.ImportFailureReason
import com.wardrobapp.domain.ImportParser
import com.wardrobapp.domain.ImportWarning
import com.wardrobapp.domain.ImportedGarmentPreview
import com.wardrobapp.domain.Occasion
import com.wardrobapp.domain.OutfitReason
import com.wardrobapp.domain.OutfitSlot
import com.wardrobapp.domain.PhantomGarment
import com.wardrobapp.domain.ScoredOutfit
import com.wardrobapp.domain.Season
import com.wardrobapp.domain.UnsafeUrlReason
import com.wardrobapp.domain.WardrobeGap
import java.io.File
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.createType
import kotlin.reflect.full.primaryConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Everything a screen's source hands over survives being sent as JSON.
 *
 * Since the Home Assistant server, a source can be on the other side of an HTTP
 * request: the browser asks, the server answers with the very types the phone's
 * sources return, serialised. So a type that does not come back the same --
 * a property computed in the class body and sent as if it were data, a value
 * lost to a default -- is a screen in the browser showing something the phone
 * never would. Each type here goes out and back, and must arrive equal.
 *
 * The samples set every property away from its default, so a property the
 * encoder skipped would arrive as its default and fail the comparison.
 *
 * And no type is left out: every class marked `@Serializable` in the common
 * code of :domain, :data and :presentation must have a sample here. A new type
 * that crosses the wire gets one, or this fails naming it.
 */
class WireTypesTest {

    private val json = Json { encodeDefaults = true }

    private val record = GarmentRecord(
        id = "g1",
        imageUri = "photos/a.jpg",
        imageUriNoBg = "photos/a-cut.png",
        imageUris = listOf("photos/a.jpg", "photos/b.jpg"),
        imageUrisNoBg = listOf("photos/a-cut.png", ""),
        category = "tops",
        subcategory = "shirt",
        subcategories = listOf("shirt", "blouse"),
        tags = listOf("season:summer", "linen"),
        brand = "Acme",
        colorPrimary = "#112233",
        colorSecondary = "#445566",
        colorPalette = listOf("#112233", "#445566"),
        size = "M",
        purchaseDate = "2024-01-02",
        isAvailable = false,
        unavailableDate = "2025-03-04T05:06:07.000Z",
        createdAt = "2023-01-01T00:00:00.000Z",
        updatedAt = "2025-03-04T05:06:07.000Z",
    )

    private val garment = Garment(
        id = "g2",
        category = "bottoms",
        subcategory = "jeans",
        subcategories = listOf("jeans"),
        tags = listOf("denim"),
        brand = "Acme",
        colorPrimary = "#000080",
        colorSecondary = "#ffffff",
        colorPalette = listOf("#000080", "#ffffff"),
        size = "32",
        isAvailable = false,
    )

    private val outfit = OutfitRecord(
        id = "o1",
        name = "Friday",
        garmentIds = listOf("g1", "g2"),
        occasion = "work",
        season = "summer",
        createdAt = "2025-01-01T00:00:00.000Z",
        isSuggested = true,
        isArchived = true,
        isPinned = true,
    )

    private val deletion = Deletion(kind = DeletionKind.OUTFIT, id = "o9", deletedAt = "2025-06-07T08:09:10.000Z")

    private val syncOutfit = SyncOutfit(
        id = "o1",
        name = "Friday",
        garmentIds = listOf("g1", "g2"),
        occasion = "work",
        season = "summer",
        createdAt = "2025-01-01T00:00:00.000Z",
        updatedAt = "2025-02-01T00:00:00.000Z",
        isSuggested = true,
        isPinned = true,
        isArchived = true,
    )

    private val syncRating = SyncRating(id = "r1", outfitId = "o1", rating = 4, feedback = "comfy", ratedAt = "2025-03-01T00:00:00.000Z")

    private val suggested = SuggestedOutfit(
        name = "Linen and denim",
        score = 0.75,
        garments = listOf(record),
        reasons = OutfitReason.entries.toList(),
    )

    private val phantom = PhantomGarment(category = "shoes", subcategory = "loafers", colorPrimary = "#8b4513")

    private val scored = ScoredOutfit(
        garments = listOf(garment),
        score = 0.5,
        name = "With loafers",
        reasons = listOf(OutfitReason.entries.last()),
    )

    private val gap = WardrobeGap(
        want = phantom,
        slot = OutfitSlot.entries.last(),
        occasion = Occasion.entries.last(),
        season = Season.entries.last(),
        outfitsUnlocked = 7,
        examples = listOf(scored),
        evidence = GapEvidence.entries.last(),
        replaces = garment,
        alternatives = listOf(phantom.copy(subcategory = null)),
    )

    private val warnings = listOf(
        ImportWarning.StructuredDataUnreadable,
        ImportWarning.ImagesCapped(listed = 12, used = 8),
        ImportWarning.ImagesBlocked(count = 2),
        ImportWarning.ImagesFailed(count = 1),
    )

    private val unsafeReasons = listOf(
        UnsafeUrlReason.UrlRequired,
        UnsafeUrlReason.NotAWebAddress,
        UnsafeUrlReason.SchemeNotAllowed,
        UnsafeUrlReason.CredentialsInUrl,
        UnsafeUrlReason.HostIsLocal("192.168.1.1"),
        UnsafeUrlReason.RedirectUnreadable,
        UnsafeUrlReason.RedirectedToLocalHost("router.local"),
    )

    private val importFailures = listOf(
        ImportFailureReason.PageTimedOut,
        ImportFailureReason.PageTooLarge,
        ImportFailureReason.PageNotLoaded(status = 503),
        ImportFailureReason.NotAWebPage,
        ImportFailureReason.NoImagesFound,
        ImportFailureReason.NoFetchableImages,
        ImportFailureReason.NoImagesDownloaded,
    )

    /** One of every type that crosses the wire, each set away from its defaults. */
    private val samples: List<Any> = buildList {
        add(record)
        add(garment)
        add(outfit)
        add(suggested)
        add(phantom)
        add(scored)
        add(gap)
        add(DuplicateGarmentGroup(listOf(record, record.copy(id = "g3"))))
        add(DuplicateGarment(record))
        add(GapOutfit(name = "Missing shoes", garments = listOf(record, null)))
        add(GapWithPhotos(gap = gap, examples = listOf(GapOutfit("x", listOf(null))), replaces = record))
        add(MaintenanceSummary(examined = 10, shrunk = 3, bytesSaved = 4096, deleted = 2))
        add(DeletionKind.entries.last())
        add(deletion)
        add(syncOutfit)
        add(syncRating)
        add(WardrobeSnapshot(garments = listOf(record), outfits = listOf(syncOutfit), ratings = listOf(syncRating), deletions = listOf(deletion)))
        add(
            DuplicateCandidate(
                category = "tops",
                subcategories = listOf("shirt"),
                colorPrimary = "#112233",
                colorPalette = listOf("#112233"),
            ),
        )
        add(
            ImportedGarmentPreview(
                sourceUrl = "https://shop.example/shirt",
                title = "A shirt",
                brand = "Acme",
                imageUrls = listOf("https://shop.example/a.jpg"),
                downloadedImageUris = listOf("photos/a.jpg"),
                warnings = warnings,
                parser = ImportParser.entries.last(),
            ),
        )
        addAll(warnings)
        addAll(unsafeReasons)
        addAll(importFailures)

        add(HomeCounts(items = 3, archived = 2, rated = 1))
        add(OutfitDetailContent(outfit = outfit, garments = listOf(record), rating = 4))
        add(OutfitDraft(name = "Friday", garmentIds = listOf("g1"), occasion = Occasion.entries.last(), season = Season.entries.last()))
        add(
            WardrobeQuery(
                search = "linen",
                sort = GarmentSort.entries.last(),
                category = "tops",
                subcategory = "shirt",
                season = Season.entries.last(),
                occasion = Occasion.entries.last(),
                brand = "Acme",
                size = "M",
                color = "#112233",
                includeRetired = true,
            ),
        )
        add(
            StatisticsCounts(
                inUse = 4,
                retired = 1,
                categories = listOf(Distribution("tops", 3)),
                colors = listOf(Distribution("#112233", 2)),
                brands = listOf(Distribution("Acme", 1)),
                subcategories = mapOf("tops" to listOf(Distribution("shirt", 2))),
                lifespans = listOf(LifespanEntry(garmentId = "g1", category = "tops", subcategories = listOf("shirt"), days = 400)),
            ),
        )
        add(Distribution("tops", 3))
        add(LifespanEntry(garmentId = "g1", category = "tops", subcategories = listOf("shirt"), days = 400))
        add(OutfitFilters(seasons = Season.entries.toList(), occasion = Occasion.entries.last()))
        add(
            SuggestionRequest(
                filters = OutfitFilters(seasons = listOf(Season.entries.last()), occasion = Occasion.entries.first()),
                alreadySeen = listOf(listOf("g1", "g2")),
                seedGarmentId = "g1",
                count = 5,
                explore = true,
            ),
        )
        add(SavedOutfits(outfits = listOf(outfit), archivedCount = 2))
        add(OutfitsScreenState.Suggestion(id = "s1", outfit = suggested, rating = 5, saved = true))
        add(BackgroundEdit(images = listOf("photos/a.jpg"), cutouts = listOf("photos/a-cut.png"), discardable = "photos/old.png"))
        add(
            BulkAddState.Draft(
                imageUri = "photos/a.jpg",
                cutoutUri = "photos/a-cut.png",
                category = "bottoms",
                subcategories = listOf("jeans"),
                seasons = listOf(Season.entries.last()),
                brand = "Acme",
                colorPalette = listOf("#000080", "#ffffff"),
            ),
        )
        add(
            GarmentFormState(
                imageUris = listOf("photos/a.jpg", "photos/b.jpg"),
                bgRemovedUris = listOf("photos/a-cut.png", ""),
                selectedImageIndex = 1,
                category = "bottoms",
                subcategories = listOf("jeans"),
                tags = listOf("denim"),
                seasons = listOf(Season.entries.last()),
                brand = "Acme",
                colorPalette = listOf("#000080"),
                colorsChosen = true,
                size = "32",
            ),
        )
        add(StorageFigures(garments = 10, retired = 2, photoBytes = 123_456_789))
    }

    @Test
    fun `every type comes back equal`() {
        for (sample in samples) {
            @Suppress("UNCHECKED_CAST")
            val serializer = serializer(sample::class.createType()) as KSerializer<Any>
            val encoded = json.encodeToString(serializer, sample)
            assertEquals(sample, json.decodeFromString(serializer, encoded), "${sample::class.simpleName} as $encoded")
        }
    }

    @Test
    fun `a type sends its constructor and nothing else`() {
        // A property declared in the class body with a value of its own is sent
        // by kotlinx.serialization too -- it has a backing field -- and on the
        // other side the receiver's copy is overwritten by the sender's. Equal
        // in a round trip, wrong the day the two sides compute it differently.
        // So the wire carries exactly what the constructor takes.
        for (sample in samples) {
            // An enum sends its name, and has no constructor anybody calls.
            if (sample is Enum<*>) continue
            val constructor = sample::class.primaryConstructor ?: continue
            val sent = serializer(sample::class.createType()).descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
            assertEquals(
                constructor.parameters.map { it.name }.toSet(),
                sent.toSet(),
                "${sample::class.qualifiedName} sends something its constructor does not take",
            )
        }
    }

    @Test
    fun `one of several kinds says which by a name of its own`() {
        // The JSON for a sealed type's subclass carries a discriminator, and
        // without @SerialName that is the class's fully qualified name: move
        // or rename the class and the server sends something an older client
        // cannot read.
        for (sample in samples.filter { s -> s::class.allSuperclasses.any { it.isSealed } }) {
            val name = serializer(sample::class.createType()).descriptor.serialName
            assertTrue('.' !in name, "${sample::class.qualifiedName} goes on the wire as $name; give it a @SerialName")
        }
    }

    @Test
    fun `every serializable type has a sample here`() {
        val sampled = samples.mapNotNull { it::class.simpleName }.toSet()
        val declared = serializableClassNames()
        assertTrue(declared.isNotEmpty(), "found no @Serializable classes; is wireSourceDirs right?")

        val missing = declared - sampled
        if (missing.isNotEmpty()) fail("No sample in WireTypesTest for: ${missing.sorted().joinToString()}")
    }

    /** Every class marked `@Serializable` in the common code of the modules that define wire types. */
    private fun serializableClassNames(): Set<String> {
        val directories = System.getProperty("wireSourceDirs")
            ?.split(File.pathSeparator)
            ?.map(::File)
            ?: error("wireSourceDirs was not set; see presentation/build.gradle.kts")
        val declaration = Regex("""@Serializable\s+(?:(?:data|sealed|enum)\s+)?(?:class|interface|object)\s+(\w+)""")

        return directories
            .flatMap { directory -> directory.walk().filter { it.extension == "kt" }.toList() }
            .flatMap { file -> declaration.findAll(file.readText()).map { it.groupValues[1] } }
            // Sealed parents have no instances of their own; their subclasses are
            // sampled, and travel as them.
            .filterNot { it in setOf("ImportWarning", "UnsafeUrlReason", "ImportFailureReason") }
            .toSet()
    }
}

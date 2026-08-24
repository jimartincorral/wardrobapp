package com.wardrobapp.domain

/**
 * The categories and sizes the form offers.
 *
 * A transcription of src/constants/categories.ts. Here rather than in
 * :presentation because these strings are the vocabulary the rest of the domain
 * keys on: a subcategory is stored verbatim and looked up by name when a
 * garment's occasions are derived, so a typo would not fail -- it would silently
 * give the garment its category's fallback occasions instead of its type's. A
 * `GarmentCatalogueTest` holds the table and the translation keys to each other,
 * so a type added here without a key fails rather than showing untranslated.
 */
data class GarmentCategory(
    val id: String,
    /**
     * The English label, and only a fallback: :app resolves every category and
     * type to a string resource through `Vocabulary.kt`, because this module has
     * no resources and no business holding words in one language.
     */
    val label: String,
    val subcategories: List<String>,
)

val GARMENT_CATEGORIES: List<GarmentCategory> = listOf(
    GarmentCategory(
        id = "tops",
        label = "Tops",
        subcategories = listOf("T-Shirt", "Blouse", "Shirt", "Tank Top", "Sweater", "Hoodie", "Crop Top", "Polo"),
    ),
    GarmentCategory(
        id = "bottoms",
        label = "Bottoms",
        subcategories = listOf("Jeans", "Pants", "Shorts", "Skirt", "Leggings", "Sweatpants", "Chinos"),
    ),
    GarmentCategory(
        id = "dresses",
        label = "Dresses",
        subcategories = listOf("Mini", "Midi", "Maxi", "Cocktail", "Sundress", "Jumpsuit", "Romper"),
    ),
    GarmentCategory(
        id = "midlayer",
        label = "Mid-Layer",
        subcategories = listOf("Blazer", "Overshirt", "Vest", "Poncho", "Cape"),
    ),
    GarmentCategory(
        id = "outerwear",
        label = "Outerwear",
        subcategories = listOf("Jacket", "Coat", "Cardigan", "Windbreaker", "Parka"),
    ),
    GarmentCategory(
        id = "shoes",
        label = "Shoes",
        subcategories = listOf("Sneakers", "Boots", "Sandals", "Heels", "Flats", "Loafers", "Athletic"),
    ),
    GarmentCategory(
        id = "accessories",
        label = "Accessories",
        subcategories = listOf("Hat", "Scarf", "Foulard", "Belt", "Bag", "Wallet", "Gloves", "Jewelry", "Watch", "Sunglasses", "Tie"),
    ),
    GarmentCategory(
        id = "activewear",
        label = "Activewear",
        subcategories = listOf("Sports Bra", "Workout Top", "Workout Shorts", "Yoga Pants", "Track Suit"),
    ),
    GarmentCategory(
        id = "underwear",
        label = "Underwear",
        subcategories = listOf("Bra", "Briefs", "Boxers", "Bodysuit", "Shapewear", "Socks", "Tights", "Thermal"),
    ),
    GarmentCategory(
        id = "loungewear",
        label = "Loungewear/Pajamas",
        subcategories = listOf("Pajama Set", "Pajama Top", "Pajama Bottoms", "Nightgown", "Robe", "Lounge Set"),
    ),
)

/** The category a new garment starts as, matching the form's default. */
const val DEFAULT_CATEGORY = "tops"

fun garmentCategory(id: String): GarmentCategory? = GARMENT_CATEGORIES.find { it.id == id }

/**
 * Sizes offered as chips.
 *
 * Every scale at once -- letters, numbers, waists, shoe sizes -- because a
 * wardrobe holds all of them and which one applies depends on the garment. The
 * form shows the first twelve and takes anything typed.
 */
val COMMON_SIZES: List<String> = listOf(
    "XXS",
    "XS",
    "S",
    "M",
    "L",
    "XL",
    "XXL",
    "XXXL",
    "0",
    "2",
    "4",
    "6",
    "8",
    "10",
    "12",
    "14",
    "16",
    "28",
    "29",
    "30",
    "31",
    "32",
    "33",
    "34",
    "36",
    "38",
    "40",
    "5",
    "5.5",
    "6",
    "6.5",
    "7",
    "7.5",
    "8",
    "8.5",
    "9",
    "9.5",
    "10",
    "10.5",
    "11",
    "12",
    "13",
    "One Size",
)

/** How many size chips the form has room for. */
const val SIZE_CHIPS = 12

/**
 * The translation key each garment type is looked up under.
 *
 * Keyed on the label, because the label is what a garment row stores -- and the
 * key cannot be worked out from it: "T-Shirt" is `tshirt` but "Tank Top" is
 * `tank_top`, so the hyphen disappears where the space does not. A transcription
 * of `SUBCATEGORY_KEY_MAP` in src/constants/categories.ts, compared against it by
 * `garment-catalogue.jsonl` rather than trusted.
 *
 * Here rather than in :app because it is the same kind of thing as the labels
 * above -- vocabulary the rest of the app keys on -- and because a test in a
 * module that builds without the Android SDK has to be able to see it.
 */
val SUBCATEGORY_KEYS: Map<String, String> = mapOf(
    "T-Shirt" to "tshirt",
    "Blouse" to "blouse",
    "Shirt" to "shirt",
    "Tank Top" to "tank_top",
    "Sweater" to "sweater",
    "Hoodie" to "hoodie",
    "Crop Top" to "crop_top",
    "Polo" to "polo",
    "Jeans" to "jeans",
    "Pants" to "pants",
    "Shorts" to "shorts",
    "Skirt" to "skirt",
    "Leggings" to "leggings",
    "Sweatpants" to "sweatpants",
    "Chinos" to "chinos",
    "Mini" to "mini",
    "Midi" to "midi",
    "Maxi" to "maxi",
    "Cocktail" to "cocktail",
    "Sundress" to "sundress",
    "Jumpsuit" to "jumpsuit",
    "Romper" to "romper",
    "Blazer" to "blazer",
    "Overshirt" to "overshirt",
    "Vest" to "vest",
    "Poncho" to "poncho",
    "Cape" to "cape",
    "Jacket" to "jacket",
    "Coat" to "coat",
    "Cardigan" to "cardigan",
    "Windbreaker" to "windbreaker",
    "Parka" to "parka",
    "Sneakers" to "sneakers",
    "Boots" to "boots",
    "Sandals" to "sandals",
    "Heels" to "heels",
    "Flats" to "flats",
    "Loafers" to "loafers",
    "Athletic" to "athletic",
    "Hat" to "hat",
    "Scarf" to "scarf",
    "Foulard" to "foulard",
    "Belt" to "belt",
    "Bag" to "bag",
    "Wallet" to "wallet",
    "Gloves" to "gloves",
    "Jewelry" to "jewelry",
    "Watch" to "watch",
    "Sunglasses" to "sunglasses",
    "Tie" to "tie",
    "Sports Bra" to "sports_bra",
    "Workout Top" to "workout_top",
    "Workout Shorts" to "workout_shorts",
    "Yoga Pants" to "yoga_pants",
    "Track Suit" to "track_suit",
    "Bra" to "bra",
    "Briefs" to "briefs",
    "Boxers" to "boxers",
    "Bodysuit" to "bodysuit",
    "Shapewear" to "shapewear",
    "Socks" to "socks",
    "Tights" to "tights",
    "Thermal" to "thermal",
    "Pajama Set" to "pajama_set",
    "Pajama Top" to "pajama_top",
    "Pajama Bottoms" to "pajama_bottoms",
    "Nightgown" to "nightgown",
    "Robe" to "robe",
    "Lounge Set" to "lounge_set",
)

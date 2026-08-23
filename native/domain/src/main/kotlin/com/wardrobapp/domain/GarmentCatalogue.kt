package com.wardrobapp.domain

/**
 * The categories and sizes the form offers.
 *
 * A transcription of src/constants/categories.ts. Here rather than in
 * :presentation because these strings are the vocabulary the rest of the domain
 * keys on: a subcategory is stored verbatim and looked up by name when a
 * garment's occasions are derived, so a typo would not fail -- it would silently
 * give the garment its category's fallback occasions instead of its type's. A
 * parity fixture compares both lists rather than trusting the transcription.
 */
data class GarmentCategory(
    val id: String,
    /** The English label. The React Native app localizes these; the port cannot yet. */
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

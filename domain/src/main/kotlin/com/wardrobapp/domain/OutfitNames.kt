package com.wardrobapp.domain

/**
 * What an outfit is called when nobody has typed a name.
 *
 * Two places need this and have to agree: the suggestion engine, naming what it
 * came up with, and the outfit builder, naming what somebody put together and
 * left untitled. "Shirt + Jeans + Sneakers" from one and "shirt, jeans, sneakers"
 * from the other would read as two kinds of outfit in one list.
 */

/** What joins the parts of a derived name. */
const val OUTFIT_NAME_SEPARATOR = " + "

/**
 * How a garment is named inside an outfit's name.
 *
 * Its type where it has one, since "Parka" says more than "outerwear", and the
 * category otherwise -- a garment catalogued in a hurry may have no type at all.
 */
fun garmentLabelFor(category: String, subcategory: String?): String =
    subcategory?.takeIf { it.isNotBlank() } ?: category

/** Join garment labels into an outfit's name. */
fun outfitNameFrom(labels: List<String>): String =
    labels.joinToString(OUTFIT_NAME_SEPARATOR)

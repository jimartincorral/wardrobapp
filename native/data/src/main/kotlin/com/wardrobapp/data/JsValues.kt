package com.wardrobapp.data

/**
 * JavaScript coercions the stored data depends on.
 *
 * The rows being read were written by a TypeScript app, and the mapping it
 * applied on the way out relied on JS semantics in two places that matter. This
 * is a port, so those semantics are reproduced deliberately rather than
 * approximated with Kotlin's -- getting them subtly wrong is how an existing
 * wardrobe starts rendering differently under the native app.
 */

/**
 * JavaScript truthiness.
 *
 * `is_available` is read with `Boolean(row.is_available)`. SQLite hands back an
 * integer, so 0/1 is the normal case -- but the string "0" is *truthy* in JS,
 * and a column that has been through a restore or an older write path can hold
 * one. Kotlin's own null/boolean checks would disagree.
 */
internal fun jsTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> {
        val d = value.toDouble()
        !(d == 0.0 || d.isNaN())
    }
    is String -> value.isNotEmpty()
    else -> true
}

/**
 * JavaScript `String(value)` for the shapes a SQLite row can hold.
 *
 * Notably `String(null)` is the text "null", not an empty string -- so a row
 * whose column really holds the JSON null would stringify to "null" on the
 * TypeScript side too.
 */
internal fun jsString(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "true" else "false"
    is Double -> if (value == value.toLong().toDouble() && value.isFinite()) {
        value.toLong().toString()
    } else {
        value.toString()
    }
    is Float -> jsString(value.toDouble())
    else -> value.toString()
}

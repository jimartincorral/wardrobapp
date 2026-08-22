package com.wardrobapp.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.fail

/**
 * Support for the parity fixtures.
 *
 * These modules are a *port*, so the question worth asking is not whether they
 * pass tests written for them but whether they agree with the TypeScript they
 * were ported from. scripts/dump-domain-parity.ts records the TypeScript answers
 * for a fixed corpus; the tests here replay it against the Kotlin.
 *
 * Regenerate with `npm run parity:dump` after changing either side.
 */
internal object Parity {
    /**
     * Doubles are compared to this tolerance rather than exactly. Both sides do
     * IEEE-754 arithmetic in the same order, but library primitives (cbrt, hypot,
     * exp) are not required to agree to the last bit, so demanding exact equality
     * would be testing the JDK against V8 rather than testing the port. Far
     * tighter than any difference that could change a decision.
     */
    const val TOLERANCE = 1e-9

    fun load(name: String): List<JsonObject> {
        val stream = Parity::class.java.getResourceAsStream("/parity/$name")
            ?: fail(
                "Missing parity fixture '$name'. Generate it with: npm run parity:dump"
            )

        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) fail("Parity fixture '$name' is empty")

        return lines.map { Json.parseToJsonElement(it).jsonObject }
    }

    /** A JSON value that may be null, as a Double?. */
    fun JsonObject.optionalDouble(key: String): Double? {
        val value = this[key] ?: fail("Fixture line has no '$key': $this")
        return if (value is JsonNull) null else value.jsonPrimitive.content.toDouble()
    }

    fun JsonObject.double(key: String): Double =
        optionalDouble(key) ?: fail("Expected '$key' to be a number, got null: $this")

    fun JsonObject.string(key: String): String {
        val value = this[key] ?: fail("Fixture line has no '$key': $this")
        return value.jsonPrimitive.content
    }

    fun JsonObject.stringOrNull(key: String): String? {
        val value = this[key] ?: fail("Fixture line has no '$key': $this")
        return if (value is JsonNull) null else value.jsonPrimitive.content
    }

    fun JsonObject.strings(key: String): List<String> {
        val value = this[key] ?: fail("Fixture line has no '$key': $this")
        return (value as JsonArray).map { it.jsonPrimitive.content }
    }

    fun JsonObject.objects(key: String): List<JsonObject> {
        val value = this[key] ?: fail("Fixture line has no '$key': $this")
        return value.jsonArray.map { it.jsonObject }
    }

    /**
     * Compare two nullable doubles, distinguishing "both absent" from "both
     * zero" -- the distinction the whole abstention design turns on.
     */
    fun sameNumber(expected: Double?, actual: Double?): Boolean = when {
        expected == null || actual == null -> expected == null && actual == null
        else -> kotlin.math.abs(expected - actual) <= TOLERANCE
    }
}

/** The i18n keys the TypeScript emits, so reasons can be compared across sides. */
internal fun DuplicateReason.tsKey(): String = when (this) {
    DuplicateReason.SIMILAR_TAGS -> "duplicateReasons.similarTags"
    DuplicateReason.SIMILAR_COLOR -> "duplicateReasons.similarColor"
    DuplicateReason.SAME_SIZE -> "duplicateReasons.sameSize"
    DuplicateReason.OVERALL_SIMILARITY -> "duplicateReasons.overallSimilarity"
}

internal fun ColorRelationship.tsKey(): String = when (this) {
    ColorRelationship.UNKNOWN -> "unknown"
    ColorRelationship.SAME -> "same"
    ColorRelationship.NEUTRAL -> "neutral"
    ColorRelationship.ANALOGOUS -> "analogous"
    ColorRelationship.NEAR_MISS -> "near-miss"
    ColorRelationship.CONTRASTING -> "contrasting"
}

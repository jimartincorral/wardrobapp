package com.wardrobapp.presentation

/**
 * The browser's collator for the page's locale.
 *
 * One `Intl.Collator`, made once and reused: constructing one per comparison is
 * the slow way to sort, and `localeCompare`, which does exactly that, is what
 * the documentation warns against for lists.
 */
private val collator: JsAny = js("new Intl.Collator()")

private fun compareWith(collator: JsAny, a: String, b: String): Int = js("collator.compare(a, b)")

internal actual fun readerOrder(): Comparator<String> = Comparator { a, b -> compareWith(collator, a, b) }

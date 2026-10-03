package com.wardrobapp.presentation

/**
 * Text in the order a reader expects: accents beside their letters, not after Z.
 *
 * What brands and sizes sort by, in the wardrobe's filters and in the brand
 * chart. Comparing raw characters puts "Émile" after "Zara", which is nobody's
 * alphabet. Every platform already knows how its user's language sorts, so each
 * supplies its own: `java.text.Collator` on the JVM, `Intl.Collator` in the
 * browser. Neither is reimplemented here, because a collation table is exactly
 * the kind of thing that is right on the platform and subtly wrong in a copy.
 *
 * In the reader's own locale on both, as `Collator.getInstance()` always was.
 */
internal expect fun readerOrder(): Comparator<String>

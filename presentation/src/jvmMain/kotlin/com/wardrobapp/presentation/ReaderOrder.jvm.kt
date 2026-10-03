package com.wardrobapp.presentation

import java.text.Collator

/** The JVM's collator for the default locale, as the sort always used. */
internal actual fun readerOrder(): Comparator<String> {
    val collator = Collator.getInstance()
    return Comparator { a, b -> collator.compare(a, b) }
}

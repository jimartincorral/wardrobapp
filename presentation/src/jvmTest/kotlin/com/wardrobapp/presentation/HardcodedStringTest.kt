package com.wardrobapp.presentation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No user-facing text left in Kotlin.
 *
 * The point of this one is completeness rather than correctness: extracting 135
 * strings across ten screens is exactly the job where one gets missed, and a
 * missed string is invisible in English -- it reads perfectly until someone
 * switches to Spanish and one label stays put.
 *
 * Here for the same reason as [StringResourceParityTest]: :app and :ui cannot be
 * compiled on a machine with no Android SDK, so a check that reads their sources
 * is the only one available before CI. It looks at the call sites where text reaches a person
 * -- `Text(...)`, `contentDescription`, and the message fields a ViewModel puts on
 * its state -- and not at every literal, because plenty of literals are route
 * names, SQL, or animation labels.
 *
 * [filesStillToConvert] is the work not yet done, and shrinks to nothing. A file
 * listed there is exempt; a file not listed has to be clean. That way the test
 * says which screens are finished instead of failing until every one of them is.
 */
class HardcodedStringTest {

    /**
     * Screens whose text has not been moved to resources yet.
     *
     * Every entry is a promise, not a decision. Empty is the finished state.
     */
    private val filesStillToConvert = emptySet<String>()

    @Test
    fun `converted screens have no user-facing literal left`() {
        val offenders = sourceFiles()
            .filter { it.name !in filesStillToConvert }
            .flatMap { file -> userFacingLiterals(file.readText()).map { "${file.name}: \"$it\"" } }

        assertTrue(
            offenders.isEmpty(),
            "text that would not translate:\n  " + offenders.joinToString("\n  "),
        )
    }

    @Test
    fun `the list of unconverted screens is honest`() {
        // A file that has been converted but left on the list would exempt itself
        // from the check above for good, which is how this kind of allowlist stops
        // meaning anything.
        val names = sourceFiles().map { it.name }.toSet()
        assertEquals(emptySet(), filesStillToConvert - names, "no such file")

        val alreadyClean = sourceFiles()
            .filter { it.name in filesStillToConvert }
            .filter { userFacingLiterals(it.readText()).isEmpty() }
            .map { it.name }

        assertEquals(emptyList(), alreadyClean, "these are done -- take them off the list")
    }

    @Test
    fun `the detector finds the shapes it claims to`() {
        // Otherwise "no offenders" could mean the patterns match nothing at all,
        // which is the same result as success and reads identically.
        val found = userFacingLiterals(
            """
            Text("plain")
            Text(
                "wrapped onto its own line",
                style = x,
            )
            Icon(x, contentDescription = "described")
            state.copy(error = "a message")
            it.copy(error = e.message ?: "a fallback message")
            throw IOException("thrown to be read")
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "plain",
                "wrapped onto its own line",
                "described",
                "a message",
                "a fallback message",
                "thrown to be read",
            ),
            found.sortedBy { found.indexOf(it) },
        )
    }

    @Test
    fun `the detector ignores what is not shown to anyone`() {
        val found = userFacingLiterals(
            """
            // Text("in a comment")
            /* Text("in a block comment") */
            navigate("garment/${'$'}id")
            Text(stringResource(R.string.already_done))
            Icon(x, contentDescription = null)
            driver.query("SELECT 1")
            animateFloatAsState(targetValue = f, label = "bar")
            Text("${'$'}value")
            Text("${'$'}{count} ")
            Text("\u203a")
            /* nested /* comments */ Text("still inside the outer one") */
            """.trimIndent()
        )

        assertEquals(emptyList(), found)
    }

    @Test
    fun `a string that looks like a comment hides nothing`() {
        // The shapes that fooled the regexes this used to strip comments with:
        // a MIME glob opening a "block comment" that a later string closes, a
        // URL's `//` read as a line comment, and a quote character read as the
        // start of a string.
        val found = userFacingLiterals(
            """
            launcher.launch(arrayOf("image/*"))
            Text("after the glob")
            val close = "*/"
            Text("see https://example.com for more")
            val quote = '"'
            Text("after a quote character")
            """.trimIndent()
        )

        assertEquals(
            listOf("after the glob", "see https://example.com for more", "after a quote character"),
            found,
        )
    }

    /**
     * :app's screens and :ui's, which is where they are moving.
     *
     * Both, and each required to hold something: a screen that moved would
     * otherwise leave this check's sight without anything failing, and an empty
     * directory -- a path that stopped matching -- would make every file clean.
     */
    private fun sourceFiles(): List<File> =
        listOf("appSourceDir", "uiSourceDir").flatMap { property ->
            val dir = System.getProperty(property)
                ?: error("$property was not set; see presentation/build.gradle.kts")
            val files = File(dir).listFiles { f: File -> f.name.endsWith(".kt") }?.sorted()

            assertTrue(!files.isNullOrEmpty(), "no Kotlin sources found under $dir")
            files!!.toList()
        }

    private fun userFacingLiterals(source: String): List<String> {
        val code = withoutComments(source)

        val patterns = listOf(
            // Text("..."), including the form where the text is on its own line.
            Regex("""\bText\(\s*"((?:[^"\\]|\\.)*)"""),
            Regex("""contentDescription\s*=\s*"((?:[^"\\]|\\.)*)""""),
            // What a ViewModel hands to a screen to show.
            Regex("""\b(?:error|actionError|message)\s*=\s*(?:[\w.]+\s*\?:\s*)?"((?:[^"\\]|\\.)*)""""),
            // Thrown text. :app's own photo and background classes throw
            // sentences written to be read, and the ViewModels show them through
            // `e.message ?: fallback` -- so a screen can be free of literals while
            // still putting English in front of a Spanish reader. Leaving these
            // out made the check call two files clean that were not.
            Regex("""(?:IOException|IllegalStateException|IllegalArgumentException|Exception)\(\s*"((?:[^"\\]|\\.)*)""""),
            Regex("""\berror\(\s*"((?:[^"\\]|\\.)*)""""),
        )

        return patterns.flatMap { pattern ->
            pattern.findAll(code).map { it.groupValues[1] }
        }.filter { hasWords(it) }
    }

    /**
     * [source] with its comments taken out and everything else kept as written.
     *
     * One pass over the characters, because regexes applied in turn cannot tell a
     * comment from a string that contains one. The two this replaced read the
     * slash-star inside MainActivity's MIME type for images as the start of a
     * block comment and hid everything up to the next star-slash -- fifty lines,
     * with Text calls in them -- from the check, which reported them clean.
     * Strings are kept, templates and all, since they are what the patterns look
     * for; comments nest, as they do in Kotlin. (Which is also why this comment
     * spells the delimiters out: written as symbols they would open a nested
     * comment inside it.)
     */
    private fun withoutComments(source: String): String = CommentStripper(source).run()

    private class CommentStripper(private val source: String) {
        private val out = StringBuilder()
        private var i = 0

        fun run(): String {
            code(insideTemplate = false)
            return out.toString()
        }

        /** Code, up to the end or -- inside a `${...}` -- the brace that closes it. */
        private fun code(insideTemplate: Boolean) {
            var depth = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    source.startsWith("//", i) ->
                        while (i < source.length && source[i] != '\n') i++
                    source.startsWith("/*", i) -> blockComment()
                    c == '"' -> string()
                    c == '\'' -> charLiteral()
                    insideTemplate && c == '}' && depth == 0 -> return
                    else -> {
                        if (c == '{') depth++
                        if (c == '}') depth--
                        out.append(c)
                        i++
                    }
                }
            }
        }

        private fun blockComment() {
            var depth = 0
            do {
                when {
                    source.startsWith("/*", i) -> { depth++; i += 2 }
                    source.startsWith("*/", i) -> { depth--; i += 2 }
                    else -> i++
                }
            } while (depth > 0 && i < source.length)
            out.append(' ')
        }

        private fun string() {
            val raw = source.startsWith("\"\"\"", i)
            val quote = if (raw) "\"\"\"" else "\""
            out.append(quote)
            i += quote.length
            while (i < source.length) {
                when {
                    raw && source.startsWith("\"\"\"", i) -> {
                        // A raw string may end in more quotes than three; they are its own.
                        while (i < source.length && source[i] == '"') out.append(source[i++])
                        return
                    }
                    !raw && source[i] == '\\' && i + 1 < source.length -> {
                        out.append(source, i, i + 2)
                        i += 2
                    }
                    !raw && source[i] == '"' -> {
                        out.append(source[i++])
                        return
                    }
                    source.startsWith("\${", i) -> {
                        out.append("\${")
                        i += 2
                        code(insideTemplate = true)
                        if (i < source.length) out.append(source[i++])
                    }
                    else -> out.append(source[i++])
                }
            }
        }

        /** `'"'` is a character, not the start of a string. */
        private fun charLiteral() {
            val end = source.indexOf('\'', if (source.startsWith("\\", i + 1)) i + 3 else i + 2)
            val stop = if (end < 0) source.length else end + 1
            out.append(source, i, stop)
            i = stop
        }
    }

    /**
     * Whether a literal is text a translator would have anything to do with.
     *
     * Interpolations and glyphs are not: `"$count"` is a number wherever you read
     * it, and a chevron is a shape. What is left after taking those out has to
     * contain at least two letters, which is the shortest thing here that is a
     * word ("Ok" would qualify; "%" and "\u203a" do not).
     */
    private fun hasWords(literal: String): Boolean {
        val withoutValues = literal
            .replace(Regex("""\$\{[^}]*\}"""), "")
            .replace(Regex("""\$\w+"""), "")
            .replace(Regex("""%[\d$]*[sdf]"""), "")
            // A \uXXXX escape is six characters in the source, two of which are
            // letters -- so counting letters without taking these out first reads
            // a chevron as a word.
            .replace(Regex("""\\u[0-9a-fA-F]{4}"""), "")

        return withoutValues.count { it.isLetter() } >= 2
    }
}

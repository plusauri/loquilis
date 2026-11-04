package loquilis.store

/** Turns what a person types into an FTS5 query, and makes the snippet shown for a hit. */
internal object SearchQuery {
    private val PART = Regex(""""([^"]+)"|(\S+)""")
    private const val BEFORE = 60
    private const val AFTER = 140

    /** The words and quoted phrases of [query]. */
    fun parts(query: String): List<String> =
        PART.findAll(query).map { (it.groupValues[1] + it.groupValues[2]).replace("\"", "") }.filter { it.isNotBlank() }.toList()

    /** Quotes every part, so what a person types can never be read as FTS5 syntax. */
    fun match(parts: List<String>): String = parts.joinToString(" ") { "\"$it\"" }

    /** The text around the first match, with matches marked. Tokens not found literally fall back to the start. */
    fun snippet(text: String, parts: List<String>): String {
        val words = parts.joinToString("|") { part ->
            part.split(Regex("""\W+""")).filter { it.isNotEmpty() }.joinToString("""\W+""") { Regex.escape(it) }
        }
        val pattern = runCatching { Regex("""(?<!\w)(?:$words)(?!\w)""", RegexOption.IGNORE_CASE) }.getOrNull()
        val first = pattern?.find(text)?.range?.first ?: 0
        val start = (first - BEFORE).coerceAtLeast(0)
        val end = (first + AFTER).coerceAtMost(text.length)
        val window = text.substring(start, end).replace('\n', ' ')
        val marked = pattern?.replace(window) { "${Store.MATCH_OPEN}${it.value}${Store.MATCH_CLOSE}" } ?: window
        return (if (start > 0) "…" else "") + marked + (if (end < text.length) "…" else "")
    }
}

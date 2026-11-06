package loquilis.views

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.entities.EntityConverter
import org.intellij.markdown.parser.MarkdownParser

/** Past this a reply is shown as plain text, since it was never written to be read as a page. */
private const val LARGEST_MARKDOWN = 256 * 1024

/** A stretch of text with one look. */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

/** One block of a reply. Free of Compose, like the rest of the projections, so tests can pin it. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val runs: List<InlineRun>) : MarkdownBlock
    data class Paragraph(val runs: List<InlineRun>) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** A list. [marker] is the bullet or number of each item, in order. */
    data class Items(val markers: List<String>, val items: List<List<MarkdownBlock>>) : MarkdownBlock
    data class Table(val header: List<List<InlineRun>>, val rows: List<List<List<InlineRun>>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/**
 * Reads what an agent wrote as GitHub-flavored Markdown. Agents write Markdown in their replies without
 * being asked, so this is the normal case. Nothing is dropped: a block with no special look stays as
 * the text it was.
 */
fun markdownBlocks(text: String): List<MarkdownBlock> {
    if (text.length > LARGEST_MARKDOWN) return listOf(MarkdownBlock.Paragraph(listOf(InlineRun(text))))
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    return Reader(text).blocks(root)
}

private class Reader(private val source: String) {
    fun blocks(parent: ASTNode): List<MarkdownBlock> = parent.children.mapNotNull(::block)

    private fun block(node: ASTNode): MarkdownBlock? = when (val type = node.type) {
        MarkdownElementTypes.PARAGRAPH -> MarkdownBlock.Paragraph(inline(node))
        MarkdownElementTypes.CODE_FENCE -> fence(node)
        MarkdownElementTypes.CODE_BLOCK -> MarkdownBlock.Code("", codeLines(node))
        MarkdownElementTypes.BLOCK_QUOTE -> MarkdownBlock.Quote(blocks(node))
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> list(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownBlock.Rule
        GFMElementTypes.TABLE -> table(node)
        in IGNORED_BETWEEN_BLOCKS -> null
        else -> headingLevel(type)?.let { MarkdownBlock.Heading(it, headingRuns(node)) } ?: plain(node)
    }

    private fun plain(node: ASTNode): MarkdownBlock? =
        node.getTextInNode(source).toString().takeIf { it.isNotBlank() }?.let { MarkdownBlock.Paragraph(listOf(InlineRun(it.trim()))) }

    private fun headingLevel(type: IElementType): Int? = when (type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
        MarkdownElementTypes.ATX_3 -> 3
        MarkdownElementTypes.ATX_4 -> 4
        MarkdownElementTypes.ATX_5 -> 5
        MarkdownElementTypes.ATX_6 -> 6
        else -> null
    }

    private fun headingRuns(node: ASTNode): List<InlineRun> {
        val content = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT }
        return trimmed(tidy(walk(content?.children.orEmpty(), InlineRun(""))))
    }

    private fun fence(node: ASTNode): MarkdownBlock {
        val language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(source)?.toString().orEmpty()
        val parts = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
        val body = parts.dropWhile { it.type == MarkdownTokenTypes.EOL }.dropLastWhile { it.type == MarkdownTokenTypes.EOL }
        return MarkdownBlock.Code(language.trim(), body.joinToString("") { it.getTextInNode(source) })
    }

    private fun codeLines(node: ASTNode): String =
        node.getTextInNode(source).lines().joinToString("\n") { it.removePrefix("    ") }.trimEnd()

    private fun list(node: ASTNode): MarkdownBlock {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val markers = items.map { item ->
            val marker = item.children.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
            marker?.getTextInNode(source)?.toString()?.trim() ?: "•"
        }
        return MarkdownBlock.Items(markers, items.map(::listItem))
    }

    private fun listItem(item: ASTNode): List<MarkdownBlock> {
        val box = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }?.getTextInNode(source)?.toString()
        val blocks = blocks(item)
        val first = blocks.firstOrNull() as? MarkdownBlock.Paragraph
        if (box == null || first == null) return blocks
        val mark = if ('x' in box.lowercase()) "☑ " else "☐ "
        return listOf(MarkdownBlock.Paragraph(listOf(InlineRun(mark)) + first.runs)) + blocks.drop(1)
    }

    private fun table(node: ASTNode): MarkdownBlock {
        val header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }
        return MarkdownBlock.Table(header?.let(::cells).orEmpty(), rows.map(::cells))
    }

    private fun cells(row: ASTNode): List<List<InlineRun>> =
        row.children.filter { it.type == GFMTokenTypes.CELL }.map { trimmed(tidy(walk(it.children, InlineRun("")))) }

    private fun inline(node: ASTNode): List<InlineRun> = tidy(walk(node.children, InlineRun("")))

    private fun walk(nodes: List<ASTNode>, look: InlineRun): List<InlineRun> = nodes.flatMap { node ->
        when (node.type) {
            MarkdownElementTypes.STRONG -> walk(node.children, look.copy(bold = true))
            MarkdownElementTypes.EMPH -> walk(node.children, look.copy(italic = true))
            GFMElementTypes.STRIKETHROUGH -> walk(node.children, look.copy(strike = true))
            MarkdownElementTypes.CODE_SPAN -> listOf(look.copy(text = codeSpan(node), code = true))
            MarkdownElementTypes.INLINE_LINK -> link(node, look)
            MarkdownElementTypes.AUTOLINK -> autolink(node.getTextInNode(source).toString().trim('<', '>'), look)
            GFMTokenTypes.GFM_AUTOLINK -> autolink(node.getTextInNode(source).toString(), look)
            MarkdownElementTypes.IMAGE -> walk(node.children.filter { it.type == MarkdownElementTypes.INLINE_LINK }, look)
            MarkdownTokenTypes.EMPH, GFMTokenTypes.TILDE, MarkdownTokenTypes.BLOCK_QUOTE -> emptyList()
            MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> listOf(look.copy(text = "\n"))
            else ->
                if (node.children.isEmpty()) listOf(look.copy(text = node.getTextInNode(source).toString()))
                else walk(node.children, look)
        }
    }

    private fun codeSpan(node: ASTNode): String {
        val text = node.getTextInNode(source).toString()
        val ticks = text.takeWhile { it == '`' }.length
        return text.drop(ticks).dropLast(ticks).let { if (it.length > 2 && it.startsWith(' ') && it.endsWith(' ')) it.drop(1).dropLast(1) else it }
    }

    private fun link(node: ASTNode, look: InlineRun): List<InlineRun> {
        val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.getTextInNode(source)?.toString()
        val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }?.children.orEmpty()
        return walk(label.drop(1).dropLast(1), look.copy(link = destination?.trim('<', '>')?.takeIf(::opens)))
    }

    private fun autolink(url: String, look: InlineRun) = listOf(look.copy(text = url, link = url.takeIf(::opens)))

    /** The agent writes these, so only what a browser or mail app would open becomes clickable. */
    private fun opens(url: String): Boolean = OPENABLE.any { url.startsWith(it, ignoreCase = true) }

    /** Joins neighbors that look alike, and turns backslash escapes and entities into what they mean. */
    private fun tidy(runs: List<InlineRun>): List<InlineRun> {
        val joined = ArrayList<InlineRun>()
        for (run in runs) {
            val last = joined.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) joined[joined.size - 1] = last.copy(text = last.text + run.text)
            else joined += run
        }
        return joined
            .map { if (it.code) it else it.copy(text = unescape(it.text)) }
            .map { it.copy(text = it.text.replace(CONTINUATION_INDENT, "\n")) }
            .filter { it.text.isNotEmpty() }
    }

    /** The space a heading or a cell has around it, which is part of its syntax and not its text. */
    private fun trimmed(runs: List<InlineRun>): List<InlineRun> {
        val start = runs.toMutableList()
        start.firstOrNull()?.let { start[0] = it.copy(text = it.text.trimStart()) }
        start.lastOrNull()?.let { start[start.lastIndex] = it.copy(text = it.text.trimEnd()) }
        return start.filter { it.text.isNotEmpty() }
    }

    /** The converter writes for HTML, so what it escapes for HTML has to come back. */
    private fun unescape(text: String): String =
        EntityConverter.replaceEntities(text, processEntities = true, processEscapes = true)
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private companion object {
        val IGNORED_BETWEEN_BLOCKS = setOf(
            MarkdownTokenTypes.EOL,
            MarkdownTokenTypes.WHITE_SPACE,
            MarkdownTokenTypes.BLOCK_QUOTE,
            MarkdownTokenTypes.LIST_BULLET,
            MarkdownTokenTypes.LIST_NUMBER,
            GFMTokenTypes.CHECK_BOX,
        )
        val CONTINUATION_INDENT = Regex("\n[ \t]+")
        val OPENABLE = listOf("https://", "http://", "mailto:")
    }
}

/** The words of [blocks] with their marks gone, for checking nothing was lost. */
internal fun plainTextOf(blocks: List<MarkdownBlock>): String = blocks.joinToString("\n") { block ->
    fun runs(runs: List<InlineRun>) = runs.joinToString("") { it.text }
    when (block) {
        is MarkdownBlock.Heading -> runs(block.runs)
        is MarkdownBlock.Paragraph -> runs(block.runs)
        is MarkdownBlock.Code -> block.text
        is MarkdownBlock.Quote -> plainTextOf(block.blocks)
        is MarkdownBlock.Items -> block.items.indices.joinToString("\n") { i ->
            val number = block.markers[i].takeIf { it != "•" }?.let { "$it " }.orEmpty()
            number + plainTextOf(block.items[i])
        }
        is MarkdownBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString(" | ", transform = ::runs) }
        MarkdownBlock.Rule -> ""
    }
}

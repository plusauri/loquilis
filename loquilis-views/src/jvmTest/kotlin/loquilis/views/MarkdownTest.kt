package loquilis.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.TextRole
import loquilis.store.text

class MarkdownTest {
    private fun single(text: String) = markdownBlocks(text).single()

    private fun runs(text: String) = assertIs<MarkdownBlock.Paragraph>(single(text)).runs

    @Test fun marksBecomeLooks() {
        assertEquals(
            listOf(
                InlineRun("A "),
                InlineRun("bold", bold = true),
                InlineRun(" and "),
                InlineRun("slanted", italic = true),
                InlineRun(" and "),
                InlineRun("code", code = true),
                InlineRun(" and "),
                InlineRun("gone", strike = true),
            ),
            runs("A **bold** and *slanted* and `code` and ~~gone~~"),
        )
    }

    @Test fun marksNest() {
        assertEquals(listOf(InlineRun("both", bold = true, italic = true)), runs("***both***"))
    }

    @Test fun whatTheOwnerSawReadsCleanly() {
        val blocks = markdownBlocks("**This is for: worktree: `loquilis-convo`**\n\nI can't tell yet.")
        assertEquals(2, blocks.size)
        assertEquals(
            listOf(InlineRun("This is for: worktree: ", bold = true), InlineRun("loquilis-convo", bold = true, code = true)),
            assertIs<MarkdownBlock.Paragraph>(blocks[0]).runs,
        )
    }

    @Test fun aSingleLineBreakStays() {
        assertEquals(listOf(InlineRun("/Users/me/folder\n→ file:///Users/me/folder", link = null)).map { it.text }, listOf(plainTextOf(markdownBlocks("/Users/me/folder\n→ file:///Users/me/folder"))))
    }

    @Test fun linksKeepTheirTarget() {
        assertEquals(listOf(InlineRun("See "), InlineRun("the docs", link = "https://example.com/a")), runs("See [the docs](https://example.com/a)"))
        assertEquals(listOf(InlineRun("https://example.com", link = "https://example.com")), runs("<https://example.com>"))
    }

    @Test fun headingsKeepTheirLevel() {
        assertEquals(MarkdownBlock.Heading(2, listOf(InlineRun("Title"))), single("## Title"))
        assertEquals(MarkdownBlock.Heading(1, listOf(InlineRun("Title"))), single("Title\n====="))
    }

    @Test fun aFenceKeepsItsLanguageAndItsLines() {
        assertEquals(MarkdownBlock.Code("kotlin", "val a = 1\n\n  val b = **2**"), single("```kotlin\nval a = 1\n\n  val b = **2**\n```"))
    }

    @Test fun anIndentedBlockIsCode() {
        assertEquals(MarkdownBlock.Code("", "a line\nanother"), single("    a line\n    another"))
    }

    @Test fun listsKeepTheirMarkersAndNesting() {
        val bullets = assertIs<MarkdownBlock.Items>(single("- one\n- two\n  - inner"))
        assertEquals(listOf("•", "•"), bullets.markers)
        assertIs<MarkdownBlock.Items>(bullets.items[1][1])
        assertEquals(listOf("3.", "4."), assertIs<MarkdownBlock.Items>(single("3. a\n4. b")).markers)
    }

    @Test fun aCheckBoxShows() {
        val items = assertIs<MarkdownBlock.Items>(single("- [x] done\n- [ ] not"))
        assertEquals("☑ done\n☐ not", plainTextOf(listOf(items)))
    }

    @Test fun aQuoteHoldsBlocks() {
        val quote = assertIs<MarkdownBlock.Quote>(single("> a **b**\n> c"))
        assertEquals("a b\nc", plainTextOf(quote.blocks))
    }

    @Test fun aTableHasAHeaderAndRows() {
        val table = assertIs<MarkdownBlock.Table>(single("| A | B |\n|---|---|\n| 1 | **2** |\n| 3 | 4 |"))
        assertEquals(listOf(listOf(InlineRun("A")), listOf(InlineRun("B"))), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf(InlineRun("2", bold = true)), table.rows[0][1])
    }

    @Test fun aRuleIsARule() {
        assertEquals(MarkdownBlock.Rule, single("---"))
    }

    @Test fun escapesAndEntitiesReadAsMeant() {
        assertEquals("not *bold* & fine", plainTextOf(markdownBlocks("not \\*bold\\* &amp; fine")))
    }

    @Test fun rawHtmlStaysAsText() {
        assertTrue("<div>hi</div>" in plainTextOf(markdownBlocks("<div>hi</div>")))
    }

    @Test fun anEnormousReplyStaysPlain() {
        val text = "**word** ".repeat(40_000)
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(InlineRun(text)))), markdownBlocks(text))
    }

    @Test fun emptyTextHasNoBlocks() {
        assertEquals(emptyList(), markdownBlocks(""))
    }

    @Test fun onlyWebAndMailLinksOpen() {
        assertEquals(listOf(InlineRun("run it")), runs("[run it](console:bin/tool)"))
        assertEquals(listOf(InlineRun("a file")), runs("[a file](file:///etc/hosts)"))
        assertEquals(listOf(InlineRun("write", link = "mailto:a@example.com")), runs("[write](mailto:a@example.com)"))
    }

    /** Every word of every real reply in the take gets through. A link's target and a fence's language are not shown words. */
    @Test fun aRealTakeLosesNothing() {
        val replies = Fixture.entries.mapNotNull { (it.body?.kind as? EntryKind.Text)?.text }
            .filter { it.role == TextRole.TEXT_ROLE_REPLY }
            .map { Fixture.store.text(it.text) }
        assertTrue(replies.isNotEmpty())
        val words = Regex("[\\p{L}\\p{N}]+")
        for (reply in replies) {
            val shown = plainTextOf(markdownBlocks(reply))
            val before = words.findAll(reply.replace(Regex("]\\([^)]*\\)"), "]").replace(Regex("```\\w+"), "```")).map { it.value }.toList()
            val after = words.findAll(shown).map { it.value }.toList()
            assertEquals(before, after)
        }
    }
}

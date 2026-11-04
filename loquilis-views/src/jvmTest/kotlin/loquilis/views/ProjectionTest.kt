package loquilis.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import loquilis.core.inlineContent
import loquilis.model.AgentText
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.TextRole
import loquilis.model.Thinking
import loquilis.model.ToolCall
import loquilis.model.ToolKind
import loquilis.model.ToolResult
import loquilis.model.TurnSummary
import loquilis.model.UserMessage
import loquilis.model.Visibility
import loquilis.store.Store

class ProjectionTest {
    private var seq = 0L
    private fun entry(id: String, parent: String, body: EntryBody, state: EntryState = EntryState.ENTRY_STATE_COMPLETE, visibility: Visibility = Visibility.VISIBILITY_NORMAL) =
        Entry(id = id, seq = ++seq, parent_id = parent, body = body, state = state, visibility = visibility)

    private fun user(id: String, parent: String) = entry(id, parent, EntryBody(user_message = UserMessage(inlineContent("hello"))))
    private fun reply(id: String, parent: String) = entry(id, parent, EntryBody(agent_text = AgentText(inlineContent("done"), TextRole.TEXT_ROLE_REPLY)))
    private fun call(id: String, parent: String, kind: ToolKind = ToolKind.TOOL_KIND_SHELL) =
        entry(id, parent, EntryBody(tool_call = ToolCall(call_id = "c$id", tool = "Bash", kind = kind, input = inlineContent("ls"))))
    private fun result(id: String, callEntry: String, state: EntryState = EntryState.ENTRY_STATE_COMPLETE) =
        entry(id, callEntry, EntryBody(tool_result = ToolResult(call_id = "c$callEntry", output = inlineContent("ok"))), state)

    private fun idsOf(rows: List<MessageRow>): List<String> = rows.flatMap { row ->
        when (row) {
            is MessageRow.Said -> listOf(row.entry.id)
            is MessageRow.Reply -> listOf(row.entry.id)
            is MessageRow.Note -> listOf(row.entry.id)
            is MessageRow.Footer -> listOf(row.entry.id)
            is MessageRow.Step -> listOfNotNull(row.entry.id, row.result?.id)
            is MessageRow.Work -> emptyList()
        }
    }

    @Test
    fun aTurnReadsMessageThenWorkThenReplyThenFooter() {
        val u = user("u", "")
        val c = call("c", "u")
        val r = result("r", "c")
        val a = reply("a", "c")
        val rows = messagesOf(listOf(u, c, r, a))
        assertEquals(listOf("Said", "Work", "Step", "Reply"), rows.map { it::class.simpleName })
    }

    @Test
    fun aTurnWithNoWorkHasNoFold() {
        val rows = messagesOf(listOf(user("u", ""), reply("a", "u")))
        assertFalse(rows.any { it is MessageRow.Work })
    }

    @Test
    fun theFoldCountsCallsByKindAndFailures() {
        val c1 = call("c1", "u")
        val c2 = call("c2", "c1", ToolKind.TOOL_KIND_FILE_READ)
        val c3 = call("c3", "c2")
        val steps = listOf(
            MessageRow.Step(c1, result("r1", "c1"), 0, 1),
            MessageRow.Step(c2, result("r2", "c2", EntryState.ENTRY_STATE_FAILED), 0, 1),
            MessageRow.Step(c3, result("r3", "c3"), 0, 1),
        )
        assertEquals("3 tool calls: 2 commands, 1 read, 1 failed", workSummary(steps))
    }

    @Test
    fun aFoldOfOneKindJustCountsIt() {
        val c1 = call("c1", "u")
        val c2 = call("c2", "c1")
        assertEquals("2 commands", workSummary(listOf(MessageRow.Step(c1, null, 0, 1), MessageRow.Step(c2, null, 0, 1))))
    }

    @Test
    fun privateThinkingStaysOutOfMessagesAndInTheTree() {
        val u = user("u", "")
        val t = entry("t", "u", EntryBody(thinking = Thinking(inlineContent("hmm"))), visibility = Visibility.VISIBILITY_PRIVATE)
        val entries = listOf(u, t, reply("a", "t"))
        assertFalse("t" in idsOf(messagesOf(entries)))
        assertTrue(treeOf(entries).any { it.entry.id == "t" })
    }

    @Test
    fun aSubagentsWorkIsNestedUnderTheCallThatStartedIt() {
        val task = call("task", "u", ToolKind.TOOL_KIND_TASK)
        val inner = call("inner", "task")
        val rows = messagesOf(listOf(user("u", ""), task, inner, result("rinner", "inner"), result("rtask", "task")))
        val depths = rows.filterIsInstance<MessageRow.Step>().associate { it.entry.id to it.depth }
        assertEquals(mapOf("task" to 0, "inner" to 1), depths)
    }

    @Test
    fun theTreeHoldsEveryEntryOnce() {
        val rows = treeOf(Fixture.entries)
        assertEquals(Fixture.entries.map { it.id }.sorted(), rows.map { it.entry.id }.sorted())
    }

    @Test
    fun turnsSitAtTheTopWithTheirWorkInsideAndResultsUnderTheirCalls() {
        val u = user("u", "")
        val c = call("c", "u")
        val r = result("r", "c")
        val a = reply("a", "c")
        val u2 = user("u2", "a")
        val depths = treeOf(listOf(u, c, r, a, u2)).associate { it.entry.id to it.depth }
        assertEquals(mapOf("u" to 0, "c" to 1, "r" to 2, "a" to 1, "u2" to 0), depths)
    }

    @Test
    fun theRecordedTakesTurnsAreAllAtTheTop() {
        val rows = treeOf(Fixture.entries)
        assertEquals(setOf(0), rows.filter { it.entry.body?.user_message != null }.map { it.depth }.toSet())
        assertTrue(rows.maxOf { it.depth } <= 3, "depth stayed small")
    }

    @Test
    fun aForkShowsAsASideBranchAndTheLaterLineCarriesOn() {
        val u = user("u", "")
        val a = reply("a", "u")
        val fork = user("fork", "a")
        val forkReply = reply("forkReply", "fork")
        val main = user("main", "a")
        val rows = treeOf(listOf(u, a, fork, forkReply, main))
        assertEquals(listOf("u", "a", "fork", "forkReply", "main"), rows.map { it.entry.id })
        assertEquals(listOf(0, 1, 2, 3, 0), rows.map { it.depth })
    }

    @Test
    fun aVeryLongChainStaysFlatAndDoesNotOverflow() {
        val entries = buildList {
            add(user("u", ""))
            for (i in 1..100_000) add(reply("r$i", if (i == 1) "u" else "r${i - 1}"))
        }
        val rows = treeOf(entries)
        assertEquals(100_001, rows.size)
        assertEquals(1, rows.maxOf { it.depth })
    }

    @Test
    fun collapsingHidesOnlyWhatIsNested() {
        val rows = treeOf(listOf(user("u", ""), call("c", "u"), result("r", "c"), user("u2", "c"), reply("a", "u2")))
        assertEquals(listOf("u", "u2", "a"), visibleTree(rows, setOf("u")).map { it.entry.id })
        assertEquals(listOf("u", "c", "u2", "a"), visibleTree(rows, setOf("c")).map { it.entry.id })
    }

    @Test
    fun onlyRowsWithSomethingNestedCanFold() {
        val rows = treeOf(listOf(user("u", ""), call("c", "u"), result("r", "c")))
        assertEquals(setOf("u", "c"), treeParents(rows))
    }

    @Test
    fun ancestorsAreTheRowsThatCouldHideAnEntry() {
        val rows = treeOf(listOf(user("u", ""), call("c", "u"), result("r", "c")))
        assertEquals(listOf("c", "u"), treeAncestors(rows, "r"))
    }

    @Test
    fun turnsCountOnFromTheFirstTurnGiven() {
        val rows = messagesOf(listOf(user("u", ""), reply("a", "u")), firstTurn = 41)
        assertEquals(setOf(42), rows.map { it.turn }.toSet())
    }

    @Test
    fun aSnippetSplitsIntoPlainAndMatchedRuns() {
        val snippet = "before ${Store.MATCH_OPEN}hit${Store.MATCH_CLOSE} after ${Store.MATCH_OPEN}two${Store.MATCH_CLOSE}"
        assertEquals(listOf("before " to false, "hit" to true, " after " to false, "two" to true), snippetRuns(snippet))
        assertEquals(listOf("no match" to false), snippetRuns("no match"))
    }

    @Test
    fun aClosedTurnsTagCountsItsWork() {
        assertEquals("3 calls · 1 failed", turnTag(TurnSummary(tool_call_count = 3, error_count = 1)))
        assertEquals(null, turnTag(TurnSummary()))
        assertEquals(null, turnTag(null))
    }
}

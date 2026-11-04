package loquilis.agents

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import loquilis.agents.Fixture.text
import loquilis.kit.EntryKind
import loquilis.kit.kind
import loquilis.model.Entry
import loquilis.model.TextRole
import loquilis.model.ToolKind

/**
 * The proof behind decision 0001: a real Cogilis take, replayed from its raw stream, has everything
 * the Cogilis recorder found in it. `conversation.json` is the statement of what must be there.
 */
class ClaudeParityTest {
    private val replayed = replayFixture()

    /** Entries of each turn, in order. */
    private val entriesByTurn: List<List<Entry>> = run {
        val all = replayed.log.entries
        var from = 0
        replayed.endsByTurn.map { end -> all.subList(from, end).also { from = end } }
    }

    private fun steps(turn: JsonObject) = turn.getValue("cogilis").jsonObject.getValue("steps").jsonArray.map { it.jsonObject }

    private fun instantText(e: Entry): String = e.at.toString()

    @Test fun everyTurnIsThere() {
        assertEquals(Fixture.turns.size, Fixture.linesByTurn.size, "a result per turn")
        Fixture.turns.forEachIndexed { i, turn ->
            val first = assertIsUser(entriesByTurn[i].first())
            assertEquals(turn.getValue("client").jsonObject.text("said"), replayed.blobs.text(first))
        }
    }

    private fun assertIsUser(e: Entry) = (e.body!!.kind as EntryKind.User).message.text

    /** Every step, in order, with its kind, tool, exact input, path, error flag and timestamp. */
    @Test fun everyStepIsThereInOrder() {
        Fixture.turns.forEachIndexed { i, turn ->
            val expected = steps(turn)
            val found = entriesByTurn[i].filter { e ->
                when (val k = e.body!!.kind) {
                    is EntryKind.Call -> true
                    is EntryKind.Text -> k.text.role == TextRole.TEXT_ROLE_NARRATION
                    else -> false
                }
            }
            assertEquals(expected.map { it.text("kind") }, found.map(::cogilisKind), "turn ${i + 1} step kinds")
            expected.zip(found).forEach { (step, entry) ->
                val label = "turn ${i + 1} ${step.text("id")}"
                assertEquals(step.text("at"), instantText(entry), "$label time")
                when (val k = entry.body!!.kind) {
                    is EntryKind.Text -> assertEquals(step.text("text"), replayed.blobs.text(k.text.text).trim(), label)
                    is EntryKind.Call -> {
                        assertEquals(step.text("tool"), k.call.tool, label)
                        step.text("input")?.let { assertEquals(it, replayed.blobs.text(k.call.input), "$label input") }
                        step.text("path")?.let { assertEquals(it, k.call.target_path, "$label path") }
                    }
                    else -> error("not a step")
                }
            }
        }
    }

    private fun cogilisKind(e: Entry): String = when (val k = e.body!!.kind) {
        is EntryKind.Text -> "narration"
        is EntryKind.Call -> when (k.call.kind) {
            ToolKind.TOOL_KIND_FILE_READ -> "read"
            ToolKind.TOOL_KIND_FILE_WRITE, ToolKind.TOOL_KIND_FILE_EDIT -> "write"
            else -> "command"
        }
        else -> error("not a step")
    }

    /** Each call's result: the error flag, and the output, whose first lines Cogilis kept inline. */
    @Test fun everyCommandHasItsOutput() {
        var checked = 0
        Fixture.turns.forEachIndexed { i, turn ->
            val results = entriesByTurn[i].mapNotNull { (it.body!!.kind as? EntryKind.Result)?.result }.associateBy { it.call_id }
            val calls = entriesByTurn[i].mapNotNull { (it.body!!.kind as? EntryKind.Call)?.call }
            val commands = steps(turn).filter { it.text("kind") != "narration" }
            assertEquals(commands.size, calls.size, "turn ${i + 1}")
            commands.zip(calls).forEach { (step, call) ->
                val result = results.getValue(call.call_id)
                assertEquals(step["isError"]?.jsonPrimitive?.booleanOrNull ?: false, result.is_error, step.text("id"))
                step.text("outputHead")?.let { head ->
                    assertEquals(head, replayed.blobs.text(result.output).lines().take(12).joinToString("\n").trimEnd(), step.text("id"))
                    checked++
                }
            }
        }
        assertTrue(checked > 20, "only $checked outputs compared")
    }

    @Test fun everyReplyIsThere() {
        Fixture.turns.forEachIndexed { i, turn ->
            val replies = entriesByTurn[i].mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text }.filter { it.role == TextRole.TEXT_ROLE_REPLY }
            assertEquals(turn.getValue("cogilis").jsonObject.text("reply"), replies.joinToString("\n\n") { replayed.blobs.text(it.text) }.trim(), "turn ${i + 1}")
        }
    }

    @Test fun everyTurnResultMatches() {
        Fixture.turns.forEachIndexed { i, turn ->
            val reply = turn.getValue("cogilis").jsonObject
            val result = (entriesByTurn[i].last().body!!.kind as EntryKind.TurnEnd).result
            assertEquals(reply["durationMs"]!!.jsonPrimitive.longOrNull, result.duration!!.toMillis(), "turn ${i + 1} duration")
            assertEquals(reply["agentTurns"]!!.jsonPrimitive.longOrNull?.toInt(), result.agent_turns, "turn ${i + 1} agent turns")
            val micro = reply["costUsd"]!!.jsonPrimitive.doubleOrNull!! * 1_000_000
            assertTrue(abs(micro - result.usage!!.cost_micro_usd) <= 1.0, "turn ${i + 1} cost: $micro vs ${result.usage!!.cost_micro_usd}")
        }
    }

    @Test fun theAgentIsIdentified() {
        val info = replayed.sessions.first()
        assertEquals("claude-opus-5-5", info.model)
        assertEquals(Fixture.record.getValue("agents").jsonObject.getValue("cogilis").jsonObject.text("version")?.substringBefore(" "), info.cliVersion)
    }

    /** seq is dense from 1, and every parent is an entry that came first. */
    @Test fun theLogIsAWellFormedTree() {
        val entries = replayed.log.entries
        assertEquals((1L..entries.size).toList(), entries.map { it.seq })
        val seen = mutableSetOf("")
        entries.forEach { assertTrue(it.parent_id in seen, "orphan ${it.id}"); seen += it.id }
        assertEquals(1, entries.count { it.parent_id.isEmpty() })
    }

    /** A second run over the same raw lines gives the same record. This is what `reparse` relies on. */
    @Test fun parsingIsRepeatable() {
        assertEquals(replayed.log.entries, replayFixture().log.entries)
    }
}

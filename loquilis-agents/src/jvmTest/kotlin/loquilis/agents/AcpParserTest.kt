package loquilis.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import loquilis.core.ContentFactory
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.AppCommand
import loquilis.model.Entry
import loquilis.model.EntryState
import loquilis.model.Interrupt
import loquilis.model.RawRange
import loquilis.model.TextRole
import loquilis.model.ToolKind
import loquilis.model.Visibility

/** Lines are hand-written to match what `grok agent stdio` sends. Read from Grok 1.0.70. */
class AcpParserTest {
    private val blobs = MemoryBlobs()
    private val parser = AcpParser("c1", ContentFactory(blobs), idsInOrder())
    private val log = EntryLog()
    private var rawSeq = 0L

    private fun feed(line: String): List<ParseOutput> = parser.line(++rawSeq, line, EPOCH).onEach(log::apply)

    private fun update(body: String) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":$body}}"""
    private fun words(vararg parts: String) = parts.forEach { feed(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$it"}}""")) }
    private fun thought(text: String) = feed(update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"$text"}}"""))
    private fun call(id: String, kind: String = "execute", input: String = """{"command":"ls","description":"List files"}""") = feed(
        update("""{"sessionUpdate":"tool_call","toolCallId":"$id","title":"run_terminal_command","rawInput":$input,"_meta":{"x.ai/tool":{"name":"run_terminal_command","kind":"$kind","label":"Run Command"}}}"""),
    )
    private fun done(id: String, status: String = "completed", text: String = "a.txt", exit: Int = 0) = feed(
        update("""{"sessionUpdate":"tool_call_update","toolCallId":"$id","status":"$status","content":[{"type":"content","content":{"type":"text","text":"$text"}}],"rawOutput":{"output_for_prompt":"exit: $exit\n","exit_code":$exit}}"""),
    )
    private val ended = """{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn","_meta":{"usage":{"inputTokens":40,"outputTokens":19,"cachedReadTokens":21,"cacheCreationTokens":0,"modelCalls":2,"costUsdTicks":203517200}}}}"""
    private val cancelled = """{"jsonrpc":"2.0","id":3,"result":{"stopReason":"cancelled","_meta":{"inputTokens":1}}}"""

    private fun texts() = log.entries.mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text }
    private fun endOf(): Entry = log.entries.last { it.body!!.kind is EntryKind.TurnEnd }

    @Test fun chunksBecomeOneEntryCoveringTheirLines() {
        words("Hel", "lo ", "there")
        feed(ended)
        val text = texts().single()
        assertEquals("Hello there", blobs.text(text.text))
        assertEquals(RawRange(1, 3), log.entries.first().raw)
    }

    private fun writing(outputs: List<ParseOutput>) = outputs.filterIsInstance<ParseOutput.Writing>().map { it.text }

    @Test fun theReplyShowsAsItIsWrittenAndClearsAfterItsEntry() {
        val shown = listOf("Hel", "lo").flatMap { part ->
            writing(feed(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$part"}}""")))
        }
        assertEquals(listOf("Hel", "Hello"), shown)
        val end = feed(ended)
        assertEquals(listOf(""), writing(end))
        assertTrue(end.indexOfFirst { it is ParseOutput.Append } < end.indexOfFirst { it is ParseOutput.Writing })
    }

    @Test fun thinkingIsNeverShownAsWriting() {
        assertEquals(emptyList(), writing(feed(update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}"""))))
        assertEquals(emptyList(), writing(feed(ended)))
    }

    @Test fun textAfterTheLastToolIsTheReply() {
        words("Looking")
        call("t1")
        done("t1")
        words("Done")
        feed(ended)
        assertEquals(listOf(TextRole.TEXT_ROLE_NARRATION, TextRole.TEXT_ROLE_REPLY), texts().map { it.role })
    }

    @Test fun thinkingIsPrivateAndSplitsTheText() {
        words("One")
        thought("Hmm")
        words("Two")
        feed(ended)
        assertEquals(listOf("One", "Two"), texts().map { blobs.text(it.text) })
        assertEquals(Visibility.VISIBILITY_PRIVATE, log.entries.single { it.body!!.kind is EntryKind.Reasoning }.visibility)
        assertTrue(texts().all { it.role == TextRole.TEXT_ROLE_REPLY })
    }

    @Test fun aCallAndItsResultAreLinked() {
        call("t1")
        done("t1")
        val (made, result) = log.entries
        val tool = (made.body!!.kind as EntryKind.Call).call
        assertEquals(ToolKind.TOOL_KIND_SHELL, tool.kind)
        assertEquals("ls", tool.input!!.inline_)
        assertEquals("List files", tool.description)
        assertEquals(EntryState.ENTRY_STATE_COMPLETE, made.state)
        assertEquals(made.id, result.parent_id)
        assertEquals("a.txt", blobs.text((result.body!!.kind as EntryKind.Result).result.output))
    }

    @Test fun aCommandThatFailsSaysCompletedButIsAFailure() {
        call("t1")
        done("t1", text = "", exit = 1)
        val result = (log.entries.last().body!!.kind as EntryKind.Result).result
        assertTrue(result.is_error)
        assertEquals("exit: 1\n", blobs.text(result.output))
        assertEquals(EntryState.ENTRY_STATE_FAILED, log.entries.first().state)
    }

    @Test fun anEditShowsWhatChanged() {
        call("t1", "edit", """{"file_path":"/w/a.txt","old_string":"a","new_string":"b"}""")
        feed(update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"diff","path":"/w/a.txt","oldText":"a","newText":"b"}]}"""))
        val tool = (log.entries.first().body!!.kind as EntryKind.Call).call
        assertEquals(ToolKind.TOOL_KIND_FILE_EDIT, tool.kind)
        assertEquals("/w/a.txt", tool.target_path)
        assertEquals("/w/a.txt\n- a\n+ b", blobs.text((log.entries.last().body!!.kind as EntryKind.Result).result.output))
    }

    @Test fun theTurnCarriesItsUsageAndCost() {
        parser.userMessage("Hi", EPOCH).forEach(log::apply)
        feed(ended)
        val result = (endOf().body!!.kind as EntryKind.TurnEnd).result
        assertEquals(20_351L, result.usage!!.cost_micro_usd)
        assertEquals(19L, result.usage!!.output_tokens)
        assertEquals(21L, result.usage!!.cache_read_tokens)
        assertEquals(2, result.agent_turns)
        assertEquals(EntryState.ENTRY_STATE_COMPLETE, endOf().state)
    }

    @Test fun aCancelledTurnIsInterruptedAndClosesItsCalls() {
        call("t1")
        parser.command(AppCommand(interrupt = Interrupt()), EPOCH).forEach(log::apply)
        feed(cancelled)
        assertEquals(EntryState.ENTRY_STATE_INTERRUPTED, endOf().state)
        assertEquals(EntryState.ENTRY_STATE_INTERRUPTED, log.entries.first().state)
    }

    @Test fun anUnusualStopReasonFailsTheTurnAndSaysWhy() {
        feed("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"max_tokens"}}""")
        assertEquals(EntryState.ENTRY_STATE_FAILED, endOf().state)
        assertEquals(1, log.entries.count { it.body!!.notice?.text == "The turn ended early: max_tokens" })
    }

    @Test fun aFailedPromptEndsTheTurn() {
        parser.userMessage("Hi", EPOCH).forEach(log::apply)
        feed("""{"jsonrpc":"2.0","id":3,"error":{"code":-32603,"message":"Out of credit"}}""")
        assertEquals(EntryState.ENTRY_STATE_FAILED, endOf().state)
        assertEquals(listOf("Out of credit"), log.entries.mapNotNull { it.body!!.notice?.text })
        assertEquals(log.entries.map { it.seq }, (1L..log.entries.size).toList())
    }

    @Test fun anAskOffersEveryAnswerAsALine() {
        val outputs = feed(
            """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"Execute `ls`","rawInput":{"command":"ls","description":"List files"},"_meta":{"x.ai/tool":{"label":"Run Command"}}},"options":[{"optionId":"always-allow","name":"Yes, and don't ask again for bash commands","kind":"allow_always"},{"optionId":"allow-once","name":"Yes, proceed","kind":"allow_once"},{"optionId":"reject-once","name":"No","kind":"reject_once"}]}}""",
        )
        val ask = assertIs<ParseOutput.Ask>(outputs.last()).ask
        assertEquals("Run Command", ask.tool)
        assertEquals("ls", ask.detail)
        assertEquals("bash commands", ask.remember)
        assertTrue(""""id":7""" in ask.allow && """"optionId":"allow-once"""" in ask.allow)
        assertTrue(""""optionId":"always-allow"""" in assertNotNull(ask.allowAlways))
        assertTrue(""""optionId":"reject-once"""" in ask.deny)
    }

    @Test fun anAskWithNoLastingChoiceOffersNone() {
        val outputs = feed("""{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"toolCall":{},"options":[{"optionId":"o","kind":"allow_once"}]}}""")
        val ask = assertIs<ParseOutput.Ask>(outputs.last()).ask
        assertNull(ask.allowAlways)
        assertTrue(""""outcome":"cancelled"""" in ask.deny)
    }

    @Test fun aNewSessionReportsItself() {
        val info = assertIs<ParseOutput.Session>(
            feed("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","models":{"currentModelId":"grok-4.7"},"_meta":{"currentWorkingDirectory":"/w"}}}""").single(),
        ).info
        assertEquals(SessionInfo("", "grok-4.7", "s1", "/w"), info)
    }

    @Test fun whatItHasNoUseForMakesNoEntry() {
        feed(update("""{"sessionUpdate":"available_commands_update","availableCommands":[]}"""))
        feed("""{"jsonrpc":"2.0","method":"_x.ai/queue/changed","params":{}}""")
        feed("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        assertTrue(log.entries.isEmpty())
    }

    @Test fun aBrokenLineBecomesANotice() {
        feed("not json")
        assertEquals(1, log.entries.count { it.body!!.notice != null })
    }

    @Test fun textStillRunningWhenTheAgentDiesIsKept() {
        words("Half")
        parser.abandon(EPOCH).forEach(log::apply)
        assertEquals("Half", blobs.text(texts().single().text))
    }
}

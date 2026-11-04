package loquilis.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import loquilis.core.ContentFactory
import loquilis.core.EntryKind
import loquilis.core.isStored
import loquilis.core.kind
import loquilis.model.EntryState
import loquilis.model.NoticeLevel
import loquilis.model.TextRole
import loquilis.model.ToolKind
import loquilis.model.Visibility
import okio.ByteString.Companion.decodeBase64

/** Shapes the recorded take does not cover, or does not cover on its own. Lines are hand-written to match the real format. */
class ClaudeStreamParserTest {
    private val blobs = MemoryBlobs()
    private val parser = ClaudeStreamParser("c1", ContentFactory(blobs), idsInOrder())
    private val log = EntryLog()
    private var rawSeq = 0L

    private fun feed(line: String): List<ParseOutput> = parser.line(++rawSeq, line, EPOCH).onEach(log::apply)

    private fun assistant(vararg blocks: String, parent: String = "null") =
        """{"type":"assistant","message":{"content":[${blocks.joinToString(",")}]},"parent_tool_use_id":$parent,"timestamp":"2026-11-02T02:41:34.647Z"}"""

    private fun toolUse(id: String, name: String, input: String) = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""
    private fun text(t: String) = """{"type":"text","text":"$t"}"""
    private fun toolResult(id: String, content: String, isError: Boolean = false) =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":$content,"is_error":$isError}]},"timestamp":"2026-11-02T02:41:36.647Z"}"""

    private val result = """{"type":"result","subtype":"success","is_error":false,"result":"Done","duration_ms":1500,"num_turns":1,"total_cost_usd":0.25,"usage":{"input_tokens":2,"output_tokens":3}}"""

    @Test fun textAfterTheLastToolIsTheReply() {
        feed(assistant(text("Looking")))
        feed(assistant(toolUse("t1", "Bash", """{"command":"ls","description":"List files"}""")))
        feed(toolResult("t1", "\"a.txt\""))
        feed(assistant(text("Done")))
        feed(result)
        val roles = log.entries.mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text?.role }
        assertEquals(listOf(TextRole.TEXT_ROLE_NARRATION, TextRole.TEXT_ROLE_REPLY), roles)
    }

    @Test fun aReplyOnlyInTheResultStillGetsAnEntry() {
        feed(result)
        val text = log.entries.mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text }.single()
        assertEquals(TextRole.TEXT_ROLE_REPLY, text.role)
        assertEquals("Done", blobs.text(text.text))
    }

    @Test fun aShellCallKeepsItsCommandAndDescription() {
        feed(assistant(toolUse("t1", "Bash", """{"command":"ls -la","description":"List files"}""")))
        val call = (log.entries.single().body!!.kind as EntryKind.Call).call
        assertEquals(ToolKind.TOOL_KIND_SHELL, call.kind)
        assertEquals("ls -la", call.input!!.inline_)
        assertEquals("List files", call.description)
        assertEquals(EntryState.ENTRY_STATE_STREAMING, log.entries.single().state)
    }

    @Test fun anotherToolKeepsItsArgumentsAsJson() {
        feed(assistant(toolUse("t1", "mcp__engine__get_artifact", """{"id":"forecast.mc"}""")))
        val call = (log.entries.single().body!!.kind as EntryKind.Call).call
        assertEquals(ToolKind.TOOL_KIND_MCP, call.kind)
        assertEquals("application/json", call.input!!.media_type)
    }

    @Test fun aResultCompletesItsCallAndHangsUnderIt() {
        feed(assistant(toolUse("t1", "Bash", """{"command":"false"}""")))
        feed(toolResult("t1", "\"boom\"", isError = true))
        val (call, res) = log.entries
        assertEquals(EntryState.ENTRY_STATE_FAILED, call.state)
        assertEquals(call.id, res.parent_id)
        assertEquals(2_000L, (res.body!!.kind as EntryKind.Result).result.elapsed!!.toMillis())
    }

    @Test fun anImageGoesToTheBlobStoreAndLeavesAMark() {
        val png = "iVBORw0KGgo="
        feed(assistant(toolUse("t1", "Read", """{"file_path":"chart.png"}""")))
        feed(toolResult("t1", """[{"type":"text","text":"Read it"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$png"}}]"""))
        val res = (log.entries.last().body!!.kind as EntryKind.Result).result
        assertEquals("Read it\n[image]", res.output!!.inline_)
        assertEquals("image/png", res.images.single().media_type)
        assertEquals(png.decodeBase64(), blobs.stored[res.images.single().sha256])
    }

    @Test fun longOutputGoesToTheBlobStoreWithAHead() {
        val longLine = "x".repeat(5000)
        feed(assistant(toolUse("t1", "Bash", """{"command":"cat big"}""")))
        feed(toolResult("t1", "\"first\\n$longLine\""))
        val output = (log.entries.last().body!!.kind as EntryKind.Result).result.output!!
        assertTrue(output.isStored)
        assertTrue(output.head.startsWith("first\n"), output.head)
        assertEquals("first\n$longLine", blobs.text(output))
    }

    @Test fun thinkingIsPrivateAndEmptyThinkingMakesNoEntry() {
        feed(assistant("""{"type":"thinking","thinking":"","signature":"abc"}"""))
        assertTrue(log.entries.isEmpty())
        feed(assistant("""{"type":"thinking","thinking":"Maybe","signature":"abc"}"""))
        assertEquals(Visibility.VISIBILITY_PRIVATE, log.entries.single().visibility)
    }

    @Test fun harnessTextIsMarkedInjected() {
        feed("""{"type":"user","message":{"content":[{"type":"text","text":"Skill prompt"}]},"isSynthetic":true,"timestamp":"2026-11-02T02:41:34.647Z"}""")
        val text = (log.entries.single().body!!.kind as EntryKind.Text).text
        assertEquals(TextRole.TEXT_ROLE_INJECTED, text.role)
    }

    @Test fun aSubagentHangsUnderItsCallAndLeavesTheMainLineAlone() {
        feed(assistant(toolUse("t1", "Task", """{"prompt":"go"}""")))
        val call = log.entries.single()
        feed(assistant(text("inside"), parent = "\"t1\""))
        feed(assistant(text("outside")))
        val (_, inside, outside) = log.entries
        assertEquals(call.id, inside.parent_id)
        assertEquals(call.id, outside.parent_id)
        feed(result)
        val replies = log.entries.mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text }.filter { it.role == TextRole.TEXT_ROLE_REPLY }
        assertEquals(listOf("outside"), replies.map { blobs.text(it.text) })
    }

    @Test fun onlyARealLimitIsANotice() {
        feed("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}""")
        assertTrue(log.entries.isEmpty())
        feed("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","rateLimitType":"five_hour"}}""")
        assertEquals(NoticeLevel.NOTICE_LEVEL_WARNING, (log.entries.single().body!!.kind as EntryKind.Note).notice.level)
    }

    @Test fun telemetryMakesNoEntry() {
        feed("""{"type":"system","subtype":"thinking_tokens","estimated_tokens":50}""")
        feed("""{"type":"tool_progress","tool_use_id":"t1","elapsed_time_seconds":30}""")
        feed("""{"type":"something_new","x":1}""")
        assertTrue(log.entries.isEmpty())
    }

    @Test fun aLineThatIsNotJsonIsANoticeNotACrash() {
        feed("not json at all")
        assertEquals("unreadable_line", (log.entries.single().body!!.kind as EntryKind.Note).notice.code)
        assertEquals(1, log.entries.single().raw!!.first_seq.toInt())
    }

    @Test fun costIsTheDifferenceFromTheSessionTotal() {
        feed(result)
        feed(result.replace("0.25", "0.75"))
        val costs = log.entries.mapNotNull { (it.body!!.kind as? EntryKind.TurnEnd)?.result?.usage?.cost_micro_usd }
        assertEquals(listOf(250_000L, 500_000L), costs)
    }

    @Test fun anUnchangedInitIsReportedOnce() {
        val init = """{"type":"system","subtype":"init","model":"m","claude_code_version":"2","cwd":"/w"}"""
        assertIs<ParseOutput.Session>(feed(init).single())
        assertTrue(feed(init).isEmpty())
        assertEquals("m2", feed(init.replace("\"m\"", "\"m2\"")).filterIsInstance<ParseOutput.Session>().single().info.model)
    }

    @Test fun abandoningInterruptsWhatWasRunning() {
        feed(assistant(toolUse("t1", "Bash", """{"command":"sleep 100"}""")))
        parser.abandon(EPOCH).forEach(log::apply)
        assertEquals(EntryState.ENTRY_STATE_INTERRUPTED, log.entries.single().state)
    }
}

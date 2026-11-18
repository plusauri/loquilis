package loquilis.agents

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loquilis.core.ContentFactory
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.EntryState
import loquilis.model.ToolKind

/** Lines are those `opencode acp` sent on 2026-11-18, OpenCode 2.3.2 on `ollama/gemma4:e2b`, with paths shortened. */
class OpenCodeTest {
    private val blobs = MemoryBlobs()
    private val parser = AcpParser("c1", ContentFactory(blobs), idsInOrder(), adapter = "opencode-acp")
    private val log = EntryLog()
    private var rawSeq = 0L

    private fun feed(line: String): List<ParseOutput> = parser.line(++rawSeq, line, EPOCH).onEach(log::apply)
    private fun update(body: String) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"ses_1","update":$body}}"""
    private fun model(current: String) =
        """{"id":"model","name":"Model","category":"model","type":"select","currentValue":"$current","options":[{"value":"ollama/gemma4:e2b","name":"ollama/gemma4:e2b"}]}"""

    private val initialized = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"OpenCode","version":"2.3.2"}}}"""
    private val opened = """{"jsonrpc":"2.0","id":2,"result":{"sessionId":"ses_1","configOptions":[${model("ollama/gemma3:4b")}]}}"""
    private val modelSet = """{"jsonrpc":"2.0","id":3,"result":{"configOptions":[${model("ollama/gemma4:e2b")}]}}"""

    @Test fun theSessionNamesTheModelTheConsoleSetAndTheAgentsVersion() {
        val sessions = listOf(initialized, opened, modelSet).flatMap(::feed).filterIsInstance<ParseOutput.Session>().map { it.info }
        assertEquals(listOf("ollama/gemma3:4b", "ollama/gemma4:e2b"), sessions.map { it.model })
        assertEquals("ses_1", sessions.last().sessionId, "setting the model keeps the session")
        assertEquals("2.3.2", sessions.last().cliVersion)
        assertEquals("", sessions.last().workspacePath, "OpenCode does not say, so the folder stays as it was")
    }

    @Test fun aCallsInputArrivesWithItsFirstUpdate() {
        feed(update("""{"sessionUpdate":"tool_call","toolCallId":"call_1","name":"write","title":"write","kind":"edit","status":"pending","locations":[],"rawInput":{}}"""))
        feed(update("""{"sessionUpdate":"tool_call_update","toolCallId":"call_1","status":"in_progress","kind":"edit","title":"write","locations":[{"path":"/w/hello.txt"}],"rawInput":{"path":"hello.txt","content":"hi"}}"""))
        feed(update("""{"sessionUpdate":"tool_call_update","toolCallId":"call_1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"Created file successfully"}}],"rawOutput":{"metadata":{"truncated":false}}}"""))

        val call = log.entries.first().body!!.tool_call!!
        assertEquals("write", call.tool)
        assertEquals(ToolKind.TOOL_KIND_FILE_EDIT, call.kind)
        assertEquals("hello.txt", call.target_path)
        assertEquals(EntryState.ENTRY_STATE_COMPLETE, log.entries.first().state)
        assertEquals("Created file successfully", blobs.text(log.entries.last().body!!.tool_result!!.output))
    }

    @Test fun aShellCommandThatExitsNonZeroFails() {
        feed(update("""{"sessionUpdate":"tool_call","toolCallId":"call_2","name":"shell","title":"shell","kind":"execute","status":"pending","rawInput":{"cwd":"/w"}}"""))
        feed(update("""{"sessionUpdate":"tool_call_update","toolCallId":"call_2","status":"in_progress","kind":"execute","title":"ls nothing","rawInput":{"command":"ls nothing","cwd":"/w"}}"""))
        feed(update("""{"sessionUpdate":"tool_call_update","toolCallId":"call_2","status":"completed","content":[{"type":"content","content":{"type":"text","text":"No such file"}}],"rawOutput":{"metadata":{"status":"completed","exit":1}}}"""))

        val call = log.entries.first()
        assertEquals("ls nothing", blobs.text(call.body!!.tool_call!!.input))
        assertEquals(ToolKind.TOOL_KIND_SHELL, call.body!!.tool_call!!.kind)
        assertEquals(EntryState.ENTRY_STATE_FAILED, call.state)
    }

    @Test fun whatTheTurnUsedSitsBesideItsStopReason() {
        feed(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Plovers are a type of shorebird."}}"""))
        feed("""{"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn","usage":{"inputTokens":9911,"outputTokens":415,"totalTokens":19941,"cachedReadTokens":9615},"_meta":{}}}""")

        val usage = log.entries.last().body!!.turn_result!!.usage!!
        assertEquals(9911, usage.input_tokens)
        assertEquals(415, usage.output_tokens)
        assertEquals(9615, usage.cache_read_tokens)
        assertEquals(0, usage.cost_micro_usd)
    }

    @Test fun anAskNamesTheToolAndWhatItWouldDo() {
        val asked = feed(
            """{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"ses_1","toolCall":{"toolCallId":"call_3","name":"shell","title":"ls","kind":"execute","rawInput":{"command":"ls","cwd":"/w"}},"options":[{"optionId":"once","kind":"allow_once","name":"Allow once"},{"optionId":"always","kind":"allow_always","name":"Always allow"},{"optionId":"reject","kind":"reject_once","name":"Reject"}]}}""",
        ).filterIsInstance<ParseOutput.Ask>().single().ask

        assertEquals("shell", asked.tool)
        assertEquals("ls", asked.detail)
        assertTrue(""""optionId":"reject"""" in asked.deny)
    }

    private val protocol = AcpProtocol("/w", null, "OpenCode", model = "ollama/gemma4:e2b")

    @Test fun theModelIsSetBeforeTheSessionCountsAsOpen() {
        val afterOpen = protocol.heard(opened)
        assertFalse(afterOpen.opened)
        assertTrue(""""method":"session/set_config_option"""" in afterOpen.reply!! && """"value":"ollama/gemma4:e2b"""" in afterOpen.reply!!)
        assertTrue(protocol.heard(modelSet).opened)
    }

    @Test fun aModelNotTakenStopsTheSessionBeforeAnythingIsSent() {
        protocol.heard(opened)
        val kept = protocol.heard("""{"jsonrpc":"2.0","id":3,"result":{"configOptions":[${model("opencode/big-pickle")}]}}""")
        assertFalse(kept.opened)
        assertEquals("OpenCode did not switch to ollama/gemma4:e2b, so nothing was sent.", kept.refused)

        val other = AcpProtocol("/w", null, "OpenCode", model = "ollama/gemma4:e2b").apply { heard(opened) }
        val refused = other.heard("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"unknown model"}}""")
        assertEquals("OpenCode could not use ollama/gemma4:e2b: unknown model", refused.refused)
    }

    @Test fun theProcessIsToldToAskFirstAndToKeepToLocalModels() {
        val dir = Files.createTempDirectory("opencode").toFile()
        val seen = File(dir, "seen")
        val script = File(dir, "fake-opencode.sh").apply {
            writeText(
                """
                #!/bin/sh
                { echo "args: $@"; echo "config: ${'$'}OPENCODE_CONFIG_CONTENT"; echo "free: ${'$'}OPENCODE_DISABLE_FREE_MODELS"; echo "claude: ${'$'}OPENCODE_DISABLE_CLAUDE_CODE"; } > "${seen.path}"
                read -r line; printf '%s\n' '$initialized'
                read -r line; printf '%s\n' '$opened'
                read -r line; echo "${'$'}line" >> "${seen.path}"; printf '%s\n' '$modelSet'
                read -r line; echo "${'$'}line" >> "${seen.path}"
                """.trimIndent(),
            )
        }
        val running = OpenCodeLiveSession(parser, dir, model = "ollama/gemma4:e2b", command = listOf("/bin/sh", script.path))
        running.send("Hi")
        running.close()
        runBlocking { withTimeout(10_000) { running.events.toList() } }

        val lines = seen.readLines()
        assertEquals("args: acp", lines[0])
        assertTrue(""""*":"ask"""" in lines[1] && """"read":"allow"""" in lines[1] && """"external_directory":"ask"""" in lines[1], lines[1])
        assertEquals("free: 1", lines[2])
        assertEquals("claude: 1", lines[3])
        assertTrue("session/set_config_option" in lines[4], "the model is set first")
        assertTrue("session/prompt" in lines[5] && "Hi" in lines[5], "and only then is the message sent")
    }
}

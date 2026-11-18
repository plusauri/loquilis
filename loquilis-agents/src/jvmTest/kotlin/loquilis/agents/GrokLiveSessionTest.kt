package loquilis.agents

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loquilis.core.ContentFactory
import loquilis.core.kind
import loquilis.model.Decision

class GrokLiveSessionTest {
    private val dir: File = Files.createTempDirectory("grok-session").toFile()
    private val stdin = File(dir, "stdin")

    private fun fake(body: String): List<String> {
        val script = File.createTempFile("fake-grok", ".sh", dir)
        script.writeText("#!/bin/sh\nkeep() { printf '%s\\n' \"\$1\" >> \"${stdin.path}\"; }\n$body\n")
        return listOf("/bin/sh", script.path)
    }

    private val opening = """
        echo "${'$'}@" > "${File(dir, "args").path}"
        read -r line; keep "${'$'}line"
        printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'
        read -r line; keep "${'$'}line"
        printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1","models":{"currentModelId":"m"}}}'
    """.trimIndent()

    private val answers = fake(
        opening + """

        n=3
        while read -r line; do
          keep "${'$'}line"
          printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Answer"}}}}'
          printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":${'$'}n,\"result\":{\"stopReason\":\"end_turn\"}}"
          n=${'$'}((n + 1))
        done
        """.trimIndent(),
    )

    private fun session(command: List<String>, resume: String? = null, model: String? = null): GrokLiveSession {
        val parser = AcpParser("c1", ContentFactory(MemoryBlobs()), idsInOrder())
        return GrokLiveSession(parser, dir, model = model, resume = resume, command = command)
    }

    private fun collect(s: GrokLiveSession) = runBlocking { withTimeout(10_000) { s.events.toList() } }

    private fun entriesOf(events: List<SessionEvent>) =
        EntryLog().also { log -> events.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) } }.entries

    @Test fun messagesWaitForTheSessionAndGoOneAtATime() {
        val s = session(answers)
        s.send("First")
        s.send("Second")
        s.close()
        val events = collect(s)
        assertEquals(SessionEvent.Ended(0, ""), events.last())
        assertEquals(listOf("User", "Text", "TurnEnd", "User", "Text", "TurnEnd"), entriesOf(events).map { it.body!!.kind::class.simpleName })
        val sent = stdin.readLines()
        assertEquals(4, sent.size)
        assertTrue(""""method":"initialize"""" in sent[0])
        assertTrue(""""method":"session/new"""" in sent[1])
        assertTrue(""""yoloMode":false""" in sent[1])
        assertTrue(""""sessionId":"s1"""" in sent[2] && "First" in sent[2])
        assertTrue(""""id":5""" in sent[3] && "Second" in sent[3], "3 is kept for setting a model")
    }

    @Test fun theAgentIsStartedInItsStdioForm() {
        val s = session(answers, model = "a-model")
        s.close()
        collect(s)
        assertEquals("agent --model a-model stdio", File(dir, "args").readText().trim())
    }

    @Test fun aResumedSessionAsksAgainAndKeepsItsId() {
        val resumed = fake(
            opening.replace(""""sessionId":"s1",""", "") + """

            read -r line; keep "${'$'}line"
            """.trimIndent(),
        )
        val s = session(resumed, resume = "s9")
        s.send("Again")
        s.close()
        collect(s)
        val sent = stdin.readLines()
        assertTrue(""""method":"session/resume"""" in sent[1] && """"sessionId":"s9"""" in sent[1] && """"yoloMode":false""" in sent[1])
        assertTrue(""""sessionId":"s9"""" in sent[2])
    }

    @Test fun aRefusedSessionEndsWithTheReasonAndKeepsTheMessage() {
        val refused = fake(
            """
            read -r line
            printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
            read -r line
            printf '%s\n' '{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"No such session"}}'
            exec sleep 30
            """.trimIndent(),
        )
        val s = session(refused, resume = "gone")
        s.send("Hello")
        val ended = assertIs<SessionEvent.Ended>(collect(s).last())
        assertEquals("No such session", ended.errorTail)
        assertEquals(listOf("Hello"), ended.unsent)
    }

    @Test fun anAskIsAnsweredAndAStopWithdrawsAnOpenOne() {
        val asking = fake(
            opening + """

            read -r line; keep "${'$'}line"
            printf '%s\n' '{"jsonrpc":"2.0","id":70,"method":"session/request_permission","params":{"toolCall":{},"options":[{"optionId":"allow-once","kind":"allow_once"}]}}'
            read -r line; keep "${'$'}line"
            printf '%s\n' '{"jsonrpc":"2.0","id":71,"method":"session/request_permission","params":{"toolCall":{},"options":[{"optionId":"allow-once","kind":"allow_once"}]}}'
            read -r line; keep "${'$'}line"
            read -r line; keep "${'$'}line"
            printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"cancelled"}}'
            """.trimIndent(),
        )
        val s = session(asking)
        s.send("Go")
        fun nextAsk(): PermissionAsk = runBlocking {
            withTimeout(10_000) {
                (s.events.first { it is SessionEvent.Parsed && it.output is ParseOutput.Ask } as SessionEvent.Parsed).output.let { (it as ParseOutput.Ask).ask }
            }
        }
        s.answer(nextAsk(), Decision.DECISION_ALLOW_ONCE)
        nextAsk()
        s.interrupt()
        s.close()
        collect(s)
        val sent = stdin.readLines()
        assertTrue(""""optionId":"allow-once"""" in sent[3] && """"id":70""" in sent[3])
        assertTrue(""""method":"session/cancel"""" in sent[4])
        assertTrue(""""id":71""" in sent[5] && """"outcome":"cancelled"""" in sent[5])
    }

    @Test fun aRequestItCannotServeIsTurnedDown() {
        val requesting = fake(
            opening + """

            printf '%s\n' '{"jsonrpc":"2.0","id":90,"method":"fs/read_text_file","params":{}}'
            read -r line; keep "${'$'}line"
            read -r line; keep "${'$'}line"
            """.trimIndent(),
        )
        val s = session(requesting)
        s.send("Go")
        collect(s)
        assertTrue(""""id":90""" in stdin.readLines().last() && "-32601" in stdin.readLines().last())
    }
}

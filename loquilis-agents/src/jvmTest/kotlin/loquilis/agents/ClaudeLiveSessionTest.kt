package loquilis.agents

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loquilis.core.ContentFactory
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.Decision
import loquilis.model.EntryState
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** The process side, with a shell script standing in for `claude`. */
class ClaudeLiveSessionTest {
    private val dir: File = Files.createTempDirectory("loquilis-session").toFile().also { it.deleteOnExit() }

    /** A script that answers each line on stdin as `claude` would, and records what it was sent. */
    private fun fake(body: String): List<String> {
        val script = File.createTempFile("fake-claude", ".sh", dir)
        script.writeText("#!/bin/sh\n$body\n")
        return listOf("/bin/sh", script.path)
    }

    private val answers = fake(
        """
        echo "$@" > "${File(dir, "args").path}"
        printf '%s\n' '{"type":"system","subtype":"init","model":"m","claude_code_version":"2","cwd":"/w"}'
        n=0
        while read -r line; do
          n=$((n + 1))
          printf '%s\n' "${'$'}line" >> "${File(dir, "stdin").path}"
          printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"Answer"}]},"timestamp":"2026-11-02T02:41:34.647Z"}'
          printf '%s\n' '{"type":"result","is_error":false,"result":"Answer","total_cost_usd":0.1,"num_turns":1}'
        done
        """.trimIndent(),
    )

    private fun session(command: List<String>): ClaudeLiveSession {
        val parser = ClaudeStreamParser("c1", ContentFactory(MemoryBlobs()), idsInOrder())
        return ClaudeLiveSession(parser, dir, model = "a-model", command = command)
    }

    private fun collect(s: ClaudeLiveSession): List<SessionEvent> = runBlocking { withTimeout(10_000) { s.events.toList() } }

    @Test fun aConversationRunsAndEndsCleanly() {
        val s = session(answers)
        s.send("First \"quoted\"")
        s.send("Second")
        s.close()
        val events = collect(s)
        assertEquals(SessionEvent.Ended(0, ""), events.last())
        val entries = EntryLog().also { log -> events.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) } }.entries
        val kinds = entries.map { it.body!!.kind::class.simpleName }
        assertEquals(listOf("User", "Text", "TurnEnd", "User", "Text", "TurnEnd"), kinds)

        val args = File(dir, "args").readText()
        assertTrue("--input-format stream-json --output-format stream-json --verbose --include-partial-messages --model a-model" in args, args)
        val sent = File(dir, "stdin").readLines()
        assertEquals(2, sent.size)
        assertTrue("""First \"quoted\"""" in sent[0], sent[0])
    }

    @Test fun aMessageSentMidTurnWaitsForTheTurnToEnd() {
        val slow = fake(
            """
            while read -r line; do
              sleep 0.2
              printf '%s\n' "${'$'}line" >> "${File(dir, "stdin").path}"
              printf '%s\n' '{"type":"result","is_error":false,"result":"Answer","num_turns":1}'
            done
            """.trimIndent(),
        )
        val s = session(slow)
        s.send("One")
        s.send("Two")
        s.send("Three")
        assertEquals(0, if (File(dir, "stdin").exists()) File(dir, "stdin").readLines().size else 0, "nothing has gone past the first turn yet")
        s.close()
        val events = collect(s)
        assertEquals(3, File(dir, "stdin").readLines().size)
        assertEquals(emptyList(), assertIs<SessionEvent.Ended>(events.last()).unsent)
    }

    @Test fun aCrashReportsTheMessagesItNeverReached() {
        val crash = fake("read -r line\nexit 1")
        val s = session(crash)
        s.send("One")
        s.send("Two")
        val ended = assertIs<SessionEvent.Ended>(collect(s).last())
        assertEquals(listOf("Two"), ended.unsent)
    }

    @Test fun rawLinesComeBeforeWhatWasParsedFromThem() {
        val s = session(answers)
        s.send("Hello")
        s.close()
        val events = collect(s)
        val rawSeqs = events.filterIsInstance<SessionEvent.Raw>().map { it.seq }
        assertEquals((1L..rawSeqs.size).toList(), rawSeqs)
        events.forEachIndexed { i, e ->
            val raw = ((e as? SessionEvent.Parsed)?.output as? ParseOutput.Append)?.entry?.raw ?: return@forEachIndexed
            val before = events.take(i).filterIsInstance<SessionEvent.Raw>().map { it.seq }
            assertTrue(raw.first_seq in before, "entry from raw ${raw.first_seq} came first")
        }
    }

    @Test fun aCrashEndsTheSessionAndInterruptsTheRunningCall() {
        val crash = fake(
            """
            read -r line
            printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sleep 9"}}]},"timestamp":"2026-11-02T02:41:34.647Z"}'
            echo "it went wrong" >&2
            exit 3
            """.trimIndent(),
        )
        val s = session(crash)
        s.send("Hello")
        val events = collect(s)
        val ended = assertIs<SessionEvent.Ended>(events.last())
        assertEquals(3, ended.exitCode)
        assertEquals("it went wrong", ended.errorTail)
        val entries = EntryLog().also { log -> events.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) } }.entries
        assertEquals(EntryState.ENTRY_STATE_INTERRUPTED, entries.last().state)
    }

    private val asks = fake(
        """
        read -r line
        printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}'
        printf '%s\n' '{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"ls"},"description":"List files","tool_use_id":"t1","permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"ls *"}],"behavior":"allow","destination":"localSettings"},{"type":"addDirectories","directories":["/w"],"destination":"session"}]}}'
        read -r answer
        printf '%s\n' "${'$'}answer" >> "${File(dir, "stdin").path}"
        printf '%s\n' '{"type":"result","is_error":false,"result":"Done","num_turns":1}'
        """.trimIndent(),
    )

    private fun askAndAnswer(decision: Decision): Pair<List<SessionEvent>, String> {
        val s = session(asks)
        val seen = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
        val reader = kotlin.concurrent.thread(isDaemon = true) {
            runBlocking {
                s.events.collect { e ->
                    seen += e
                    ((e as? SessionEvent.Parsed)?.output as? ParseOutput.Ask)?.let { s.answer(it.ask, decision); s.close() }
                }
            }
        }
        s.send("Go")
        reader.join(10_000)
        return seen.toList() to File(dir, "stdin").readText()
    }

    @Test fun anAskIsRecordedAndAnAllowGoesBackWithTheInput() {
        val (events, sent) = askAndAnswer(Decision.DECISION_ALLOW_ONCE)
        val ask = events.filterIsInstance<SessionEvent.Parsed>().map { it.output }.filterIsInstance<ParseOutput.Ask>().single().ask
        assertEquals("Bash", ask.tool)
        assertEquals("ls", ask.detail)
        assertEquals("Bash(ls *)", ask.remember)
        assertTrue(""""behavior":"allow","updatedInput":{"command":"ls"}""" in sent, sent)
        assertTrue("updatedPermissions" !in sent, sent)
        assertTrue(""""request_id":"r1"""" in sent, sent)

        val entries = EntryLog().also { log -> events.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) } }.entries
        assertEquals(listOf("User", "Call", "Permission", "Command", "Text", "TurnEnd"), entries.map { it.body!!.kind::class.simpleName })
        assertEquals("t1", entries[2].body!!.permission_request!!.call_id)
        assertEquals(Decision.DECISION_ALLOW_ONCE, entries[3].body!!.app_command!!.permission_decision!!.decision)
    }

    @Test fun anAlwaysSendsOnlyTheRules() {
        val (_, sent) = askAndAnswer(Decision.DECISION_ALLOW_ALWAYS)
        assertTrue(""""updatedPermissions":[{"type":"addRules"""" in sent, sent)
        assertTrue("addDirectories" !in sent, sent)
    }

    @Test fun aDenyGoesBackWithAReason() {
        val (_, sent) = askAndAnswer(Decision.DECISION_DENY)
        assertTrue(""""behavior":"deny","message":"The person said no."""" in sent, sent)
    }

    @Test fun anInterruptIsRecordedAndSent() {
        val stoppable = fake(
            """
            read -r line
            read -r control
            printf '%s\n' "${'$'}control" >> "${File(dir, "stdin").path}"
            printf '%s\n' '{"type":"control_response","response":{"subtype":"success","request_id":"interrupt-1","response":{"still_queued":[]}}}'
            printf '%s\n' '{"type":"result","is_error":true,"subtype":"error_during_execution","num_turns":1}'
            """.trimIndent(),
        )
        val s = session(stoppable)
        s.send("Go")
        s.interrupt()
        s.close()
        val entries = EntryLog().also { log -> collect(s).filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) } }.entries
        assertEquals(listOf("User", "Command", "TurnEnd"), entries.map { it.body!!.kind::class.simpleName })
        assertTrue(""""subtype":"interrupt"""" in File(dir, "stdin").readText())
    }

    @Test fun anInterruptWithNothingRunningDoesNothing() {
        val s = session(answers)
        s.interrupt()
        s.close()
        val appended = collect(s).filterIsInstance<SessionEvent.Parsed>().map { it.output }.filterIsInstance<ParseOutput.Append>()
        assertEquals(emptyList(), appended)
    }

    /** The real agent, one short exchange. Costs a few cents, so it runs only with LOQUILIS_LIVE=1. */
    @Test
    @EnabledIfEnvironmentVariable(named = "LOQUILIS_LIVE", matches = "1")
    fun theRealClaudeKeepsContextAcrossMessages() {
        val parser = ClaudeStreamParser("c1", ContentFactory(MemoryBlobs()))
        val s = ClaudeLiveSession(parser, dir, model = "claude-haiku-5-5", extraArgs = listOf("--setting-sources", "project,local", "--permission-mode", "dontAsk"))
        val done = Channel2(s)
        s.send("Remember the word plover. Reply with only OK.")
        done.awaitResult()
        s.send("What word did I ask you to remember? Reply with only that word.")
        done.awaitResult()
        s.close()
        val events = done.finish()
        events.forEach { println("LIVE " + it) }
        val entries = EntryLog().also { log -> events.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) } }.entries
        val replies = entries.mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text?.text?.inline_ }
        assertTrue(replies.last().contains("plover", ignoreCase = true), replies.toString())
        assertEquals(2, entries.count { it.body!!.kind is EntryKind.TurnEnd })
    }
}

/** Reads a session on a thread so a test can wait for each turn before sending the next message. */
private class Channel2(session: ClaudeLiveSession) {
    private val events = java.util.concurrent.CopyOnWriteArrayList<SessionEvent>()
    private val results = java.util.concurrent.Semaphore(0)
    private val reader = kotlin.concurrent.thread(isDaemon = true) {
        runBlocking {
            session.events.collect { e ->
                events += e
                val isResult = ((e as? SessionEvent.Parsed)?.output as? ParseOutput.Append)?.entry?.body?.turn_result != null
                if (isResult) results.release()
            }
        }
    }

    fun awaitResult() = check(results.tryAcquire(120, java.util.concurrent.TimeUnit.SECONDS)) { "no result: $events" }

    fun finish(): List<SessionEvent> { reader.join(30_000); return events.toList() }
}

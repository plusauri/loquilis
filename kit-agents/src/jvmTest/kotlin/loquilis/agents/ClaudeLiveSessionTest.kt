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
import loquilis.kit.ContentFactory
import loquilis.kit.EntryKind
import loquilis.kit.kind
import loquilis.model.EntryState
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** The process side, with a shell script standing in for `claude`. */
class ClaudeLiveSessionTest {
    private val dir: File = Files.createTempDirectory("loquilis-session").toFile().also { it.deleteOnExit() }

    /** A script that answers each line on stdin as `claude` would, and records what it was sent. */
    private fun fake(body: String): List<String> {
        val script = File(dir, "fake-claude.sh")
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
        assertTrue("--input-format stream-json --output-format stream-json --verbose --model a-model" in args, args)
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

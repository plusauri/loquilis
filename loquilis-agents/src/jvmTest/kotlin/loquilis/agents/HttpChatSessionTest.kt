package loquilis.agents

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loquilis.core.ContentFactory
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.Entry
import loquilis.model.EntryState
import loquilis.model.TextRole

/** A small server stands in for a model runner, sending what Ollama documents for this interface. */
class HttpChatSessionTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val blobs = MemoryBlobs()
    private val requests = CopyOnWriteArrayList<String>()
    private val released = CountDownLatch(1)

    @AfterTest fun close() {
        released.countDown()
        server.stop(0)
    }

    private fun chunk(text: String) = """{"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"$text"}}]}"""
    private val usage = """{"model":"m","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""

    private fun replying(vararg lines: String, hold: Boolean = false) {
        server.createContext("/v1/chat/completions") { exchange ->
            requests += exchange.requestBody.readBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { out ->
                    for (line in lines) {
                        out.write("data: $line\n\n".toByteArray())
                        out.flush()
                    }
                    if (hold) released.await(10, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun session(model: String? = "m", history: List<ChatMessage> = emptyList(), base: String = url) =
        HttpChatSession(OpenAiChatParser("c1", ContentFactory(blobs), idsInOrder()), base, model, "", history)

    private fun collected(running: HttpChatSession): Pair<List<Entry>, List<SessionEvent>> {
        val events = runBlocking { withTimeout(10_000) { running.events.toList() } }
        val log = EntryLog()
        events.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) }
        return log.entries to events
    }

    private fun turnEnd(entries: List<Entry>) = entries.last { it.body!!.kind is EntryKind.TurnEnd }
    private fun replyText(entries: List<Entry>) =
        entries.mapNotNull { (it.body!!.kind as? EntryKind.Text)?.text }.single().let { it.role to blobs.text(it.text) }

    @Test fun aReplyStreamsAndEndsWithItsUsage() {
        replying(chunk("Hel"), chunk("lo"), usage, "[DONE]")
        val running = session()
        running.send("Hi")
        running.close()
        val (entries, events) = collected(running)

        assertEquals(TextRole.TEXT_ROLE_REPLY to "Hello", replyText(entries))
        val shown = events.filterIsInstance<SessionEvent.Parsed>().mapNotNull { (it.output as? ParseOutput.Writing)?.text }
        assertEquals(listOf("Hel", "Hello", ""), shown)
        val result = (turnEnd(entries).body!!.kind as EntryKind.TurnEnd).result
        assertEquals(EntryState.ENTRY_STATE_COMPLETE, turnEnd(entries).state)
        assertEquals(12, result.usage?.input_tokens)
        assertEquals(3, result.usage?.output_tokens)
        assertEquals(0, result.usage?.cost_micro_usd)
        assertEquals(4, events.count { it is SessionEvent.Raw })
    }

    @Test fun theWholeConversationGoesEachTurnBecauseARunnerRemembersNothing() {
        replying(chunk("Second answer"), "[DONE]")
        val running = session(history = listOf(ChatMessage("user", "Before"), ChatMessage("assistant", "Earlier")))
        running.send("One")
        running.send("Two")
        running.close()
        collected(running)

        assertTrue(""""content":"Before"""" in requests[0] && """"content":"One"""" in requests[0], requests[0])
        assertTrue(""""content":"Second answer"""" in requests[1] && """"content":"Two"""" in requests[1], requests[1])
    }

    @Test fun withNoModelTheRunnersFirstIsUsed() {
        server.createContext("/v1/models") { exchange ->
            val answer = """{"object":"list","data":[{"id":"small:latest"},{"id":"large:latest"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        replying(chunk("Hi"), "[DONE]")
        val running = session(model = null)
        running.send("Hi")
        running.close()
        val (_, events) = collected(running)

        assertTrue(""""model":"small:latest"""" in requests.single())
        val info = events.filterIsInstance<SessionEvent.Parsed>().firstNotNullOf { it.output as? ParseOutput.Session }
        assertEquals("small:latest", info.info.model)
    }

    @Test fun nothingListeningFailsTheTurnAndSaysWhy() {
        val running = session(base = "http://127.0.0.1:9")
        running.send("Hi")
        running.close()
        val (entries, _) = collected(running)

        assertEquals(EntryState.ENTRY_STATE_FAILED, turnEnd(entries).state)
        val notice = entries.firstNotNullOf { (it.body!!.kind as? EntryKind.Note)?.notice }
        assertTrue("Nothing answered" in notice.text, notice.text)
    }

    @Test fun aRefusalShowsWhatTheRunnerSaid() {
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            val answer = """{"error":{"message":"model 'm' not found"}}""".toByteArray()
            exchange.sendResponseHeaders(404, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        val running = session()
        running.send("Hi")
        running.close()
        val (entries, _) = collected(running)

        val notice = entries.firstNotNullOf { (it.body!!.kind as? EntryKind.Note)?.notice }
        assertEquals("model 'm' not found", notice.text)
    }

    @Test fun stoppingKeepsWhatWasWrittenAndTheNextMessageStillWorks() {
        replying(chunk("Part of it"), hold = true)
        val running = session()
        running.send("Hi")
        val reached = CountDownLatch(1)
        val all = CopyOnWriteArrayList<SessionEvent>()
        val reader = kotlin.concurrent.thread {
            runBlocking {
                running.events.collect {
                    all += it
                    if (it is SessionEvent.Parsed && it.output is ParseOutput.Writing) reached.countDown()
                }
            }
        }
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        running.interrupt()
        running.close()
        reader.join(5_000)
        assertFalse(reader.isAlive)
        val log = EntryLog()
        all.filterIsInstance<SessionEvent.Parsed>().forEach { log.apply(it.output) }

        assertEquals(TextRole.TEXT_ROLE_REPLY to "Part of it", replyText(log.entries))
        assertEquals(EntryState.ENTRY_STATE_INTERRUPTED, turnEnd(log.entries).state)
        assertNotNull(log.entries.firstOrNull { it.body!!.kind is EntryKind.Command })
    }
}

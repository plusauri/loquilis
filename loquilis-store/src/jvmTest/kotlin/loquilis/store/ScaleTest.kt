package loquilis.store

import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue
import loquilis.core.ContentFactory
import loquilis.core.inlineContent
import loquilis.model.AgentText
import loquilis.model.Author
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.TextRole
import loquilis.model.ToolCall
import loquilis.model.ToolKind
import loquilis.model.ToolResult
import loquilis.model.UserMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A long synthetic conversation, on the 256 MB test heap. By default it is small enough for every run.
 * `LOQUILIS_SCALE=1` runs the design's full size: 100k entries and 1 GB of output, which takes minutes.
 * Word-salad output keeps compression and the search index from having it easy.
 */
class ScaleTest {
    @TempDir
    lateinit var dir: Path

    private val full = System.getenv("LOQUILIS_SCALE") == "1"
    private val entryCount = if (full) 100_000 else 4_000
    private val resultBytes = if (full) 22_000 else 6_000

    private val random = Random(7)
    private val vocabulary = List(20_000) { i -> "w" + i.toString(36) + "x".repeat(i % 7) }

    private fun output(bytes: Int): String = buildString(bytes + 16) {
        while (length < bytes) append(vocabulary[random.nextInt(vocabulary.size)]).append(if (length % 90 < 8) '\n' else ' ')
    }

    private fun build(store: Store) {
        store.putConversation(conversation())
        val contents = ContentFactory(store)
        var seq = 0L
        var parent = ""
        fun add(author: Author, body: EntryBody) {
            val entry = Entry(id = "e${++seq}", conversation_id = CONVERSATION, seq = seq, parent_id = parent, at = AT.plusSeconds(seq), author = author, state = EntryState.ENTRY_STATE_COMPLETE, body = body)
            store.appendEntry(entry)
            parent = entry.id
        }
        while (seq < entryCount) {
            store.transaction {
                add(Author.AUTHOR_USER, EntryBody(user_message = UserMessage(inlineContent("question $seq about ${vocabulary[seq.toInt() % vocabulary.size]}"))))
                repeat(9) {
                    add(Author.AUTHOR_AGENT, EntryBody(tool_call = ToolCall(call_id = "c$seq", tool = "Bash", kind = ToolKind.TOOL_KIND_SHELL, input = inlineContent("run step $seq"))))
                    add(Author.AUTHOR_TOOL, EntryBody(tool_result = ToolResult(call_id = "c${seq - 1}", output = contents.text(output(resultBytes)))))
                }
                add(Author.AUTHOR_AGENT, EntryBody(agent_text = AgentText(inlineContent("reply $seq"), TextRole.TEXT_ROLE_REPLY)))
            }
        }
    }

    private fun <T> within(budget: Duration, what: String, block: () -> T): T {
        val (value, took) = measureTimedValue(block)
        println("scale: $what took $took (budget $budget)")
        assertTrue(took <= budget, "$what took $took, over its budget of $budget")
        return value
    }

    @Test
    fun aLongConversationStaysFastAndSmallInMemory() {
        val path = dir.resolve("library.sqlite")
        val (_, built) = measureTimedValue { SqliteStore.open(path).use(::build) }
        val onDisk = Files.walk(dir).use { paths -> paths.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
        println("scale: wrote $entryCount entries in $built, ${onDisk / (1024 * 1024)} MB on disk")

        val store = within(1000.milliseconds, "opening") { SqliteStore.open(path) }
        store.use {
            val turnCount = within(100.milliseconds, "counting turns") { store.turnCount(CONVERSATION, MAIN) }
            val middle = turnCount / 2
            val page = within(100.milliseconds, "a page of turns from the middle") { store.turns(CONVERSATION, MAIN, middle, 50) }
            assertEquals(middle + 1, page.first().ordinal)
            val seq = page.first().first_seq
            val entries = within(100.milliseconds, "a page of entries from the middle") { store.entries(CONVERSATION, seq until seq + 100) }
            assertEquals(100, entries.size)
            within(100.milliseconds, "a page of heads") { store.heads(CONVERSATION, seq until seq + 1000) }
            val blob = entries.firstNotNullOf { it.body?.tool_result?.output?.blob }
            within(100.milliseconds, "one body") { store.blob(blob.sha256) }

            val word = vocabulary[1234]
            val hits = within(2000.milliseconds, "searching a common word") { store.search(word, limit = 50) }
            assertTrue(hits.isNotEmpty())
            val rare = "question ${page.first().first_seq - 1}"
            val phrase = within(1000.milliseconds, "searching a phrase") { store.search("\"$rare\"", CONVERSATION) }
            assertEquals(1, phrase.size)
        }
    }
}

package loquilis.store

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import loquilis.core.ContentFactory
import loquilis.core.inlineContent
import loquilis.model.AgentText
import loquilis.model.Author
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.RawRange
import loquilis.model.TextRole
import loquilis.model.Thinking
import loquilis.model.UserMessage
import loquilis.model.Visibility
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExportTest {
    @TempDir
    lateinit var dir: Path

    private fun files(folder: Path): Map<String, ByteArray> = Files.walk(folder).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { folder.relativize(it).toString() to it.readBytes() }
    }

    @Test
    fun aRecordedTakeRoundTripsToIdenticalFiles() {
        val first = dir.resolve("first")
        val before = recorded(dir.resolve("a.sqlite")).use { store ->
            exportConversation(store, CONVERSATION, first, AT, includePrivate = true)
            store.allEntries() to store.conversation(CONVERSATION)
        }
        val second = dir.resolve("second")
        SqliteStore.open(dir.resolve("b.sqlite")).use { store ->
            assertEquals(CONVERSATION, importConversation(store, first))
            assertEquals(before.first, store.allEntries())
            assertEquals(before.second, store.conversation(CONVERSATION))
            assertEquals(7, store.turnCount(CONVERSATION, MAIN))
            assertTrue(store.search("monte carlo").isNotEmpty(), "an import is searchable")
            exportConversation(store, CONVERSATION, second, AT, includePrivate = true)
        }
        val a = files(first)
        val b = files(second)
        assertEquals(a.keys, b.keys)
        a.forEach { (name, bytes) -> assertContentEquals(bytes, b[name], name) }
    }

    @Test
    fun blobsTravelWithTheExport() {
        val out = dir.resolve("out")
        recorded(dir.resolve("a.sqlite")).use { store ->
            exportConversation(store, CONVERSATION, out, AT)
            val refs = store.allEntries().flatMap(::blobsOf).map { it.sha256 }.toSet()
            assertTrue(refs.isNotEmpty(), "the take has stored content")
            assertEquals(refs, Files.list(out.resolve("blobs")).use { it.map { p -> p.name }.toList() }.toSet())
        }
    }

    @Test
    fun privateEntriesAndTheirRawLinesLeaveNoTrace() {
        val out = dir.resolve("out")
        SqliteStore.open(dir.resolve("a.sqlite")).use { store ->
            store.putConversation(conversation())
            val contents = ContentFactory(store, inlineLimitBytes = 8)
            fun add(id: String, seq: Long, parent: String, body: EntryBody, raw: Long?, visibility: Visibility = Visibility.VISIBILITY_NORMAL) =
                store.appendEntry(Entry(id = id, conversation_id = CONVERSATION, seq = seq, parent_id = parent, author = Author.AUTHOR_AGENT, visibility = visibility, raw = raw?.let { RawRange(it, it) }, body = body))

            store.appendRaw(CONVERSATION, """{"thinking":"hiddenword"}""")
            store.appendRaw(CONVERSATION, """{"text":"visible"}""")
            add("u", 1, "", EntryBody(user_message = UserMessage(inlineContent("hi"))), null)
            add("t", 2, "u", EntryBody(thinking = Thinking(contents.text("hiddenword in a long enough blob"))), 1, Visibility.VISIBILITY_PRIVATE)
            add("a", 3, "t", EntryBody(agent_text = AgentText(inlineContent("visible"), TextRole.TEXT_ROLE_REPLY)), 2)
            exportConversation(store, CONVERSATION, out, AT)
        }

        val entries = Files.readAllLines(out.resolve("entries.jsonl"))
        val raw = GZIPInputStream(Files.newInputStream(out.resolve("raw.jsonl.gz"))).bufferedReader().readLines()
        assertEquals(2, entries.size)
        assertFalse(entries.any { "hiddenword" in it })
        assertTrue(""""parentId":"u"""" in entries[1], entries[1])
        assertEquals(listOf("", """{"text":"visible"}"""), raw, "line numbers hold, with the hidden line blank")
        assertEquals(0, Files.list(out.resolve("blobs")).use { it.count() })

        SqliteStore.open(dir.resolve("b.sqlite")).use { store ->
            importConversation(store, out)
            assertEquals("visible", store.raw(CONVERSATION, 2L..2L).single().let { "visible".takeIf { _ -> "visible" in it } })
            assertEquals("u", store.entry("a")?.parent_id)
        }
    }

    @Test
    fun importingAConversationTwiceIsRefused() {
        val out = dir.resolve("out")
        recorded(dir.resolve("a.sqlite")).use { store ->
            exportConversation(store, CONVERSATION, out, AT)
            assertFailsWith<IllegalArgumentException> { importConversation(store, out) }
        }
    }
}

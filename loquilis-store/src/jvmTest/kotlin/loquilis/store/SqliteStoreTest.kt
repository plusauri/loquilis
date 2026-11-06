package loquilis.store

import com.squareup.wire.WireField
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import loquilis.core.ContentFactory
import loquilis.core.inlineContent
import loquilis.model.AgentBinding
import loquilis.model.AgentText
import loquilis.model.Author
import loquilis.model.Branch
import loquilis.model.Conversation
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.Extension
import loquilis.model.Outcome
import loquilis.model.OutcomeStatus
import loquilis.model.ParserRef
import loquilis.model.RawRange
import loquilis.model.TextRole
import loquilis.model.Thinking
import loquilis.model.Transport
import loquilis.model.TurnSummary
import loquilis.model.Usage
import loquilis.model.UserMessage
import loquilis.model.Visibility
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteStoreTest {
    @TempDir
    lateinit var dir: Path

    private val path get() = dir.resolve("library.sqlite")

    private fun plain(store: Store, seq: Long, text: String, id: String = "e$seq", visibility: Visibility = Visibility.VISIBILITY_NORMAL, thinking: Boolean = false) {
        val body = if (thinking) EntryBody(thinking = Thinking(inlineContent(text)))
        else EntryBody(agent_text = AgentText(inlineContent(text), TextRole.TEXT_ROLE_NARRATION))
        store.appendEntry(Entry(id = id, conversation_id = CONVERSATION, seq = seq, at = AT, author = Author.AUTHOR_AGENT, state = EntryState.ENTRY_STATE_COMPLETE, visibility = visibility, body = body))
    }

    @Test
    fun aRecordedTakeReadsBackAsWritten() {
        recorded(path).use { store ->
            val entries = store.allEntries()
            assertEquals(131, entries.size)
            assertEquals((1L..131L).toList(), entries.map { it.seq })
            assertEquals(entries.first(), store.entry(entries.first().id))
            assertEquals(Take.lines, store.raw(CONVERSATION, 1L..store.rawCount(CONVERSATION)))
            assertEquals(7, store.turnCount(CONVERSATION, MAIN))
        }
    }

    @Test
    fun entriesSurviveClosingAndReopening() {
        val before = recorded(path).use { it.allEntries() }
        SqliteStore.open(path).use { assertEquals(before, it.allEntries()) }
    }

    @Test
    fun headsReadColumnsWithoutBodies() {
        recorded(path).use { store ->
            val heads = store.heads(CONVERSATION, 1L..5L)
            assertEquals(5, heads.size)
            assertEquals("User", heads.first().kind)
            assertTrue(heads.all { it.entry.body == null })
        }
    }

    @Test
    fun turnsKeptOnEveryWriteMatchARebuildFromEntries() {
        recorded(path).use { store ->
            val kept = store.turns(CONVERSATION, MAIN, 0, 100)
            assertEquals(7, kept.size)
            assertTrue(kept.all { it.reply_head.isNotEmpty() && it.result_entry_id.isNotEmpty() && it.usage != null && it.duration != null })
            store.rebuildTurns(CONVERSATION)
            assertEquals(kept, store.turns(CONVERSATION, MAIN, 0, 100))
        }
    }

    @Test
    fun turnsPage() {
        recorded(path).use { store ->
            assertEquals(listOf(3, 4), store.turns(CONVERSATION, MAIN, 2, 2).map { it.ordinal })
        }
    }

    @Test
    fun theConversationTotalsAddUpFromItsTurns() {
        recorded(path).use { store ->
            val conversation = assertNotNull(store.conversation(CONVERSATION))
            val turns = store.turns(CONVERSATION, MAIN, 0, 100)
            assertEquals(turns.sumOf { it.usage?.cost_micro_usd ?: 0 }, conversation.total_usage?.cost_micro_usd)
            assertEquals(turns.sumOf { it.usage?.output_tokens ?: 0 }, conversation.total_usage?.output_tokens)
            assertNotNull(conversation.updated_at)
        }
    }

    @Test
    fun theRecorderFillsInWhatTheAgentReported() {
        recorded(path).use { store ->
            val conversation = assertNotNull(store.conversation(CONVERSATION))
            assertTrue(conversation.agent?.cli_version.orEmpty().isNotEmpty())
            assertEquals(ParserRef("claude-stream-json", 3), conversation.parser)
            assertEquals("claude", conversation.agent?.cli)
        }
    }

    @Test
    fun aFailedTransactionLeavesNothing() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            assertFailsWith<IllegalStateException> {
                store.transaction {
                    plain(store, 1, "gone")
                    store.appendRaw(CONVERSATION, "gone")
                    error("boom")
                }
            }
            assertEquals(0, store.lastSeq(CONVERSATION))
            assertEquals(0, store.rawCount(CONVERSATION))
            assertTrue(store.search("gone").isEmpty())
        }
    }

    @Test
    fun theSameBlobIsStoredOnce() {
        SqliteStore.open(path).use { store ->
            val first = store.put("same bytes".encodeUtf8(), "text/plain")
            val second = store.put("same bytes".encodeUtf8(), "text/plain")
            assertEquals(first, second)
            assertEquals("same bytes".encodeUtf8(), store.blob(first.sha256))
        }
        assertEquals(1, count("SELECT count(*) FROM blob"))
    }

    @Test
    fun aLargeBlobIsAFileBesideTheDatabase() {
        SqliteStore.open(path, fileBlobBytes = 1000).use { store ->
            val big = "x".repeat(5000).encodeUtf8()
            val ref = store.put(big, "text/plain")
            assertEquals(big, store.blob(ref.sha256))
            assertTrue(Files.exists(dir.resolve("library.sqlite.blobs/${ref.sha256.take(2)}/${ref.sha256}")))
        }
        assertEquals(1, count("SELECT count(*) FROM blob WHERE bytes IS NULL AND file IS NOT NULL"))
    }

    @Test
    fun incompressibleBytesAreKeptAsTheyAre() {
        SqliteStore.open(path).use { store ->
            val noise = kotlin.random.Random(1).nextBytes(500)
            val bytes = okio.ByteString.of(*noise)
            assertEquals(bytes, store.blob(store.put(bytes, "application/octet-stream").sha256))
        }
        assertEquals(1, count("SELECT count(*) FROM blob WHERE compressed = 0"))
    }

    @Test
    fun rawLinesFoldIntoBlocksAndStayInOrder() {
        SqliteStore.open(path, rawBlockBytes = 500).use { store ->
            store.putConversation(conversation())
            val lines = (1..200).map { """{"n":$it,"text":"${"y".repeat(40)}"}""" }
            lines.forEach { store.appendRaw(CONVERSATION, it) }
            assertEquals(lines, store.raw(CONVERSATION, 1L..200L))
            assertEquals(lines.subList(49, 151), store.raw(CONVERSATION, 50L..151L))
            assertEquals(200, store.rawCount(CONVERSATION))
        }
        assertTrue(count("SELECT count(*) FROM raw_event WHERE encoding = 1") >= 2)
    }

    @Test
    fun anEmptyRawLineSurvivesInsideABlock() {
        SqliteStore.open(path, rawBlockBytes = 5).use { store ->
            store.putConversation(conversation())
            val lines = listOf("first line", "", "third line")
            lines.forEach { store.appendRaw(CONVERSATION, it) }
            assertEquals(lines, store.raw(CONVERSATION, 1L..3L))
        }
    }

    @Test
    fun searchFindsWordsAcrossTheLibraryAndMarksThem() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            store.putConversation(conversation("other"))
            plain(store, 1, "The Monte Carlo run finished")
            store.appendEntry(Entry(id = "o1", conversation_id = "other", seq = 1, body = EntryBody(agent_text = AgentText(inlineContent("monte carlo again")))))
            plain(store, 2, "nothing relevant")

            val hits = store.search("monte carlo")
            assertEquals(setOf("e1", "o1"), hits.map { it.entry_id }.toSet())
            assertEquals(listOf("e1"), store.search("monte", CONVERSATION).map { it.entry_id })
            val snippet = store.search("carlo", CONVERSATION).single().snippet
            assertTrue("${Store.MATCH_OPEN}Carlo${Store.MATCH_CLOSE}" in snippet, snippet)
        }
    }

    @Test
    fun aQuotedPhraseMustMatchInOrder() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            plain(store, 1, "take the check early")
            plain(store, 2, "early on, take a check")
            assertEquals(listOf("e1"), store.search("\"check early\"").map { it.entry_id })
        }
    }

    @Test
    fun whatAPersonTypesIsNeverReadAsSearchSyntax() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            plain(store, 1, "see foo-bar for detail")
            for (query in listOf("foo-bar", "AND", "(", "\"", "a:b", "*", "NEAR(", "foo\"bar")) store.search(query)
            assertEquals(listOf("e1"), store.search("foo-bar").map { it.entry_id })
            assertTrue(store.search("   ").isEmpty())
        }
    }

    @Test
    fun searchReadsTheFullTextOfStoredBlobs() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            val contents = ContentFactory(store, inlineLimitBytes = 10)
            val text = "filler ".repeat(5000) + "needleword at the end"
            val body = EntryBody(agent_text = AgentText(contents.text(text)))
            store.appendEntry(Entry(id = "e1", conversation_id = CONVERSATION, seq = 1, body = body))
            val snippet = store.search("needleword").single().snippet
            assertTrue("needleword" in snippet, snippet)
        }
    }

    @Test
    fun privateEntriesStayOutOfSearch() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            plain(store, 1, "secretword", visibility = Visibility.VISIBILITY_PRIVATE, thinking = true)
            assertTrue(store.search("secretword").isEmpty())
        }
    }

    @Test
    fun anUpdatedEntryIsFoundByItsNewTextOnly() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            plain(store, 1, "oldword")
            val entry = assertNotNull(store.entry("e1"))
            store.updateEntry(entry.copy(body = EntryBody(agent_text = AgentText(inlineContent("newword")))))
            assertTrue(store.search("oldword").isEmpty())
            assertEquals(1, store.search("newword").size)
        }
    }

    @Test
    fun updatingNothingFails() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            assertFailsWith<IllegalStateException> { store.updateEntry(Entry(id = "nope", conversation_id = CONVERSATION, seq = 1)) }
        }
    }

    @Test
    fun aRecordedTakeIsSearchable() {
        recorded(path).use { store ->
            assertTrue(store.search("monte carlo", CONVERSATION).isNotEmpty())
            assertTrue(store.search("forecast").isNotEmpty(), "tool input and output are indexed")
        }
    }

    @Test
    fun readsRunWhileAnotherThreadWrites() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            val done = AtomicBoolean(false)
            val failures = mutableListOf<Throwable>()
            val writer = thread {
                runCatching { (1L..2000L).forEach { plain(store, it, "word$it") } }.onFailure { synchronized(failures) { failures += it } }
                done.set(true)
            }
            var seen = 0L
            while (!done.get()) {
                val last = store.lastSeq(CONVERSATION)
                assertTrue(last >= seen, "the log never goes backward")
                seen = last
                store.entries(CONVERSATION, (last - 5).coerceAtLeast(1)..last)
            }
            writer.join()
            assertEquals(emptyList(), failures)
            assertEquals(2000, store.lastSeq(CONVERSATION))
        }
    }

    @Test
    fun everyConversationFieldRoundTrips() {
        val full = Conversation(
            id = "c", title = "t", created_at = AT, updated_at = AT.plusNanos(7),
            agent = AgentBinding("claude", "2.1", "m", "rm", Transport.TRANSPORT_LIVE, "s"),
            workspace_path = "/w", outcome = Outcome(OutcomeStatus.OUTCOME_STATUS_COMPLETED, "r"),
            total_usage = Usage(1, 2, 3, 4, 5), main_branch_id = "b", parser = ParserRef("a", 2),
            extensions = listOf(Extension("type.example/A", "v".encodeUtf8())),
        )
        SqliteStore.open(path).use { store ->
            store.putConversation(full)
            assertEquals(full, store.conversation("c"))
        }
    }

    @Test
    fun everyBranchAndEntryFieldRoundTrips() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            val branch = Branch("b", CONVERSATION, "fork", "h", "f", AT, listOf(Extension("t", "v".encodeUtf8())))
            store.putBranch(branch)
            assertEquals(listOf(branch), store.branches(CONVERSATION))
            val entry = Entry(
                id = "e", conversation_id = CONVERSATION, seq = 1, parent_id = "p", at = AT.plusNanos(3),
                author = Author.AUTHOR_TOOL, state = EntryState.ENTRY_STATE_FAILED, visibility = Visibility.VISIBILITY_PRIVATE,
                raw = RawRange(4, 5), body = EntryBody(user_message = UserMessage(inlineContent("x"))),
                extensions = listOf(Extension("t", "v".encodeUtf8())),
            )
            store.appendEntry(entry)
            assertEquals(entry, store.entry("e"))
        }
    }

    @Test
    fun anUnfinishedTurnReadsBackWithNoUsage() {
        SqliteStore.open(path).use { store ->
            store.putConversation(conversation())
            store.appendEntry(Entry(id = "u", conversation_id = CONVERSATION, seq = 1, body = EntryBody(user_message = UserMessage(inlineContent("hi")))))
            val turn = store.turns(CONVERSATION, MAIN, 0, 1).single()
            assertNull(turn.usage)
            assertEquals("", turn.result_entry_id)
        }
    }

    @Test
    fun aLibraryFromANewerVersionIsRefused() {
        SqliteStore.open(path).close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { it.createStatement().execute("PRAGMA user_version = 99") }
        assertFailsWith<IllegalStateException> { SqliteStore.open(path) }
    }

    /** A proto field with no column fails here, so the schema cannot quietly fall behind the model. */
    @Test
    fun everyProtoFieldHasAColumn() {
        SqliteStore.open(path).close()
        fun columns(table: String) = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().executeQuery("PRAGMA table_info($table)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
        }
        fun check(type: KClass<*>, table: String, bodyless: Set<String> = emptySet()) {
            val cols = columns(table)
            for (field in type.java.declaredFields.filter { it.isAnnotationPresent(WireField::class.java) }) {
                val name = field.name.removeSuffix("_")
                assertTrue(name in bodyless || name in cols || cols.any { it.startsWith("${name}_") }, "$table has no column for ${type.simpleName}.$name")
            }
        }
        check(Conversation::class, "conversation")
        check(Entry::class, "entry")
        check(Branch::class, "branch")
        check(TurnSummary::class, "turn")
    }

    private fun count(sql: String): Int = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().executeQuery(sql).use { it.next(); it.getInt(1) }
    }

    @Test
    fun anEntryFindsItsTurn() {
        recorded(dir.resolve("turn-of.sqlite")).use { store ->
            val branch = store.conversation(CONVERSATION)!!.main_branch_id
            for (turn in store.turns(CONVERSATION, branch, 0, 100)) {
                assertEquals(turn.ordinal, store.turnOf(CONVERSATION, branch, turn.first_seq))
                assertEquals(turn.ordinal, store.turnOf(CONVERSATION, branch, turn.last_seq))
            }
            assertEquals(0, store.turnOf(CONVERSATION, branch, 0))
        }
    }

    @Test
    fun aTransactionReadsTheConversationItWrote() {
        SqliteStore.open(dir.resolve("in-transaction.sqlite")).use { store ->
            store.transaction {
                store.putConversation(conversation())
                assertEquals("A take", store.conversation(CONVERSATION)?.title)
            }
        }
    }

    @Test
    fun recordingATakeInOneTransactionMatchesRecordingItLineByLine() {
        val path = dir.resolve("import.sqlite")
        recorded(dir.resolve("lines.sqlite")).use { lines ->
            SqliteStore.open(path).use { store ->
                fun open(name: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/claude-take/$name")) { name }
                val id = recordTake(store, open("conversation.json"), open("raw.jsonl.gz"))
                val imported = store.conversation(id)!!
                val direct = lines.conversation(CONVERSATION)!!
                assertEquals(direct.total_usage, imported.total_usage)
                assertEquals(direct.agent?.reported_model, imported.agent?.reported_model)
                assertEquals(lines.lastSeq(CONVERSATION), store.lastSeq(id))
                assertEquals(lines.rawCount(CONVERSATION), store.rawCount(id))
            }
        }
    }
}

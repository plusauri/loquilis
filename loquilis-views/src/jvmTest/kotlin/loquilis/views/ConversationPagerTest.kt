package loquilis.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import loquilis.store.SqliteStore

class ConversationPagerTest {
    private val pager by lazy { ConversationPager(Fixture.store, Fixture.conversationId) }
    private val turns by lazy { (pager.firstOrdinal..pager.lastOrdinal).map(pager::load) }

    @Test
    fun theTurnsTogetherHoldEveryEntryOnceInOrder() {
        assertEquals(Fixture.entries.map { it.id }, turns.flatMap { it.entries }.map { it.id })
    }

    @Test
    fun theRecordedTakeHasOneUserRowAndOneFooterForEachTurn() {
        val rows = turns.flatMap { it.messages }
        assertEquals(7, rows.count { it is MessageRow.Said })
        assertEquals(7, rows.count { it is MessageRow.Footer })
        assertTrue(rows.count { it is MessageRow.Reply } >= 7)
    }

    @Test
    fun rowsCarryTheirTurnsOrdinal() {
        turns.forEach { turn -> assertEquals(setOf(turn.ordinal), turn.messages.map { it.turn }.toSet()) }
    }

    @Test
    fun readingATurnAtATimeLaysOutTheSameAsReadingTheWholeConversation() {
        val whole = messagesOf(Fixture.entries).filter { it !is MessageRow.Work }
        assertEquals(whole.map { it.key }.sorted(), turns.flatMap { t -> t.messages.filter { it !is MessageRow.Work } }.map { it.key }.sorted())
        assertEquals(treeOf(Fixture.entries), turns.flatMap { it.tree })
    }

    @Test
    fun aTurnIsReadOnceAndThenHeld() {
        assertNull(ConversationPager(Fixture.store, Fixture.conversationId).cached(1))
        assertSame(pager.load(1), pager.cached(1))
    }

    @Test
    fun theOverviewComesFromTheStore() {
        assertEquals(7, pager.turnCount)
        assertEquals(Fixture.entries.size.toLong(), pager.entryCount)
        assertTrue(pager.rawCount > 0)
        assertTrue(pager.conversation.total_usage!!.cost_micro_usd > 0)
        assertTrue(pager.conversation.agent!!.reported_model.isNotBlank())
    }

    @Test
    fun everyParsedEntryInspectsWithItsRawLines() {
        Fixture.entries.filter { it.raw != null }.forEach { assertTrue(assertNotNull(pager.inspect(it.id)).raw.isNotEmpty(), it.id) }
    }

    @Test
    fun aToolCallInspectsWithItsResult() {
        val call = Fixture.entries.first { it.body?.tool_call != null }
        assertEquals(listOf("Input", "Result"), assertNotNull(pager.inspect(call.id)).sections.map { it.label })
        val result = Fixture.entries.first { it.body?.tool_result?.call_id == call.body?.tool_call?.call_id }
        val lines = listOf(call, result).sumOf { it.raw!!.last_seq - it.raw!!.first_seq + 1 }
        assertEquals(lines.toInt(), assertNotNull(pager.inspect(call.id)).raw.size, "the raw lines behind both the call and its result")
    }

    @Test
    fun aChildInspectsWithItsParent() {
        val child = Fixture.entries[2]
        assertEquals(child.parent_id, assertNotNull(pager.inspect(child.id)).parent?.id)
    }

    @Test
    fun entriesBeforeTheFirstMessageAreTurnZero() = SqliteStore.open(tempDir().resolve("before.sqlite")).use { store ->
        val pager = ConversationPager(store, store.writeConversation("Before", turns = 2, before = 3))
        assertEquals(0, pager.firstOrdinal)
        assertEquals(3, pager.load(0).entries.size)
        assertEquals(setOf(0), pager.load(0).messages.map { it.turn }.toSet())
        assertEquals(1, pager.load(1).messages.first().turn)
    }

    @Test
    fun aConversationWithNoMessagesIsAllTurnZero() = SqliteStore.open(tempDir().resolve("none.sqlite")).use { store ->
        val pager = ConversationPager(store, store.writeConversation("None", turns = 0, before = 2))
        assertEquals(0..0, pager.firstOrdinal..pager.lastOrdinal)
        assertEquals(2, pager.load(0).entries.size)
    }

    @Test
    fun anEmptyConversationHasNoTurnsToList() = SqliteStore.open(tempDir().resolve("empty.sqlite")).use { store ->
        val pager = ConversationPager(store, store.writeConversation("Empty", turns = 0))
        assertTrue((pager.firstOrdinal..pager.lastOrdinal).isEmpty())
    }

    @Test
    fun whatIsHeldStaysBoundedHoweverManyTurnsAreRead() = SqliteStore.open(tempDir().resolve("long.sqlite")).use { store ->
        val pager = ConversationPager(store, store.writeConversation("Long", turns = 1000))
        for (ordinal in 1..1000) pager.load(ordinal)
        assertEquals(128, pager.heldTurns)
        assertEquals("Question number 777", pager.summary(777)?.user_head)
    }
}

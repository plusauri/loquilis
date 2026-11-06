package loquilis.views

import loquilis.core.isStored
import loquilis.model.Content
import loquilis.model.Conversation
import loquilis.model.Entry
import loquilis.model.TurnSummary
import loquilis.store.Store
import loquilis.store.text

/** How many turns stay decoded. Past this the least recently shown are dropped and read again if needed. */
private const val TURNS_HELD = 128

/** How many turn summaries are read at a time. */
private const val SUMMARY_PAGE = 256

/** A blob larger than this is not read just to be shown: the inspector cuts far below it anyway. */
private const val LARGEST_SHOWN_BLOB = 8L * 1024 * 1024

/** One turn read from the store, laid out for every view. */
class LoadedTurn(val ordinal: Int, val entries: List<Entry>, private val texts: Map<String, String>) {
    val messages: List<MessageRow> = messagesOf(entries, firstTurn = (ordinal - 1).coerceAtLeast(0))
    val tree: List<TreeRow> = treeOf(entries)
    val treeParents: Set<String> = treeParents(tree)

    private val resultsByCall: Map<String, Entry> =
        entries.mapNotNull { e -> e.body?.tool_result?.let { it.call_id to e } }.toMap()

    /** The whole text of a message or reply on this turn. */
    fun text(entry: Entry): String = texts[entry.id].orEmpty()

    fun resultOf(entry: Entry): Entry? = entry.body?.tool_call?.let { resultsByCall[it.call_id] }
}

/** One part of an entry the inspector shows. */
class Section(val label: String, val content: Content?, val text: String)

/** Everything the inspector shows for one entry. */
class Inspection(val entry: Entry, val parent: Entry?, val sections: List<Section>, val raw: List<String>)

/**
 * One conversation as the views read it: a small header, then turns on demand. Summaries come in pages,
 * and a decoded turn stays only while it is among the recently shown, so what is held does not grow with
 * the conversation. A single turn is read whole. Calls may come from any thread.
 *
 * Turns are numbered by ordinal, as the store numbers them. Entries before the first user message, if
 * any, are ordinal 0, which has no summary.
 */
class ConversationPager(private val store: Store, val conversationId: String) {
    val conversation: Conversation = checkNotNull(store.conversation(conversationId)) { "no conversation $conversationId" }
    val turnCount: Int = store.turnCount(conversationId, conversation.main_branch_id)
    val entryCount: Long = store.lastSeq(conversationId)
    val rawCount: Long = store.rawCount(conversationId)

    private val summaries = object : LinkedHashMap<Int, List<TurnSummary>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, List<TurnSummary>>) = size > 16
    }
    private val turns = object : LinkedHashMap<Int, LoadedTurn>(TURNS_HELD, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, LoadedTurn>) = size > TURNS_HELD
    }

    /** The turn before which entries have no user message: 1 when the first turn opens the conversation. */
    val firstOrdinal: Int = when {
        turnCount > 0 -> if (summary(1)!!.first_seq > 1) 0 else 1
        entryCount > 0 -> 0
        else -> 1
    }
    val lastOrdinal: Int = turnCount
    val heldTurns: Int get() = synchronized(turns) { turns.size }

    /**
     * The same conversation as it stands now, for a view of one that is growing. Call off the main
     * thread. Turns before the last are finished, so their decoded rows carry over, and the newest
     * turn is read here so it never shows half drawn.
     */
    fun refreshed(): ConversationPager {
        val next = ConversationPager(store, conversationId)
        synchronized(turns) { next.turns.putAll(turns.filterKeys { it < lastOrdinal }) }
        if (next.entryCount > 0) next.load(next.lastOrdinal)
        return next
    }

    fun summary(ordinal: Int): TurnSummary? {
        if (ordinal !in 1..turnCount) return null
        val page = (ordinal - 1) / SUMMARY_PAGE
        val held = synchronized(summaries) { summaries[page] } ?: store
            .turns(conversationId, conversation.main_branch_id, page * SUMMARY_PAGE, SUMMARY_PAGE)
            .also { read -> synchronized(summaries) { summaries[page] = read } }
        return held[(ordinal - 1) % SUMMARY_PAGE]
    }

    /** The turn if it is already held. Never touches the disk, so it is safe while composing. */
    fun cached(ordinal: Int): LoadedTurn? = synchronized(turns) { turns[ordinal] }

    /** The turn, read from the disk if needed. */
    fun load(ordinal: Int): LoadedTurn {
        cached(ordinal)?.let { return it }
        val seqs = when {
            ordinal > 0 -> summary(ordinal)!!.let { it.first_seq..it.last_seq }
            turnCount > 0 -> 1 until summary(1)!!.first_seq
            else -> 1..entryCount
        }
        val entries = store.entries(conversationId, seqs)
        val texts = entries.mapNotNull { entry -> shownText(entry)?.let { entry.id to it } }.toMap()
        return LoadedTurn(ordinal, entries, texts).also { synchronized(turns) { turns[ordinal] = it } }
    }

    private fun shownText(entry: Entry): String? {
        val body = entry.body ?: return null
        return (body.user_message?.text ?: body.agent_text?.text)?.let(::boundedText)
    }

    private fun boundedText(content: Content): String {
        val size = content.blob?.size ?: 0
        return if (content.isStored && size > LARGEST_SHOWN_BLOB) "[${bytes(size)}: too large to show here]" else store.text(content)
    }

    fun turnOf(seq: Long): Int = store.turnOf(conversationId, conversation.main_branch_id, seq)

    /** Where the entry [id] sits, if it is in this conversation. */
    fun turnOf(id: String): Int? = store.entry(id)?.takeIf { it.conversation_id == conversationId }?.let { turnOf(it.seq) }

    fun inspect(id: String): Inspection? {
        val entry = store.entry(id) ?: return null
        val call = load(turnOf(entry.seq)).resultOf(entry)?.body?.tool_result
        val sections = listOfNotNull(
            bodyContent(entry)?.let { (label, content) -> section(label, content) },
            call?.let { section("Result", it.output) },
        )
        val raw = entry.raw?.let { store.raw(conversationId, it.first_seq..it.last_seq) }.orEmpty()
        return Inspection(entry, entry.parent_id.takeIf { it.isNotEmpty() }?.let(store::entry), sections, raw)
    }

    private fun section(label: String, content: Content?) =
        Section(label, content, content?.let(::boundedText).orEmpty())
}

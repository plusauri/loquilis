package loquilis.store

import com.squareup.wire.Instant
import loquilis.kit.BlobSink
import loquilis.model.Branch
import loquilis.model.Content
import loquilis.model.Conversation
import loquilis.model.Entry
import loquilis.model.SearchHit
import loquilis.model.TurnSummary
import okio.ByteString

/** An entry's header, read from columns with no body decoded. [Entry.body] is null on [entry]. */
data class EntryHead(val entry: Entry, val kind: String)

/**
 * A library of conversations on disk. Everything is paged, so nothing here holds a conversation in
 * memory. Reads may run while another thread writes, and writes go one at a time.
 */
interface Store : BlobSink, AutoCloseable {
    /** Runs [block] as one atomic write. Nests: only the outermost call commits. */
    fun <T> transaction(block: () -> T): T

    fun putConversation(conversation: Conversation)
    fun conversation(id: String): Conversation?
    fun conversations(limit: Int, offset: Int = 0): List<Conversation>

    fun putBranch(branch: Branch)
    fun branches(conversationId: String): List<Branch>

    /** Stores one raw agent line verbatim, and returns its number in the conversation's raw log. */
    fun appendRaw(conversationId: String, line: String): Long
    fun rawCount(conversationId: String): Long
    fun raw(conversationId: String, seqs: LongRange): List<String>

    fun appendEntry(entry: Entry)

    /** Replaces an entry already appended. Its id and seq do not change. */
    fun updateEntry(entry: Entry)

    fun entry(id: String): Entry?
    fun entries(conversationId: String, seqs: LongRange): List<Entry>
    fun heads(conversationId: String, seqs: LongRange): List<EntryHead>
    fun lastSeq(conversationId: String): Long

    fun turns(conversationId: String, branchId: String, offset: Int, limit: Int): List<TurnSummary>
    fun turnCount(conversationId: String, branchId: String): Int

    /** Recomputes the derived turn rows from entries. */
    fun rebuildTurns(conversationId: String)

    fun blob(sha256: String): ByteString?

    /**
     * Full-text search, best match first, across one conversation or the whole library. Each word
     * must match. A double-quoted part must match as a phrase. In [SearchHit.snippet], matches sit
     * between [MATCH_OPEN] and [MATCH_CLOSE].
     */
    fun search(query: String, conversationId: String? = null, limit: Int = 50): List<SearchHit>

    companion object {
        const val MATCH_OPEN = '\u0001'
        const val MATCH_CLOSE = '\u0002'
    }
}

/** The whole text of [content], from wherever it lives. */
fun Store.text(content: Content?): String =
    content?.inline_ ?: content?.blob?.let { blob(it.sha256)?.utf8() }.orEmpty()

/** Reads a range in pages, so walking a whole conversation never holds it all. */
inline fun Store.forEachEntry(conversationId: String, pageSize: Long = 1000, action: (Entry) -> Unit) {
    val last = lastSeq(conversationId)
    var first = 1L
    while (first <= last) {
        entries(conversationId, first until first + pageSize).forEach(action)
        first += pageSize
    }
}

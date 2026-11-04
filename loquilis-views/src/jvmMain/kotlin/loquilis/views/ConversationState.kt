package loquilis.views

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import loquilis.model.SearchHit
import loquilis.store.Store

enum class ConversationView(val label: String) { Messages("Messages"), Tree("Tree"), Search("Search") }

/** A search hit with what a row needs to say about it. */
class SearchRow(val hit: SearchHit, val kind: String, val conversationTitle: String)

/** Where search looks. */
enum class SearchScope(val label: String) { Library("All conversations"), Conversation("This conversation") }

/**
 * What the window remembers. The selection lives here, above every view, so it survives a switch. Open
 * turns hold their rows, so a list can lay them out as plain items without touching the disk.
 */
class ConversationState(val store: Store, conversationId: String) {
    var pager by mutableStateOf(ConversationPager(store, conversationId))
        private set
    var view by mutableStateOf(ConversationView.Messages)
        private set
    var selectedId by mutableStateOf<String?>(null)

    val openTurns = mutableStateMapOf<Int, LoadedTurn>()
    val openTreeTurns = mutableStateMapOf<Int, LoadedTurn>()
    val collapsed = mutableStateSetOf<String>()

    var query by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.Library)
    var hits by mutableStateOf<List<SearchRow>>(emptyList())
        private set

    /** The view a search hit lands in: the one search was opened from. */
    private var landing = ConversationView.Messages

    /** Switches view and opens whatever would hide the selected entry there. */
    fun show(next: ConversationView) {
        if (next == ConversationView.Search) {
            if (view != ConversationView.Search) landing = view
        } else {
            reveal(next)
        }
        view = next
    }

    private fun reveal(next: ConversationView) {
        val id = selectedId ?: return
        val ordinal = pager.turnOf(id) ?: return
        val turn = pager.load(ordinal)
        when (next) {
            ConversationView.Messages -> if (turn.messages.firstOrNull { holds(it, id) } is MessageRow.Step) openTurns[ordinal] = turn
            ConversationView.Tree -> {
                openTreeTurns[ordinal] = turn
                collapsed -= treeAncestors(turn.tree, id).toSet()
            }
            ConversationView.Search -> Unit
        }
    }

    fun toggleTurn(turn: LoadedTurn) {
        if (openTurns.remove(turn.ordinal) == null) openTurns[turn.ordinal] = turn
    }

    fun toggleTreeTurn(ordinal: Int) {
        if (openTreeTurns.remove(ordinal) == null) openTreeTurns[ordinal] = pager.load(ordinal)
    }

    fun toggleCollapsed(id: String) {
        if (!collapsed.remove(id)) collapsed += id
    }

    fun collapseAll() {
        openTreeTurns.clear()
        collapsed.clear()
    }

    /** The rows an open turn adds to the tree. */
    fun treeRows(turn: LoadedTurn): List<TreeRow> = visibleTree(turn.tree, collapsed)

    /** The list position of the selected entry in the Messages view. */
    fun messageIndex(id: String): Int? {
        val ordinal = pager.turnOf(id) ?: return null
        val inside = openTurns[ordinal]?.messages?.indexOfFirst { holds(it, id) } ?: 0
        return positionOf(ordinal, openTurns.mapValues { it.value.messages.size }) + inside.coerceAtLeast(0)
    }

    /** The list position of the selected entry in the Tree view. */
    fun treeIndex(id: String): Int? {
        val ordinal = pager.turnOf(id) ?: return null
        val inside = openTreeTurns[ordinal]?.let { turn -> treeRows(turn).indexOfFirst { it.entry.id == id } } ?: 0
        return positionOf(ordinal, openTreeTurns.mapValues { treeRows(it.value).size }) + inside.coerceAtLeast(0)
    }

    /** A closed turn is one list item and an open one is one item per row. */
    private fun positionOf(ordinal: Int, openSizes: Map<Int, Int>): Int =
        ordinal - pager.firstOrdinal + openSizes.filterKeys { it < ordinal }.values.sumOf { it - 1 }

    /** Reads hits from the store. Call off the main thread, then hand the result to [showHits]. */
    fun search(query: String, scope: SearchScope): List<SearchRow> {
        val within = pager.conversationId.takeIf { scope == SearchScope.Conversation }
        val titles = hashMapOf<String, String>()
        return store.search(query, within, SEARCH_LIMIT).mapNotNull { hit ->
            val entry = store.entry(hit.entry_id) ?: return@mapNotNull null
            val title = titles.getOrPut(hit.conversation_id) { store.conversation(hit.conversation_id)?.title.orEmpty() }
            SearchRow(hit, kindLabel(entry), title)
        }
    }

    fun showHits(rows: List<SearchRow>) {
        hits = rows
    }

    /** Goes to a hit, in its own conversation, in the view search was opened from. */
    fun openHit(row: SearchRow) {
        if (row.hit.conversation_id != pager.conversationId) open(row.hit.conversation_id)
        selectedId = row.hit.entry_id
        show(landing)
    }

    private fun open(conversationId: String) {
        pager = ConversationPager(store, conversationId)
        openTurns.clear()
        openTreeTurns.clear()
        collapsed.clear()
    }

    /** True when [row] shows the entry [id]. A step shows its call and its result as one. */
    fun holds(row: MessageRow, id: String): Boolean = when (row) {
        is MessageRow.Said -> row.entry.id == id
        is MessageRow.Step -> row.entry.id == id || row.result?.id == id
        is MessageRow.Reply -> row.entry.id == id
        is MessageRow.Note -> row.entry.id == id
        is MessageRow.Footer -> row.entry.id == id
        is MessageRow.Work -> false
    }

    private companion object {
        const val SEARCH_LIMIT = 100
    }
}

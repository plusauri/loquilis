package loquilis.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue

enum class ReviewView(val label: String) { Messages("Messages"), Tree("Tree") }

/** What the window remembers. The selection lives here, above both views, so it survives a switch. */
class ReviewState(val take: Take) {
    val messages: List<MessageRow> = messagesOf(take.entries)
    val tree: List<TreeRow> = treeOf(take.entries)
    val treeParents: Set<String> = treeParents(tree)

    var view by mutableStateOf(ReviewView.Messages)
        private set
    var selectedId by mutableStateOf<String?>(null)
    val openTurns = mutableStateSetOf<Int>()
    val collapsed = mutableStateSetOf<String>()

    val turnCount: Int = messages.count { it is MessageRow.Said }
    val totalCostMicroUsd: Long = messages.filterIsInstance<MessageRow.Footer>().sumOf { it.result.usage?.cost_micro_usd ?: 0L }

    /** Switches view and opens whatever would hide the selected entry there. */
    fun show(next: ReviewView) {
        view = next
        val id = selectedId ?: return
        when (next) {
            ReviewView.Messages -> messages.firstOrNull { holds(it, id) }
                ?.takeIf { it is MessageRow.Step }
                ?.let { openTurns += it.turn }
            ReviewView.Tree -> collapsed -= treeAncestors(tree, id).toSet()
        }
    }

    fun toggleTurn(turn: Int) {
        if (!openTurns.remove(turn)) openTurns += turn
    }

    fun toggleCollapsed(id: String) {
        if (!collapsed.remove(id)) collapsed += id
    }

    fun visibleMessages(): List<MessageRow> =
        messages.filter { it !is MessageRow.Step || it.turn in openTurns }

    fun visibleTreeRows(): List<TreeRow> = visibleTree(tree, collapsed)

    /** True when [row] shows the entry [id]. A step shows its call and its result as one. */
    fun holds(row: MessageRow, id: String): Boolean = when (row) {
        is MessageRow.Said -> row.entry.id == id
        is MessageRow.Step -> row.entry.id == id || row.result?.id == id
        is MessageRow.Reply -> row.entry.id == id
        is MessageRow.Note -> row.entry.id == id
        is MessageRow.Footer -> row.entry.id == id
        is MessageRow.Work -> false
    }
}

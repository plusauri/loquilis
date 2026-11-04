package loquilis.views

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import loquilis.model.Entry
import loquilis.model.EntryState
import loquilis.model.Visibility

/**
 * Each turn is one line until opened, drawn from its summary with no entry read, so the outline of a
 * conversation of any length is immediate.
 */
@Composable
fun TreeView(state: ConversationState, list: LazyListState) {
    val pager = state.pager

    LaunchedEffect(Unit) {
        val at = state.selectedId?.let(state::treeIndex) ?: return@LaunchedEffect
        list.scrollToItem(at)
    }

    Column(Modifier.fillMaxSize()) {
        CollapseAllBar(state)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 10.dp)) {
                turnItems(
                    first = pager.firstOrdinal,
                    last = pager.lastOrdinal,
                    open = state.openTreeTurns.keys,
                    closed = { ordinal ->
                        val summary = pager.summary(ordinal)
                        TreeLine(
                            depth = 0,
                            label = "You".takeIf { summary != null } ?: "Start",
                            text = summary?.user_head?.lineSequence()?.firstOrNull() ?: "Before the first message",
                            emphasized = true,
                            failed = false,
                            tag = turnTag(summary),
                            fold = TreeFold.Closed,
                            selected = summary?.user_entry_id == state.selectedId,
                            onClick = {
                                summary?.let { state.selectedId = it.user_entry_id }
                                state.toggleTreeTurn(ordinal)
                            },
                        )
                    },
                    opened = { ordinal ->
                        val turn = checkNotNull(state.openTreeTurns[ordinal])
                        itemsIndexed(state.treeRows(turn), key = { _, row -> row.entry.id }) { index, row ->
                            val id = row.entry.id
                            val folds = index == 0 || id in turn.treeParents
                            TreeLine(
                                depth = row.depth,
                                label = kindLabel(row.entry),
                                text = headline(row.entry),
                                emphasized = row.entry.body?.user_message != null,
                                failed = row.entry.state == EntryState.ENTRY_STATE_FAILED,
                                tag = tag(row.entry),
                                fold = when {
                                    !folds -> TreeFold.None
                                    id in state.collapsed -> TreeFold.Closed
                                    else -> TreeFold.Open
                                },
                                selected = id == state.selectedId,
                                onClick = {
                                    state.selectedId = id
                                    when {
                                        index == 0 -> state.toggleTreeTurn(ordinal)
                                        folds -> state.toggleCollapsed(id)
                                    }
                                },
                            )
                        }
                    },
                )
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Always there, so the list does not shift when the first turn opens. */
@Composable
private fun CollapseAllBar(state: ConversationState) {
    val palette = LocalPalette.current
    val anyOpen = state.openTreeTurns.isNotEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Text(
            "Collapse all",
            color = if (anyOpen) palette.accent else palette.muted,
            fontSize = 12.sp,
            modifier = Modifier.clickable(enabled = anyOpen, onClick = state::collapseAll).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private enum class TreeFold { None, Open, Closed }

@Composable
private fun TreeLine(
    depth: Int,
    label: String,
    text: String,
    emphasized: Boolean,
    failed: Boolean,
    tag: String?,
    fold: TreeFold,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (12 + 18 * depth).dp, end = 20.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when (fold) {
                TreeFold.None -> ""
                TreeFold.Open -> "▾"
                TreeFold.Closed -> "▸"
            },
            color = palette.muted,
            fontSize = 16.sp,
            modifier = Modifier.width(18.dp),
        )
        Text(
            label,
            color = if (failed) palette.failed else palette.accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
        )
        Text(
            text,
            color = if (failed) palette.failed else palette.ink,
            fontSize = 13.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        tag?.let { Text(it, color = palette.muted, fontSize = 11.sp) }
    }
}

/** Anything about an entry's header worth seeing without selecting it. */
private fun tag(entry: Entry): String? = listOfNotNull(
    "private".takeIf { entry.visibility == Visibility.VISIBILITY_PRIVATE },
    when (entry.state) {
        EntryState.ENTRY_STATE_STREAMING -> "open"
        EntryState.ENTRY_STATE_FAILED -> "failed"
        EntryState.ENTRY_STATE_INTERRUPTED -> "interrupted"
        else -> null
    },
).joinToString(" · ").ifEmpty { null }

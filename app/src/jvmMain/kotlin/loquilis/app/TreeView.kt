package loquilis.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

@Composable
fun TreeView(state: ReviewState, list: LazyListState) {
    val rows by remember(state) { derivedStateOf { state.visibleTreeRows() } }

    LaunchedEffect(Unit) {
        val id = state.selectedId ?: return@LaunchedEffect
        val at = rows.indexOfFirst { it.entry.id == id }
        if (at >= 0) list.scrollToItem(at)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(rows, key = { it.entry.id }) { row ->
                TreeLine(
                    row = row,
                    hasChildren = row.entry.id in state.treeParents,
                    collapsed = row.entry.id in state.collapsed,
                    selected = row.entry.id == state.selectedId,
                    onToggle = { state.toggleCollapsed(row.entry.id) },
                    onSelect = { state.selectedId = row.entry.id },
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun TreeLine(
    row: TreeRow,
    hasChildren: Boolean,
    collapsed: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val palette = LocalPalette.current
    val entry = row.entry
    val failed = entry.state == EntryState.ENTRY_STATE_FAILED
    val isUser = entry.body?.user_message != null
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = (12 + 18 * row.depth).dp, end = 20.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasChildren) {
            Text(
                if (collapsed) "▸" else "▾",
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.width(14.dp).clickable(onClick = onToggle),
            )
        } else {
            Spacer(Modifier.width(14.dp))
        }
        Text(
            kindLabel(entry),
            color = if (failed) palette.failed else palette.accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
        )
        Text(
            headline(entry),
            color = if (failed) palette.failed else palette.ink,
            fontSize = 13.sp,
            fontWeight = if (isUser) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        tag(entry)?.let { Text(it, color = palette.muted, fontSize = 11.sp) }
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

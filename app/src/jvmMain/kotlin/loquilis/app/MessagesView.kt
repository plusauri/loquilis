package loquilis.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import loquilis.kit.EntryKind
import loquilis.kit.kind
import loquilis.model.Content
import loquilis.model.Entry
import loquilis.model.EntryState

@Composable
fun MessagesView(state: ReviewState, list: LazyListState) {
    val rows by remember(state) { derivedStateOf { state.visibleMessages() } }

    LaunchedEffect(Unit) {
        val id = state.selectedId ?: return@LaunchedEffect
        val at = rows.indexOfFirst { state.holds(it, id) }
        if (at >= 0) list.scrollToItem(at)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                val selected = state.selectedId?.let { state.holds(row, it) } ?: false
                val select = { id: String -> state.selectedId = id }
                when (row) {
                    is MessageRow.Said -> UserBubble(state.take.text(row.entry.body?.user_message?.text), selected) { select(row.entry.id) }
                    is MessageRow.Reply -> AgentBubble(state.take.text(row.entry.body?.agent_text?.text), selected) { select(row.entry.id) }
                    is MessageRow.Work -> WorkFold(row, row.turn in state.openTurns) { state.toggleTurn(row.turn) }
                    is MessageRow.Step -> StepLine(row, selected) { select(row.entry.id) }
                    is MessageRow.Note -> NoteLine(row.entry, selected) { select(row.entry.id) }
                    is MessageRow.Footer -> TurnFooter(row, selected) { select(row.entry.id) }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun UserBubble(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
        Bubble(text, palette.userBubble, palette.userInk, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), selected, onClick)
    }
}

@Composable
private fun AgentBubble(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Bubble(text, palette.agentBubble, palette.agentInk, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), selected, onClick)
    }
}

@Composable
private fun Bubble(text: String, fill: Color, ink: Color, shape: RoundedCornerShape, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(fill)
            .then(if (selected) Modifier.border(2.dp, palette.ink, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = ink, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun WorkFold(row: MessageRow.Work, open: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (open) "▾" else "▸", color = palette.muted, fontSize = 12.sp)
        Text(row.summary, color = palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun StepLine(row: MessageRow.Step, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val failed = row.result?.state == EntryState.ENTRY_STATE_FAILED || row.entry.state == EntryState.ENTRY_STATE_FAILED
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (18 + 18 * row.depth).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            kindLabel(row.entry),
            color = if (failed) palette.failed else palette.accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Text(
            headline(row.entry),
            color = if (failed) palette.failed else palette.ink,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteLine(entry: Entry, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        headline(entry),
        color = palette.muted,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun TurnFooter(row: MessageRow.Footer, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val failed = row.entry.state == EntryState.ENTRY_STATE_FAILED
    Text(
        turnLine(row.result),
        color = if (failed) palette.failed else palette.muted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The whole body of an entry, for the inspector. */
fun bodyContent(entry: Entry): Pair<String, Content?>? = when (val kind = entry.body?.kind ?: EntryKind.Unknown) {
    is EntryKind.User -> "Message" to kind.message.text
    is EntryKind.Text -> "Text" to kind.text.text
    is EntryKind.Reasoning -> "Thinking" to kind.thinking.text
    is EntryKind.Call -> "Input" to kind.call.input
    is EntryKind.Result -> "Output" to kind.result.output
    else -> null
}

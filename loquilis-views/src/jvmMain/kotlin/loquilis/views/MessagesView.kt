package loquilis.views

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.Content
import loquilis.model.Entry
import loquilis.model.EntryState

private val USER_WIDTH = 560.dp

/** Wider than a message, since a reply can hold code and tables. */
private val REPLY_WIDTH = 760.dp

@Composable
fun MessagesView(state: ConversationState, list: LazyListState) {
    val pager = state.pager

    LaunchedEffect(Unit) {
        val at = state.selectedId?.let(state::messageIndex) ?: return@LaunchedEffect
        list.scrollToItem(at)
    }

    // Only a hand on the list changes this, so a list growing under a still reader never counts.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }
            .collect { (scrolling, more) -> if (scrolling) state.following = !more }
    }

    LaunchedEffect(pager, state.following) {
        if (state.following) {
            withFrameNanos {}
            list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), Int.MAX_VALUE)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            turnItems(
                first = pager.firstOrdinal,
                last = pager.lastOrdinal,
                open = state.openTurns.keys,
                closed = { ClosedTurn(state, it) },
                opened = { ordinal ->
                    val turn = checkNotNull(state.openTurns[ordinal])
                    items(turn.messages, key = { it.key }) { row -> TurnRow(state, turn, row) }
                },
            )
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** A closed turn, drawn from its summary until its entries arrive. */
@Composable
private fun ClosedTurn(state: ConversationState, ordinal: Int) {
    val turn by rememberTurn(state.pager, ordinal)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val loaded = turn
        if (loaded != null) {
            loaded.messages.filter { it !is MessageRow.Step }.forEach { TurnRow(state, loaded, it) }
        } else {
            state.pager.summary(ordinal)?.let { summary ->
                UserBubble(summary.user_head, false) {}
                if (summary.reply_head.isNotEmpty()) AgentBubble(remember(summary.reply_head) { markdownBlocks(summary.reply_head) }, false) {}
            }
        }
    }
}

@Composable
private fun TurnRow(state: ConversationState, turn: LoadedTurn, row: MessageRow) {
    val selected = state.selectedId?.let { state.holds(row, it) } ?: false
    val select = { id: String -> state.selectedId = id }
    when (row) {
        is MessageRow.Said -> UserBubble(turn.text(row.entry), selected) { select(row.entry.id) }
        is MessageRow.Reply -> AgentBubble(turn.reply(row.entry), selected) { select(row.entry.id) }
        is MessageRow.Work -> WorkFold(row, row.turn in state.openTurns) { state.toggleTurn(turn) }
        is MessageRow.Step -> StepLine(row, selected) { select(row.entry.id) }
        is MessageRow.Note -> NoteLine(row.entry, selected) { select(row.entry.id) }
        is MessageRow.Footer -> TurnFooter(row, selected) { select(row.entry.id) }
    }
}

@Composable
private fun UserBubble(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
        Bubble(palette.userBubble, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), USER_WIDTH, selected, onClick) {
            // The usual selection color is blue, which cannot be seen on this bubble.
            val selection = TextSelectionColors(palette.userInk, palette.userInk.copy(alpha = 0.35f))
            CompositionLocalProvider(LocalTextSelectionColors provides selection) {
                SelectionContainer { Text(text, color = palette.userInk, fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
    }
}

@Composable
private fun AgentBubble(blocks: List<MarkdownBlock>, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Bubble(palette.agentBubble, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), REPLY_WIDTH, selected, onClick) {
            SelectionContainer { MarkdownView(blocks, palette.agentInk) }
        }
    }
}

@Composable
private fun Bubble(fill: Color, shape: RoundedCornerShape, width: Dp, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .widthIn(max = width)
            .clip(shape)
            .background(fill)
            .then(if (selected) Modifier.border(2.dp, palette.ink, shape) else Modifier)
            .tapWithoutDrag(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        content()
    }
}

/**
 * A click for something whose text can be selected. It listens last and takes nothing, so a press and
 * a drag still reach the selection, and only a press that stays put counts as a tap.
 */
@Composable
private fun Modifier.tapWithoutDrag(onTap: () -> Unit): Modifier {
    val tap by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (!change.pressed) {
                    if (!moved) tap()
                    break
                }
            }
        }
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
        turnEndLine(row.entry, row.result),
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

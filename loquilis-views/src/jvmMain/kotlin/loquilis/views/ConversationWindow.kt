package loquilis.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One conversation from a library: three views of it, and the inspector showing the selected entry. */
@Composable
fun ConversationWindow(state: ConversationState) {
    val palette = LocalPalette.current
    val messagesList = remember(state.pager) { LazyListState() }
    val treeList = remember(state.pager) { LazyListState() }

    Column(Modifier.fillMaxSize().background(palette.page)) {
        TopBar(state)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (state.view) {
                    ConversationView.Messages -> MessagesView(state, messagesList)
                    ConversationView.Tree -> TreeView(state, treeList)
                    ConversationView.Search -> SearchView(state)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.line))
            Inspector(state, Modifier.width(420.dp).fillMaxHeight().background(palette.panel))
        }
    }
}

@Composable
private fun TopBar(state: ConversationState) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.pager.conversation.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = palette.ink)
                Text(summaryLine(state.pager), fontSize = 12.sp, color = palette.muted)
            }
            Spacer(Modifier.weight(1f))
            Segments(ConversationView.entries, state.view, { it.label }, state::show)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}

private fun summaryLine(pager: ConversationPager): String = buildList {
    val conversation = pager.conversation
    conversation.agent?.let { agent ->
        add(agent.reported_model.ifBlank { "unknown model" })
        if (agent.cli_version.isNotBlank()) add("Claude Code ${agent.cli_version}")
    }
    add("${pager.turnCount} turns")
    add("${pager.entryCount} entries")
    add("${pager.rawCount} raw lines")
    conversation.total_usage?.let { add(money(it.cost_micro_usd)) }
    add("read-only")
}.joinToString(" · ")

/** A row of mutually exclusive choices. */
@Composable
internal fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, palette.line, RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                color = if (on) palette.userInk else palette.ink,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(if (on) palette.accent else palette.page)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

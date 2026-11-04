package loquilis.app

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

/** The review window: one take, two views of it, and the inspector showing the selected entry. */
@Composable
fun ReviewApp(state: ReviewState) {
    val palette = LocalPalette.current
    val messagesList = remember { LazyListState() }
    val treeList = remember { LazyListState() }

    Column(Modifier.fillMaxSize().background(palette.page)) {
        TopBar(state)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (state.view) {
                    ReviewView.Messages -> MessagesView(state, messagesList)
                    ReviewView.Tree -> TreeView(state, treeList)
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.line))
            Inspector(state, Modifier.width(420.dp).fillMaxHeight().background(palette.panel))
        }
    }
}

@Composable
private fun TopBar(state: ReviewState) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.take.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = palette.ink)
                Text(takeLine(state), fontSize = 12.sp, color = palette.muted)
            }
            Spacer(Modifier.weight(1f))
            ViewSwitch(state)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}

private fun takeLine(state: ReviewState): String = buildList {
    state.take.session?.let { session ->
        add(session.model.ifBlank { "unknown model" })
        if (session.cliVersion.isNotBlank()) add("Claude Code ${session.cliVersion}")
    }
    add("${state.turnCount} turns")
    add("${state.take.entries.size} entries")
    add("${state.take.rawLines.size} raw lines")
    add(money(state.totalCostMicroUsd))
    add("read-only replay")
}.joinToString(" · ")

@Composable
private fun ViewSwitch(state: ReviewState) {
    val palette = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, palette.line, RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ReviewView.entries.forEach { view ->
            val on = state.view == view
            Text(
                view.label,
                color = if (on) palette.userInk else palette.ink,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(if (on) palette.accent else palette.page)
                    .clickable { state.show(view) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}



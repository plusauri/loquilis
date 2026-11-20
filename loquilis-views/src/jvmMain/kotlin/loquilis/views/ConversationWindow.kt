package loquilis.views

import plusauri.ui.ButtonData
import plusauri.ui.ButtonImage
import plusauri.ui.MultiButtonSelect
import plusauri.ui.PanelSide
import plusauri.ui.MultiButtonSelectDefaults
import plusauri.ui.SelectionMark
import plusauri.ui.ToggleButtonWidth
import plusauri.ui.ToggleState
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.dp
import loquilis.store.modelInUse

/**
 * One conversation from a library: three views of it, and the inspector showing the selected entry.
 * [composer] sits under the views that show the conversation, for a host that can talk to the agent, and
 * [titleActions] beside its title, for what a host can do to the conversation itself (Rename, in the console).
 */
@Composable
fun ConversationWindow(
    state: ConversationState,
    inspector: PanelState = remember { PanelState(true, ConsoleSettings.INSPECTOR_WIDTH.dp, INSPECTOR_MIN, INSPECTOR_MAX) },
    titleActions: @Composable RowScope.() -> Unit = {},
    composer: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    val conversationId = state.pager.conversationId
    val messagesList = remember(conversationId) { LazyListState() }
    val treeList = remember(conversationId) { LazyListState() }

    Column(Modifier.fillMaxSize().background(palette.page)) {
        TopBar(state, titleActions)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.view) {
                        ConversationView.Messages -> MessagesView(state, messagesList)
                        ConversationView.Tree -> TreeView(state, treeList)
                        ConversationView.Search -> SearchView(state)
                    }
                }
                if (state.view != ConversationView.Search) composer()
            }
            PanelSeam(inspector, PanelSide.Trailing, "inspector")
            if (inspector.shown) Inspector(state, Modifier.width(inspector.width).fillMaxHeight().background(palette.panel))
        }
    }
}

@Composable
private fun TopBar(state: ConversationState, titleActions: @Composable RowScope.() -> Unit) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.pager.conversation.title.ifEmpty { NEW_CONVERSATION }, style = type.title, color = palette.ink)
                    titleActions()
                }
                Text(summaryLine(state.pager), style = type.detail, color = palette.muted)
            }
            Spacer(Modifier.weight(1f))
            PickOne(ConversationView.entries, state.view, { it.label }, state::show)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
    }
}

private fun summaryLine(pager: ConversationPager): String = buildList {
    val conversation = pager.conversation
    conversation.agent?.let { agent ->
        add(conversation.modelInUse ?: "unknown model")
        if (agent.cli_version.isNotBlank()) add("${agent.cli} ${agent.cli_version}".trim())
    }
    add(plural(pager.turnCount, "turn", "turns"))
    add(plural(pager.entryCount, "entry", "entries"))
    add(plural(pager.rawCount, "raw line", "raw lines"))
    // A local model costs nothing, and "$0.000" reads as a bill.
    conversation.total_usage?.cost_micro_usd?.takeIf { it > 0 }?.let { add(money(it)) }
}.joinToString(" · ")

/**
 * A row of mutually exclusive choices: the kit's house pick-one control (`MultiButtonSelect` in its compact
 * toolbar form), the same one Tutilis uses, with a check on the choice that is on so color is not the only cue.
 */
@Composable
internal fun <T> PickOne(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    MultiButtonSelect(
        buttonSelections = options.map { ButtonData(ButtonImage.Unicode(label(it))) to ToggleState.Deselected },
        selectedIndex = options.indexOf(selected),
        onSelectedIndexChange = { index -> options[index].takeIf { it != selected }?.let(onSelect) },
        toolbarOnly = true,
        buttonSize = MultiButtonSelectDefaults.CompactHeight,
        widthMode = ToggleButtonWidth.Fit,
        labelTextStyle = MaterialTheme.typography.labelSmall,
        selectionMark = SelectionMark.CheckBox,
    )
}

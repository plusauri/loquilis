package loquilis.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plusauri.ui.ActionKind
import plusauri.ui.ActionPill
import plusauri.ui.ButtonData
import plusauri.ui.ButtonImage
import plusauri.ui.MultiButtonSelect
import plusauri.ui.MultiButtonSelectDefaults
import plusauri.ui.SelectionMark
import plusauri.ui.ToggleButtonWidth
import plusauri.ui.ToggleState
import plusauri.ui.ViewSide

/**
 * Chooses the agent for a new conversation: the agents in a column on the left, the kit's pick-one control in its
 * panel form, and what the chosen one is on the right, with where its data goes. The column grows as agents are
 * added, and each agent writes its own description, so a new one needs nothing here. Return creates, Escape and
 * Cancel close.
 */
@Composable
internal fun NewConversationCard(state: LibraryState, onCreate: (agent: String) -> Unit) {
    val palette = LocalPalette.current
    val names = state.agentNames
    val chosen = state.choosingAgent?.takeIf { it in names } ?: names.first()
    Modal(onDismiss = { state.choosingAgent = null }, width = 560.dp) {
        Text("New conversation", color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Choose who to talk to.", color = palette.muted, fontSize = 13.sp)
        MultiButtonSelect(
            buttonSelections = names.map { ButtonData(ButtonImage.Unicode(agentLabel(it))) to ToggleState.Deselected },
            contentMaker = { index -> AgentAbout(state, names[index]) },
            buttonBarSide = ViewSide.Left,
            selectedIndex = names.indexOf(chosen),
            onSelectedIndexChange = { state.choosingAgent = names[it] },
            buttonSize = MultiButtonSelectDefaults.CompactHeight,
            widthMode = ToggleButtonWidth.Fit,
            labelTextStyle = MaterialTheme.typography.labelMedium,
            selectionMark = SelectionMark.CheckBox,
            equalSlots = true,
            fillPanelHeight = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ActionPill("Cancel", ActionKind.Clear, onClick = { state.choosingAgent = null })
            ActionPill("Create", ActionKind.Go, onClick = { onCreate(chosen) }, selected = true)
        }
    }
}

/** The chosen agent: its name, what it is, where what you write goes, and whether it asks for a folder. */
@Composable
private fun AgentAbout(state: LibraryState, agent: String) {
    val palette = LocalPalette.current
    val goes = state.agentDataGoes(agent)
    Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(start = 14.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(agentLabel(agent), color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        state.describe(agent).takeIf { it.isNotBlank() }?.let { Text(it, color = palette.ink, fontSize = 13.sp, lineHeight = 18.sp) }
        DataGoesLine(goes, goes.words)
        Text(
            if (state.needsFolder(agent)) "You choose the folder it works in next." else "It needs no folder.",
            color = palette.muted,
            fontSize = 12.sp,
        )
    }
}

package loquilis.views

import plusauri.ui.DialogConfirmButton
import plusauri.ui.DialogDismissButton
import androidx.compose.ui.input.key.Key
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import plusauri.ui.LabeledField
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
 * Starts a conversation: an optional name, then the agent for it: the agents in a column on the left, the kit's pick-one control in its
 * panel form, and what the chosen one is on the right, with where its data goes. The column grows as agents are
 * added, and each agent writes its own description, so a new one needs nothing here. Return creates, Escape and
 * Cancel close.
 */
@Composable
internal fun NewConversationCard(state: LibraryState, onCreate: (agent: String) -> Unit) {
    val palette = LocalPalette.current
    val names = state.agentNames
    val chosen = state.choosingAgent?.takeIf { it in names } ?: names.first()
    ConsoleDialog(
        "New conversation",
        onDismiss = { state.choosingAgent = null },
        width = 580.dp,
        keys = { key -> (key == Key.Enter).also { if (it) onCreate(chosen) } },
        footer = {
            DialogDismissButton("Cancel", onClick = { state.choosingAgent = null })
            DialogConfirmButton("Create", onClick = { onCreate(chosen) })
        },
    ) {
        NameBox(state)
        if (names.size == 1) {
            // No choice to make, but still worth saying what the one agent is and where its data goes.
            AgentAbout(state, chosen)
        } else {
            Text("Choose who to talk to.", color = palette.muted, style = type.body)
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
        }
    }
}

/**
 * The conversation's name, optional. It takes the keys as the card opens, so a person can type a name and press
 * Return. Left blank, the first message names the conversation, as it always has.
 */
@Composable
private fun NameBox(state: LibraryState) {
    NameField(state.newName, { state.newName = it }, placeholder = "Optional. Otherwise named from your first message")
}

/**
 * A conversation's name, in the box the new-conversation and rename dialogs share. It takes the keys as it opens,
 * so a person can type and press Return, and it keeps to one line.
 */
@Composable
internal fun NameField(value: String, onValueChange: (String) -> Unit, placeholder: String = "") {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LabeledField("Name") {
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", " ")) },
            singleLine = true,
            textStyle = type.body.copy(color = palette.ink),
            cursorBrush = SolidColor(palette.ink),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(focus)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, palette.line, RoundedCornerShape(8.dp))
                .background(palette.page)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = palette.muted, style = type.body)
                    field()
                }
            },
        )
    }
}

/** The chosen agent: its name, what it is, where what you write goes, and whether it asks for a folder. */
@Composable
private fun AgentAbout(state: LibraryState, agent: String) {
    val palette = LocalPalette.current
    val goes = state.agentDataGoes(agent)
    Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(start = 14.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(agentLabel(agent), color = palette.ink, style = type.reading, fontWeight = FontWeight.SemiBold)
        state.describe(agent).takeIf { it.isNotBlank() }?.let { Text(it, color = palette.ink, style = type.body) }
        DataGoesLine(goes, goes.words)
        Text(
            if (state.needsFolder(agent)) "You choose the folder it works in next." else "It needs no folder.",
            color = palette.muted,
            style = type.detail,
        )
    }
}

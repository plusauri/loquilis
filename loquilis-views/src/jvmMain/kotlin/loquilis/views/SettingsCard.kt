package loquilis.views

import plusauri.ui.ActionKind
import plusauri.ui.ActionPill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The console's settings, each kept as soon as it changes. Escape or a click outside closes it. */
@Composable
internal fun SettingsCard(state: LibraryState, onClose: () -> Unit) {
    val palette = LocalPalette.current
    val settings = state.settings
    Modal(onClose, width = 420.dp) {
        Text("Settings", color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        Section("Where data goes")
        OfflineOnlySwitch(state, Modifier)
        Hint("Nothing is sent while this computer is on a network, and only to a model on this computer.")

        Section("Agents in the list")
        for (agent in state.allAgentNames) {
            Toggle(agentLabel(agent), agent !in settings.hiddenAgents) { state.showAgent(agent, it) }
        }
        Hint("A hidden agent's conversations still open and go on. One agent always stays.")

        Section("Local models")
        Text("Keep a model in memory after it answers", color = palette.ink, fontSize = 13.sp)
        PickOne(ConsoleSettings.KEEP_MINUTES, settings.keepModelMinutes, label = { "$it min" }) {
            state.change(settings.copy(keepModelMinutes = it))
        }
        Hint("For Local conversations: longer answers the next message sooner, shorter gives the memory back sooner. OpenCode leaves its model to Ollama, which keeps it five minutes. Quitting frees both.")

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ActionPill("Close", ActionKind.Clear, onClick = onClose)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = LocalPalette.current.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalPalette.current.muted, fontSize = 12.sp, lineHeight = 16.sp)
}

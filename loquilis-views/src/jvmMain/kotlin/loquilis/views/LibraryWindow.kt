package loquilis.views

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import loquilis.agents.PermissionAsk
import loquilis.model.Conversation
import loquilis.model.Decision
import loquilis.store.ChatStatus

/** How often a growing conversation is read again. */
private const val REFRESH_MILLIS = 100L

/**
 * A library you can talk to: its conversations down the side, the one showing, and a box to write in.
 * [onNewConversation] asks the host where the agent should work.
 */
@Composable
fun LibraryWindow(state: LibraryState, onNewConversation: (agent: String) -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxSize().background(palette.page)) {
        ConversationList(state, onNewConversation, Modifier.width(260.dp).fillMaxHeight().background(palette.panel))
        Box(Modifier.width(1.dp).fillMaxHeight().background(palette.line))
        val showing = state.current
        val id = state.currentId
        if (showing == null || id == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Start a conversation to talk to an agent.", color = palette.muted, fontSize = 14.sp)
            }
        } else {
            key(id) { Follow(state, showing, id) }
            ConversationWindow(showing) { key(id) { Composer(state, id) } }
        }
    }
}

/** Shows what a chat writes as it writes it, reading at most ten times a second. */
@OptIn(FlowPreview::class)
@Composable
private fun Follow(state: LibraryState, showing: ConversationState, id: String) {
    LaunchedEffect(id) {
        state.chat(id).written.sample(REFRESH_MILLIS).collect {
            val writing = state.chat(id).writing.value
            showing.show(withContext(Dispatchers.IO) { showing.pager.refreshed() })
            // Set with the entries read after it, so a finished reply never leaves the screen for a moment.
            showing.writing = writing
            state.refreshList()
        }
    }
}

@Composable
private fun ConversationList(state: LibraryState, onNewConversation: (agent: String) -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        for (agent in state.agentNames) Text(
            if (state.agentNames.size == 1) NEW_CONVERSATION else "New ${agentLabel(agent)} conversation",
            color = palette.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable { onNewConversation(agent) }.padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(state.conversations, key = { it.id }) { conversation ->
                    val asks by state.chat(conversation.id).asks.collectAsState()
                    ConversationLine(conversation, conversation.id == state.currentId, asks.isNotEmpty()) { state.select(conversation.id) }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ConversationLine(conversation: Conversation, selected: Boolean, asking: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) palette.selection else palette.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(conversation.shownTitle, color = palette.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (asking) "Waiting for your answer" else place(conversation),
            color = if (asking) palette.accent else palette.muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun agentLabel(cli: String) = cli.replaceFirstChar(Char::uppercase)

/** Where a conversation's agent worked, and which agent it was. */
private fun place(conversation: Conversation) = listOf(
    conversation.workspace_path.substringAfterLast('/'),
    agentLabel(conversation.agent?.cli.orEmpty()),
).filter { it.isNotEmpty() }.joinToString(" · ")

/** The agent's oldest question, with what it wants to do spelled out and every answer one click away. */
@Composable
private fun AskCard(ask: PermissionAsk, more: Int, onAnswer: (Decision) -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.accent, RoundedCornerShape(10.dp))
            .background(palette.page)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title = "May the agent use ${ask.tool}?" + if (more > 0) " ($more more after this)" else ""
        Text(title, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (ask.description.isNotBlank()) Text(ask.description, color = palette.muted, fontSize = 12.sp)
        if (ask.detail.isNotBlank()) {
            Text(
                ask.detail,
                color = palette.ink,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(palette.panel).padding(8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComposerButton("Allow", palette.accent, palette.userInk) { onAnswer(Decision.DECISION_ALLOW_ONCE) }
            ComposerButton("Deny", palette.line, palette.ink) { onAnswer(Decision.DECISION_DENY) }
        }
        ask.remember?.let { rule ->
            Text(
                "Always allow $rule in this folder",
                color = palette.accent,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onAnswer(Decision.DECISION_ALLOW_ALWAYS) }.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ComposerButton(label: String, background: Color, ink: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = ink,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** Return sends and Shift-Return starts a new line. What the agent never got comes back here. */
@Composable
private fun Composer(state: LibraryState, id: String) {
    val palette = LocalPalette.current
    val chat = state.chat(id)
    val status by chat.status.collectAsState()
    val asks by chat.asks.collectAsState()
    val text = state.drafts[id].orEmpty()
    val canSend = text.isNotBlank()
    fun send() {
        if (!canSend) return
        state.drafts.remove(id)
        state.send(text)
    }

    LaunchedEffect(status) {
        val unsent = (status as? ChatStatus.Failed)?.unsent.orEmpty()
        if (unsent.isNotEmpty() && state.drafts[id].isNullOrEmpty()) state.drafts[id] = unsent.joinToString("\n\n")
    }

    Column(Modifier.fillMaxWidth().background(palette.panel)) {
        Box(Modifier.fillMaxWidth().background(palette.line).padding(top = 1.dp))
        asks.firstOrNull()?.let { ask -> AskCard(ask, asks.size - 1) { chat.answer(ask, it) } }
        statusLine(status)?.let { line ->
            Text(
                line,
                color = if (status is ChatStatus.Failed) palette.failed else palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { state.drafts[id] = it },
                textStyle = TextStyle(color = palette.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.ink),
                maxLines = 8,
                modifier = Modifier.weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, palette.line, RoundedCornerShape(18.dp))
                    .background(palette.page)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .onPreviewKeyEvent { event ->
                        val isReturn = event.key == Key.Enter && !event.isShiftPressed
                        if (isReturn && event.type == KeyEventType.KeyDown) send()
                        isReturn
                    },
                decorationBox = { field ->
                    Box {
                        if (text.isEmpty()) Text("Message", color = palette.muted, fontSize = 14.sp)
                        field()
                    }
                },
            )
            if (status is ChatStatus.Working) ComposerButton("Stop", palette.line, palette.failed) { chat.interrupt() }
            ComposerButton(
                "Send",
                if (canSend) palette.accent else palette.line,
                if (canSend) palette.userInk else palette.muted,
                enabled = canSend,
            ) { send() }
        }
    }
}

private fun statusLine(status: ChatStatus): String? = when (status) {
    ChatStatus.Idle -> null
    is ChatStatus.Working -> when (status.waiting) {
        0 -> "Working…"
        1 -> "Working… 1 message waiting"
        else -> "Working… ${status.waiting} messages waiting"
    }
    is ChatStatus.Failed -> status.reason
}

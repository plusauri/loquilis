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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import loquilis.model.Conversation
import loquilis.store.ChatStatus

/** How often a growing conversation is read again. */
private const val REFRESH_MILLIS = 100L

/**
 * A library you can talk to: its conversations down the side, the one showing, and a box to write in.
 * [onNewConversation] asks the host where the agent should work.
 */
@Composable
fun LibraryWindow(state: LibraryState, onNewConversation: () -> Unit) {
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
            showing.show(withContext(Dispatchers.IO) { showing.pager.refreshed() })
            state.refreshList()
        }
    }
}

@Composable
private fun ConversationList(state: LibraryState, onNewConversation: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        Text(
            "New conversation",
            color = palette.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onNewConversation).padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(state.conversations, key = { it.id }) { conversation ->
                    ConversationLine(conversation, conversation.id == state.currentId) { state.select(conversation.id) }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ConversationLine(conversation: Conversation, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) palette.selection else palette.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(conversation.shownTitle, color = palette.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            conversation.workspace_path.substringAfterLast('/'),
            color = palette.muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Return sends and Shift-Return starts a new line. What the agent never got comes back here. */
@Composable
private fun Composer(state: LibraryState, id: String) {
    val palette = LocalPalette.current
    val status by state.chat(id).status.collectAsState()
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
            Text(
                "Send",
                color = if (canSend) palette.userInk else palette.muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (canSend) palette.accent else palette.line)
                    .clickable(enabled = canSend) { send() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
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

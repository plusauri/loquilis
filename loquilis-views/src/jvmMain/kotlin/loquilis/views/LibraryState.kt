package loquilis.views

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.squareup.wire.Instant
import java.nio.file.Path
import loquilis.model.Conversation
import loquilis.store.Agent
import loquilis.store.LiveChat
import loquilis.store.Store
import loquilis.store.newConversation

/**
 * A library in a window: its conversations, the one showing, and the chats running. A chat keeps
 * recording when another conversation is showing.
 */
class LibraryState(
    val store: Store,
    private val agent: Agent,
    first: String? = null,
    private val now: () -> Instant = { java.time.Instant.now() },
) {
    var conversations by mutableStateOf(store.conversations(LISTED))
        private set
    var current by mutableStateOf(first?.let { ConversationState(store, it) })
        private set

    private val chats = HashMap<String, LiveChat>()

    /** What is typed and not sent, by conversation. */
    val drafts = mutableStateMapOf<String, String>()

    /** The id of the conversation showing, which a search hit can change. */
    val currentId: String? get() = current?.pager?.conversationId

    fun chat(conversationId: String): LiveChat =
        synchronized(chats) { chats.getOrPut(conversationId) { LiveChat(store, conversationId, agent, now) } }

    fun select(conversationId: String) {
        val showing = current
        if (showing == null) current = ConversationState(store, conversationId).also { it.following = true } else showing.open(conversationId)
    }

    /** Starts a conversation for an agent to work on in [workspace], and shows it. */
    fun newConversation(workspace: Path) {
        val conversation = store.newConversation(workspace, now())
        refreshList()
        select(conversation.id)
    }

    /** Sends [text] on the conversation showing, and keeps the view on the answer. */
    fun send(text: String) {
        val showing = current ?: return
        showing.following = true
        chat(showing.pager.conversationId).send(text)
        refreshList()
    }

    fun refreshList() {
        conversations = store.conversations(LISTED)
    }

    /** Stops every agent and waits for what they produced to reach the disk. */
    fun close() {
        synchronized(chats) { chats.values.toList() }.forEach(LiveChat::stop)
    }

    private companion object {
        const val LISTED = 500
    }
}

internal const val NEW_CONVERSATION = "New conversation"

internal val Conversation.shownTitle: String get() = title.ifEmpty { NEW_CONVERSATION }

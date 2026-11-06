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
    private val agents: Map<String, Agent>,
    first: String? = null,
    private val now: () -> Instant = { java.time.Instant.now() },
) {
    constructor(store: Store, agent: Agent, first: String? = null, now: () -> Instant = { java.time.Instant.now() }) :
        this(store, mapOf(DEFAULT_AGENT to agent), first, now)

    /** The agents a new conversation can start with, by their command-line tool's name. */
    val agentNames: List<String> get() = agents.keys.toList()

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
        synchronized(chats) { chats.getOrPut(conversationId) { LiveChat(store, conversationId, agentOf(conversationId), now) } }

    /** A stored conversation goes on with the agent that had it, or the first one if that one is not here. */
    private fun agentOf(conversationId: String): Agent =
        agents[store.conversation(conversationId)?.agent?.cli] ?: agents.values.first()

    fun select(conversationId: String) {
        val showing = current
        if (showing == null) current = ConversationState(store, conversationId).also { it.following = true } else showing.open(conversationId)
    }

    /** Starts a conversation for [agent] to work on in [workspace], and shows it. */
    fun newConversation(workspace: Path, agent: String = agentNames.first()) {
        val conversation = store.newConversation(workspace, now(), cli = agent)
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
private const val DEFAULT_AGENT = "claude"

internal val Conversation.shownTitle: String get() = title.ifEmpty { NEW_CONVERSATION }

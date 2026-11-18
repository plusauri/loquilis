package loquilis.views

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.squareup.wire.Instant
import java.nio.file.Path
import loquilis.agents.OllamaSetup
import loquilis.model.Conversation
import loquilis.store.Agent
import loquilis.store.DataGoes
import loquilis.store.LiveChat
import loquilis.store.modelInUse
import loquilis.store.Store
import loquilis.store.newConversation
import loquilis.store.OfflineOnly
import loquilis.store.joinedNetworks

/**
 * A library in a window: its conversations, the one showing, and the chats running. A chat keeps
 * recording when another conversation is showing.
 */
class LibraryState(
    val store: Store,
    private val agents: Map<String, Agent>,
    first: String? = null,
    private val now: () -> Instant = { java.time.Instant.now() },
    /** What an agent needs set up first, such as a runner and a model, by agent name. */
    private val setups: Map<String, OllamaSetup> = emptyMap(),
    /** Whether offline only starts on, and where a change to it is kept. */
    offlineOnly: Boolean = false,
    private val onOfflineOnly: (Boolean) -> Unit = {},
    /** The networks this computer is joined to, read before every send while offline only is on. */
    private val networks: () -> List<String> = ::joinedNetworks,
) {
    constructor(store: Store, agent: Agent, first: String? = null, now: () -> Instant = { java.time.Instant.now() }) :
        this(store, mapOf(DEFAULT_AGENT to agent), first, now)

    /** Offline only: nothing is sent while this computer is on a network, nor anywhere but this computer. */
    var offlineOnly by mutableStateOf(offlineOnly)
        private set

    fun changeOfflineOnly(on: Boolean) {
        offlineOnly = on
        onOfflineOnly(on)
    }

    /** The networks joined when last looked, for showing. A send looks again. */
    var joined by mutableStateOf<List<String>>(emptyList())
        private set

    suspend fun lookAtNetworks() {
        joined = withContext(Dispatchers.IO) { networks() }
    }

    /** Why a message in [conversationId] cannot be sent now, or null when it can. */
    fun refusal(conversationId: String): String? =
        if (!offlineOnly) null else OfflineOnly.refusal(dataGoes(conversationId), networks().also { joined = it })

    /** The agents a new conversation can start with, by their command-line tool's name. */
    val agentNames: List<String> get() = agents.keys.toList()

    /** Whether a new conversation with [agent] needs a folder to work on. */
    fun needsFolder(agent: String): Boolean = agents[agent]?.needsFolder ?: true

    var conversations by mutableStateOf(store.conversations(LISTED))
        private set
    var current by mutableStateOf(first?.let { ConversationState(store, it) })
        private set

    private val chats = HashMap<String, LiveChat>()

    /** The setup showing, for an agent that is not ready to start. */
    var setup by mutableStateOf<SetupState?>(null)

    /**
     * Whether [agent] can start a conversation now. One with a setup always shows it, which is also where
     * its model is chosen, and says no.
     */
    suspend fun prepare(agent: String): Boolean {
        val needed = setups[agent] ?: return true
        val shown = SetupState(agent, needed, lastUsed = lastModel(agent), offlineOnly = offlineOnly)
        setup = shown
        shown.refresh()
        return false
    }

    /** The model [agent]'s latest conversation used, so it can be offered first. */
    private fun lastModel(agent: String): String? = conversations
        .filter { it.agent?.cli == agent }
        .firstNotNullOfOrNull { it.modelInUse }

    /** The conversation waiting on the user to confirm its deletion. */
    var deleting by mutableStateOf<Conversation?>(null)

    /** What is typed and not sent, by conversation. */
    val drafts = mutableStateMapOf<String, String>()

    /** The id of the conversation showing, which a search hit can change. */
    val currentId: String? get() = current?.pager?.conversationId

    fun chat(conversationId: String): LiveChat =
        synchronized(chats) { chats.getOrPut(conversationId) { LiveChat(store, conversationId, agentOf(conversationId), now) } }

    /** A stored conversation goes on with the agent that had it, or the first one if that one is not here. */
    private fun agentOf(conversationId: String): Agent =
        agents[store.conversation(conversationId)?.agent?.cli] ?: agents.values.first()

    /** Where what is written in [conversationId] goes, as its agent can tell from what it uses. */
    fun dataGoes(conversationId: String): DataGoes = agentOf(conversationId).dataGoes(store.conversation(conversationId))

    /** What [conversationId] says beside where its data goes: the model, once one is known. */
    fun goesLine(conversationId: String): String =
        listOfNotNull(dataGoes(conversationId).words, store.conversation(conversationId)?.modelInUse).joinToString(" · ")

    fun select(conversationId: String) {
        val showing = current
        if (showing == null) current = ConversationState(store, conversationId).also { it.following = true } else showing.open(conversationId)
    }

    /** Starts a conversation for [agent] to work on in [workspace], and shows it. */
    fun newConversation(workspace: Path, agent: String = agentNames.first(), model: String = "") {
        val conversation = store.newConversation(workspace, now(), cli = agent, model = model)
        refreshList()
        select(conversation.id)
    }

    /** Does what [action] names. Whatever asks, a key or a button, comes through here. */
    fun perform(action: ConsoleAction) {
        when (action) {
            ConsoleAction.DeleteConversation -> deleting = conversations.firstOrNull { it.id == currentId }
        }
    }

    /** Ends the agent and removes the conversation from this library. What the agent keeps itself stays. */
    suspend fun confirmDelete() {
        val conversation = deleting ?: return
        val id = conversation.id
        deleting = null
        conversations = conversations.filter { it.id != id }
        drafts.remove(id)
        if (currentId == id) {
            val next = conversations.firstOrNull()
            if (next == null) current = null else select(next.id)
        }
        withContext(Dispatchers.IO) {
            synchronized(chats) { chats.remove(id) }?.stop()
            store.deleteConversation(id)
        }
        refreshList()
    }

    /** Sends [text] on the conversation showing, and keeps the view on the answer. */
    /** Sends [text] in the conversation showing, or says why it cannot go. */
    fun send(text: String): String? {
        val showing = current ?: return null
        refusal(showing.pager.conversationId)?.let { return it }
        showing.following = true
        chat(showing.pager.conversationId).send(text)
        refreshList()
        return null
    }

    fun refreshList() {
        conversations = store.conversations(LISTED)
    }

    /** Stops every agent and waits for what they produced to reach the disk. */
    fun close() {
        synchronized(chats) { chats.values.toList() }.forEach(LiveChat::stop)
        agents.values.forEach(Agent::close)
    }

    private companion object {
        const val LISTED = 500
    }
}

internal const val NEW_CONVERSATION = "New conversation"
private const val DEFAULT_AGENT = "claude"

internal val Conversation.shownTitle: String get() = title.ifEmpty { NEW_CONVERSATION }

package loquilis.views

import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
    /** The settings to start with, and where a change to them is kept. */
    settings: ConsoleSettings = ConsoleSettings(),
    private val onSettings: (ConsoleSettings) -> Unit = {},
    /** The networks this computer is joined to, read before every send while offline only is on. */
    private val networks: () -> List<String> = ::joinedNetworks,
    /** This computer's memory and free disk, which decide the models a setup suggests. */
    private val memoryGb: () -> Double = OllamaSetup::installedMemoryGb,
    private val freeDiskGb: () -> Double = OllamaSetup::freeDiskGb,
) {
    constructor(store: Store, agent: Agent, first: String? = null, now: () -> Instant = { java.time.Instant.now() }) :
        this(store, mapOf(DEFAULT_AGENT to agent), first, now)

    /** Offline only: nothing is sent while this computer is on a network, nor anywhere but this computer. */
    var settings by mutableStateOf(settings)
        private set

    fun change(changed: ConsoleSettings) {
        settings = changed
        onSettings(changed)
    }

    /** Whether the settings card is showing. */
    var showingSettings by mutableStateOf(false)

    val offlineOnly: Boolean get() = settings.offlineOnly

    fun changeOfflineOnly(on: Boolean) = change(settings.copy(offlineOnly = on))

    /** The conversation list down the left. Its fold and width are kept with the settings. */
    val listPanel = PanelState(settings.listShown, settings.listWidth.dp, LIST_MIN, LIST_MAX) {
        change(this.settings.copy(listShown = it.shown, listWidth = it.width.value.roundToInt()))
    }

    /** The inspector down the right. Its fold and width are kept with the settings. */
    val inspectorPanel = PanelState(settings.inspectorShown, settings.inspectorWidth.dp, INSPECTOR_MIN, INSPECTOR_MAX) {
        change(this.settings.copy(inspectorShown = it.shown, inspectorWidth = it.width.value.roundToInt()))
    }

    /** The agent chosen in the new-conversation card while it shows, or null when it does not. */
    var choosingAgent by mutableStateOf<String?>(null)

    /** What is typed in the new-conversation card's Name box. Blank lets the first message name it. */
    var newName by mutableStateOf("")

    /** The name given at Create, held until the host has chosen a folder and [newConversation] runs. */
    private var namedAtCreate = ""

    /** Opens the new-conversation card, blank, on the agent of the latest conversation, or the first. */
    fun startChoosing() {
        val names = agentNames
        newName = ""
        choosingAgent = conversations.firstNotNullOfOrNull { c -> c.agent?.cli?.takeIf { it in names } } ?: names.first()
    }

    /** From a setup dialog back to the new-conversation card, on [agent] and with the name typed before. */
    fun backToChoosing(agent: String) {
        setup = null
        newName = namedAtCreate
        choosingAgent = agent
    }

    /** Closes the card and keeps its name for the conversation about to start. Returns the agent chosen. */
    fun chosen(agent: String): String {
        namedAtCreate = newName.trim()
        choosingAgent = null
        return agent
    }

    /** What [agent] says it is, for choosing between agents. */
    fun describe(agent: String): String = agents[agent]?.description.orEmpty()

    /** Where a new conversation with [agent] would send what is written in it. */
    fun agentDataGoes(agent: String): DataGoes = agents[agent]?.dataGoes(null) ?: DataGoes.Unknown

    /** Every agent this console can start, shown in the list or not. */
    val allAgentNames: List<String> get() = agents.keys.toList()

    /** Shows or hides [agent]'s new-conversation link. The last one shown stays, so a conversation can always start. */
    fun showAgent(agent: String, shown: Boolean) {
        val hidden = if (shown) settings.hiddenAgents - agent else settings.hiddenAgents + agent
        if (agents.keys.all { it in hidden }) return
        change(settings.copy(hiddenAgents = hidden))
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
    val agentNames: List<String> get() = agents.keys.filter { it !in settings.hiddenAgents }.ifEmpty { agents.keys.toList() }

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
        val shown = SetupState(agent, needed, memoryGb = memoryGb(), freeDiskGb = freeDiskGb, lastUsed = lastModel(agent), offlineOnly = offlineOnly, toolsOnly = agents[agent]?.modelNeedsTools == true)
        setup = shown
        shown.refresh()
        return false
    }

    /** The model [agent]'s latest conversation used, so it can be offered first. */
    private fun lastModel(agent: String): String? = conversations
        .filter { it.agent?.cli == agent }
        .firstNotNullOfOrNull { it.modelInUse }

    /** Counts asks for a new conversation that came by action rather than by the button, for the window to answer. */
    var newConversationAsked by mutableStateOf(0)
        private set

    /** The conversation being renamed while the rename dialog shows, or null when it does not. */
    var renaming by mutableStateOf<Conversation?>(null)
        private set

    /** What is typed in the rename dialog. */
    var newTitle by mutableStateOf("")

    /** Opens the rename dialog on [conversation], with its name as it reads now. */
    fun startRenaming(conversation: Conversation) {
        newTitle = conversation.title
        renaming = conversation
    }

    fun cancelRename() {
        renaming = null
    }

    /**
     * Names the conversation being renamed [newTitle]. A blank name changes nothing: the first message has already
     * named it, so there is nothing left to fall back to.
     */
    suspend fun rename() {
        val conversation = renaming ?: return
        val title = newTitle.trim()
        renaming = null
        if (title.isEmpty() || title == conversation.title) return
        val showing = current?.takeIf { it.pager.conversationId == conversation.id }
        val refreshed = withContext(Dispatchers.IO) {
            store.renameConversation(conversation.id, title)
            showing?.let { runCatching { it.pager.refreshed() }.getOrNull() }
        }
        refreshed?.let { showing?.show(it) }
        refreshList()
    }

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
        val title = namedAtCreate.also { namedAtCreate = "" }
        val conversation = store.newConversation(workspace, now(), cli = agent, model = model, title = title)
        refreshList()
        select(conversation.id)
    }

    /** Does what [action] names. Whatever asks, a key or a button, comes through here. */
    fun perform(action: ConsoleAction) {
        when (action) {
            ConsoleAction.DeleteConversation -> deleting = conversations.firstOrNull { it.id == currentId }
            ConsoleAction.RenameConversation -> conversations.firstOrNull { it.id == currentId }?.let(::startRenaming)
            ConsoleAction.ToggleConversationList -> listPanel.toggle()
            ConsoleAction.ToggleInspector -> inspectorPanel.toggle()
            // Opening the card needs the host, which LibraryWindow brings; see newConversationAsked.
            ConsoleAction.NewConversation -> newConversationAsked++
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

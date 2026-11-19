package loquilis.store

import loquilis.agents.ChatMessage
import loquilis.agents.HttpChatSession
import loquilis.agents.isCloudModel
import loquilis.agents.isLoopback
import loquilis.agents.unloadModel
import loquilis.agents.OpenAiChatParser
import loquilis.model.TextRole
import com.squareup.wire.Instant
import java.io.File
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import loquilis.agents.AgentParser
import loquilis.agents.AgentSession
import loquilis.agents.ClaudeLiveSession
import loquilis.agents.ClaudeStreamParser
import loquilis.agents.AcpParser
import loquilis.agents.GrokLiveSession
import loquilis.agents.OpenCodeLiveSession
import loquilis.agents.ParseOutput
import loquilis.agents.PermissionAsk
import loquilis.agents.SessionEvent
import loquilis.core.BlobSink
import loquilis.core.ContentFactory
import loquilis.core.Ulid
import loquilis.model.AgentBinding
import loquilis.model.Conversation
import loquilis.model.Decision
import loquilis.model.Outcome
import loquilis.model.OutcomeStatus
import loquilis.model.Transport

/** What a [LiveChat] is doing. */
sealed interface ChatStatus {
    /** Nothing running. A message starts or carries on the agent. */
    data object Idle : ChatStatus

    /** The agent is on a turn, with [waiting] more messages to go when it ends. */
    data class Working(val waiting: Int, val since: Instant) : ChatStatus

    /** The agent could not start or stopped early. [unsent] never reached it. */
    data class Failed(val reason: String, val unsent: List<String>) : ChatStatus
}

/** One kind of agent: how to read it, and how to start it for a conversation. */
interface Agent {
    fun parser(conversationId: String, blobs: BlobSink, newId: () -> String = Ulid::next): AgentParser

    /** Starts the agent. [firstRawSeq] follows the raw lines the conversation already has. */
    fun start(conversation: Conversation, parser: AgentParser, firstRawSeq: Long): AgentSession

    /** Whether this one works on a folder of files, which the host then has to ask for. */
    val needsFolder: Boolean get() = true

    /**
     * What this agent is, in a sentence or two a newcomer can choose by, shown where a conversation is started.
     * Each agent says it of itself, so a new one needs no change to the views.
     */
    val description: String get() = ""

    /** Where what is written in [conversation] goes. Unknown unless the agent can say from what it uses. */
    fun dataGoes(conversation: Conversation?): DataGoes = DataGoes.Unknown

    /** Whether its model has to call tools, which only some local models can. */
    val modelNeedsTools: Boolean get() = false

    /** Lets go of what the agent holds beyond its sessions, such as a model in memory, as the console closes. */
    fun close() {}
}

/**
 * Where a conversation's words go, from the addresses and settings actually in use, never from a name.
 * Doubt reads as [Unknown], never as the safe answer.
 */
sealed interface DataGoes {
    /** To [company]'s servers, which run the model. */
    data class ToCompany(val company: String) : DataGoes

    /** To a runner on this computer, with no tools that could reach further. */
    data object StaysHere : DataGoes

    /**
     * To a model on this computer, through an agent whose tools (a shell, a web fetch) can reach the network
     * when a person allows them. True of the model, not a promise about the tools.
     */
    data object ModelHere : DataGoes

    data object Unknown : DataGoes
}

/** `claude`, in its one-process-per-conversation form. A conversation carries on through `--resume`. */
class ClaudeAgent(
    private val extraArgs: List<String> = emptyList(),
    private val command: List<String> = listOf("claude"),
    private val env: Map<String, String> = System.getenv(),
) : Agent {
    override val description =
        "Claude Code, Anthropic's coding agent. It works in a folder you choose: it reads and changes files there and runs commands, asking first when it needs to."

    /**
     * Anthropic, unless `claude` was pointed elsewhere: another address, or a cloud that resells the model.
     * Even pointed at this computer it may still report to Anthropic, so anything else is unknown.
     */
    override fun dataGoes(conversation: Conversation?): DataGoes =
        if (REDIRECTS.any { !env[it].isNullOrBlank() }) DataGoes.Unknown else DataGoes.ToCompany("Anthropic")

    override fun parser(conversationId: String, blobs: BlobSink, newId: () -> String): AgentParser =
        ClaudeStreamParser(conversationId, ContentFactory(blobs), newId)

    override fun start(conversation: Conversation, parser: AgentParser, firstRawSeq: Long): AgentSession =
        ClaudeLiveSession(
            parser,
            File(conversation.workspace_path),
            model = conversation.agent?.model?.ifEmpty { null },
            extraArgs = extraArgs,
            resume = conversation.agent?.agent_session_id?.ifEmpty { null },
            firstRawSeq = firstRawSeq,
            command = command,
        )

    private companion object {
        val REDIRECTS = listOf("ANTHROPIC_BASE_URL", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX")
    }
}

/** `grok`, in its one-process-per-conversation form. A conversation carries on through its session id. */
class GrokAgent(private val command: List<String> = listOf("grok")) : Agent {
    override val description =
        "Grok, xAI's coding agent, run from its own command line. It works in a folder you choose, reading and changing files there."

    override fun dataGoes(conversation: Conversation?): DataGoes = DataGoes.ToCompany("xAI")

    override fun parser(conversationId: String, blobs: BlobSink, newId: () -> String): AgentParser =
        AcpParser(conversationId, ContentFactory(blobs), newId)

    override fun start(conversation: Conversation, parser: AgentParser, firstRawSeq: Long): AgentSession =
        GrokLiveSession(
            parser,
            File(conversation.workspace_path),
            model = conversation.agent?.model?.ifEmpty { null },
            resume = conversation.agent?.agent_session_id?.ifEmpty { null },
            firstRawSeq = firstRawSeq,
            command = command,
        )
}

/**
 * OpenCode, an open agent, on a model in Ollama on this computer. A conversation names the Ollama model it chose
 * (`gemma4:e2b`), and OpenCode reports it back with its provider (`ollama/gemma4:e2b`).
 */
class OpenCodeAgent(
    private val command: List<String> = listOf("opencode"),
    private val ollamaUrl: String = "http://localhost:11434",
    private val unload: (baseUrl: String, model: String) -> Unit = ::unloadModel,
) : Agent {
    override val description =
        "OpenCode, an open-source coding agent, with a model that runs on this computer through Ollama. It works in a folder you choose, and its tools reach the network only when you allow them."

    override val modelNeedsTools = true

    private val used = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun dataGoes(conversation: Conversation?): DataGoes {
        val model = conversation?.modelInUse ?: return DataGoes.Unknown
        val local = ollamaModel(model)
        return if (local != null && !isCloudModel(local) && isLoopback(ollamaUrl)) DataGoes.ModelHere else DataGoes.Unknown
    }

    override fun parser(conversationId: String, blobs: BlobSink, newId: () -> String): AgentParser =
        AcpParser(conversationId, ContentFactory(blobs), newId, adapter = "opencode-acp")

    override fun start(conversation: Conversation, parser: AgentParser, firstRawSeq: Long): AgentSession {
        val model = conversation.modelInUse?.let { if ('/' in it) it else "ollama/$it" }
        model?.let(::ollamaModel)?.let(used::add)
        return OpenCodeLiveSession(
            parser,
            File(conversation.workspace_path),
            model = model,
            resume = conversation.agent?.agent_session_id?.ifEmpty { null },
            firstRawSeq = firstRawSeq,
            command = command,
        )
    }

    /** OpenCode leaves the model to Ollama, which would hold it five minutes after Loquilis closes. */
    override fun close() {
        used.forEach { unload(ollamaUrl, it) }
    }

    /** The Ollama model in [model], with or without OpenCode's `ollama/`, or null for another provider's. */
    private fun ollamaModel(model: String): String? = when {
        model.startsWith("ollama/") -> model.removePrefix("ollama/")
        '/' in model -> null
        else -> model
    }
}

/**
 * Plain chat with a model on a runner at [baseUrl], such as Ollama, with no agent and so no tools. Ollama
 * listens on this address by default. A runner keeps no conversation, so each one comes from [store].
 */
class LocalChatAgent(
    private val store: Store,
    private val baseUrl: String = "http://localhost:11434",
    /**
     * How long the runner keeps a model in memory after it answers. Ollama keeps one five minutes, and a model
     * holds gigabytes, so less, but long enough that a reply read and answered does not wait for it to load again.
     */
    private val keepAlive: () -> String = { "2m" },
    private val unload: (baseUrl: String, model: String) -> Unit = ::unloadModel,
) : Agent {
    override val description =
        "A plain chat with an open model that runs on this computer through Ollama. What you write stays here. It reads no files and runs no commands."

    override val needsFolder = false

    /** The conversations this agent has run, whose models it lets go of when the console closes. */
    private val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * On this computer when the runner is, and the model is not one the runner forwards to its maker's cloud.
     * Plain chat has no tools, so nothing else can send it on.
     */
    override fun dataGoes(conversation: Conversation?): DataGoes {
        val model = conversation?.modelInUse
        return when {
            !isLoopback(baseUrl) -> DataGoes.Unknown
            model != null && isCloudModel(model) -> DataGoes.ToCompany("Ollama")
            else -> DataGoes.StaysHere
        }
    }

    override fun parser(conversationId: String, blobs: BlobSink, newId: () -> String): AgentParser =
        OpenAiChatParser(conversationId, ContentFactory(blobs), newId)

    override fun start(conversation: Conversation, parser: AgentParser, firstRawSeq: Long): AgentSession {
        started += conversation.id
        return HttpChatSession(
            parser,
            baseUrl,
            model = conversation.modelInUse,
            workspace = conversation.workspace_path,
            history = historyOf(conversation.id),
            firstRawSeq = firstRawSeq,
            keepAlive = keepAlive(),
        )
    }

    /** Frees the memory of every model it used, at once, without waiting out [keepAlive]. */
    override fun close() {
        started.mapNotNull { store.conversation(it)?.modelInUse }.toSet().forEach { unload(baseUrl, it) }
    }

    private fun historyOf(conversationId: String): List<ChatMessage> = buildList {
        store.forEachEntry(conversationId) { entry ->
            val body = entry.body
            body?.user_message?.let { add(ChatMessage("user", store.text(it.text))) }
            body?.agent_text?.takeIf { it.role == TextRole.TEXT_ROLE_REPLY }?.let { add(ChatMessage("assistant", store.text(it.text))) }
        }
    }.takeLast(RESENT_MESSAGES)

    private companion object {
        /** Enough to hold a conversation together, without outgrowing what a small model can read. */
        const val RESENT_MESSAGES = 40
    }
}

/** Adds an empty conversation that an agent will work on in [workspace]. Its first message names it. */
fun Store.newConversation(workspace: Path, at: Instant, cli: String = "claude", model: String = ""): Conversation {
    val conversation = Conversation(
        id = Ulid.next(),
        created_at = at,
        updated_at = at,
        main_branch_id = Ulid.next(),
        workspace_path = workspace.toString(),
        agent = AgentBinding(cli = cli, model = model, transport = Transport.TRANSPORT_LIVE),
        outcome = Outcome(OutcomeStatus.OUTCOME_STATUS_ACTIVE),
    )
    putConversation(conversation)
    return conversation
}

/**
 * A conversation with a running agent, recorded as it happens. The agent starts with the first message,
 * and again with the next message after it stops, carrying on from what is stored. One thread writes
 * what the session produces in the order it arrived, so views only ever read a recorded conversation.
 * Calls may come from any thread.
 */
class LiveChat(
    private val store: Store,
    val conversationId: String,
    private val agent: Agent,
    private val now: () -> Instant = { java.time.Instant.now() },
) {
    private val lock = Any()
    private var session: AgentSession? = null
    private var reader: Thread? = null
    private var turnsToGo = 0
    private var stopping = false

    private val currentStatus = MutableStateFlow<ChatStatus>(ChatStatus.Idle)
    private val waitingAsks = MutableStateFlow<List<PermissionAsk>>(emptyList())
    private val writes = MutableStateFlow(0L)
    private val beingWritten = MutableStateFlow("")

    val status: StateFlow<ChatStatus> = currentStatus.asStateFlow()

    /** What the agent is waiting to hear, oldest first. */
    val asks: StateFlow<List<PermissionAsk>> = waitingAsks.asStateFlow()

    /** Moves each time something new is on disk. */
    val written: StateFlow<Long> = writes.asStateFlow()

    /** The reply so far, held only here until its entry is stored. Empty when none is on its way. */
    val writing: StateFlow<String> = beingWritten.asStateFlow()

    fun send(text: String) {
        synchronized(lock) {
            val running = session ?: runCatching(::start).getOrElse { failure ->
                currentStatus.value = ChatStatus.Failed(failure.message ?: "The agent did not start.", listOf(text))
                return
            }
            nameConversation(text)
            running.send(text)
            turnsToGo++
            val since = (currentStatus.value as? ChatStatus.Working)?.since ?: now()
            currentStatus.value = ChatStatus.Working(turnsToGo - 1, since)
        }
    }

    fun answer(ask: PermissionAsk, decision: Decision) {
        synchronized(lock) {
            session?.answer(ask, decision)
            waitingAsks.value -= ask
        }
    }

    /** Stops the running turn. The agent stays up, and messages waiting go next. */
    fun interrupt() {
        synchronized(lock) { session?.interrupt() }
    }

    /** Ends the agent now, and returns once what it produced is on disk. */
    fun stop() {
        val finishing = synchronized(lock) {
            stopping = true
            session?.stop()
            reader
        }
        finishing?.join(STOP_MILLIS)
    }

    private fun start(): AgentSession {
        stopping = false
        val conversation = checkNotNull(store.conversation(conversationId)) { "no conversation $conversationId" }
        if (lastTurnUnfinished(conversation)) {
            recover(store, conversationId, now()) { newId -> agent.parser(conversationId, store, newId) }
        }
        val parser = agent.parser(conversationId, store)
        store.entries(conversationId, store.lastSeq(conversationId).let { it..it }).lastOrNull()?.let(parser::continueAfter)
        val started = agent.start(conversation, parser, store.rawCount(conversationId) + 1)
        store.putConversation(checkNotNull(store.conversation(conversationId)).copy(outcome = Outcome(OutcomeStatus.OUTCOME_STATUS_ACTIVE)))
        session = started
        reader = thread(isDaemon = true, name = "chat-$conversationId") { record(started, Recorder(store, conversationId, parser)) }
        return started
    }

    /** True after a crash, when entries stored last may not match the raw log. */
    private fun lastTurnUnfinished(conversation: Conversation): Boolean {
        val branch = conversation.main_branch_id
        val count = store.turnCount(conversationId, branch)
        return count > 0 && store.turns(conversationId, branch, count - 1, 1).single().result_entry_id.isEmpty()
    }

    private fun nameConversation(text: String) {
        val conversation = checkNotNull(store.conversation(conversationId))
        if (conversation.title.isEmpty()) {
            store.putConversation(conversation.copy(title = text.lineSequence().first().take(TITLE_LENGTH).trim()))
        }
    }

    private fun record(running: AgentSession, recorder: Recorder) {
        try {
            runBlocking {
                running.events.collect { event ->
                    when (event) {
                        is SessionEvent.Raw -> store.appendRaw(conversationId, event.line)
                        is SessionEvent.Parsed -> {
                            recorder.apply(event.output)
                            when (val output = event.output) {
                                is ParseOutput.Ask -> waitingAsks.value += output.ask
                                is ParseOutput.AskEnded -> waitingAsks.value = waitingAsks.value.filter { it.requestId != output.requestId }
                                is ParseOutput.Writing -> beingWritten.value = output.text
                                else -> if (endsATurn(event)) turnEnded()
                            }
                        }
                        is SessionEvent.Ended -> ended(event)
                    }
                    writes.value++
                }
            }
        } catch (failure: Exception) {
            ended(SessionEvent.Ended(-1, failure.message.orEmpty()))
        }
    }

    private fun endsATurn(event: SessionEvent.Parsed): Boolean =
        (event.output as? ParseOutput.Append)?.entry?.body?.turn_result != null

    private fun turnEnded() = synchronized(lock) {
        waitingAsks.value = emptyList()
        turnsToGo = (turnsToGo - 1).coerceAtLeast(0)
        currentStatus.value = if (turnsToGo > 0) ChatStatus.Working(turnsToGo - 1, now()) else ChatStatus.Idle
    }

    private fun ended(end: SessionEvent.Ended) = synchronized(lock) {
        session = null
        reader = null
        turnsToGo = 0
        waitingAsks.value = emptyList()
        beingWritten.value = ""
        if (stopping || (end.exitCode == 0 && end.unsent.isEmpty())) {
            currentStatus.value = ChatStatus.Idle
            return
        }
        val reason = "The agent stopped with status ${end.exitCode}. ${end.errorTail}".trim()
        runCatching {
            val conversation = checkNotNull(store.conversation(conversationId))
            store.putConversation(conversation.copy(outcome = Outcome(OutcomeStatus.OUTCOME_STATUS_ERROR, reason)))
        }
        currentStatus.value = ChatStatus.Failed(reason, end.unsent)
    }

    private companion object {
        const val STOP_MILLIS = 3000L
        const val TITLE_LENGTH = 60
    }
}

/** The model [this] conversation uses: the one its agent reported, or else the one chosen when it began. */
val Conversation.modelInUse: String?
    get() = listOfNotNull(agent?.reported_model, agent?.model).firstOrNull(String::isNotEmpty)

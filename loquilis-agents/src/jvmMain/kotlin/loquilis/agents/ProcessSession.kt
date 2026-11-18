package loquilis.agents

import com.squareup.wire.Instant
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import loquilis.model.AppCommand
import loquilis.model.Decision
import loquilis.model.Interrupt
import loquilis.model.PermissionDecision

/** What a running session produces, in the order it must be stored. */
sealed interface SessionEvent {
    /** One line exactly as the agent wrote it. It always comes before what was parsed from it. */
    data class Raw(val seq: Long, val line: String, val receivedAt: Instant) : SessionEvent

    data class Parsed(val output: ParseOutput) : SessionEvent

    /**
     * The agent's process ended. [exitCode] is 0 for a clean exit, and [unsent] holds messages that
     * were still waiting for their turn. Nothing follows this.
     */
    data class Ended(val exitCode: Int, val errorTail: String, val unsent: List<String> = emptyList()) : SessionEvent
}

/** A conversation with a running agent. */
interface AgentSession : AutoCloseable {
    val events: Flow<SessionEvent>

    /**
     * Sends a message. If the agent is mid-turn, the message waits and goes when the turn ends, so a
     * turn's entries are never interleaved with the next one's.
     */
    fun send(text: String)

    /** Answers what the agent asked. The answer is recorded as a command. */
    fun answer(ask: PermissionAsk, decision: Decision)

    /** Stops the running turn. The agent stays up, and the turn still ends with its usual result. */
    fun interrupt()

    /** Ends the session now, mid-turn if need be. It still reports [SessionEvent.Ended]. */
    fun stop()
}

/** What a [SessionProtocol] made of a line from the agent. [reply] goes straight back. */
class Heard(val opened: Boolean = false, val refused: String? = null, val reply: String? = null)

/** How one agent's process is spoken to. The lines it returns go to the agent's stdin as they are. */
interface SessionProtocol {
    /** What to send when the process starts. Messages wait until [heard] says the agent is open. */
    val opening: List<String> get() = emptyList()

    fun heard(line: String): Heard = Heard()

    fun message(text: String): String

    fun interrupting(): List<String>

    /** The user answered [requestId]. */
    fun answered(requestId: String) {}
}

/**
 * One agent process for the whole conversation, with messages written to its stdin one at a time. The
 * agent holds its context across them, and the session sends one message per turn. A message is recorded
 * when it goes to the agent rather than when it was typed.
 *
 * [firstRawSeq] numbers the raw lines after the ones a stored conversation already has.
 */
class ProcessSession(
    private val parser: AgentParser,
    private val protocol: SessionProtocol,
    commandLine: List<String>,
    workingDir: File,
    firstRawSeq: Long = 1,
    private val now: () -> Instant = { java.time.Instant.now() },
    /** Set for the agent on top of what Loquilis itself was started with. */
    environment: Map<String, String> = emptyMap(),
) : AgentSession {
    private val channel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val lock = Any()
    private var rawSeq = firstRawSeq - 1
    private val stderrTail = ArrayDeque<String>()
    private val waiting = ArrayDeque<String>()
    private var open = protocol.opening.isEmpty()
    private var turnRunning = false
    private var closing = false

    private val process: Process = ProcessBuilder(commandLine).directory(workingDir).apply { environment().putAll(environment) }.start()

    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()

    init {
        protocol.opening.forEach(::write)
    }

    override val events: Flow<SessionEvent> = channel.receiveAsFlow()

    private val stderrReader = thread(isDaemon = true, name = "agent-stderr") {
        process.errorStream.bufferedReader().forEachLine(::keepError)
    }

    private val stdoutReader = thread(isDaemon = true, name = "agent-stdout") {
        // Ending the process closes this stream, and the next read then fails rather than finding the end.
        try {
            process.inputStream.bufferedReader().forEachLine(::read)
        } catch (_: IOException) {
        }
        val exitCode = process.waitFor()
        stderrReader.join(STDERR_DRAIN_MILLIS)
        synchronized(lock) {
            parser.abandon(now()).forEach { channel.trySend(SessionEvent.Parsed(it)) }
            val tail = synchronized(stderrTail) { stderrTail.joinToString("\n") }
            channel.trySend(SessionEvent.Ended(exitCode, tail, waiting.toList()))
            waiting.clear()
            channel.close()
        }
    }

    private fun read(line: String) {
        if (line.isBlank()) return
        synchronized(lock) {
            val at = now()
            val seq = ++rawSeq
            channel.trySend(SessionEvent.Raw(seq, line, at))
            val outputs = parser.line(seq, line, at)
            outputs.forEach { channel.trySend(SessionEvent.Parsed(it)) }
            val heard = protocol.heard(line)
            heard.reply?.let(::write)
            if (heard.refused != null) {
                keepError(heard.refused)
                process.destroy()
            }
            if (heard.opened) open = true
            if (heard.opened || outputs.any(::endsATurn)) nextTurn()
        }
    }

    private fun keepError(line: String) {
        synchronized(stderrTail) {
            stderrTail.addLast(line)
            if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
        }
    }

    override fun send(text: String) {
        synchronized(lock) {
            if (turnRunning || !open) waiting.addLast(text) else deliver(text)
        }
    }

    /**
     * Ends the session once the running turn and any waiting messages are done, by closing stdin. The
     * reader thread then reports [SessionEvent.Ended].
     */
    override fun close() {
        synchronized(lock) {
            closing = true
            if (!turnRunning && waiting.isEmpty()) closeStdin()
        }
    }

    override fun answer(ask: PermissionAsk, decision: Decision) {
        val line = when (decision) {
            Decision.DECISION_ALLOW_ONCE -> ask.allow
            Decision.DECISION_ALLOW_ALWAYS -> ask.allowAlways ?: ask.allow
            else -> ask.deny
        }
        synchronized(lock) {
            record(AppCommand(permission_decision = PermissionDecision(ask.requestId, decision)))
            protocol.answered(ask.requestId)
            write(line)
        }
    }

    override fun interrupt() {
        synchronized(lock) {
            if (!turnRunning) return
            record(AppCommand(interrupt = Interrupt()))
            protocol.interrupting().forEach(::write)
        }
    }

    private fun record(command: AppCommand) {
        parser.command(command, now()).forEach { channel.trySend(SessionEvent.Parsed(it)) }
    }

    override fun stop() {
        process.destroy()
    }

    private fun deliver(text: String) {
        turnRunning = true
        parser.userMessage(text, now()).forEach { channel.trySend(SessionEvent.Parsed(it)) }
        write(protocol.message(text))
    }

    private fun write(line: String) {
        // A dead process fails the write. The reader thread is about to report that as Ended.
        runCatching {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        }
    }

    private fun nextTurn() {
        turnRunning = false
        val next = waiting.pollFirst()
        when {
            next != null -> deliver(next)
            closing -> closeStdin()
        }
    }

    private fun closeStdin() {
        runCatching { stdin.close() }
    }

    private companion object {
        const val STDERR_TAIL_LINES = 20
        const val STDERR_DRAIN_MILLIS = 1000L

        fun endsATurn(output: ParseOutput): Boolean = (output as? ParseOutput.Append)?.entry?.body?.turn_result != null
    }
}

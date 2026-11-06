package loquilis.agents

import com.squareup.wire.Instant
import java.io.BufferedWriter
import java.io.File
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonPrimitive

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

    /** Ends the session now, mid-turn if need be. It still reports [SessionEvent.Ended]. */
    fun stop()
}

/**
 * One `claude` process for the whole conversation, with messages written to its stdin one at a time.
 * Claude Code holds its context across them and emits one `result` per message. The session sends
 * one message per turn, and records a message when it goes to the agent rather than when it was typed.
 *
 * [resume] is an earlier session of the agent's own to carry on, and [firstRawSeq] numbers the raw
 * lines after the ones a stored conversation already has. [command] is the program and any fixed
 * arguments, so a test can stand a script in for `claude`.
 */
class ClaudeLiveSession(
    private val parser: AgentParser,
    workingDir: File,
    model: String? = null,
    extraArgs: List<String> = emptyList(),
    resume: String? = null,
    firstRawSeq: Long = 1,
    command: List<String> = listOf("claude"),
    private val now: () -> Instant = { java.time.Instant.now() },
) : AgentSession {
    private val channel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val lock = Any()
    private var rawSeq = firstRawSeq - 1
    private val stderrTail = ArrayDeque<String>()
    private val waiting = ArrayDeque<String>()
    private var turnRunning = false
    private var closing = false

    private val process: Process = ProcessBuilder(
        buildList {
            addAll(command)
            addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
            if (model != null) addAll(listOf("--model", model))
            if (resume != null) addAll(listOf("--resume", resume))
            addAll(extraArgs)
        },
    ).directory(workingDir).start()

    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()

    override val events: Flow<SessionEvent> = channel.receiveAsFlow()

    private val stderrReader = thread(isDaemon = true, name = "claude-stderr") {
        process.errorStream.bufferedReader().forEachLine { line ->
            synchronized(stderrTail) {
                stderrTail.addLast(line)
                if (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
            }
        }
    }

    private val stdoutReader = thread(isDaemon = true, name = "claude-stdout") {
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.isNotBlank()) synchronized(lock) {
                val at = now()
                val seq = ++rawSeq
                channel.trySend(SessionEvent.Raw(seq, line, at))
                val outputs = parser.line(seq, line, at)
                outputs.forEach { channel.trySend(SessionEvent.Parsed(it)) }
                if (outputs.any(::endsATurn)) nextTurn()
            }
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

    override fun send(text: String) {
        synchronized(lock) {
            if (turnRunning) waiting.addLast(text) else deliver(text)
        }
    }

    /**
     * Ends the session once the running turn and any waiting messages are done, by closing stdin. The
     * reader thread then reports [SessionEvent.Ended].
     */
    override fun close() {
        synchronized(lock) {
            closing = true
            if (!turnRunning) closeStdin()
        }
    }

    override fun stop() {
        process.destroy()
    }

    private fun deliver(text: String) {
        turnRunning = true
        parser.userMessage(text, now()).forEach { channel.trySend(SessionEvent.Parsed(it)) }
        // A dead process fails the write. The reader thread is about to report that as Ended.
        runCatching {
            stdin.write(userMessageLine(text))
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

        fun userMessageLine(text: String): String {
            val quoted = JsonPrimitive(text).toString()
            return """{"type":"user","message":{"role":"user","content":[{"type":"text","text":$quoted}]}}"""
        }
    }
}

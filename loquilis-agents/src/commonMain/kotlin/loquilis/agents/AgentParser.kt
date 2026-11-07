package loquilis.agents

import com.squareup.wire.Instant
import loquilis.model.AppCommand
import loquilis.model.Entry

/** What reading one line of an agent's stream produced, in order. */
sealed interface ParseOutput {
    /** A new entry. */
    data class Append(val entry: Entry) : ParseOutput

    /** A new version of an entry already appended, with the same id and seq. */
    data class Update(val entry: Entry) : ParseOutput

    /** What the agent reported about itself. Fills in the conversation's [loquilis.model.AgentBinding]. */
    data class Session(val info: SessionInfo) : ParseOutput

    /** The agent is waiting for an answer. The request itself is an entry, appended just before this. */
    data class Ask(val ask: PermissionAsk) : ParseOutput

    /** The agent no longer needs an answer to [requestId]. */
    data class AskEnded(val requestId: String) : ParseOutput

    /**
     * The reply as far as it has been written, empty when nothing is being written. It is for showing
     * only and is never stored: the raw lines hold it, and the entry that follows replaces it.
     */
    data class Writing(val text: String) : ParseOutput
}

data class SessionInfo(
    val cliVersion: String,
    val model: String,
    val sessionId: String,
    val workspacePath: String,
)

/**
 * The agent asking whether it may do something. Each answer is the exact line to send back, so a host
 * can offer the choices with no idea how this agent's protocol works. [allowAlways] and [remember],
 * which says what it would allow from now on, are null when the agent offers nothing lasting.
 */
data class PermissionAsk(
    val requestId: String,
    val tool: String,
    val description: String,
    val detail: String,
    val remember: String?,
    val allow: String,
    val allowAlways: String?,
    val deny: String,
)

/**
 * Turns one agent's stream into entries, one line at a time, so a live turn and a re-parse of stored
 * raw events run the same code. Adding an agent means writing one of these plus a fixture test.
 *
 * A parser holds a conversation's state and is not thread-safe. It never throws on what an agent
 * sends: a line it cannot read becomes a notice, and a line it has no use for stays in the raw log.
 */
interface AgentParser {
    /** Which parser this is, for [loquilis.model.ParserRef]. */
    val adapter: String

    /** Bumped whenever the same raw events would parse to different entries. */
    val version: Int

    /** The user's message. Agents do not echo it in their streams, so the app records it here. */
    fun userMessage(text: String, at: Instant): List<ParseOutput>

    /** A command the app sent the agent. It is not in the agent's stream either, so it goes through here. */
    fun command(command: AppCommand, at: Instant): List<ParseOutput>

    /** One raw line. [rawSeq] is its number in the conversation's raw log. */
    fun line(rawSeq: Long, line: String, receivedAt: Instant): List<ParseOutput>

    /** The agent stopped mid-turn. Whatever was still running will never finish. */
    fun abandon(at: Instant): List<ParseOutput>

    /** Carries on a stored conversation: what comes next follows [last], its newest entry. */
    fun continueAfter(last: Entry)
}

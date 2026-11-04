package loquilis.agents

import com.squareup.wire.Instant
import loquilis.model.Entry

/** What reading one line of an agent's stream produced, in order. */
sealed interface ParseOutput {
    /** A new entry. */
    data class Append(val entry: Entry) : ParseOutput

    /** A new version of an entry already appended, with the same id and seq. */
    data class Update(val entry: Entry) : ParseOutput

    /** What the agent reported about itself. Fills in the conversation's [loquilis.model.AgentBinding]. */
    data class Session(val info: SessionInfo) : ParseOutput
}

data class SessionInfo(
    val cliVersion: String,
    val model: String,
    val sessionId: String,
    val workspacePath: String,
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

    /** One raw line. [rawSeq] is its number in the conversation's raw log. */
    fun line(rawSeq: Long, line: String, receivedAt: Instant): List<ParseOutput>

    /** The agent stopped mid-turn. Whatever was still running will never finish. */
    fun abandon(at: Instant): List<ParseOutput>
}

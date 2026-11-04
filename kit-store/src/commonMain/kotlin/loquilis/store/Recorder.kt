package loquilis.store

import com.squareup.wire.Instant
import loquilis.agents.AgentParser
import loquilis.agents.ParseOutput
import loquilis.kit.Ulid
import loquilis.model.AgentBinding
import loquilis.model.Entry
import loquilis.model.ParserRef

/**
 * Writes one conversation as it happens. A raw line and what was parsed from it commit together, so a
 * crash can never leave an entry without its line.
 */
class Recorder(private val store: Store, private val conversationId: String, private val parser: AgentParser) {
    fun userMessage(text: String, at: Instant) = store.transaction { parser.userMessage(text, at).forEach(::apply) }

    fun line(line: String, at: Instant) = store.transaction {
        val seq = store.appendRaw(conversationId, line)
        parser.line(seq, line, at).forEach(::apply)
    }

    fun abandon(at: Instant) = store.transaction { parser.abandon(at).forEach(::apply) }

    /** Stores one output of a parser that runs elsewhere, as a live session's does. */
    fun apply(output: ParseOutput) {
        when (output) {
            is ParseOutput.Append -> store.appendEntry(output.entry)
            is ParseOutput.Update -> store.updateEntry(output.entry)
            is ParseOutput.Session -> {
                val conversation = checkNotNull(store.conversation(conversationId)) { "no conversation $conversationId" }
                val agent = conversation.agent ?: AgentBinding()
                store.putConversation(
                    conversation.copy(
                        agent = agent.copy(
                            cli_version = output.info.cliVersion,
                            reported_model = output.info.model,
                            agent_session_id = output.info.sessionId,
                        ),
                        workspace_path = output.info.workspacePath,
                        parser = ParserRef(parser.adapter, parser.version),
                    ),
                )
            }
        }
    }
}

/**
 * Brings stored entries back in line with the raw log, after a crash or a parser fix. Raw lines are
 * the truth: a fresh parser replays them, and the entries it makes replace what is stored. Entries
 * keep their ids, so links into the conversation survive. Tool calls still open at the end of the
 * log are closed as interrupted, since their process is gone.
 *
 * User messages are not in the raw log, so they come from their entries, each replayed after the raw
 * lines of the entry before it. Holds the conversation in memory while it runs. Returns how many
 * entries it changed.
 */
fun recover(store: Store, conversationId: String, at: Instant, parserOf: (newId: () -> String) -> AgentParser): Int {
    val stored = ArrayList<Entry>()
    store.forEachEntry(conversationId) { stored += it }
    val ids = ArrayDeque(stored.map { it.id })
    val parser = parserOf { ids.removeFirstOrNull() ?: Ulid.next() }

    val replayed = linkedMapOf<String, Entry>()
    fun take(outputs: List<ParseOutput>) = outputs.forEach { output ->
        when (output) {
            is ParseOutput.Append -> replayed[output.entry.id] = output.entry
            is ParseOutput.Update -> replayed[output.entry.id] = output.entry
            is ParseOutput.Session -> Unit
        }
    }

    var nextRaw = 1L
    fun feedThrough(seq: Long) {
        while (nextRaw <= seq) {
            val page = store.raw(conversationId, nextRaw..minOf(seq, nextRaw + 999))
            page.forEach { line -> take(parser.line(nextRaw++, line, at)) }
        }
    }

    var mark = 0L
    for (entry in stored) {
        val range = entry.raw
        val message = entry.body?.user_message
        if (range != null) {
            mark = maxOf(mark, range.last_seq)
        } else if (message != null) {
            feedThrough(mark)
            take(parser.userMessage(store.text(message.text), entry.at ?: at))
        }
    }
    feedThrough(store.rawCount(conversationId))
    take(parser.abandon(at))

    val before = stored.associateBy { it.id }
    var changed = 0
    store.transaction {
        for (entry in replayed.values) {
            val old = before[entry.id]
            when {
                old == null -> store.appendEntry(entry).also { changed++ }
                old != entry -> store.updateEntry(entry).also { changed++ }
            }
        }
    }
    if (changed > 0) store.rebuildTurns(conversationId)
    return changed
}

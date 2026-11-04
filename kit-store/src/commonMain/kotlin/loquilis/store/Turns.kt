package loquilis.store

import loquilis.kit.ContentFactory
import loquilis.kit.EntryKind
import loquilis.kit.kind
import loquilis.kit.preview
import loquilis.model.Content
import loquilis.model.Entry
import loquilis.model.TextRole
import loquilis.model.TurnSummary

/**
 * How entries add up to a [TurnSummary]. The store folds these on every write, and a rebuild folds the
 * same functions over stored entries, so the two cannot disagree.
 */
object Turns {
    /** [current] after appending [entry]: a new turn for a user message, else the same turn grown. */
    fun append(current: TurnSummary?, entry: Entry, branchId: String): TurnSummary? {
        val kind = entry.body?.kind
        if (kind is EntryKind.User) {
            return TurnSummary(
                conversation_id = entry.conversation_id,
                branch_id = branchId,
                ordinal = (current?.ordinal ?: 0) + 1,
                user_entry_id = entry.id,
                first_seq = entry.seq,
                last_seq = entry.seq,
                started_at = entry.at,
                user_head = head(kind.message.text),
            )
        }
        val turn = current?.copy(last_seq = maxOf(current.last_seq, entry.seq)) ?: return null
        return when (kind) {
            is EntryKind.Call -> turn.copy(tool_call_count = turn.tool_call_count + 1)
            is EntryKind.Result -> if (kind.result.is_error) turn.copy(error_count = turn.error_count + 1) else turn
            is EntryKind.Made -> turn.copy(artifact_count = turn.artifact_count + 1)
            is EntryKind.TurnEnd -> turn.copy(
                result_entry_id = entry.id,
                duration = kind.result.duration,
                usage = kind.result.usage,
            )
            else -> withReply(turn, entry)
        }
    }

    /** [turn] after [entry] changed in place. Only a reply's promotion changes what a turn shows. */
    fun update(turn: TurnSummary, entry: Entry): TurnSummary = withReply(turn, entry)

    private fun withReply(turn: TurnSummary, entry: Entry): TurnSummary {
        val text = (entry.body?.kind as? EntryKind.Text)?.text ?: return turn
        if (text.role != TextRole.TEXT_ROLE_REPLY || turn.reply_head.isNotEmpty()) return turn
        return turn.copy(reply_head = head(text.text))
    }

    private fun head(content: Content?): String = ContentFactory.headOf(content?.preview.orEmpty())
}

/** The text search should find in [entry]. Private entries stay out, as they are hidden by default. */
fun searchableText(entry: Entry, store: Store): String? = when (val kind = entry.body?.kind) {
    is EntryKind.User -> store.text(kind.message.text)
    is EntryKind.Text -> store.text(kind.text.text)
    is EntryKind.Call -> listOf(kind.call.description, kind.call.target_path, store.text(kind.call.input))
        .filter { it.isNotEmpty() }.joinToString("\n")
    is EntryKind.Result -> store.text(kind.result.output)
    is EntryKind.Made -> kind.artifact.title
    is EntryKind.Note -> kind.notice.text
    else -> null
}

package loquilis.agents

import com.squareup.wire.Instant
import com.squareup.wire.ofEpochSecond as instantOfEpochSecond
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import loquilis.model.Author
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.RawRange
import loquilis.model.Visibility

/**
 * The entries a parser has written: it numbers each one and links it to the one before. Entries in
 * the main line of the turn link to the one before. A subagent's entries hang under the call that
 * started it and leave the main line where it was.
 */
internal class EntryChain(private val conversationId: String, private val newId: () -> String) {
    private var seq = 0L

    /** The newest entry on the main line. */
    var spine = ""
        private set

    fun append(
        at: Instant,
        author: Author,
        body: EntryBody,
        raw: RawRange?,
        parent: String? = null,
        state: EntryState = EntryState.ENTRY_STATE_COMPLETE,
        visibility: Visibility = Visibility.VISIBILITY_NORMAL,
    ): ParseOutput.Append {
        val entry = Entry(
            id = newId(),
            conversation_id = conversationId,
            seq = ++seq,
            parent_id = parent ?: spine,
            at = at,
            author = author,
            state = state,
            visibility = visibility,
            raw = raw,
            body = body,
        )
        if (parent == null) spine = entry.id
        return ParseOutput.Append(entry)
    }

    fun continueAfter(last: Entry) {
        seq = last.seq
        spine = last.id
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun elapsedBetween(from: Instant, to: Instant) = run {
    val nanos = (to.getEpochSecond() - from.getEpochSecond()) * 1_000_000_000 + (to.getNano() - from.getNano())
    com.squareup.wire.durationOfSeconds(nanos.floorDiv(1_000_000_000), nanos.mod(1_000_000_000L))
}

internal fun instantOfMillis(millis: Long): Instant =
    instantOfEpochSecond(millis.floorDiv(1000), millis.mod(1000L) * 1_000_000)

package loquilis.views

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lays turns out so a list costs nothing per turn: the runs of closed turns are single counted blocks,
 * however many turns they span, and only an open turn adds items of its own.
 */
internal fun LazyListScope.turnItems(
    first: Int,
    last: Int,
    open: Collection<Int>,
    closed: @Composable (ordinal: Int) -> Unit,
    opened: LazyListScope.(ordinal: Int) -> Unit,
) {
    fun run(from: Int, until: Int) {
        if (until > from) items(until - from, key = { "turn-${from + it}" }) { closed(from + it) }
    }
    var next = first
    for (ordinal in open.sorted()) {
        run(next, ordinal)
        opened(ordinal)
        next = ordinal + 1
    }
    run(next, last + 1)
}

/** A turn, null until its read from the disk finishes. Reads happen off the main thread. */
@Composable
internal fun rememberTurn(pager: ConversationPager, ordinal: Int): State<LoadedTurn?> =
    produceState(pager.cached(ordinal), pager, ordinal) {
        // A new pager may hold a newer turn, or another conversation's: never keep showing the old one.
        value = pager.cached(ordinal)
        if (value == null) value = withContext(Dispatchers.IO) { pager.load(ordinal) }
    }

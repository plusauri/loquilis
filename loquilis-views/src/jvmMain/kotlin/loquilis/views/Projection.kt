package loquilis.views

import loquilis.core.CommandKind
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.core.preview
import loquilis.model.AppCommand
import loquilis.model.Content
import loquilis.model.Decision
import loquilis.model.Entry
import loquilis.model.EntryState
import loquilis.model.TextRole
import loquilis.model.ToolKind
import loquilis.model.TurnResult
import loquilis.model.TurnSummary
import loquilis.model.Visibility
import loquilis.store.Store

/**
 * Both views are plain functions of the same entries, so neither owns any data and switching between
 * them loses nothing. Everything here is free of Compose, which is what lets the tests pin it.
 */

/** One row of the Messages view. [turn] is the turn's ordinal; rows before the first user message are turn 0. */
sealed interface MessageRow {
    val key: String
    val turn: Int

    data class Said(val entry: Entry, override val turn: Int) : MessageRow {
        override val key get() = entry.id
    }

    /** The fold that holds a turn's [Step]s. */
    data class Work(override val turn: Int, val summary: String) : MessageRow {
        override val key get() = "work-$turn"
    }

    /** One thing the agent did. A tool call carries its result, so the two read as a single step. */
    data class Step(val entry: Entry, val result: Entry?, val depth: Int, override val turn: Int) : MessageRow {
        override val key get() = entry.id
    }

    data class Reply(val entry: Entry, override val turn: Int) : MessageRow {
        override val key get() = entry.id
    }

    data class Note(val entry: Entry, override val turn: Int) : MessageRow {
        override val key get() = entry.id
    }

    data class Footer(val entry: Entry, val result: TurnResult, override val turn: Int) : MessageRow {
        override val key get() = entry.id
    }
}

/**
 * Lays entries out as a chat, counting turns on from [firstTurn]. Private entries (the agent's thinking) are left out, as the model says
 * they are by default; every other entry lands on exactly one row.
 */
fun messagesOf(entries: List<Entry>, firstTurn: Int = 0): List<MessageRow> {
    val resultsByCall = entries.mapNotNull { e -> e.body?.tool_result?.let { it.call_id to e } }.toMap()
    val calledIds = entries.mapNotNull { it.body?.tool_call?.call_id }.toSet()
    val byId = entries.associateBy { it.id }
    val rows = mutableListOf<MessageRow>()

    var turn = firstTurn
    val steps = mutableListOf<MessageRow.Step>()
    val closing = mutableListOf<MessageRow>()

    fun endTurn() {
        if (steps.isNotEmpty()) rows += MessageRow.Work(turn, workSummary(steps))
        rows += steps
        rows += closing
        steps.clear()
        closing.clear()
    }

    for (entry in entries) {
        if (entry.visibility == Visibility.VISIBILITY_PRIVATE) continue
        val depth = subAgentDepth(entry, byId)
        when (val kind = entry.body?.kind ?: EntryKind.Unknown) {
            is EntryKind.User -> {
                endTurn()
                turn++
                rows += MessageRow.Said(entry, turn)
            }
            is EntryKind.Text ->
                if (kind.text.role == TextRole.TEXT_ROLE_REPLY && depth == 0) closing += MessageRow.Reply(entry, turn)
                else steps += MessageRow.Step(entry, null, depth, turn)
            is EntryKind.Call -> steps += MessageRow.Step(entry, resultsByCall[kind.call.call_id], depth, turn)
            is EntryKind.Result ->
                if (kind.result.call_id !in calledIds) steps += MessageRow.Step(entry, null, depth, turn)
            is EntryKind.TurnEnd -> closing += MessageRow.Footer(entry, kind.result, turn)
            is EntryKind.Note -> closing += MessageRow.Note(entry, turn)
            else -> steps += MessageRow.Step(entry, null, depth, turn)
        }
    }
    endTurn()
    return rows
}

/** How many subagents an entry sits inside: each call it hangs under, other than the main line's. */
private fun subAgentDepth(entry: Entry, byId: Map<String, Entry>): Int {
    val parent = byId[entry.parent_id] ?: return 0
    val call = parent.body?.tool_call ?: return 0
    if (entry.body?.tool_result?.call_id == call.call_id) return 0
    return if (call.kind == ToolKind.TOOL_KIND_TASK) 1 else 0
}

private const val FAILED_MARK = "❌"
private const val STOPPED_MARK = "⏹️"
private const val DONE_MARK = "✅"

/** How a step came out, readable without telling red from blue. Null while it runs and where nothing was at stake. */
fun outcomeMark(entry: Entry, result: Entry?): String? {
    val settled = result ?: entry.takeIf { it.body?.tool_result != null }
    val states = listOfNotNull(entry.state, settled?.state)
    return when {
        EntryState.ENTRY_STATE_FAILED in states -> FAILED_MARK
        EntryState.ENTRY_STATE_INTERRUPTED in states -> STOPPED_MARK
        settled?.state == EntryState.ENTRY_STATE_COMPLETE -> DONE_MARK
        else -> null
    }
}

/** What the fold says while closed: "6 tool calls: 4 commands, 2 reads, ❌ 1 failed", or "8 commands". */
fun workSummary(steps: List<MessageRow.Step>): String {
    val calls = steps.mapNotNull { it.entry.body?.tool_call }
    if (calls.isEmpty()) return plural(steps.size, "step", "steps")
    val byKind = calls.groupingBy { it.kind }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<ToolKind, Int>> { it.value }.thenBy { it.key.ordinal })
        .map { (kind, count) -> nounOf(kind).let { (one, many) -> plural(count, one, many) } }
    val failed = steps.count { it.result?.state == EntryState.ENTRY_STATE_FAILED }
    val parts = byKind + listOfNotNull(if (failed > 0) "$FAILED_MARK $failed failed" else null)
    return if (byKind.size == 1) parts.joinToString(", ")
    else "${plural(calls.size, "tool call", "tool calls")}: ${parts.joinToString(", ")}"
}

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

private fun nounOf(kind: ToolKind): Pair<String, String> = when (kind) {
    ToolKind.TOOL_KIND_SHELL -> "command" to "commands"
    ToolKind.TOOL_KIND_FILE_READ -> "read" to "reads"
    ToolKind.TOOL_KIND_FILE_WRITE -> "write" to "writes"
    ToolKind.TOOL_KIND_FILE_EDIT -> "edit" to "edits"
    ToolKind.TOOL_KIND_SEARCH -> "search" to "searches"
    ToolKind.TOOL_KIND_WEB -> "web call" to "web calls"
    ToolKind.TOOL_KIND_MCP -> "MCP call" to "MCP calls"
    ToolKind.TOOL_KIND_TASK -> "subagent" to "subagents"
    else -> "other" to "others"
}

/** One row of the Tree view. */
data class TreeRow(val entry: Entry, val depth: Int)

/**
 * Lays entries out by their real parent links. Taken literally, a conversation is one very long chain,
 * since each entry's parent is the one before, so only real structure indents here: a turn's contents
 * sit under its user message, and a chain carries on at its own depth, with side children (a tool's
 * result, a subagent's work, a fork) one level in.
 *
 * Walks with an explicit stack, since the chain's length is the length of the conversation.
 */
fun treeOf(entries: List<Entry>): List<TreeRow> {
    val known = entries.mapTo(hashSetOf()) { it.id }
    val children = entries.filter { it.parent_id in known }.groupBy { it.parent_id }
    val roots = entries.filter { it.parent_id !in known }

    class Visit(val entry: Entry, val depth: Int, val turnDepth: Int)

    val rows = ArrayList<TreeRow>(entries.size)
    val stack = ArrayDeque<Visit>()
    roots.asReversed().forEach { stack.addLast(Visit(it, 0, 0)) }

    while (stack.isNotEmpty()) {
        val visit = stack.removeLast()
        val isUser = visit.entry.body?.user_message != null
        val shown = if (isUser) visit.turnDepth else visit.depth
        rows += TreeRow(visit.entry, shown)

        val inner = if (isUser) visit.turnDepth + 1 else visit.depth
        val kids = children[visit.entry.id].orEmpty()
        val next = kids.lastOrNull { it.body?.tool_result == null }
        next?.let { stack.addLast(Visit(it, inner, visit.turnDepth)) }
        kids.filter { it !== next }.asReversed().forEach { stack.addLast(Visit(it, inner + 1, inner + 1)) }
    }
    return rows
}

/** The ids of rows with something nested under them: the next row in the full tree sits deeper. */
fun treeParents(rows: List<TreeRow>): Set<String> =
    rows.zipWithNext().filter { (row, next) -> next.depth > row.depth }.mapTo(hashSetOf()) { it.first.entry.id }

/** The rows left showing once the rows in [collapsed] hide everything nested under them. */
fun visibleTree(rows: List<TreeRow>, collapsed: Set<String>): List<TreeRow> {
    val visible = ArrayList<TreeRow>(rows.size)
    var hideDeeperThan = Int.MAX_VALUE
    for (row in rows) {
        if (row.depth > hideDeeperThan) continue
        hideDeeperThan = if (row.entry.id in collapsed) row.depth else Int.MAX_VALUE
        visible += row
    }
    return visible
}

/** The rows above [id] that would hide it if collapsed. */
fun treeAncestors(rows: List<TreeRow>, id: String): List<String> {
    val at = rows.indexOfFirst { it.entry.id == id }
    if (at < 0) return emptyList()
    val ancestors = mutableListOf<String>()
    var depth = rows[at].depth
    for (i in at - 1 downTo 0) {
        if (rows[i].depth < depth) {
            ancestors += rows[i].entry.id
            depth = rows[i].depth
        }
    }
    return ancestors
}

/** A short name for what an entry is. */
fun kindLabel(entry: Entry): String = when (val kind = entry.body?.kind ?: EntryKind.Unknown) {
    is EntryKind.User -> "You"
    is EntryKind.Text -> when (kind.text.role) {
        TextRole.TEXT_ROLE_REPLY -> "Reply"
        TextRole.TEXT_ROLE_INJECTED -> "Injected"
        else -> "Narration"
    }
    is EntryKind.Reasoning -> "Thinking"
    is EntryKind.Call -> kind.call.tool
    is EntryKind.Result -> "Result"
    is EntryKind.Made -> "Artifact"
    is EntryKind.Sub -> "Subagent"
    is EntryKind.Command -> "You"
    is EntryKind.Permission -> "Asked"
    is EntryKind.TurnEnd -> "Turn end"
    is EntryKind.Note -> "Notice"
    EntryKind.Unknown -> "Unknown"
}

/** One line saying what an entry holds, from what the record has inline: never touches a blob. */
fun headline(entry: Entry): String = when (val kind = entry.body?.kind ?: EntryKind.Unknown) {
    is EntryKind.User -> firstLine(kind.message.text)
    is EntryKind.Text -> firstLine(kind.text.text)
    is EntryKind.Reasoning -> firstLine(kind.thinking.text)
    is EntryKind.Call -> kind.call.description
        .ifBlank { kind.call.target_path }
        .ifBlank { firstLine(kind.call.input) }
    is EntryKind.Result -> firstLine(kind.result.output).ifBlank { "(no output)" }
    is EntryKind.Made -> kind.artifact.title
    is EntryKind.Sub -> kind.subAgent.label
    is EntryKind.Command -> commandLine(kind.command)
    is EntryKind.Permission -> kind.request.description.ifBlank { "Asked for permission" }
    is EntryKind.TurnEnd -> turnEndLine(entry, kind.result)
    is EntryKind.Note -> kind.notice.text
    EntryKind.Unknown -> "Written by a newer schema"
}

private fun commandLine(command: AppCommand): String = when (val kind = command.kind) {
    is CommandKind.Stop -> "Stopped the turn"
    is CommandKind.Decide -> when (kind.decision.decision) {
        Decision.DECISION_ALLOW_ONCE -> "Allowed"
        Decision.DECISION_ALLOW_ALWAYS -> "Allowed from now on"
        else -> "Denied"
    }
    else -> "App command"
}

private fun firstLine(content: Content?): String =
    content?.preview.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** How a turn ended: its [turnLine], or "Stopped · 7.4 s" for one the user stopped, which reports no tokens and may report no cost. */
fun turnEndLine(entry: Entry, result: TurnResult): String {
    if (entry.state != EntryState.ENTRY_STATE_INTERRUPTED) return turnLine(result)
    return buildList {
        add("Stopped")
        result.duration?.let { add(seconds(it.getSeconds() + it.getNano() / 1e9)) }
        result.usage?.cost_micro_usd?.takeIf { it > 0 }?.let { add(money(it)) }
    }.joinToString(" · ")
}

/** "12.4 s · $0.031 · 1,204 tokens out · 3 round trips". */
fun turnLine(result: TurnResult): String = buildList {
    result.duration?.let { add(seconds(it.getSeconds() + it.getNano() / 1e9)) }
    result.usage?.let { usage ->
        add(money(usage.cost_micro_usd))
        add("${"%,d".format(usage.output_tokens)} tokens out")
    }
    if (result.agent_turns > 0) add(plural(result.agent_turns, "round trip", "round trips"))
}.joinToString(" · ")

fun seconds(value: Double): String =
    if (value < 60) "%.1f s".format(value) else "%d m %02d s".format((value / 60).toInt(), (value % 60).toInt())

fun money(microUsd: Long): String = "$%.3f".format(microUsd / 1_000_000.0)

fun bytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

/** What a closed turn's tree line says about the work inside it. */
fun turnTag(turn: TurnSummary?): String? = turn?.let {
    listOfNotNull(
        plural(it.tool_call_count, "call", "calls").takeIf { _ -> it.tool_call_count > 0 },
        "${it.error_count} failed".takeIf { _ -> it.error_count > 0 },
    ).joinToString(" · ").ifEmpty { null }
}

/** Splits a search snippet into plain and matched runs, in order. */
fun snippetRuns(snippet: String): List<Pair<String, Boolean>> = buildList {
    var rest = snippet
    while (rest.isNotEmpty()) {
        val open = rest.indexOf(Store.MATCH_OPEN)
        if (open < 0) {
            add(rest to false)
            break
        }
        val close = rest.indexOf(Store.MATCH_CLOSE, open).let { if (it < 0) rest.length else it }
        if (open > 0) add(rest.substring(0, open) to false)
        add(rest.substring(open + 1, close) to true)
        rest = rest.substring((close + 1).coerceAtMost(rest.length))
    }
}

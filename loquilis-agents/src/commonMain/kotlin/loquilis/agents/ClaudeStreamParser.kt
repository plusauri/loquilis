package loquilis.agents

import com.squareup.wire.Instant
import com.squareup.wire.durationOfSeconds
import com.squareup.wire.ofEpochSecond
import kotlin.math.roundToLong
import kotlin.time.Instant as KotlinInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import loquilis.core.ContentFactory
import loquilis.core.Ulid
import loquilis.model.AgentText
import loquilis.model.Author
import loquilis.model.BlobRef
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.Notice
import loquilis.model.NoticeLevel
import loquilis.model.RawRange
import loquilis.model.TextRole
import loquilis.model.Thinking
import loquilis.model.ToolCall
import loquilis.model.ToolKind
import loquilis.model.ToolResult
import loquilis.model.TurnResult
import loquilis.model.Usage
import loquilis.model.UserMessage
import loquilis.model.Visibility
import okio.ByteString.Companion.decodeBase64

/**
 * Reads a Claude Code `stream-json` session (`claude -p --output-format stream-json --verbose`).
 *
 * What the stream looks like, as read from recorded sessions on Claude Code 2.1.x:
 * - The user's own message is not echoed, which is why [userMessage] exists.
 * - One API message arrives as several `assistant` events, one content block each.
 * - `thinking` blocks usually carry an empty `thinking` with only a signature. An empty one becomes no
 *   entry, since it stays in the raw log and there is nothing to show.
 * - Which text is the reply is only known at the end of a turn, so all assistant text is narration
 *   until the turn's `result` promotes what came after the last tool call.
 * - `total_cost_usd` is cumulative over the session, while `usage` covers the one turn.
 * - `system` (`thinking_tokens`, `task_*`), `tool_progress` and allowed `rate_limit_event` lines are
 *   telemetry. They stay in the raw log and make no entry.
 */
class ClaudeStreamParser(
    private val conversationId: String,
    private val contents: ContentFactory,
    private val newId: () -> String = Ulid::next,
) : AgentParser {
    override val adapter = "claude-stream-json"
    override val version = 1

    private var seq = 0L
    private var spine = ""
    private var costSoFarMicroUsd = 0L
    private var session: SessionInfo? = null
    private var lastAt: Instant? = null
    private val callsById = mutableMapOf<String, Entry>()
    private val textSinceLastTool = mutableListOf<Entry>()

    override fun userMessage(text: String, at: Instant): List<ParseOutput> {
        lastAt = at
        return listOf(append(at, Author.AUTHOR_USER, EntryBody(user_message = UserMessage(contents.text(text))), raw = null))
    }

    override fun line(rawSeq: Long, line: String, receivedAt: Instant): List<ParseOutput> {
        val raw = RawRange(rawSeq, rawSeq)
        val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return listOf(append(receivedAt, Author.AUTHOR_APP, unreadable(line), raw))
        val at = event.str("timestamp")?.let(::instantOf) ?: lastAt ?: receivedAt
        lastAt = at
        return when (event.str("type")) {
            "system" -> if (event.str("subtype") == "init") init(event) else emptyList()
            "assistant" -> assistant(event, at, raw)
            "user" -> user(event, at, raw)
            "rate_limit_event" -> rateLimit(event, at, raw)
            "result" -> result(event, at, raw)
            else -> emptyList()
        }
    }

    override fun abandon(at: Instant): List<ParseOutput> {
        val open = callsById.values.filter { it.state == EntryState.ENTRY_STATE_STREAMING }
        callsById.clear()
        textSinceLastTool.clear()
        return open.map { ParseOutput.Update(it.copy(state = EntryState.ENTRY_STATE_INTERRUPTED)) }
    }

    override fun continueAfter(last: Entry) {
        seq = last.seq
        spine = last.id
        lastAt = last.at
    }

    private fun init(event: JsonObject): List<ParseOutput> {
        val info = SessionInfo(
            cliVersion = event.str("claude_code_version").orEmpty(),
            model = event.str("model").orEmpty(),
            sessionId = event.str("session_id").orEmpty(),
            workspacePath = event.str("cwd").orEmpty(),
        )
        if (info == session) return emptyList()
        session = info
        return listOf(ParseOutput.Session(info))
    }

    private fun assistant(event: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val parent = parentOf(event)
        return blocksOf(event).flatMap { block ->
            when (block.str("type")) {
                "text" -> agentText(block.str("text").orEmpty(), at, raw, parent)
                "thinking" -> thinking(block.str("thinking").orEmpty(), at, raw, parent)
                "tool_use" -> listOf(toolUse(block, at, raw, parent))
                else -> emptyList()
            }
        }
    }

    private fun agentText(text: String, at: Instant, raw: RawRange, parent: String?): List<ParseOutput> {
        if (text.isBlank()) return emptyList()
        val body = EntryBody(agent_text = AgentText(contents.text(text), TextRole.TEXT_ROLE_NARRATION))
        val output = append(at, Author.AUTHOR_AGENT, body, raw, parent)
        if (parent == null) textSinceLastTool += output.entry
        return listOf(output)
    }

    private fun thinking(text: String, at: Instant, raw: RawRange, parent: String?): List<ParseOutput> {
        if (text.isBlank()) return emptyList()
        val body = EntryBody(thinking = Thinking(contents.text(text)))
        return listOf(append(at, Author.AUTHOR_AGENT, body, raw, parent, visibility = Visibility.VISIBILITY_PRIVATE))
    }

    private fun toolUse(block: JsonObject, at: Instant, raw: RawRange, parent: String?): ParseOutput {
        val name = block.str("name").orEmpty()
        val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
        val command = input.str("command")
        val call = ToolCall(
            call_id = block.str("id").orEmpty(),
            tool = name,
            kind = kindOf(name),
            input = if (command != null) contents.text(command) else contents.text(input.toString(), "application/json"),
            target_path = input.str("file_path") ?: input.str("notebook_path") ?: "",
            description = input.str("description").orEmpty(),
        )
        if (parent == null) textSinceLastTool.clear()
        val output = append(at, Author.AUTHOR_AGENT, EntryBody(tool_call = call), raw, parent, state = EntryState.ENTRY_STATE_STREAMING)
        callsById[call.call_id] = output.entry
        return output
    }

    private fun user(event: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val parent = parentOf(event)
        return blocksOf(event).flatMap { block ->
            when (block.str("type")) {
                "tool_result" -> toolResult(block, at, raw)
                "text" -> injected(block.str("text").orEmpty(), at, raw, parent)
                else -> emptyList()
            }
        }
    }

    private fun injected(text: String, at: Instant, raw: RawRange, parent: String?): List<ParseOutput> {
        if (text.isBlank()) return emptyList()
        val body = EntryBody(agent_text = AgentText(contents.text(text), TextRole.TEXT_ROLE_INJECTED))
        return listOf(append(at, Author.AUTHOR_SYSTEM, body, raw, parent))
    }

    private fun toolResult(block: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val callId = block.str("tool_use_id").orEmpty()
        val call = callsById.remove(callId)
        val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
        val images = mutableListOf<BlobRef>()
        val output = resultText(block["content"], images)
        val result = ToolResult(
            call_id = callId,
            output = contents.text(output),
            is_error = isError,
            elapsed = call?.at?.let { elapsedBetween(it, at) },
            images = images,
        )
        val state = if (isError) EntryState.ENTRY_STATE_FAILED else EntryState.ENTRY_STATE_COMPLETE
        val appended = append(at, Author.AUTHOR_TOOL, EntryBody(tool_result = result), raw, parent = call?.id ?: spine, state = state)
        return listOfNotNull(call?.let { ParseOutput.Update(it.copy(state = state)) }, appended)
    }

    /** A tool result's content is a string, or blocks of text and images. Images go to the blob store. */
    private fun resultText(content: JsonElement?, images: MutableList<BlobRef>): String = when (content) {
        null -> ""
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            block.str("text") ?: if (block.str("type") == "image") {
                imageOf(block)?.let { images += it }
                "[image]"
            } else null
        }.joinToString("\n")
        else -> content.toString()
    }

    private fun imageOf(block: JsonObject): BlobRef? {
        val source = block["source"] as? JsonObject ?: return null
        val bytes = source.str("data")?.decodeBase64() ?: return null
        return contents.bytes(bytes, source.str("media_type") ?: "application/octet-stream")
    }

    private fun rateLimit(event: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val info = event["rate_limit_info"] as? JsonObject ?: return emptyList()
        val status = info.str("status")
        if (status == null || status == "allowed") return emptyList()
        val notice = Notice(NoticeLevel.NOTICE_LEVEL_WARNING, "Rate limit: $status (${info.str("rateLimitType").orEmpty()})", "rate_limit")
        return listOf(append(at, Author.AUTHOR_SYSTEM, EntryBody(notice = notice), raw))
    }

    private fun result(event: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val outputs = mutableListOf<ParseOutput>()
        textSinceLastTool.mapTo(outputs) { ParseOutput.Update(asReply(it)) }
        val replyText = event.str("result").orEmpty()
        if (textSinceLastTool.isEmpty() && replyText.isNotBlank()) {
            val body = EntryBody(agent_text = AgentText(contents.text(replyText), TextRole.TEXT_ROLE_REPLY))
            outputs += append(at, Author.AUTHOR_AGENT, body, raw)
        }
        textSinceLastTool.clear()

        val costMicroUsd = ((event["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull?.times(1_000_000))?.roundToLong()
        val turnCost = costMicroUsd?.let { it - costSoFarMicroUsd } ?: 0L
        costMicroUsd?.let { costSoFarMicroUsd = it }
        val usage = event["usage"] as? JsonObject
        val isError = (event["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
        val turn = TurnResult(
            duration = event.long("duration_ms")?.let { durationOfSeconds(it / 1000, (it % 1000) * 1_000_000) },
            usage = Usage(
                input_tokens = usage?.long("input_tokens") ?: 0,
                output_tokens = usage?.long("output_tokens") ?: 0,
                cache_read_tokens = usage?.long("cache_read_input_tokens") ?: 0,
                cache_write_tokens = usage?.long("cache_creation_input_tokens") ?: 0,
                cost_micro_usd = turnCost,
            ),
            agent_turns = (event["num_turns"] as? JsonPrimitive)?.intOrNull ?: 0,
            is_error = isError,
        )
        val state = if (isError) EntryState.ENTRY_STATE_FAILED else EntryState.ENTRY_STATE_COMPLETE
        outputs += append(at, Author.AUTHOR_SYSTEM, EntryBody(turn_result = turn), raw, state = state)
        return outputs
    }

    private fun asReply(entry: Entry): Entry {
        val body = requireNotNull(entry.body?.agent_text) { "only agent text can be a reply" }
        return entry.copy(body = EntryBody(agent_text = body.copy(role = TextRole.TEXT_ROLE_REPLY)))
    }

    private fun unreadable(line: String): EntryBody = EntryBody(
        notice = Notice(NoticeLevel.NOTICE_LEVEL_WARNING, "An agent line could not be read: ${line.take(200)}", "unreadable_line"),
    )

    /**
     * Entries in the main line of the turn link to the one before. A subagent's entries hang under the
     * call that started it and leave the main line where it was.
     */
    private fun append(
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

    private fun parentOf(event: JsonObject): String? =
        event.str("parent_tool_use_id")?.let { callsById[it]?.id ?: spine }

    private fun blocksOf(event: JsonObject): List<JsonObject> =
        ((event["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private companion object {
        fun kindOf(tool: String): ToolKind = when {
            tool == "Bash" -> ToolKind.TOOL_KIND_SHELL
            tool == "Read" -> ToolKind.TOOL_KIND_FILE_READ
            tool == "Write" -> ToolKind.TOOL_KIND_FILE_WRITE
            tool == "Edit" || tool == "MultiEdit" || tool == "NotebookEdit" -> ToolKind.TOOL_KIND_FILE_EDIT
            tool == "Grep" || tool == "Glob" -> ToolKind.TOOL_KIND_SEARCH
            tool == "WebFetch" || tool == "WebSearch" -> ToolKind.TOOL_KIND_WEB
            tool == "Task" || tool == "Agent" -> ToolKind.TOOL_KIND_TASK
            tool.startsWith("mcp__") -> ToolKind.TOOL_KIND_MCP
            else -> ToolKind.TOOL_KIND_OTHER
        }

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        fun instantOf(text: String): Instant? = runCatching {
            val parsed = KotlinInstant.parse(text)
            ofEpochSecond(parsed.epochSeconds, parsed.nanosecondsOfSecond.toLong())
        }.getOrNull()

        fun elapsedBetween(from: Instant, to: Instant) = run {
            val nanos = (to.getEpochSecond() - from.getEpochSecond()) * 1_000_000_000 + (to.getNano() - from.getNano())
            durationOfSeconds(nanos.floorDiv(1_000_000_000), nanos.mod(1_000_000_000L))
        }
    }
}

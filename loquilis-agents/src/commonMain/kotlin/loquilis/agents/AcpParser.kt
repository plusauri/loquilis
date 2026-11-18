package loquilis.agents

import com.squareup.wire.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loquilis.core.ContentFactory
import loquilis.core.Ulid
import loquilis.model.AgentText
import loquilis.model.AppCommand
import loquilis.model.Author
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.Notice
import loquilis.model.NoticeLevel
import loquilis.model.PermissionRequest
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

/**
 * Reads an agent that speaks the Agent Client Protocol: JSON-RPC, one message per line. Read from Grok 1.0.70
 * (`grok agent stdio`) and OpenCode 2.3.2 (`opencode acp`), whose differences are noted where they fall.
 *
 * - `session/update` carries the turn. Text and thinking come in small chunks, each its own raw line,
 *   so a run of chunks becomes one entry covering all their raw lines. It is written when the run ends.
 * - A tool call opens with `tool_call`, with the tool's real kind under `_meta["x.ai/tool"]`, and closes
 *   with a `tool_call_update` whose status is `completed` or `failed`. A shell command that exits non-zero
 *   still says `completed`, so `rawOutput.exit_code` decides.
 * - The turn ends with the response to `session/prompt`, which has `stopReason` and, in `_meta.usage`,
 *   what the whole turn used. Its `costUsdTicks` has ten billion ticks to the dollar. A cancelled turn
 *   has no usage.
 * - `session/request_permission` asks whether a call may run, and is answered with a JSON-RPC response.
 * - Everything else (the `_x.ai` methods, `available_commands_update`) stays in the raw log and makes no entry.
 */
class AcpParser(
    conversationId: String,
    private val contents: ContentFactory,
    newId: () -> String = Ulid::next,
    /** Which agent this reads, as stored with its conversations: `grok-acp`, `opencode-acp`. */
    override val adapter: String = "grok-acp",
) : AgentParser {
    override val version = 2

    private class Run(val thinking: Boolean, val first: Long, val at: Instant) {
        val text = StringBuilder()
        var last = first
    }

    private val chain = EntryChain(conversationId, newId)
    private var run: Run? = null
    private var turnStartedAt: Instant? = null
    private var lastAt: Instant? = null
    private val callsById = mutableMapOf<String, Entry>()
    private val textSinceLastTool = mutableListOf<Entry>()
    private var agentVersion = ""
    private var session: SessionInfo? = null

    override fun userMessage(text: String, at: Instant): List<ParseOutput> {
        lastAt = at
        turnStartedAt = at
        return listOf(chain.append(at, Author.AUTHOR_USER, EntryBody(user_message = UserMessage(contents.text(text))), raw = null))
    }

    override fun command(command: AppCommand, at: Instant): List<ParseOutput> =
        endRun() + chain.append(at, Author.AUTHOR_USER, EntryBody(app_command = command), raw = null)

    override fun line(rawSeq: Long, line: String, receivedAt: Instant): List<ParseOutput> {
        val raw = RawRange(rawSeq, rawSeq)
        val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return listOf(chain.append(receivedAt, Author.AUTHOR_APP, unreadable(line), raw))
        val params = message["params"] as? JsonObject
        val at = ((params?.get("_meta") as? JsonObject)?.long("agentTimestampMs"))?.let(::instantOfMillis) ?: lastAt ?: receivedAt
        lastAt = at
        val result = message["result"] as? JsonObject
        return when {
            message.str("method") == "session/update" -> (params?.get("update") as? JsonObject)?.let { update(it, rawSeq, at, raw) }.orEmpty()
            message.str("method") == "session/request_permission" -> params?.let { permission(message["id"], it, at, raw) }.orEmpty()
            message.str("method") != null -> emptyList()
            result?.str("stopReason") != null -> turnEnded(result, at, raw)
            result?.str("sessionId") != null -> sessionStarted(result)
            result?.get("configOptions") != null -> modelChosen(result)
            result?.get("agentInfo") != null -> emptyList<ParseOutput>().also { agentVersion = (result["agentInfo"] as? JsonObject)?.str("version").orEmpty() }
            message["error"] != null -> failed(message["error"] as? JsonObject, at, raw)
            else -> emptyList()
        }
    }

    override fun abandon(at: Instant): List<ParseOutput> {
        val written = endRun()
        turnStartedAt = null
        textSinceLastTool.clear()
        return written + closeOpenCalls()
    }

    override fun continueAfter(last: Entry) {
        chain.continueAfter(last)
        lastAt = last.at
    }

    private fun update(update: JsonObject, rawSeq: Long, at: Instant, raw: RawRange): List<ParseOutput> = when (update.str("sessionUpdate")) {
        "agent_message_chunk" -> chunk(update, thinking = false, rawSeq, at)
        "agent_thought_chunk" -> chunk(update, thinking = true, rawSeq, at)
        "tool_call" -> endRun() + toolCall(update, at, raw)
        "tool_call_update" -> inputArrived(update) + toolCallUpdate(update, at, raw)
        else -> emptyList()
    }

    private fun chunk(update: JsonObject, thinking: Boolean, rawSeq: Long, at: Instant): List<ParseOutput> {
        val text = (update["content"] as? JsonObject)?.str("text").orEmpty()
        val written = if (run?.thinking == thinking) emptyList() else endRun()
        val current = run ?: Run(thinking, rawSeq, at).also { run = it }
        current.text.append(text)
        current.last = rawSeq
        return if (thinking) written else written + ParseOutput.Writing(current.text.toString().trimStart())
    }

    private fun endRun(): List<ParseOutput> {
        val ended = run ?: return emptyList()
        run = null
        val text = ended.text.toString().trim()
        val shown = if (ended.thinking) emptyList() else listOf(ParseOutput.Writing(""))
        if (text.isEmpty()) return shown
        val raw = RawRange(ended.first, ended.last)
        if (ended.thinking) {
            val body = EntryBody(thinking = Thinking(contents.text(text)))
            return listOf(chain.append(ended.at, Author.AUTHOR_AGENT, body, raw, visibility = Visibility.VISIBILITY_PRIVATE))
        }
        val body = EntryBody(agent_text = AgentText(contents.text(text), TextRole.TEXT_ROLE_NARRATION))
        // The entry first, so a reader never finds neither it nor the text it replaces.
        return listOf(chain.append(ended.at, Author.AUTHOR_AGENT, body, raw).also { textSinceLastTool += it.entry }) + shown
    }

    private fun toolCall(update: JsonObject, at: Instant, raw: RawRange): ParseOutput {
        val input = update["rawInput"] as? JsonObject ?: JsonObject(emptyMap())
        val tool = toolInfo(update)
        val command = input.str("command")
        val call = ToolCall(
            call_id = update.str("toolCallId").orEmpty(),
            tool = tool?.str("name") ?: update.str("name") ?: update.str("title").orEmpty(),
            kind = kindOf(tool?.str("kind") ?: update.str("kind")),
            input = if (command != null) contents.text(command) else contents.text(input.toString(), "application/json"),
            target_path = pathOf(input).orEmpty(),
            description = input.str("description").orEmpty(),
        )
        textSinceLastTool.clear()
        val output = chain.append(at, Author.AUTHOR_AGENT, EntryBody(tool_call = call), raw, state = EntryState.ENTRY_STATE_STREAMING)
        callsById[call.call_id] = output.entry
        return output
    }

    private fun toolCallUpdate(update: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val status = update.str("status")
        if (status != "completed" && status != "failed") return emptyList()
        val callId = update.str("toolCallId").orEmpty()
        val call = callsById.remove(callId) ?: return emptyList()
        val output = update["rawOutput"] as? JsonObject
        val exit = output?.long("exit_code") ?: (output?.get("metadata") as? JsonObject)?.long("exit") ?: 0L
        val isError = status == "failed" || exit != 0L
        val result = ToolResult(
            call_id = callId,
            output = contents.text(outputOf(update, output)),
            is_error = isError,
            elapsed = call.at?.let { elapsedBetween(it, at) },
        )
        val state = if (isError) EntryState.ENTRY_STATE_FAILED else EntryState.ENTRY_STATE_COMPLETE
        val appended = chain.append(at, Author.AUTHOR_TOOL, EntryBody(tool_result = result), raw, parent = call.id, state = state)
        return listOf(ParseOutput.Update(call.copy(state = state)), appended)
    }

    /** What the tool said, or what it changed for an edit, or the shell's own summary if it printed nothing. */
    private fun outputOf(update: JsonObject, output: JsonObject?): String {
        val blocks = (update["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val text = blocks.mapNotNull { block ->
            when (block.str("type")) {
                "content" -> (block["content"] as? JsonObject)?.str("text")
                "diff" -> "${block.str("path").orEmpty()}\n- ${block.str("oldText").orEmpty()}\n+ ${block.str("newText").orEmpty()}"
                else -> null
            }
        }.joinToString("\n")
        return text.ifBlank { output?.str("output_for_prompt").orEmpty() }
    }

    private fun permission(id: JsonElement?, params: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val requestId = (id as? JsonPrimitive)?.content ?: return emptyList()
        val call = params["toolCall"] as? JsonObject ?: JsonObject(emptyMap())
        val input = call["rawInput"] as? JsonObject ?: JsonObject(emptyMap())
        val options = (params["options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        fun optionOf(kind: String) = options.firstOrNull { it.str("kind") == kind }
        val tool = toolInfo(call)?.str("label") ?: call.str("name") ?: call.str("title").orEmpty()
        val description = input.str("description") ?: call.str("title").orEmpty()
        val detail = (input.str("command") ?: pathOf(input) ?: input.toString()).take(ASK_DETAIL_LENGTH)
        val always = optionOf("allow_always")
        val ask = PermissionAsk(
            requestId = requestId,
            tool = tool,
            description = description,
            detail = detail,
            remember = always?.str("name")?.removePrefix("Yes, and don't ask again for "),
            allow = answer(id, optionOf("allow_once")),
            allowAlways = always?.let { answer(id, it) },
            deny = answer(id, optionOf("reject_once")),
        )
        val request = PermissionRequest(requestId, call.str("toolCallId").orEmpty(), "$tool: ${description.ifEmpty { detail }}")
        return endRun() + chain.append(at, Author.AUTHOR_AGENT, EntryBody(permission_request = request), raw) + ParseOutput.Ask(ask)
    }

    /** The answer naming [option], or the protocol's "cancelled" if the agent did not offer that choice. */
    private fun answer(id: JsonElement, option: JsonObject?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "result",
            buildJsonObject {
                put(
                    "outcome",
                    buildJsonObject {
                        if (option == null) {
                            put("outcome", "cancelled")
                        } else {
                            put("outcome", "selected")
                            put("optionId", option.str("optionId").orEmpty())
                        }
                    },
                )
            },
        )
    }.toString()

    private fun sessionStarted(result: JsonObject): List<ParseOutput> {
        val info = SessionInfo(
            cliVersion = agentVersion,
            model = (result["models"] as? JsonObject)?.str("currentModelId") ?: modelOption(result).orEmpty(),
            sessionId = result.str("sessionId").orEmpty(),
            workspacePath = (result["_meta"] as? JsonObject)?.str("currentWorkingDirectory").orEmpty(),
        )
        session = info
        return listOf(ParseOutput.Session(info))
    }

    /** OpenCode names its model as a config option, and says which it uses once the console sets it. */
    private fun modelChosen(result: JsonObject): List<ParseOutput> {
        val started = session ?: return emptyList()
        val model = modelOption(result) ?: return emptyList()
        return listOf(ParseOutput.Session(started.copy(model = model).also { session = it }))
    }

    /**
     * OpenCode sends a call's input only once it runs, in its first update, so the call is filled in then.
     * Grok sends it with the call, and this changes nothing.
     */
    private fun inputArrived(update: JsonObject): List<ParseOutput> {
        val input = update["rawInput"] as? JsonObject ?: return emptyList()
        val callId = update.str("toolCallId") ?: return emptyList()
        val entry = callsById[callId] ?: return emptyList()
        val call = entry.body?.tool_call ?: return emptyList()
        if (input.isEmpty() || call.target_path.isNotEmpty() || call.description.isNotEmpty()) return emptyList()
        val command = input.str("command")
        val filled = call.copy(
            input = if (command != null) contents.text(command) else contents.text(input.toString(), "application/json"),
            target_path = pathOf(input).orEmpty(),
            description = input.str("description").orEmpty(),
        )
        if (filled == call) return emptyList()
        val updated = entry.copy(body = EntryBody(tool_call = filled))
        callsById[callId] = updated
        return listOf(ParseOutput.Update(updated))
    }

    private fun turnEnded(result: JsonObject, at: Instant, raw: RawRange): List<ParseOutput> {
        val reason = result.str("stopReason")
        // Grok puts what the turn used under `_meta`, OpenCode beside the stop reason.
        val usage = ((result["_meta"] as? JsonObject)?.get("usage") as? JsonObject) ?: (result["usage"] as? JsonObject)
        val outputs = (endRun() + textSinceLastTool.map { ParseOutput.Update(asReply(it)) } + closeOpenCalls()).toMutableList()
        textSinceLastTool.clear()
        val state = when (reason) {
            "end_turn" -> EntryState.ENTRY_STATE_COMPLETE
            "cancelled" -> EntryState.ENTRY_STATE_INTERRUPTED
            else -> EntryState.ENTRY_STATE_FAILED
        }
        if (state == EntryState.ENTRY_STATE_FAILED && reason != AGENT_ERROR) {
            val notice = Notice(NoticeLevel.NOTICE_LEVEL_WARNING, "The turn ended early: $reason", "stop_reason")
            outputs += chain.append(at, Author.AUTHOR_SYSTEM, EntryBody(notice = notice), raw)
        }
        val turn = TurnResult(
            duration = turnStartedAt?.let { elapsedBetween(it, at) },
            usage = Usage(
                input_tokens = usage?.long("inputTokens") ?: 0,
                output_tokens = usage?.long("outputTokens") ?: 0,
                cache_read_tokens = usage?.long("cachedReadTokens") ?: 0,
                cache_write_tokens = usage?.long("cacheCreationTokens") ?: 0,
                cost_micro_usd = (usage?.long("costUsdTicks") ?: 0) / TICKS_PER_MICRO_USD,
            ),
            agent_turns = usage?.long("modelCalls")?.toInt() ?: 0,
            is_error = state != EntryState.ENTRY_STATE_COMPLETE,
        )
        turnStartedAt = null
        outputs += chain.append(at, Author.AUTHOR_SYSTEM, EntryBody(turn_result = turn), raw, state = state)
        return outputs
    }

    /** A failed `session/prompt` ends the turn. Any other failed request has no turn to end. */
    private fun failed(error: JsonObject?, at: Instant, raw: RawRange): List<ParseOutput> {
        val text = error?.str("message") ?: "The agent reported an error."
        val notice = Notice(NoticeLevel.NOTICE_LEVEL_ERROR, text, "agent_error")
        val outputs = (endRun() + chain.append(at, Author.AUTHOR_SYSTEM, EntryBody(notice = notice), raw)).toMutableList()
        if (turnStartedAt != null) outputs += turnEnded(JsonObject(mapOf("stopReason" to JsonPrimitive(AGENT_ERROR))), at, raw)
        return outputs
    }

    private fun closeOpenCalls(): List<ParseOutput> {
        val open = callsById.values.filter { it.state == EntryState.ENTRY_STATE_STREAMING }
        callsById.clear()
        return open.map { ParseOutput.Update(it.copy(state = EntryState.ENTRY_STATE_INTERRUPTED)) }
    }

    private fun asReply(entry: Entry): Entry {
        val body = requireNotNull(entry.body?.agent_text) { "only agent text can be a reply" }
        return entry.copy(body = EntryBody(agent_text = body.copy(role = TextRole.TEXT_ROLE_REPLY)))
    }

    private fun unreadable(line: String): EntryBody = EntryBody(
        notice = Notice(NoticeLevel.NOTICE_LEVEL_WARNING, "An agent line could not be read: ${line.take(200)}", "unreadable_line"),
    )

    private companion object {
        const val ASK_DETAIL_LENGTH = 2000
        const val TICKS_PER_MICRO_USD = 10_000L
        const val AGENT_ERROR = "error"

        fun toolInfo(update: JsonObject): JsonObject? = (update["_meta"] as? JsonObject)?.get("x.ai/tool") as? JsonObject

        fun modelOption(result: JsonObject): String? = (result["configOptions"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>().firstOrNull { it.str("id") == "model" }?.str("currentValue")

        fun pathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("target_file") ?: input.str("path")

        fun kindOf(kind: String?): ToolKind = when (kind) {
            "execute" -> ToolKind.TOOL_KIND_SHELL
            "read" -> ToolKind.TOOL_KIND_FILE_READ
            "edit" -> ToolKind.TOOL_KIND_FILE_EDIT
            "search" -> ToolKind.TOOL_KIND_SEARCH
            "fetch" -> ToolKind.TOOL_KIND_WEB
            else -> ToolKind.TOOL_KIND_OTHER
        }
    }
}

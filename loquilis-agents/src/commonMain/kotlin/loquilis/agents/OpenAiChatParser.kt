package loquilis.agents

import com.squareup.wire.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import loquilis.model.RawRange
import loquilis.model.TextRole
import loquilis.model.Thinking
import loquilis.model.TurnResult
import loquilis.model.Usage
import loquilis.model.UserMessage
import loquilis.model.Visibility

/**
 * Reads a reply from a runner's OpenAI-style `/v1/chat/completions` stream, with no agent in between: the
 * model answers and nothing runs. Each raw line is the JSON of one `data:` event, then `[DONE]`.
 *
 * - `choices[0].delta.content` is the reply, and `delta.reasoning` or `delta.reasoning_content` its thinking.
 * - The last event before `[DONE]` carries `usage`, with `prompt_tokens` and `completion_tokens`.
 * - `{"error":{"message":...}}` ends a turn as a failure. [HttpChatSession] writes this for failures the
 *   runner never reports, and `{"stream_ended":"stopped"}` for a turn the user stopped.
 */
class OpenAiChatParser(
    conversationId: String,
    private val contents: ContentFactory,
    newId: () -> String = Ulid::next,
) : AgentParser {
    override val adapter = "openai-chat"
    override val version = 1

    private val chain = EntryChain(conversationId, newId)
    private val reply = StringBuilder()
    private val thought = StringBuilder()
    private var replyFirstSeq: Long? = null
    private var thoughtFirstSeq: Long? = null
    private var lastSeq = 0L
    private var usage: JsonObject? = null
    private var turnStartedAt: Instant? = null

    override fun userMessage(text: String, at: Instant): List<ParseOutput> {
        turnStartedAt = at
        return listOf(chain.append(at, Author.AUTHOR_USER, EntryBody(user_message = UserMessage(contents.text(text))), raw = null))
    }

    override fun command(command: AppCommand, at: Instant): List<ParseOutput> =
        listOf(chain.append(at, Author.AUTHOR_USER, EntryBody(app_command = command), raw = null))

    override fun line(rawSeq: Long, line: String, receivedAt: Instant): List<ParseOutput> {
        val raw = RawRange(rawSeq, rawSeq)
        if (line == DONE) return turnEnded(EntryState.ENTRY_STATE_COMPLETE, receivedAt, raw)
        val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return emptyList()
        lastSeq = rawSeq
        (event["usage"] as? JsonObject)?.let { usage = it }
        (event["error"] as? JsonObject)?.let { error ->
            val notice = Notice(NoticeLevel.NOTICE_LEVEL_ERROR, error.str("message") ?: "The model reported an error.", "agent_error")
            val outputs = written(receivedAt) + chain.append(receivedAt, Author.AUTHOR_SYSTEM, EntryBody(notice = notice), raw)
            return outputs + turnEnded(EntryState.ENTRY_STATE_FAILED, receivedAt, raw)
        }
        if (event.str("stream_ended") != null) return turnEnded(EntryState.ENTRY_STATE_INTERRUPTED, receivedAt, raw)
        val delta = ((event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: return emptyList()
        (delta.str("reasoning") ?: delta.str("reasoning_content"))?.let {
            if (thoughtFirstSeq == null) thoughtFirstSeq = rawSeq
            thought.append(it)
        }
        val text = delta.str("content")?.takeIf(String::isNotEmpty) ?: return emptyList()
        if (replyFirstSeq == null) replyFirstSeq = rawSeq
        reply.append(text)
        return listOf(ParseOutput.Writing(reply.toString().trimStart()))
    }

    override fun abandon(at: Instant): List<ParseOutput> {
        val outputs = written(at)
        turnStartedAt = null
        return outputs
    }

    override fun continueAfter(last: Entry) {
        chain.continueAfter(last)
    }

    /** What arrived so far, as entries, and then the text that gives way to them. */
    private fun written(at: Instant): List<ParseOutput> = buildList {
        val thinking = thought.toString().trim()
        thoughtFirstSeq?.takeIf { thinking.isNotEmpty() }?.let { first ->
            val body = EntryBody(thinking = Thinking(contents.text(thinking)))
            add(chain.append(at, Author.AUTHOR_AGENT, body, RawRange(first, lastSeq), visibility = Visibility.VISIBILITY_PRIVATE))
        }
        val text = reply.toString().trim()
        replyFirstSeq?.takeIf { text.isNotEmpty() }?.let { first ->
            val body = EntryBody(agent_text = AgentText(contents.text(text), TextRole.TEXT_ROLE_REPLY))
            add(chain.append(at, Author.AUTHOR_AGENT, body, RawRange(first, lastSeq)))
            add(ParseOutput.Writing(""))
        }
        thought.clear()
        reply.clear()
        thoughtFirstSeq = null
        replyFirstSeq = null
    }

    private fun turnEnded(state: EntryState, at: Instant, raw: RawRange): List<ParseOutput> {
        val outputs = written(at)
        val turn = TurnResult(
            duration = turnStartedAt?.let { elapsedBetween(it, at) },
            usage = Usage(
                input_tokens = usage?.long("prompt_tokens") ?: 0,
                output_tokens = usage?.long("completion_tokens") ?: 0,
            ),
            agent_turns = 1,
            is_error = state != EntryState.ENTRY_STATE_COMPLETE,
        )
        turnStartedAt = null
        usage = null
        return outputs + chain.append(at, Author.AUTHOR_SYSTEM, EntryBody(turn_result = turn), raw, state = state)
    }

    companion object {
        const val DONE = "[DONE]"
    }
}

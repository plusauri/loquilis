package loquilis.store

import com.squareup.wire.Instant
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loquilis.agents.AgentParser
import loquilis.agents.ClaudeStreamParser
import loquilis.core.ContentFactory
import loquilis.model.AgentBinding
import loquilis.model.Conversation
import loquilis.model.Entry

const val CONVERSATION = "01C0NVERSAT10N000000000000"
const val MAIN = "01BRANCH000000000000000000"
val AT: Instant = Instant.ofEpochSecond(1_793_000_000)

fun conversation(id: String = CONVERSATION) =
    Conversation(id = id, title = "A take", created_at = AT, main_branch_id = MAIN, agent = AgentBinding(cli = "claude"))

/** Ids by appearance, so two runs over the same lines are directly comparable. */
fun orderedIds(prefix: String = "e"): () -> String {
    var n = 0
    return { prefix + (++n).toString().padStart(6, '0') }
}

fun parserFor(store: Store, newId: () -> String = orderedIds(), conversationId: String = CONVERSATION): AgentParser =
    ClaudeStreamParser(conversationId, ContentFactory(store), newId)

/** The public take every parser test also reads. */
object Take {
    private fun open(name: String) = checkNotNull(Take::class.java.getResourceAsStream("/fixtures/claude-take/$name")) { name }

    val lines: List<String> by lazy { GZIPInputStream(open("raw.jsonl.gz")).bufferedReader().readLines() }

    /** Each turn's message, and when it was sent. */
    val messages: List<Pair<String, Instant>> by lazy {
        val record = Json.parseToJsonElement(open("conversation.json").bufferedReader().readText()).jsonObject
        record.getValue("turns").jsonArray.map { turn ->
            val client = turn.jsonObject.getValue("client").jsonObject
            client.getValue("said").jsonPrimitive.content to Instant.parse(client.getValue("sentAt").jsonPrimitive.content)
        }
    }

    private fun isResult(line: String) = Json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.content == "result"

    /** The take in the order it happened, stopping after [limit] raw lines if one is given. */
    fun play(recorder: Recorder, limit: Int = Int.MAX_VALUE, afterLine: (Int) -> Unit = {}) {
        var next = 0
        for ((said, at) in messages) {
            if (next >= limit || next >= lines.size) return
            recorder.userMessage(said, at)
            while (next < lines.size && next < limit) {
                val line = lines[next++]
                recorder.line(line, AT)
                afterLine(next)
                if (isResult(line)) break
            }
        }
    }
}

fun recorded(path: Path, limit: Int = Int.MAX_VALUE): SqliteStore {
    val store = SqliteStore.open(path)
    store.putConversation(conversation())
    Take.play(Recorder(store, CONVERSATION, parserFor(store)), limit)
    return store
}

fun Store.allEntries(id: String = CONVERSATION): List<Entry> = buildList { forEachEntry(id) { add(it) } }

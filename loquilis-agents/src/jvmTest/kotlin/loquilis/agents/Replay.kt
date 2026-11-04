package loquilis.agents

import com.squareup.wire.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loquilis.core.BlobSink
import loquilis.core.ContentFactory
import loquilis.core.blobRefOf
import loquilis.model.BlobRef
import loquilis.model.Entry
import okio.ByteString

/** The blob store's contract, in memory. */
class MemoryBlobs : BlobSink {
    val stored = linkedMapOf<String, ByteString>()

    override fun put(bytes: ByteString, mediaType: String): BlobRef =
        blobRefOf(bytes, mediaType).also { stored[it.sha256] = bytes }

    /** The whole text of [content], from wherever it lives. */
    fun text(content: loquilis.model.Content?): String =
        content?.inline_ ?: content?.blob?.let { stored.getValue(it.sha256).utf8() }.orEmpty()
}

/** Entries as a store would hold them: appends in order, updates in place. */
class EntryLog {
    private val byId = linkedMapOf<String, Entry>()
    val entries: List<Entry> get() = byId.values.toList()

    fun apply(output: ParseOutput) {
        when (output) {
            is ParseOutput.Append -> { check(output.entry.id !in byId) { "appended twice: ${output.entry.id}" }; byId[output.entry.id] = output.entry }
            is ParseOutput.Update -> { check(output.entry.id in byId) { "update of nothing: ${output.entry.id}" }; byId[output.entry.id] = output.entry }
            is ParseOutput.Session -> Unit
        }
    }
}

fun idsInOrder(): () -> String { var n = 0; return { "e" + (++n).toString().padStart(6, '0') } }

val EPOCH: Instant = Instant.ofEpochSecond(1_793_000_000)

object Fixture {
    private fun resource(name: String) = checkNotNull(Fixture::class.java.getResourceAsStream("/fixtures/claude-take/$name")) { name }

    val rawLines: List<String> by lazy { GZIPInputStream(resource("raw.jsonl.gz")).bufferedReader().readLines() }
    val record: JsonObject by lazy { Json.parseToJsonElement(resource("conversation.json").bufferedReader().readText()).jsonObject }
    val turns: List<JsonObject> by lazy { record.getValue("turns").jsonArray.map { it.jsonObject } }

    /** The raw lines of each turn: every line up to and including that turn's `result`. */
    val linesByTurn: List<List<IndexedValue<String>>> by lazy {
        val all = rawLines.withIndex().toList()
        val cuts = all.filter { """"type":"result"""" in it.value.replace(": ", ":") }.map { it.index }
        cuts.mapIndexed { i, end -> all.subList(if (i == 0) 0 else cuts[i - 1] + 1, end + 1) }
    }

    fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}

class Replayed(val log: EntryLog, val blobs: MemoryBlobs, val sessions: List<SessionInfo>, val endsByTurn: List<Int>)

/** Runs the whole recorded take through the parser, as a live session would. */
fun replayFixture(): Replayed {
    val blobs = MemoryBlobs()
    val parser = ClaudeStreamParser("c1", ContentFactory(blobs), idsInOrder())
    val log = EntryLog()
    val sessions = mutableListOf<SessionInfo>()
    val ends = mutableListOf<Int>()
    fun take(outputs: List<ParseOutput>) = outputs.forEach { o ->
        log.apply(o)
        if (o is ParseOutput.Session) sessions += o.info
    }
    Fixture.turns.forEachIndexed { i, turn ->
        with(Fixture) {
            val client = turn.getValue("client").jsonObject
            take(parser.userMessage(client.text("said").orEmpty(), EPOCH))
        }
        Fixture.linesByTurn[i].forEach { (index, line) -> take(parser.line(index + 1L, line, EPOCH)) }
        ends += log.entries.size
    }
    return Replayed(log, blobs, sessions, ends)
}

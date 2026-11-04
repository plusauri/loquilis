package loquilis.app

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import loquilis.agents.AgentParser
import loquilis.agents.ClaudeStreamParser
import loquilis.agents.ParseOutput
import loquilis.agents.SessionInfo
import loquilis.core.BlobSink
import loquilis.core.ContentFactory
import loquilis.core.blobRefOf
import loquilis.core.kind
import loquilis.core.EntryKind
import loquilis.model.BlobRef
import loquilis.model.Content
import loquilis.model.Entry
import okio.ByteString

/**
 * A recorded take, replayed through the real parser and held in memory: the stand-in for loquilis-store
 * while there is no store. It holds raw lines too, so the review window can show what each entry
 * was parsed from.
 */
class Take(
    val title: String,
    val entries: List<Entry>,
    val rawLines: List<String>,
    val session: SessionInfo?,
    private val blobs: Map<String, ByteString>,
) {
    val byId: Map<String, Entry> = entries.associateBy { it.id }

    private val resultsByCallId: Map<String, Entry> =
        entries.mapNotNull { e -> e.body?.tool_result?.let { it.call_id to e } }.toMap()

    /** The whole text of [content], from wherever it lives. */
    fun text(content: Content?): String =
        content?.inline_ ?: content?.blob?.let { blobs[it.sha256]?.utf8() }.orEmpty()

    /** The agent's own lines that [entry] was parsed from, or nothing for an entry the app wrote. */
    fun rawOf(entry: Entry): List<String> {
        val range = entry.raw ?: return emptyList()
        return (range.first_seq..range.last_seq).mapNotNull { rawLines.getOrNull((it - 1).toInt()) }
    }

    /** The result entry of a tool call entry. */
    fun resultOf(entry: Entry): Entry? = (entry.body?.kind as? EntryKind.Call)?.let { resultsByCallId[it.call.call_id] }
}

/** The take bundled with the app: the one the parity test proves against. */
fun loadBundledTake(): Take {
    fun open(name: String): InputStream =
        checkNotNull(Take::class.java.getResourceAsStream("/fixtures/claude-take/$name")) { "missing $name" }
    return readTake(open("conversation.json"), open("raw.jsonl.gz"))
}

/** A take folder from a records repo: `conversation.json` plus `raw.jsonl.gz`. */
fun loadTake(folder: Path): Take =
    readTake(Files.newInputStream(folder.resolve("conversation.json")), Files.newInputStream(folder.resolve("raw.jsonl.gz")))

/**
 * Feeds a take through the parser in the order a live session would have. An agent's stream does not
 * echo the user, so each turn's message comes from the record, ahead of the lines up to its `result`.
 */
fun readTake(record: InputStream, raw: InputStream, parserOf: (ContentFactory) -> AgentParser = { ClaudeStreamParser("review", it) }): Take {
    val conversation = record.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    val lines = GZIPInputStream(raw).bufferedReader().use { it.readLines() }
    val blobs = HeldBlobs()
    val parser = parserOf(ContentFactory(blobs))
    val log = linkedMapOf<String, Entry>()
    var session: SessionInfo? = null

    fun take(outputs: List<ParseOutput>) = outputs.forEach { output ->
        when (output) {
            is ParseOutput.Append -> log[output.entry.id] = output.entry
            is ParseOutput.Update -> log[output.entry.id] = output.entry
            is ParseOutput.Session -> session = output.info
        }
    }

    var next = 0
    val fallback = Instant.EPOCH
    fun feedThroughResult() {
        while (next < lines.size) {
            val line = lines[next++]
            take(parser.line(next.toLong(), line, fallback))
            if (isResult(line)) return
        }
    }

    for (turn in conversation["turns"]?.jsonArray.orEmpty()) {
        val client = turn.jsonObject["client"]?.jsonObject
        val sentAt = client?.string("sentAt")?.let(Instant::parse) ?: fallback
        take(parser.userMessage(client?.string("said").orEmpty(), sentAt))
        feedThroughResult()
    }
    while (next < lines.size) feedThroughResult()

    return Take(titleOf(conversation), log.values.toList(), lines, session, blobs.held)
}

private fun titleOf(record: JsonObject): String =
    record["plan"]?.jsonObject?.string("libraryName") ?: record.string("id") ?: "Recorded take"

private fun isResult(line: String): Boolean =
    runCatching { (Json.parseToJsonElement(line) as? JsonObject)?.string("type") == "result" }.getOrDefault(false)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private class HeldBlobs : BlobSink {
    val held = hashMapOf<String, ByteString>()

    override fun put(bytes: ByteString, mediaType: String): BlobRef =
        blobRefOf(bytes, mediaType).also { held[it.sha256] = bytes }
}

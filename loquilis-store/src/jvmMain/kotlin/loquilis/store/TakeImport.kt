package loquilis.store

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
import loquilis.agents.ClaudeStreamParser
import loquilis.core.ContentFactory
import loquilis.core.Ulid
import loquilis.model.AgentBinding
import loquilis.model.Conversation

/** Records a take folder from a records repo, `conversation.json` plus `raw.jsonl.gz`, and returns its id. */
fun recordTake(store: Store, folder: Path): String =
    recordTake(store, Files.newInputStream(folder.resolve("conversation.json")), Files.newInputStream(folder.resolve("raw.jsonl.gz")))

/**
 * Feeds a take through the real parser into [store], in the order a live session would have. An agent's
 * stream does not echo the user, so each turn's message comes from the record, ahead of the lines up to
 * its `result`. Returns the new conversation's id.
 */
fun recordTake(store: Store, record: InputStream, raw: InputStream): String {
    val take = record.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    val lines = GZIPInputStream(raw).bufferedReader().use { it.readLines() }
    val turns = take["turns"]?.jsonArray.orEmpty().map { it.jsonObject["client"]?.jsonObject }
    fun sentAt(client: JsonObject?) = client?.string("sentAt")?.let(Instant::parse) ?: Instant.EPOCH

    val id = Ulid.next()
    store.transaction {
        store.putConversation(
            Conversation(
                id = id,
                title = titleOf(take),
                created_at = sentAt(turns.firstOrNull()),
                main_branch_id = Ulid.next(),
                agent = AgentBinding(cli = "claude"),
            ),
        )
        val recorder = Recorder(store, id, ClaudeStreamParser(id, ContentFactory(store)))
        var next = 0
        fun feedThroughResult() {
            while (next < lines.size) {
                val line = lines[next++]
                recorder.line(line, Instant.EPOCH)
                if (isResult(line)) return
            }
        }
        for (client in turns) {
            recorder.userMessage(client?.string("said").orEmpty(), sentAt(client))
            feedThroughResult()
        }
        while (next < lines.size) feedThroughResult()
    }
    return id
}

private fun titleOf(record: JsonObject): String =
    record["plan"]?.jsonObject?.string("libraryName") ?: record.string("id") ?: "Recorded take"

private fun isResult(line: String): Boolean =
    runCatching { (Json.parseToJsonElement(line) as? JsonObject)?.string("type") == "result" }.getOrDefault(false)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

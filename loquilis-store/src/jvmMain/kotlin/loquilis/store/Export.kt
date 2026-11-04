package loquilis.store

import com.squareup.moshi.Moshi
import com.squareup.wire.Instant
import com.squareup.wire.WireJsonAdapterFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import loquilis.core.EntryKind
import loquilis.core.kind
import loquilis.model.BlobRef
import loquilis.model.Content
import loquilis.model.Entry
import loquilis.model.ExportManifest
import loquilis.model.Visibility
import okio.ByteString.Companion.toByteString

/** The export format's version, [ExportManifest.schema]. */
const val EXPORT_SCHEMA = 1

private val moshi = Moshi.Builder().add(WireJsonAdapterFactory()).build()
private val manifestJson = moshi.adapter(ExportManifest::class.java)
private val entryJson = moshi.adapter(Entry::class.java)

/**
 * Writes one conversation as a folder (see export.proto). Without [includePrivate], private entries
 * are dropped, along with the raw lines they came from: those stay as blank lines, so every remaining
 * entry's [loquilis.model.RawRange] still points at its own line. Entries that followed a dropped
 * entry take its parent.
 */
fun exportConversation(store: Store, conversationId: String, folder: Path, exportedAt: Instant, includePrivate: Boolean = false) {
    val conversation = requireNotNull(store.conversation(conversationId)) { "no conversation $conversationId" }
    Files.createDirectories(folder.resolve("blobs"))

    val movedParents = hashMapOf<String, String>()
    val hiddenRaw = hashSetOf<Long>()
    var count = 0L
    Files.newBufferedWriter(folder.resolve("entries.jsonl")).use { out ->
        store.forEachEntry(conversationId) { entry ->
            if (!includePrivate && entry.visibility == Visibility.VISIBILITY_PRIVATE) {
                movedParents[entry.id] = movedParents[entry.parent_id] ?: entry.parent_id
                entry.raw?.let { hiddenRaw += it.first_seq..it.last_seq }
            } else {
                val kept = entry.copy(parent_id = movedParents[entry.parent_id] ?: entry.parent_id)
                out.write(entryJson.toJson(kept))
                out.newLine()
                count++
                blobsOf(entry).forEach { ref -> writeBlob(store, ref, folder) }
            }
        }
    }

    val rawCount = store.rawCount(conversationId)
    GZIPOutputStream(Files.newOutputStream(folder.resolve("raw.jsonl.gz"))).bufferedWriter().use { out ->
        var first = 1L
        while (first <= rawCount) {
            store.raw(conversationId, first..minOf(rawCount, first + 999)).forEachIndexed { i, line ->
                if (first + i !in hiddenRaw) out.write(line)
                out.newLine()
            }
            first += 1000
        }
    }

    val manifest = ExportManifest(
        schema = EXPORT_SCHEMA,
        conversation = conversation,
        branches = store.branches(conversationId),
        entry_count = count,
        raw_event_count = rawCount,
        exported_at = exportedAt,
        includes_private = includePrivate,
    )
    Files.writeString(folder.resolve("manifest.json"), manifestJson.toJson(manifest))
}

/** Reads an export folder into [store], which must not already hold that conversation. Returns its id. */
fun importConversation(store: Store, folder: Path): String {
    val manifest = requireNotNull(manifestJson.fromJson(Files.readString(folder.resolve("manifest.json")))) { "empty manifest" }
    require(manifest.schema <= EXPORT_SCHEMA) { "this export is schema ${manifest.schema}, newer than $EXPORT_SCHEMA" }
    val conversation = requireNotNull(manifest.conversation) { "the manifest names no conversation" }
    require(store.conversation(conversation.id) == null) { "the library already holds ${conversation.id}" }

    store.transaction {
        store.putConversation(conversation.copy(total_usage = null))
        manifest.branches.forEach(store::putBranch)
        val blobs = folder.resolve("blobs")
        Files.newBufferedReader(folder.resolve("entries.jsonl")).useLines { lines ->
            lines.forEach { line ->
                val entry = requireNotNull(entryJson.fromJson(line)) { "empty entry line" }
                blobsOf(entry).forEach { ref ->
                    val bytes = Files.readAllBytes(blobs.resolve(ref.sha256))
                    store.put(bytes.toByteString(), ref.media_type)
                }
                store.appendEntry(entry)
            }
        }
        GZIPInputStream(Files.newInputStream(folder.resolve("raw.jsonl.gz"))).bufferedReader().useLines { lines ->
            lines.forEach { store.appendRaw(conversation.id, it) }
        }
        // Exact as exported: appending entries moved the running totals and the update time.
        store.putConversation(conversation)
    }
    return conversation.id
}

private fun writeBlob(store: Store, ref: BlobRef, folder: Path) {
    val file = folder.resolve("blobs").resolve(ref.sha256)
    if (Files.exists(file)) return
    val bytes = requireNotNull(store.blob(ref.sha256)) { "missing blob ${ref.sha256}" }
    Files.write(file, bytes.toByteArray())
}

/** Every blob an entry refers to. Exhaustive, so a new entry kind has to say what it holds. */
internal fun blobsOf(entry: Entry): List<BlobRef> {
    fun of(vararg contents: Content?) = contents.mapNotNull { it?.blob }
    return when (val kind = entry.body?.kind) {
        is EntryKind.User -> of(kind.message.text) + kind.message.attachments.mapNotNull { it.blob }
        is EntryKind.Text -> of(kind.text.text)
        is EntryKind.Reasoning -> of(kind.thinking.text)
        is EntryKind.Call -> of(kind.call.input)
        is EntryKind.Result -> of(kind.result.output) + kind.result.images
        is EntryKind.Made -> listOfNotNull(kind.artifact.blob)
        is EntryKind.Sub, is EntryKind.Command, is EntryKind.Permission, is EntryKind.TurnEnd, is EntryKind.Note, EntryKind.Unknown, null -> emptyList()
    }
}

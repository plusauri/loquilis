package loquilis.store

import com.squareup.wire.Duration
import com.squareup.wire.Instant
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import loquilis.core.blobRefOf
import loquilis.core.kind
import loquilis.model.AgentBinding
import loquilis.model.Author
import loquilis.model.BlobRef
import loquilis.model.Branch
import loquilis.model.Conversation
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.Extension
import loquilis.model.Outcome
import loquilis.model.OutcomeStatus
import loquilis.model.ParserRef
import loquilis.model.RawRange
import loquilis.model.SearchHit
import loquilis.model.Transport
import loquilis.model.TurnSummary
import loquilis.model.Usage
import loquilis.model.Visibility
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The [Store] as one SQLite file in WAL mode, with large blobs as files in a folder beside it. One
 * connection writes and another reads, so a view can page while an agent writes.
 */
class SqliteStore private constructor(
    private val path: Path,
    private val fileBlobBytes: Int,
    private val rawBlockBytes: Int,
) : Store {
    private val writer = connect()
    private val reader = connect()
    private val blobDir: Path = path.resolveSibling("${path.fileName}.blobs")
    private val writeStatements = hashMapOf<String, PreparedStatement>()
    private val readStatements = hashMapOf<String, PreparedStatement>()
    private var depth = 0

    init {
        Schema.migrate(writer)
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
        c.createStatement().use { s ->
            s.execute("PRAGMA journal_mode = WAL")
            s.execute("PRAGMA synchronous = NORMAL")
            s.execute("PRAGMA foreign_keys = ON")
            s.execute("PRAGMA busy_timeout = 5000")
        }
    }

    override fun close() {
        synchronized(writer) {
            synchronized(reader) {
                writeStatements.values.forEach { it.close() }
                readStatements.values.forEach { it.close() }
                writer.close()
                reader.close()
            }
        }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(writer) {
        if (depth > 0) return@synchronized block()
        writer.autoCommit = false
        depth = 1
        try {
            block().also { writer.commit() }
        } catch (failure: Throwable) {
            writer.rollback()
            throw failure
        } finally {
            depth = 0
            writer.autoCommit = true
        }
    }

    private fun write(sql: String, vararg values: Any?): Int = synchronized(writer) {
        writeStatements.getOrPut(sql) { writer.prepareStatement(sql) }.run { bind(values); executeUpdate() }
    }

    /** A query on the write connection, so it sees the open transaction. */
    private fun <T> writerQuery(sql: String, vararg values: Any?, read: (ResultSet) -> T): T = synchronized(writer) {
        writeStatements.getOrPut(sql) { writer.prepareStatement(sql) }.run { bind(values); executeQuery().use(read) }
    }

    private fun <T> query(sql: String, vararg values: Any?, read: (ResultSet) -> T): T = synchronized(reader) {
        readStatements.getOrPut(sql) { reader.prepareStatement(sql) }.run { bind(values); executeQuery().use(read) }
    }

    private fun PreparedStatement.bind(values: Array<out Any?>) {
        values.forEachIndexed { i, value ->
            when (value) {
                null -> setNull(i + 1, Types.NULL)
                is Int -> setInt(i + 1, value)
                is Long -> setLong(i + 1, value)
                is String -> setString(i + 1, value)
                is ByteArray -> setBytes(i + 1, value)
                is Boolean -> setInt(i + 1, if (value) 1 else 0)
                else -> error("cannot bind ${value::class}")
            }
        }
    }

    private fun <T> ResultSet.all(row: (ResultSet) -> T): List<T> = buildList { while (next()) add(row(this@all)) }

    private fun <T> ResultSet.one(row: (ResultSet) -> T): T? = if (next()) row(this) else null

    // Conversations

    override fun putConversation(conversation: Conversation) {
        val c = conversation
        val agent = c.agent
        val usage = c.total_usage
        write(
            "INSERT OR REPLACE INTO conversation VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            c.id, c.title, nanos(c.created_at), nanos(c.updated_at),
            agent?.cli.orEmpty(), agent?.cli_version.orEmpty(), agent?.model.orEmpty(), agent?.reported_model.orEmpty(),
            agent?.transport?.value ?: 0, agent?.agent_session_id.orEmpty(), agent != null,
            c.workspace_path, c.outcome?.status?.value ?: 0, c.outcome?.reason.orEmpty(), c.outcome != null,
            usage?.input_tokens ?: 0L, usage?.output_tokens ?: 0L, usage?.cache_read_tokens ?: 0L,
            usage?.cache_write_tokens ?: 0L, usage?.cost_micro_usd ?: 0L,
            c.main_branch_id, c.parser?.adapter_.orEmpty(), c.parser?.version ?: 0, c.parser != null,
            extensionBytes(c.extensions),
        )
    }

    private fun conversationOf(r: ResultSet) = Conversation(
        id = r.getString("id"),
        title = r.getString("title"),
        created_at = instant(r.nullableLong("created_at_nanos")),
        updated_at = instant(r.nullableLong("updated_at_nanos")),
        agent = if (r.getInt("agent_present") == 0) null else AgentBinding(
            cli = r.getString("agent_cli"),
            cli_version = r.getString("agent_cli_version"),
            model = r.getString("agent_model"),
            reported_model = r.getString("agent_reported_model"),
            transport = Transport.fromValue(r.getInt("agent_transport")) ?: Transport.TRANSPORT_UNSPECIFIED,
            agent_session_id = r.getString("agent_session_id"),
        ),
        workspace_path = r.getString("workspace_path"),
        outcome = if (r.getInt("outcome_present") == 0) null else Outcome(
            status = OutcomeStatus.fromValue(r.getInt("outcome_status")) ?: OutcomeStatus.OUTCOME_STATUS_UNSPECIFIED,
            reason = r.getString("outcome_reason"),
        ),
        total_usage = usageOf(r, "total_usage_").takeIf { it != Usage() },
        main_branch_id = r.getString("main_branch_id"),
        parser = if (r.getInt("parser_present") == 0) null else ParserRef(r.getString("parser_adapter"), r.getInt("parser_version")),
        extensions = extensionsOf(r.getBytes("extensions")),
    )

    override fun conversation(id: String): Conversation? =
        query("SELECT * FROM conversation WHERE id = ?", id) { it.one(::conversationOf) }

    private fun conversationInWrite(id: String): Conversation? =
        writerQuery("SELECT * FROM conversation WHERE id = ?", id) { it.one(::conversationOf) }

    override fun conversations(limit: Int, offset: Int): List<Conversation> =
        query("SELECT * FROM conversation ORDER BY updated_at_nanos DESC LIMIT ? OFFSET ?", limit, offset) { it.all(::conversationOf) }

    override fun putBranch(branch: Branch) {
        write(
            "INSERT OR REPLACE INTO branch VALUES (?,?,?,?,?,?,?)",
            branch.id, branch.conversation_id, branch.name, branch.head_entry_id, branch.forked_from_entry_id,
            nanos(branch.created_at), extensionBytes(branch.extensions),
        )
    }

    override fun branches(conversationId: String): List<Branch> =
        query("SELECT * FROM branch WHERE conversation_id = ? ORDER BY id", conversationId) { rows ->
            rows.all { r ->
                Branch(
                    id = r.getString("id"),
                    conversation_id = r.getString("conversation_id"),
                    name = r.getString("name"),
                    head_entry_id = r.getString("head_entry_id"),
                    forked_from_entry_id = r.getString("forked_from_entry_id"),
                    created_at = instant(r.nullableLong("created_at_nanos")),
                    extensions = extensionsOf(r.getBytes("extensions")),
                )
            }
        }

    // Raw events

    override fun appendRaw(conversationId: String, line: String): Long = transaction {
        val seq = writerQuery("SELECT coalesce(max(last_seq), 0) FROM raw_event WHERE conversation_id = ?", conversationId) { it.next(); it.getLong(1) } + 1
        write("INSERT INTO raw_event VALUES (?,?,?,0,?)", conversationId, seq, seq, line.encodeToByteArray())
        foldRawTail(conversationId)
        seq
    }

    private fun foldRawTail(conversationId: String) {
        val tailBytes = writerQuery(
            "SELECT coalesce(sum(length(lines)), 0) FROM raw_event WHERE conversation_id = ? AND encoding = 0", conversationId,
        ) { it.next(); it.getLong(1) }
        if (tailBytes < rawBlockBytes) return
        val tail = writerQuery(
            "SELECT first_seq, lines FROM raw_event WHERE conversation_id = ? AND encoding = 0 ORDER BY first_seq", conversationId,
        ) { rows -> rows.all { it.getLong(1) to it.getBytes(2) } }
        val block = gzip(tail.joinToString("\n") { String(it.second, Charsets.UTF_8) }.encodeToByteArray())
        write("DELETE FROM raw_event WHERE conversation_id = ? AND encoding = 0", conversationId)
        write("INSERT INTO raw_event VALUES (?,?,?,1,?)", conversationId, tail.first().first, tail.last().first, block)
    }

    override fun rawCount(conversationId: String): Long =
        query("SELECT coalesce(max(last_seq), 0) FROM raw_event WHERE conversation_id = ?", conversationId) { it.next(); it.getLong(1) }

    override fun raw(conversationId: String, seqs: LongRange): List<String> {
        data class Row(val first: Long, val encoding: Int, val lines: ByteArray)
        val rows = query(
            "SELECT first_seq, encoding, lines FROM raw_event WHERE conversation_id = ? AND last_seq >= ? AND first_seq <= ? ORDER BY first_seq",
            conversationId, seqs.first, seqs.last,
        ) { rs -> rs.all { Row(it.getLong(1), it.getInt(2), it.getBytes(3)) } }
        return rows.flatMap { row ->
            val lines = if (row.encoding == 0) listOf(String(row.lines, Charsets.UTF_8))
            else String(gunzip(row.lines), Charsets.UTF_8).split('\n')
            lines.withIndex().filter { (i, _) -> row.first + i in seqs }.map { it.value }
        }
    }

    // Entries

    override fun appendEntry(entry: Entry) = transaction {
        val row = insertEntry(entry)
        index(row, entry)
        val conversation = checkNotNull(conversationInWrite(entry.conversation_id)) { "no conversation ${entry.conversation_id}" }
        val current = lastTurn(entry.conversation_id, conversation.main_branch_id)
        Turns.append(current, entry, conversation.main_branch_id)?.let(::putTurn)
        val added = entry.body?.turn_result?.usage
        write(
            """UPDATE conversation SET updated_at_nanos = ?,
                total_usage_input_tokens = total_usage_input_tokens + ?,
                total_usage_output_tokens = total_usage_output_tokens + ?,
                total_usage_cache_read_tokens = total_usage_cache_read_tokens + ?,
                total_usage_cache_write_tokens = total_usage_cache_write_tokens + ?,
                total_usage_cost_micro_usd = total_usage_cost_micro_usd + ?
                WHERE id = ?""",
            nanos(entry.at), added?.input_tokens ?: 0L, added?.output_tokens ?: 0L, added?.cache_read_tokens ?: 0L,
            added?.cache_write_tokens ?: 0L, added?.cost_micro_usd ?: 0L, entry.conversation_id,
        )
        Unit
    }

    private fun insertEntry(e: Entry): Long = writerQuery(
        "INSERT INTO entry (id, conversation_id, seq, parent_id, at_nanos, author, kind, state, visibility, raw_first_seq, raw_last_seq, body, extensions) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING row",
        e.id, e.conversation_id, e.seq, e.parent_id, nanos(e.at), e.author.value, kindName(e), e.state.value, e.visibility.value,
        e.raw?.first_seq, e.raw?.last_seq, e.body?.encode(), extensionBytes(e.extensions),
    ) { it.next(); it.getLong(1) }

    override fun updateEntry(entry: Entry) = transaction {
        val (row, oldBody) = checkNotNull(
            writerQuery("SELECT row, body FROM entry WHERE id = ?", entry.id) { rows -> rows.one { it.getLong(1) to it.getBytes(2) } },
        ) { "update of nothing: ${entry.id}" }
        val body = entry.body?.encode()
        val e = entry
        write(
            "UPDATE entry SET parent_id = ?, at_nanos = ?, author = ?, kind = ?, state = ?, visibility = ?, raw_first_seq = ?, raw_last_seq = ?, body = ?, extensions = ? WHERE row = ?",
            e.parent_id, nanos(e.at), e.author.value, kindName(e), e.state.value, e.visibility.value,
            e.raw?.first_seq, e.raw?.last_seq, body, extensionBytes(e.extensions), row,
        )
        if (!body.contentEqualsOrNull(oldBody)) {
            index(row, entry)
            val conversation = checkNotNull(conversationInWrite(entry.conversation_id))
            turnHolding(entry.conversation_id, conversation.main_branch_id, entry.seq)?.let { putTurn(Turns.update(it, entry)) }
        }
        Unit
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?) = this.contentEquals(other)

    private fun index(row: Long, entry: Entry) {
        val text = if (entry.visibility == Visibility.VISIBILITY_PRIVATE) null else searchableText(entry, this)
        if (text.isNullOrEmpty()) write("DELETE FROM entry_fts WHERE rowid = ?", row)
        else write("INSERT OR REPLACE INTO entry_fts (rowid, text) VALUES (?, ?)", row, text)
    }

    private fun entryOf(r: ResultSet) = headOf(r).entry.copy(
        body = r.getBytes("body")?.let(EntryBody.ADAPTER::decode),
        extensions = extensionsOf(r.getBytes("extensions")),
    )

    private fun headOf(r: ResultSet): EntryHead {
        val first = r.nullableLong("raw_first_seq")
        val last = r.nullableLong("raw_last_seq")
        return EntryHead(
            Entry(
                id = r.getString("id"),
                conversation_id = r.getString("conversation_id"),
                seq = r.getLong("seq"),
                parent_id = r.getString("parent_id"),
                at = instant(r.nullableLong("at_nanos")),
                author = Author.fromValue(r.getInt("author")) ?: Author.AUTHOR_UNSPECIFIED,
                state = EntryState.fromValue(r.getInt("state")) ?: EntryState.ENTRY_STATE_UNSPECIFIED,
                visibility = Visibility.fromValue(r.getInt("visibility")) ?: Visibility.VISIBILITY_UNSPECIFIED,
                raw = if (first != null && last != null) RawRange(first, last) else null,
            ),
            r.getString("kind"),
        )
    }

    override fun entry(id: String): Entry? = query("SELECT * FROM entry WHERE id = ?", id) { it.one(::entryOf) }

    override fun entries(conversationId: String, seqs: LongRange): List<Entry> = query(
        "SELECT * FROM entry WHERE conversation_id = ? AND seq BETWEEN ? AND ? ORDER BY seq",
        conversationId, seqs.first, seqs.last,
    ) { it.all(::entryOf) }

    override fun heads(conversationId: String, seqs: LongRange): List<EntryHead> = query(
        "SELECT id, conversation_id, seq, parent_id, at_nanos, author, kind, state, visibility, raw_first_seq, raw_last_seq FROM entry WHERE conversation_id = ? AND seq BETWEEN ? AND ? ORDER BY seq",
        conversationId, seqs.first, seqs.last,
    ) { it.all(::headOf) }

    override fun lastSeq(conversationId: String): Long =
        query("SELECT coalesce(max(seq), 0) FROM entry WHERE conversation_id = ?", conversationId) { it.next(); it.getLong(1) }

    // Turns

    private fun putTurn(t: TurnSummary) {
        write(
            "INSERT OR REPLACE INTO turn VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            t.conversation_id, t.branch_id, t.ordinal, t.user_entry_id, t.result_entry_id, t.first_seq, t.last_seq,
            nanos(t.started_at), t.user_head, t.reply_head, t.tool_call_count, t.error_count, t.artifact_count,
            t.duration?.let { it.getSeconds() * NANOS + it.getNano() },
            t.usage?.input_tokens, t.usage?.output_tokens, t.usage?.cache_read_tokens, t.usage?.cache_write_tokens, t.usage?.cost_micro_usd,
        )
    }

    private fun turnOf(r: ResultSet): TurnSummary {
        val duration = r.nullableLong("duration_nanos")
        return TurnSummary(
            conversation_id = r.getString("conversation_id"),
            branch_id = r.getString("branch_id"),
            ordinal = r.getInt("ordinal"),
            user_entry_id = r.getString("user_entry_id"),
            result_entry_id = r.getString("result_entry_id"),
            first_seq = r.getLong("first_seq"),
            last_seq = r.getLong("last_seq"),
            started_at = instant(r.nullableLong("started_at_nanos")),
            user_head = r.getString("user_head"),
            reply_head = r.getString("reply_head"),
            tool_call_count = r.getInt("tool_call_count"),
            error_count = r.getInt("error_count"),
            artifact_count = r.getInt("artifact_count"),
            duration = duration?.let { Duration.ofSeconds(it.floorDiv(NANOS), it.mod(NANOS)) },
            usage = r.nullableLong("usage_input_tokens")?.let { usageOf(r, "usage_") },
        )
    }

    private fun lastTurn(conversationId: String, branchId: String): TurnSummary? = writerQuery(
        "SELECT * FROM turn WHERE conversation_id = ? AND branch_id = ? ORDER BY ordinal DESC LIMIT 1", conversationId, branchId,
    ) { it.one(::turnOf) }

    private fun turnHolding(conversationId: String, branchId: String, seq: Long): TurnSummary? = writerQuery(
        "SELECT * FROM turn WHERE conversation_id = ? AND branch_id = ? AND first_seq <= ? ORDER BY ordinal DESC LIMIT 1", conversationId, branchId, seq,
    ) { it.one(::turnOf) }

    override fun turns(conversationId: String, branchId: String, offset: Int, limit: Int): List<TurnSummary> = query(
        "SELECT * FROM turn WHERE conversation_id = ? AND branch_id = ? AND ordinal > ? ORDER BY ordinal LIMIT ?",
        conversationId, branchId, offset, limit,
    ) { it.all(::turnOf) }

    override fun turnCount(conversationId: String, branchId: String): Int = query(
        "SELECT count(*) FROM turn WHERE conversation_id = ? AND branch_id = ?", conversationId, branchId,
    ) { it.next(); it.getInt(1) }

    override fun rebuildTurns(conversationId: String) = transaction {
        val branchId = checkNotNull(conversationInWrite(conversationId)).main_branch_id
        write("DELETE FROM turn WHERE conversation_id = ?", conversationId)
        var current: TurnSummary? = null
        forEachEntry(conversationId) { entry ->
            val next = Turns.append(current, entry, branchId)
            if (next != null) {
                putTurn(next)
                current = next
            }
        }
    }

    // Blobs

    override fun put(bytes: ByteString, mediaType: String): BlobRef {
        val ref = blobRefOf(bytes, mediaType)
        val raw = bytes.toByteArray()
        val packed = gzip(raw)
        val compressed = packed.size < raw.size
        val stored = if (compressed) packed else raw
        if (raw.size > fileBlobBytes) {
            val relative = "${ref.sha256.take(2)}/${ref.sha256}"
            val file = blobDir.resolve(relative)
            if (!Files.exists(file)) {
                Files.createDirectories(file.parent)
                val temporary = Files.createTempFile(file.parent, "incoming", null)
                Files.write(temporary, stored)
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
            }
            write("INSERT OR IGNORE INTO blob VALUES (?,?,?,?,NULL,?)", ref.sha256, ref.size, mediaType, compressed, relative)
        } else {
            write("INSERT OR IGNORE INTO blob VALUES (?,?,?,?,?,NULL)", ref.sha256, ref.size, mediaType, compressed, stored)
        }
        return ref
    }

    /** The thread inside a write reads on the write connection: indexing needs a blob just written. */
    override fun blob(sha256: String): ByteString? {
        val sql = "SELECT * FROM blob WHERE sha256 = ?"
        val load: (ResultSet) -> ByteString? = { rows -> rows.one(::blobOf) }
        return if (Thread.holdsLock(writer)) writerQuery(sql, sha256, read = load) else query(sql, sha256, read = load)
    }

    private fun blobOf(r: ResultSet): ByteString {
        val stored = r.getBytes("bytes") ?: Files.readAllBytes(blobDir.resolve(r.getString("file")))
        return (if (r.getInt("compressed") == 1) gunzip(stored) else stored).toByteString()
    }

    // Search

    override fun search(query: String, conversationId: String?, limit: Int): List<SearchHit> {
        val parts = SearchQuery.parts(query)
        if (parts.isEmpty()) return emptyList()
        val match = SearchQuery.match(parts)
        val where = if (conversationId == null) "" else "AND e.conversation_id = ?"
        val values = listOfNotNull<Any>(match, conversationId, limit).toTypedArray()
        val hits = query(
            "SELECT e.id, e.conversation_id, bm25(entry_fts) AS rank FROM entry_fts JOIN entry e ON e.row = entry_fts.rowid WHERE entry_fts MATCH ? $where ORDER BY rank LIMIT ?",
            *values,
        ) { rows -> rows.all { Triple(it.getString(1), it.getString(2), it.getDouble(3)) } }
        return hits.map { (id, conversation, rank) ->
            val text = entry(id)?.let { searchableText(it, this) }.orEmpty()
            SearchHit(conversation, id, SearchQuery.snippet(text, parts), rank)
        }
    }

    private fun usageOf(r: ResultSet, prefix: String) = Usage(
        input_tokens = r.getLong("${prefix}input_tokens"),
        output_tokens = r.getLong("${prefix}output_tokens"),
        cache_read_tokens = r.getLong("${prefix}cache_read_tokens"),
        cache_write_tokens = r.getLong("${prefix}cache_write_tokens"),
        cost_micro_usd = r.getLong("${prefix}cost_micro_usd"),
    )

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun extensionBytes(extensions: List<Extension>): ByteArray? =
        if (extensions.isEmpty()) null else Entry(extensions = extensions).encode()

    private fun extensionsOf(bytes: ByteArray?): List<Extension> =
        bytes?.let { Entry.ADAPTER.decode(it).extensions }.orEmpty()

    private fun kindName(entry: Entry): String = entry.body?.kind?.let { it::class.simpleName }.orEmpty()

    private fun nanos(at: Instant?): Long? = at?.let { it.getEpochSecond() * NANOS + it.getNano() }

    private fun instant(nanos: Long?): Instant? = nanos?.let { Instant.ofEpochSecond(it.floorDiv(NANOS), it.mod(NANOS)) }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream(bytes.size / 3 + 32).also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    companion object {
        private const val NANOS = 1_000_000_000L
        const val DEFAULT_FILE_BLOB_BYTES = 1024 * 1024
        const val DEFAULT_RAW_BLOCK_BYTES = 256 * 1024

        fun open(
            path: Path,
            fileBlobBytes: Int = DEFAULT_FILE_BLOB_BYTES,
            rawBlockBytes: Int = DEFAULT_RAW_BLOCK_BYTES,
        ): SqliteStore {
            path.parent?.let(Files::createDirectories)
            return SqliteStore(path, fileBlobBytes, rawBlockBytes)
        }
    }
}

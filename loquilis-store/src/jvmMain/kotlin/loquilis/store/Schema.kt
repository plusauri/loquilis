package loquilis.store

import java.sql.Connection

/**
 * The library's tables. Each list in [MIGRATIONS] moves the file one version on, and `user_version`
 * says how far a file has come.
 *
 * Search is a contentless FTS5 index. Entry bodies are proto bytes, so SQLite has no text column to
 * point an external-content index at, and a stored copy would double the library. The index keeps only
 * tokens, and the store makes each snippet from the entry itself.
 *
 * `raw_event` holds a conversation's newest lines one to a row (`encoding` 0), so each is durable as
 * soon as it is written, and folds them into compressed blocks (`encoding` 1) as they add up.
 */
internal object Schema {
    val MIGRATIONS: List<List<String>> = listOf(
        listOf(
            """
            CREATE TABLE conversation (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at_nanos INTEGER,
                updated_at_nanos INTEGER,
                agent_cli TEXT NOT NULL,
                agent_cli_version TEXT NOT NULL,
                agent_model TEXT NOT NULL,
                agent_reported_model TEXT NOT NULL,
                agent_transport INTEGER NOT NULL,
                agent_session_id TEXT NOT NULL,
                agent_present INTEGER NOT NULL,
                workspace_path TEXT NOT NULL,
                outcome_status INTEGER NOT NULL,
                outcome_reason TEXT NOT NULL,
                outcome_present INTEGER NOT NULL,
                total_usage_input_tokens INTEGER NOT NULL,
                total_usage_output_tokens INTEGER NOT NULL,
                total_usage_cache_read_tokens INTEGER NOT NULL,
                total_usage_cache_write_tokens INTEGER NOT NULL,
                total_usage_cost_micro_usd INTEGER NOT NULL,
                main_branch_id TEXT NOT NULL,
                parser_adapter TEXT NOT NULL,
                parser_version INTEGER NOT NULL,
                parser_present INTEGER NOT NULL,
                extensions BLOB
            )
            """,
            "CREATE INDEX conversation_by_update ON conversation (updated_at_nanos DESC)",
            """
            CREATE TABLE entry (
                row INTEGER PRIMARY KEY,
                id TEXT NOT NULL UNIQUE,
                conversation_id TEXT NOT NULL REFERENCES conversation (id),
                seq INTEGER NOT NULL,
                parent_id TEXT NOT NULL,
                at_nanos INTEGER,
                author INTEGER NOT NULL,
                kind TEXT NOT NULL,
                state INTEGER NOT NULL,
                visibility INTEGER NOT NULL,
                raw_first_seq INTEGER,
                raw_last_seq INTEGER,
                body BLOB,
                extensions BLOB,
                UNIQUE (conversation_id, seq)
            )
            """,
            "CREATE INDEX entry_by_parent ON entry (parent_id)",
            """
            CREATE TABLE turn (
                conversation_id TEXT NOT NULL,
                branch_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                user_entry_id TEXT NOT NULL,
                result_entry_id TEXT NOT NULL,
                first_seq INTEGER NOT NULL,
                last_seq INTEGER NOT NULL,
                started_at_nanos INTEGER,
                user_head TEXT NOT NULL,
                reply_head TEXT NOT NULL,
                tool_call_count INTEGER NOT NULL,
                error_count INTEGER NOT NULL,
                artifact_count INTEGER NOT NULL,
                duration_nanos INTEGER,
                usage_input_tokens INTEGER,
                usage_output_tokens INTEGER,
                usage_cache_read_tokens INTEGER,
                usage_cache_write_tokens INTEGER,
                usage_cost_micro_usd INTEGER,
                PRIMARY KEY (conversation_id, branch_id, ordinal)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE branch (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversation (id),
                name TEXT NOT NULL,
                head_entry_id TEXT NOT NULL,
                forked_from_entry_id TEXT NOT NULL,
                created_at_nanos INTEGER,
                extensions BLOB
            )
            """,
            "CREATE INDEX branch_by_conversation ON branch (conversation_id)",
            """
            CREATE TABLE raw_event (
                conversation_id TEXT NOT NULL,
                first_seq INTEGER NOT NULL,
                last_seq INTEGER NOT NULL,
                encoding INTEGER NOT NULL,
                lines BLOB NOT NULL,
                PRIMARY KEY (conversation_id, first_seq)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE blob (
                sha256 TEXT PRIMARY KEY,
                size INTEGER NOT NULL,
                media_type TEXT NOT NULL,
                compressed INTEGER NOT NULL,
                bytes BLOB,
                file TEXT
            ) WITHOUT ROWID
            """,
            "CREATE VIRTUAL TABLE entry_fts USING fts5 (text, content = '', contentless_delete = 1)",
        ),
    )

    fun migrate(connection: Connection) {
        val version = connection.createStatement().use { s ->
            s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
        }
        check(version <= MIGRATIONS.size) { "this library was written by a newer Loquilis (version $version)" }
        for (next in version until MIGRATIONS.size) {
            connection.autoCommit = false
            connection.createStatement().use { s ->
                MIGRATIONS[next].forEach { s.execute(it.trimIndent()) }
                s.execute("PRAGMA user_version = ${next + 1}")
            }
            connection.commit()
            connection.autoCommit = true
        }
    }
}

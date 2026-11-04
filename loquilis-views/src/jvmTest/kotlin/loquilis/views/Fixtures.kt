package loquilis.views

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import loquilis.core.Ulid
import loquilis.core.inlineContent
import loquilis.model.AgentBinding
import loquilis.model.AgentText
import loquilis.model.Conversation
import loquilis.model.Entry
import loquilis.model.EntryBody
import loquilis.model.EntryState
import loquilis.model.Notice
import loquilis.model.TextRole
import loquilis.model.UserMessage
import loquilis.model.Visibility
import loquilis.store.SqliteStore
import loquilis.store.Store
import loquilis.store.forEachEntry
import loquilis.store.recordTake

@OptIn(ExperimentalPathApi::class)
fun tempDir(): Path = Files.createTempDirectory("loquilis-views").also { dir ->
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { dir.deleteRecursively() } })
}

fun Store.recordFixtureTake(): String {
    fun open(name: String) = checkNotNull(Fixture::class.java.getResourceAsStream("/fixtures/claude-take/$name")) { name }
    return recordTake(this, open("conversation.json"), open("raw.jsonl.gz"))
}

fun Store.allEntries(id: String): List<Entry> = buildList { forEachEntry(id) { add(it) } }

/** The public take every parser test also reads, recorded once for all the tests that only read it. */
object Fixture {
    val store: Store by lazy { SqliteStore.open(tempDir().resolve("library.sqlite")) }
    val conversationId: String by lazy { store.recordFixtureTake() }
    val entries: List<Entry> by lazy { store.allEntries(conversationId) }
}

/** A plain conversation of [turns] turns, each a message and a reply. [before] entries precede the first turn. */
fun Store.writeConversation(title: String, turns: Int, before: Int = 0, say: (Int) -> String = { "Question number $it" }): String {
    val id = Ulid.next()
    val at = Instant.ofEpochSecond(1_793_000_000)
    transaction {
        putConversation(Conversation(id = id, title = title, created_at = at, main_branch_id = Ulid.next(), agent = AgentBinding(cli = "claude")))
        var seq = 0L
        var parent = ""
        fun add(body: EntryBody) {
            val entry = Entry(
                id = Ulid.next(), conversation_id = id, seq = ++seq, parent_id = parent, at = at, body = body,
                state = EntryState.ENTRY_STATE_COMPLETE, visibility = Visibility.VISIBILITY_NORMAL,
            )
            appendEntry(entry)
            parent = entry.id
        }
        repeat(before) { add(EntryBody(notice = Notice(text = "Starting up $it"))) }
        for (turn in 1..turns) {
            add(EntryBody(user_message = UserMessage(inlineContent(say(turn)))))
            add(EntryBody(agent_text = AgentText(inlineContent("Answer number $turn"), TextRole.TEXT_ROLE_REPLY)))
        }
    }
    return id
}

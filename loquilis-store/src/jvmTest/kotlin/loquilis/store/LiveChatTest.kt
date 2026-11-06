package loquilis.store

import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loquilis.core.kind
import loquilis.model.Entry
import loquilis.model.OutcomeStatus
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/** A chat against a shell script standing in for `claude`, with a real library on disk. */
class LiveChatTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SqliteStore.open(dir.resolve("library.sqlite")) }

    private fun fake(body: String): Agent {
        val script = dir.resolve("fake-claude.sh")
        script.writeText("#!/bin/sh\n$body\n")
        return ClaudeAgent(command = listOf("/bin/sh", script.toString()))
    }

    /** Answers every message, says who it is first, and records its arguments. */
    private val answers: Agent get() = fake(
        """
        echo "$@" >> "$dir/args"
        printf '%s\n' '{"type":"system","subtype":"init","model":"m","claude_code_version":"2","session_id":"s1","cwd":"$dir"}'
        while read -r line; do
          printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"Answer"}]}}'
          printf '%s\n' '{"type":"result","is_error":false,"result":"Answer","total_cost_usd":0.1,"num_turns":1}'
        done
        """.trimIndent(),
    )

    private fun chat(agent: Agent, id: String = store.newConversation(dir, AT).id) = LiveChat(store, id, agent)

    private fun LiveChat.until(wanted: (ChatStatus) -> Boolean): ChatStatus =
        runBlocking { withTimeout(10_000) { status.first(wanted) } }

    private fun LiveChat.exchange(text: String) {
        send(text)
        until { it == ChatStatus.Idle }
    }

    private fun entries(id: String): List<Entry> = buildList { store.forEachEntry(id) { add(it) } }

    private fun kinds(id: String) = entries(id).map { it.body!!.kind::class.simpleName }

    @Test fun anExchangeIsOnDiskAndTheFirstMessageNamesTheConversation() {
        val chat = chat(answers)
        chat.exchange("Tell me about plovers\nand more")
        assertEquals(listOf("User", "Text", "TurnEnd"), kinds(chat.conversationId))
        val conversation = store.conversation(chat.conversationId)!!
        assertEquals("Tell me about plovers", conversation.title)
        assertEquals("s1", conversation.agent!!.agent_session_id)
        assertEquals(3, store.rawCount(chat.conversationId))
        assertEquals(1, store.search("plovers").size)
        chat.stop()
    }

    @Test fun whatTheAgentSaysIsWrittenBeforeTheTurnEnds() {
        val chat = chat(
            fake(
                """
                read -r line
                printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"Looking"}]}}'
                read -r never
                """.trimIndent(),
            ),
        )
        chat.send("Go")
        runBlocking { withTimeout(10_000) { chat.written.first { store.lastSeq(chat.conversationId) == 2L } } }
        assertEquals(ChatStatus.Working(0), chat.status.value)
        chat.stop()
    }

    @Test fun aMessageSentMidTurnIsCountedAsWaiting() {
        val slow = fake("while read -r line; do sleep 0.3; printf '%s\\n' '{\"type\":\"result\",\"is_error\":false,\"result\":\"A\",\"num_turns\":1}'; done")
        val chat = chat(slow)
        chat.send("One")
        chat.send("Two")
        assertEquals(ChatStatus.Working(1), chat.status.value)
        chat.until { it == ChatStatus.Idle }
        assertEquals(2, kinds(chat.conversationId).count { it == "User" })
        chat.stop()
    }

    @Test fun aNewSessionCarriesOnTheStoredConversation() {
        val id = chat(answers).run {
            exchange("One")
            stop()
            conversationId
        }
        val before = entries(id)
        val next = chat(answers, id)
        next.exchange("Two")
        next.stop()

        val after = entries(id)
        assertEquals(before, after.take(before.size))
        assertEquals((1L..after.size).toList(), after.map { it.seq })
        assertEquals(before.last().id, after[before.size].parent_id)
        assertEquals(2, store.turnCount(id, store.conversation(id)!!.main_branch_id))
        assertEquals(after.last().raw!!.last_seq, store.rawCount(id))
        val args = dir.resolve("args").readLines()
        assertTrue("--resume" !in args[0], args[0])
        assertTrue("--resume s1" in args[1], args[1])
    }

    @Test fun aChatRestartsItsAgentAfterItStops() {
        val chat = chat(answers)
        chat.exchange("One")
        chat.stop()
        chat.exchange("Two")
        chat.stop()
        assertEquals(listOf("User", "Text", "TurnEnd", "User", "Text", "TurnEnd"), kinds(chat.conversationId))
    }

    @Test fun aCrashGivesBackWhatNeverWentOutAndRecordsWhy() {
        val crash = fake("read -r line\necho 'it went wrong' >&2\nexit 3")
        val chat = chat(crash)
        chat.send("One")
        chat.send("Two")
        val failed = assertIs<ChatStatus.Failed>(chat.until { it is ChatStatus.Failed })
        assertEquals(listOf("Two"), failed.unsent)
        assertTrue("status 3" in failed.reason && "it went wrong" in failed.reason, failed.reason)
        val outcome = store.conversation(chat.conversationId)!!.outcome!!
        assertEquals(OutcomeStatus.OUTCOME_STATUS_ERROR, outcome.status)
    }

    @Test fun anAgentThatCannotStartGivesTheMessageBack() {
        val chat = chat(ClaudeAgent(command = listOf(dir.resolve("no-such-program").toString())))
        chat.send("Hello")
        val failed = assertIs<ChatStatus.Failed>(chat.status.value)
        assertEquals(listOf("Hello"), failed.unsent)
        assertEquals(0, store.lastSeq(chat.conversationId))
    }

    @Test fun aKilledTurnIsClosedBeforeTheConversationCarriesOn() {
        val hanging = fake(
            """
            read -r line
            printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sleep 9"}}]}}'
            read -r never
            """.trimIndent(),
        )
        val first = chat(hanging)
        first.send("Go")
        runBlocking { withTimeout(10_000) { first.written.first { store.lastSeq(first.conversationId) == 2L } } }
        first.stop()
        val next = chat(answers, first.conversationId)
        next.exchange("Again")
        next.stop()
        assertEquals(listOf("User", "Call", "User", "Text", "TurnEnd"), kinds(first.conversationId))
        assertTrue(dir.resolve("args").readText().isNotEmpty())
    }

    @Test fun aNewConversationOpensEmpty() {
        val id = store.newConversation(dir, AT).id
        assertEquals("", store.conversation(id)!!.title)
        assertEquals(dir.toString(), store.conversation(id)!!.workspace_path)
        assertEquals(id, store.conversations(limit = 1).single().id)
    }

    /** The real agent across two processes. Costs a few cents, so it runs only with LOQUILIS_LIVE=1. */
    @Test
    @EnabledIfEnvironmentVariable(named = "LOQUILIS_LIVE", matches = "1")
    fun theRealClaudeRemembersAcrossARestart() {
        val agent = ClaudeAgent(extraArgs = listOf("--setting-sources", "project,local", "--permission-mode", "dontAsk"))
        val id = store.newConversation(dir, AT, model = "claude-haiku-5-5").id
        fun exchange(text: String) = LiveChat(store, id, agent).run {
            send(text)
            runBlocking { withTimeout(120_000) { status.first { it !is ChatStatus.Working } } }
            assertEquals(ChatStatus.Idle, status.value)
            stop()
        }
        exchange("Remember the word plover. Reply with only OK.")
        exchange("What word did I ask you to remember? Reply with only that word.")

        val all = entries(id)
        val reply = all.mapNotNull { it.body?.agent_text?.text?.inline_ }.last()
        assertTrue(reply.contains("plover", ignoreCase = true), reply)
        assertEquals(2, all.count { it.body?.turn_result != null })
        all.mapNotNull { it.body?.turn_result?.usage?.cost_micro_usd }.forEach { println("LIVE turn cost micro-usd $it") }
        println("LIVE total " + store.conversation(id)!!.total_usage?.cost_micro_usd)
    }
}

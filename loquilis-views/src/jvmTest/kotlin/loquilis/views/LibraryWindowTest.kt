@file:OptIn(ExperimentalTestApi::class)

package loquilis.views

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import loquilis.store.Agent
import loquilis.store.ClaudeAgent
import loquilis.store.SqliteStore
import loquilis.store.Store

/** The window with a shell script standing in for `claude`, and a real library on disk. */
class LibraryWindowTest {
    private val dir: Path = tempDir()
    private val store: Store = SqliteStore.open(dir.resolve("library.sqlite"))

    private fun fake(body: String): Agent {
        val script = dir.resolve("fake-claude.sh")
        script.writeText("#!/bin/sh\n$body\n")
        return ClaudeAgent(command = listOf("/bin/sh", script.toString()))
    }

    private val answers: Agent get() = fake(
        """
        printf '%s\n' '{"type":"system","subtype":"init","model":"m","claude_code_version":"2","session_id":"s1","cwd":"$dir"}'
        while read -r line; do
          printf '%s\n' '{"type":"assistant","message":{"content":[{"type":"text","text":"Plovers are shorebirds."}]}}'
          printf '%s\n' '{"type":"result","is_error":false,"result":"Plovers are shorebirds.","total_cost_usd":0.1,"num_turns":1}'
        done
        """.trimIndent(),
    )

    private fun ComposeUiTest.open(agent: Agent): LibraryState {
        val state = LibraryState(store, agent)
        setContent { ReviewTheme { LibraryWindow(state) { state.newConversation(dir) } } }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.showing(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.write(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.shot(name: String) {
        val folder = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
    }

    @Test fun aConversationStartsSendsAndAnswersInTheWindow() = runDesktopComposeUiTest(1100, 700) {
        val state = open(answers)
        showing("Start a conversation to talk to an agent.")
        onNodeWithText("New conversation").performClick()
        waitForIdle()
        write("Tell me about plovers")
        onNodeWithText("Send").performClick()

        showing("Plovers are shorebirds.")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Working…").fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { state.current!!.pager.conversation.title == "Tell me about plovers" }
        assertEquals(1, state.current!!.pager.turnCount)
        showing("Tell me about plovers")
        shot("chat")
        state.close()
    }

    @Test fun returnSendsAndClearsTheBox() = runDesktopComposeUiTest(1100, 700) {
        val state = open(answers)
        onNodeWithText("New conversation").performClick()
        waitForIdle()
        write("Hello")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        showing("Plovers are shorebirds.")
        assertNull(state.drafts[state.currentId])
        state.close()
    }

    @Test fun aFailureSaysWhyAndGivesTheMessageBack() = runDesktopComposeUiTest(1100, 700) {
        val state = open(ClaudeAgent(command = listOf(dir.resolve("no-such-program").toString())))
        onNodeWithText("New conversation").performClick()
        waitForIdle()
        write("Hello there")
        onNodeWithText("Send").performClick()
        waitUntil(timeoutMillis = 5_000) { state.drafts[state.currentId] == "Hello there" }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("no-such-program", substring = true).fetchSemanticsNodes().isNotEmpty() }
        state.close()
    }

    @Test fun aSecondConversationKeepsItsOwnDraft() = runDesktopComposeUiTest(1100, 700) {
        val state = open(answers)
        onNodeWithText("New conversation").performClick()
        waitForIdle()
        val first = state.currentId!!
        write("Half a thought")
        onAllNodesWithText("New conversation")[0].performClick()
        waitForIdle()
        assertEquals(2, state.conversations.size)
        assertEquals("", state.drafts[state.currentId].orEmpty())
        state.select(first)
        waitForIdle()
        assertEquals("Half a thought", state.drafts[first])
        state.close()
    }

    @Test fun anAgentKeepsRecordingWhileAnotherConversationShows() = runDesktopComposeUiTest(1100, 700) {
        val state = open(
            fake("while read -r line; do sleep 0.3; printf '%s\\n' '{\"type\":\"result\",\"is_error\":false,\"result\":\"Done.\",\"num_turns\":1}'; done"),
        )
        onNodeWithText("New conversation").performClick()
        waitForIdle()
        val first = state.currentId!!
        write("Slow one")
        onNodeWithText("Send").performClick()
        waitForIdle()
        state.newConversation(dir)
        waitUntil(timeoutMillis = 5_000) { store.lastSeq(first) >= 3 }
        state.select(first)
        showing("Done.")
        state.close()
    }
}

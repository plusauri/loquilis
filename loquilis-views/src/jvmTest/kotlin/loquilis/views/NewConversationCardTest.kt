@file:OptIn(ExperimentalTestApi::class)

package loquilis.views

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import loquilis.store.ClaudeAgent
import loquilis.store.GrokAgent
import loquilis.store.SqliteStore

/**
 * One button starts every conversation. With a choice of agents it opens a card that says what each is and where
 * its data goes; the two side panels fold and keep how they were left.
 */
class NewConversationCardTest {
    private val dir: Path = tempDir()
    private val store = SqliteStore.open(dir.resolve("library.sqlite"))
    private val kept = mutableListOf<ConsoleSettings>()
    private val started = mutableListOf<String>()

    private fun ComposeUiTest.open(): LibraryState {
        val state = LibraryState(store, mapOf("claude" to ClaudeAgent(env = emptyMap()), "grok" to GrokAgent()), onSettings = { kept += it }, networks = { emptyList() })
        setContent { ReviewTheme { LibraryWindow(state) { agent, _ -> started += agent } } }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.count(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shot(name: String) {
        val folder = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
    }

    @Test fun theCardSaysWhatEachAgentIsAndCreatesTheOneChosen() = runDesktopComposeUiTest(1100, 760) {
        val state = open()
        onNodeWithText("New conversation…").performClick()
        waitForIdle()
        assertEquals("claude", state.choosingAgent, "the first agent, with no conversation yet")
        assertEquals(1, count("Anthropic's coding agent"))
        assertEquals(1, count("Sent to Anthropic"))
        shot("new-conversation")

        onAllNodesWithText("Grok")[0].performClick()
        waitForIdle()
        assertEquals(1, count("xAI's coding agent"))
        assertEquals(0, count("Anthropic's coding agent"))

        onNodeWithText("Create").performClick()
        waitForIdle()
        assertEquals(listOf("grok"), started)
        assertNull(state.choosingAgent)
    }

    @Test fun returnCreatesEscapeClosesAndCommandNOpens() = runDesktopComposeUiTest(1100, 760) {
        val state = open()
        val command = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { withKeyDown(command) { pressKey(Key.N) } }
        waitForIdle()
        assertEquals("claude", state.choosingAgent)

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(state.choosingAgent)
        assertTrue(started.isEmpty())

        onNodeWithText("New conversation…").performClick()
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("claude"), started)
    }

    @Test fun theSidePanelsFoldAndKeepHowTheyWereLeft() = runDesktopComposeUiTest(1100, 700) {
        val state = open()
        state.newConversation(dir, "claude")
        waitForIdle()

        onNodeWithContentDescription("Hide the conversation list").performClick()
        onNodeWithContentDescription("Hide the inspector").performClick()
        waitForIdle()
        assertEquals(0, count("New conversation…"))
        assertEquals(0, count("Select anything in the conversation"))
        assertEquals(false, kept.last().listShown)
        assertEquals(false, kept.last().inspectorShown)
        shot("panels-folded")

        onNodeWithContentDescription("Show the conversation list").performClick()
        onNodeWithContentDescription("Show the inspector").performClick()
        waitForIdle()
        assertEquals(1, count("New conversation…"))
        assertEquals(true, kept.last().listShown && kept.last().inspectorShown)
    }

    @Test fun aNameGivenAtCreationIsKeptAndABlankOneWaitsForTheFirstMessage() = runDesktopComposeUiTest(1100, 760) {
        val state = LibraryState(store, mapOf("claude" to ClaudeAgent(env = emptyMap()), "grok" to GrokAgent()), networks = { emptyList() })
        setContent { ReviewTheme { LibraryWindow(state) { agent, model -> state.newConversation(dir, agent, model) } } }
        waitForIdle()

        onNodeWithText("New conversation…").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Plover notes")
        shot("new-conversation-named")
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals("Plover notes", store.conversation(state.currentId!!)!!.title)

        onNodeWithText("New conversation…").performClick()
        waitForIdle()
        assertEquals("", state.newName, "the card opens blank")
        onNodeWithText("Create").performClick()
        waitForIdle()
        assertEquals("", store.conversation(state.currentId!!)!!.title, "left for the first message to name")
    }
}

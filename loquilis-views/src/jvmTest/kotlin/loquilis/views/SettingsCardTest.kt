@file:OptIn(ExperimentalTestApi::class)

package loquilis.views

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import loquilis.store.ClaudeAgent
import loquilis.store.GrokAgent
import loquilis.store.SqliteStore

/** The settings card changes what the console offers at once, and hands each change to the host to keep. */
class SettingsCardTest {
    private val dir: Path = tempDir()
    private val store = SqliteStore.open(dir.resolve("library.sqlite"))
    private val kept = mutableListOf<ConsoleSettings>()

    private fun ComposeUiTest.open(): LibraryState {
        val state = LibraryState(store, mapOf("claude" to ClaudeAgent(env = emptyMap()), "grok" to GrokAgent()), onSettings = { kept += it }, networks = { emptyList() })
        setContent { ReviewTheme { LibraryWindow(state) { _, _ -> } } }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun hidingAnAgentTakesItsLinkAwayAndTheLastOneStays() = runDesktopComposeUiTest(1100, 760) {
        val state = open()
        assertEquals(1, count("New Grok conversation"))
        onNodeWithText("Settings").performClick()
        waitForIdle()
        val folder = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "settings.png"))

        onNodeWithText("Grok").performClick()
        waitForIdle()
        assertEquals(0, count("New Grok conversation"))
        assertEquals(setOf("grok"), kept.last().hiddenAgents)

        onNodeWithText("Claude").performClick()
        waitForIdle()
        assertEquals(setOf("grok"), state.settings.hiddenAgents, "the last agent shown cannot be hidden")
        assertEquals(1, count("New conversation"), "with one agent left, the link is plain")
    }

    @Test fun theKeepTimeIsKeptAndEscapeCloses() = runDesktopComposeUiTest(1100, 760) {
        val state = open()
        onNodeWithText("Settings").performClick()
        onNodeWithText("5 min").performClick()
        waitForIdle()
        assertEquals(5, kept.last().keepModelMinutes)

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(false, state.showingSettings)
        assertEquals(0, count("Keep a model in memory after it answers"))
    }
}

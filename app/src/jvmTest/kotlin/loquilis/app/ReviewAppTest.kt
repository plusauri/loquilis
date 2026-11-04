@file:OptIn(ExperimentalTestApi::class)

package loquilis.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives the real window offscreen, and leaves pictures in app/build/review-shots for a person to read. */
class ReviewAppTest {
    private val take by lazy { loadBundledTake() }

    private fun ComposeUiTest.open(dark: Boolean = false): ReviewState {
        val state = ReviewState(take)
        setContent { ReviewTheme(dark) { ReviewApp(state) } }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.shot(name: String) {
        val dir = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private val firstSaid: String get() = take.entries.first().body!!.user_message!!.text!!.inline_!!

    @Test
    fun opensOnMessagesWithTheUsersFirstMessage() = runDesktopComposeUiTest(1320, 880) {
        open()
        onNodeWithText(firstSaid).assertIsDisplayed()
        onNodeWithText("Select anything", substring = true).assertIsDisplayed()
        shot("messages")
    }

    @Test
    fun darkThemeRenders() = runDesktopComposeUiTest(1320, 880) {
        open(dark = true)
        onNodeWithText(firstSaid).assertIsDisplayed()
        shot("messages-dark")
    }

    @Test
    fun theFoldOpensToShowTheTurnsSteps() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val fold = state.messages.filterIsInstance<MessageRow.Work>().first()
        onNodeWithText(fold.summary).performClick()
        waitForIdle()
        assertEquals(setOf(fold.turn), state.openTurns.toSet())
        val step = state.messages.filterIsInstance<MessageRow.Step>().first { it.entry.body?.tool_call != null }
        onAllNodesWithText(headline(step.entry))[0].assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(step.entry.id, state.selectedId)
        shot("messages-open-fold")
    }

    @Test
    fun selectionSurvivesSwitchingViewsBothWays() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val step = state.messages.filterIsInstance<MessageRow.Step>().last { it.result != null }
        val resultId = assertNotNull(step.result).id
        state.selectedId = resultId
        state.show(ReviewView.Tree)
        waitForIdle()
        onNodeWithText("Tree").assertIsDisplayed()
        assertEquals(resultId, state.selectedId)
        assertTrue(state.visibleTreeRows().any { it.entry.id == resultId })
        shot("tree")

        state.show(ReviewView.Messages)
        waitForIdle()
        assertEquals(resultId, state.selectedId)
        assertTrue(step.turn in state.openTurns, "the fold holding the selection opened")
        shot("messages-after-switch")
    }

    @Test
    fun switchingToTheTreeUncollapsesWhatHidesTheSelection() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val turnRow = state.tree.first { it.entry.body?.user_message != null }
        val inside = state.tree[state.tree.indexOf(turnRow) + 1]
        state.collapsed += turnRow.entry.id
        state.selectedId = inside.entry.id
        state.show(ReviewView.Tree)
        waitForIdle()
        assertTrue(turnRow.entry.id !in state.collapsed)
    }

    @Test
    fun theTreeSwitchIsClickable() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        onNodeWithText("Tree").performClick()
        waitForIdle()
        assertEquals(ReviewView.Tree, state.view)
    }

    @Test
    fun theInspectorFollowsTheParentLink() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val child = take.entries[2]
        val parent = assertNotNull(take.byId[child.parent_id])
        state.selectedId = child.id
        waitForIdle()
        onNodeWithText("#${parent.seq} ${kindLabel(parent)}").performClick()
        waitForIdle()
        assertEquals(parent.id, state.selectedId)
    }
}

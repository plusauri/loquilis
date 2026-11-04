@file:OptIn(ExperimentalTestApi::class)

package loquilis.views

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import loquilis.model.Entry
import loquilis.store.SqliteStore
import loquilis.store.Store

/** Drives the real window offscreen, and leaves pictures in build/review-shots for a person to read. */
class ConversationWindowTest {
    private val entries get() = Fixture.entries

    private fun ComposeUiTest.open(store: Store = Fixture.store, id: String = Fixture.conversationId, dark: Boolean = false): ConversationState {
        val state = ConversationState(store, id)
        setContent { ReviewTheme(dark) { ConversationWindow(state) } }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.shot(name: String) {
        val dir = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private fun ComposeUiTest.showing(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private val firstSaid: String get() = entries.first().body!!.user_message!!.text!!.inline_!!

    /** The first ordinal whose turn has a fold, with that fold's row. */
    private fun firstFold(state: ConversationState): Pair<LoadedTurn, MessageRow.Work> =
        (1..state.pager.lastOrdinal).map(state.pager::load).firstNotNullOf { turn ->
            turn.messages.filterIsInstance<MessageRow.Work>().firstOrNull()?.let { turn to it }
        }

    @Test
    fun opensOnMessagesWithTheUsersFirstMessage() = runDesktopComposeUiTest(1320, 880) {
        open()
        showing(firstSaid)
        onNodeWithText(firstSaid).assertIsDisplayed()
        onNodeWithText("Select anything", substring = true).assertIsDisplayed()
        shot("messages")
    }

    @Test
    fun darkThemeRenders() = runDesktopComposeUiTest(1320, 880) {
        open(dark = true)
        showing(firstSaid)
        shot("messages-dark")
    }

    @Test
    fun theFoldOpensToShowTheTurnsSteps() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val (turn, fold) = firstFold(state)
        showing(fold.summary)
        onNodeWithText(fold.summary).performClick()
        waitForIdle()
        assertEquals(setOf(fold.turn), state.openTurns.keys.toSet())
        val step = turn.messages.filterIsInstance<MessageRow.Step>().first { it.entry.body?.tool_call != null }
        onAllNodesWithText(headline(step.entry))[0].assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(step.entry.id, state.selectedId)
        shot("messages-open-fold")
    }

    @Test
    fun selectionSurvivesSwitchingViewsBothWays() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val turn = state.pager.load(state.pager.lastOrdinal)
        val step = turn.messages.filterIsInstance<MessageRow.Step>().last { it.result != null }
        val resultId = assertNotNull(step.result).id
        state.selectedId = resultId
        state.show(ConversationView.Tree)
        waitForIdle()
        assertEquals(resultId, state.selectedId)
        assertTrue(state.treeRows(state.openTreeTurns.getValue(turn.ordinal)).any { it.entry.id == resultId })
        shot("tree")

        state.show(ConversationView.Messages)
        waitForIdle()
        assertEquals(resultId, state.selectedId)
        assertTrue(step.turn in state.openTurns, "the fold holding the selection opened")
        shot("messages-after-switch")
    }

    @Test
    fun clickingARowInTheTreeSelectsItAndTogglesIt() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        onNodeWithText("Tree").performClick()
        waitForIdle()
        assertEquals(ConversationView.Tree, state.view)
        assertEquals(7, onAllNodesWithText("You").fetchSemanticsNodes().size)
        shot("tree-closed")

        val first = state.pager.summary(1)!!
        onAllNodesWithText("You")[0].performClick()
        waitForIdle()
        assertEquals(setOf(1), state.openTreeTurns.keys.toSet())
        assertEquals(first.user_entry_id, state.selectedId)

        val turn = state.pager.load(1)
        val nested = turn.tree.first { it.entry.id in turn.treeParents && it.depth > 0 }
        onAllNodesWithText(headline(nested.entry))[0].performClick()
        waitForIdle()
        assertTrue(nested.entry.id in state.collapsed, "a row with something under it folds")
        assertEquals(nested.entry.id, state.selectedId)

        onAllNodesWithText("You")[0].performClick()
        waitForIdle()
        assertTrue(state.openTreeTurns.isEmpty(), "a second click closes the turn")
    }

    @Test
    fun clickingARowWithNothingUnderItOnlySelectsIt() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val turn = state.pager.load(1)
        state.show(ConversationView.Tree)
        state.toggleTreeTurn(1)
        waitForIdle()
        val leaf = turn.tree.drop(1).first { it.entry.id !in turn.treeParents }
        onAllNodesWithText(headline(leaf.entry))[0].performClick()
        waitForIdle()
        assertEquals(leaf.entry.id, state.selectedId)
        assertTrue(state.collapsed.isEmpty())
        assertEquals(setOf(1), state.openTreeTurns.keys.toSet())
    }

    @Test
    fun collapseAllClosesEveryTurn() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        state.show(ConversationView.Tree)
        waitForIdle()
        onAllNodesWithText("You")[1].performClick()
        waitForIdle()
        onAllNodesWithText("You")[0].performClick()
        waitForIdle()
        assertEquals(setOf(1, 2), state.openTreeTurns.keys.toSet())
        shot("tree-open")
        onNodeWithText("Collapse all").performClick()
        waitForIdle()
        assertTrue(state.openTreeTurns.isEmpty())
    }

    @Test
    fun switchingToTheTreeUncollapsesWhatHidesTheSelection() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val turn = state.pager.load(1)
        val nested = turn.tree.last { it.depth >= 2 }
        val above = treeAncestors(turn.tree, nested.entry.id).first()
        state.collapsed += above
        state.selectedId = nested.entry.id
        state.show(ConversationView.Tree)
        waitForIdle()
        assertTrue(above !in state.collapsed)
    }

    @Test
    fun theInspectorFollowsTheParentLink() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        val child = entries[2]
        val parent = entries.first { it.id == child.parent_id }
        state.selectedId = child.id
        val link = "#${parent.seq} ${kindLabel(parent)}"
        showing(link)
        onNodeWithText(link).performClick()
        waitForIdle()
        assertEquals(parent.id, state.selectedId)
    }

    private fun Entry.words(): String = body!!.user_message!!.text!!.inline_!!.split(' ').first { it.length > 4 }

    @Test
    fun typingSearchesAndChoosingAHitLandsInTheViewSearchWasOpenedFrom() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        state.show(ConversationView.Tree)
        waitForIdle()
        onNodeWithText("Search").performClick()
        waitForIdle()
        val word = entries.first().words()
        onNode(hasSetTextAction()).performTextInput(word)
        waitUntil(timeoutMillis = 5_000) { state.hits.isNotEmpty() }
        waitForIdle()
        shot("search")

        val hit = state.hits.first()
        onAllNodesWithText(word, substring = true)[0].assertIsDisplayed()
        state.openHit(hit)
        waitForIdle()
        assertEquals(ConversationView.Tree, state.view)
        assertEquals(hit.hit.entry_id, state.selectedId)
        shot("search-landed")
    }

    @Test
    fun aSearchWithNoMatchSaysSo() = runDesktopComposeUiTest(1320, 880) {
        val state = open()
        state.show(ConversationView.Search)
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("zzzqqqnothing")
        showing("No matches.")
    }

    @Test
    fun aHitInAnotherConversationOpensThatConversationOnTheEntry() = runDesktopComposeUiTest(1320, 880) {
        SqliteStore.open(tempDir().resolve("two.sqlite")).use { store ->
            val first = store.writeConversation("First", turns = 3) { "Plain question $it" }
            val second = store.writeConversation("Second", turns = 300) { if (it == 250) "The heliotrope one" else "Other question $it" }
            val state = open(store, first)
            state.show(ConversationView.Search)
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("heliotrope")
            waitUntil(timeoutMillis = 5_000) { state.hits.isNotEmpty() }
            state.openHit(state.hits.single())
            waitForIdle()
            assertEquals(second, state.pager.conversationId)
            showing("The heliotrope one")
            onAllNodesWithText("The heliotrope one")[0].assertIsDisplayed()
            onNodeWithText("Second").assertIsDisplayed()
        }
    }

    @Test
    fun scopingToThisConversationLeavesOutTheOthers() = runDesktopComposeUiTest(1320, 880) {
        SqliteStore.open(tempDir().resolve("scope.sqlite")).use { store ->
            val here = store.writeConversation("Here", turns = 1) { "shared word here" }
            store.writeConversation("There", turns = 1) { "shared word there" }
            val state = open(store, here)
            assertEquals(2, state.search("shared", SearchScope.Library).size)
            assertEquals(here, state.search("shared", SearchScope.Conversation).single().hit.conversation_id)
        }
    }

    @Test
    fun aLongConversationScrollsToItsMiddleHoldingOnlyWhatIsShown() = runDesktopComposeUiTest(1320, 880) {
        SqliteStore.open(tempDir().resolve("long.sqlite")).use { store ->
            val state = open(store, store.writeConversation("Long", turns = 10_000))
            showing("Question number 1")
            val middle = state.pager.summary(5_000)!!
            state.selectedId = middle.user_entry_id
            state.show(ConversationView.Tree)
            waitForIdle()
            state.show(ConversationView.Messages)
            showing("Question number 5000")
            onAllNodesWithText("Question number 5000")[0].assertIsDisplayed()
            shot("long-middle")
            assertTrue(state.pager.heldTurns <= 128, "held ${state.pager.heldTurns} turns")
        }
    }
}

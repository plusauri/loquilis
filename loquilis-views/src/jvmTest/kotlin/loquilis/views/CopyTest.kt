@file:OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package loquilis.views

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import loquilis.model.TextRole
import loquilis.store.text

/** Keeps what the window copies, so a test run leaves the real clipboard alone. */
class FakeClipboard : Clipboard {
    var text = ""
        private set
    private var held: ClipEntry? = null

    override suspend fun getClipEntry(): ClipEntry? = held

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        held = clipEntry
        val transfer = clipEntry?.nativeClipEntry as? Transferable
        text = transfer?.getTransferData(DataFlavor.stringFlavor) as? String ?: ""
    }

    override val nativeClipboard: NativeClipboard get() = error("not used")

    /** The same clipboard by the older API, which the kit's copy button writes through. */
    @Suppress("DEPRECATION")
    val manager = object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }

        override fun getText(): AnnotatedString = AnnotatedString(text)
    }
}

/** The two levels of copying: a dragged stretch of a bubble, and a whole item in the inspector. */
class CopyTest {
    private val store = Fixture.store
    private val reply get() = Fixture.entries.first { it.body?.agent_text?.role == TextRole.TEXT_ROLE_REPLY }
    private val said get() = store.text(Fixture.entries.first { it.body?.user_message != null }.body!!.user_message!!.text)

    private fun ComposeUiTest.open(clipboard: FakeClipboard): ConversationState {
        val state = ConversationState(store, Fixture.conversationId)
        setContent { CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides clipboard.manager) { ReviewTheme { ConversationWindow(state) } } }
        waitForIdle()
        return state
    }

    @Test fun dragAcrossABubbleSelectsAndTheKeyCopiesIt() = runDesktopComposeUiTest(1320, 880) {
        val clipboard = FakeClipboard()
        val state = open(clipboard)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(said).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(said).performMouseInput {
            moveTo(Offset(width * 0.05f, 10f))
            press()
            moveTo(Offset(width * 0.5f, 10f))
            moveTo(Offset(width * 0.95f, 10f))
            release()
        }
        waitForIdle()
        val folder = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "selected-message.png"))
        onRoot().performKeyInput {
            val command = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
            keyDown(command)
            keyDown(Key.C)
            keyUp(Key.C)
            keyUp(command)
        }
        waitUntil(timeoutMillis = 3_000) { clipboard.text.isNotEmpty() }
        assertTrue(clipboard.text.isNotBlank() && clipboard.text in said, clipboard.text)
        assertEquals(null, state.selectedId, "a drag is not a click")
    }

    @Test fun aClickOnABubbleStillSelectsIt() = runDesktopComposeUiTest(1320, 880) {
        val state = open(FakeClipboard())
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(said).fetchSemanticsNodes().isNotEmpty() }
        // Until its turn is read a bubble is drawn from the summary, and a click there has nothing to select.
        waitUntil(timeoutMillis = 5_000) { state.pager.cached(1) != null }
        waitForIdle()
        onNodeWithText(said).performMouseInput { click(Offset(width * 0.5f, 10f)) }
        waitForIdle()
        assertNotNull(state.selectedId)
    }

    @Test fun theInspectorCopiesAWholeItemWithItsMarks() = runDesktopComposeUiTest(1320, 880) {
        val clipboard = FakeClipboard()
        val state = open(clipboard)
        state.selectedId = reply.id
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Copy text").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Copy text").performClick()
        waitUntil(timeoutMillis = 3_000) { clipboard.text.isNotEmpty() }
        assertEquals(store.text(reply.body!!.agent_text!!.text), clipboard.text)
        // The button says it worked by turning into a check named "Copied" (the kit's copy words).
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithContentDescription("Copied").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theRawLinesCopyToo() = runDesktopComposeUiTest(1320, 880) {
        val clipboard = FakeClipboard()
        val state = open(clipboard)
        state.selectedId = reply.id
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Copy raw lines").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Copy raw lines").performScrollTo().performClick()
        waitUntil(timeoutMillis = 3_000) { clipboard.text.isNotEmpty() }
        assertTrue("\"type\"" in clipboard.text, clipboard.text.take(200))
    }
}

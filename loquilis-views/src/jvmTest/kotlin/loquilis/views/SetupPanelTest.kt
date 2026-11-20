@file:OptIn(ExperimentalTestApi::class)

package loquilis.views

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import loquilis.agents.OllamaSetup
import loquilis.store.LocalChatAgent
import loquilis.store.OpenCodeAgent
import loquilis.store.SqliteStore
import loquilis.store.Store
import loquilis.store.newConversation

/** The setup card, with a small server standing in for Ollama. */
class SetupPanelTest {
    private val dir: Path = tempDir()
    private val store: Store = SqliteStore.open(dir.resolve("library.sqlite"))
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val url = "http://127.0.0.1:${server.address.port}"
    @Volatile private var models = "[]"
    private val pulled = CountDownLatch(1)
    private val release = CountDownLatch(1)

    init {
        server.createContext("/api/tags") { exchange ->
            val bytes = """{"models":$models}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/pull") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("""{"status":"pulling a","digest":"a","total":3400000000,"completed":1700000000}""".toByteArray() + '\n'.code.toByte())
                out.flush()
                pulled.countDown()
                release.await(10, TimeUnit.SECONDS)
                models = """[{"name":"gemma3:4b"}]"""
                out.write("""{"status":"success"}""".toByteArray() + '\n'.code.toByte())
            }
        }
    }

    @AfterTest fun close() {
        release.countDown()
        server.stop(0)
    }

    private fun ComposeUiTest.open(setup: OllamaSetup): LibraryState {
        val state = LibraryState(store, mapOf("local" to LocalChatAgent(store, url)), setups = mapOf("local" to setup))
        setContent { ReviewTheme { LibraryWindow(state) { agent, model -> state.newConversation(dir, agent, model) } } }
        waitForIdle()
        return state
    }

    /** The one button opens the card, and Create starts the conversation with no name. */
    private fun ComposeUiTest.startConversation() {
        onNodeWithText("New conversation…").performClick()
        waitForIdle()
        onNodeWithText("Create").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.showing(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.shot(name: String) {
        val folder = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(windowShot(), "png", File(folder, "$name.png"))
    }

    @Test fun withNoModelTheCardOffersOneAndTheConversationStartsOnceItIsDownloaded() = runDesktopComposeUiTest(1100, 760) {
        val state = open(OllamaSetup(url, emptyList()))
        startConversation()
        showing("Suits this computer")
        assertEquals(0, state.conversations.size, "no conversation before a model is here")
        shot("setup-models")

        onAllNodesWithText("Download")[0].performClick()
        pulled.await(10, TimeUnit.SECONDS)
        showing("1.7 GB of 3.4 GB")
        showing("Cancel download")
        shot("setup-downloading")

        release.countDown()
        waitUntil(timeoutMillis = 10_000) { state.conversations.size == 1 }
        assertEquals(null, state.setup)
        assertEquals("gemma3:4b", state.conversations.single().agent?.model, "the downloaded model is the one used")
        showing("Stays on this computer · gemma3:4b")
        state.close()
    }

    @Test fun withNoOllamaTheCardSendsThePersonToItsSite() = runDesktopComposeUiTest(1100, 760) {
        val state = open(OllamaSetup("http://127.0.0.1:1", emptyList()))
        startConversation()
        showing("Get Ollama")
        shot("setup-install")
        onNodeWithText("Cancel").performClick()
        waitUntil(timeoutMillis = 5_000) { state.setup == null }
        state.close()
    }

    @Test fun aReadyRunnerOffersItsModelsWithTheLastUsedFirst() = runDesktopComposeUiTest(1100, 760) {
        models = """[{"name":"gemma3:1b"},{"name":"gemma3:4b"}]"""
        store.newConversation(dir, java.time.Instant.now(), cli = "local", model = "gemma3:4b")
        val state = open(OllamaSetup(url, emptyList()))
        startConversation()
        showing("Choose a model")
        showing("Or download another")
        shot("setup-choose")
        onAllNodesWithText("Start")[0].performClick()
        waitUntil(timeoutMillis = 10_000) { state.conversations.size == 2 }
        assertEquals(null, state.setup)
        assertEquals("gemma3:4b", state.conversations.first { it.id == state.currentId }.agent?.model)
        state.close()
    }

    @Test fun returnStartsWithTheFirstModelAndEscapeCloses() = runDesktopComposeUiTest(1100, 760) {
        models = """[{"name":"gemma3:1b"}]"""
        val state = open(OllamaSetup(url, emptyList()))
        startConversation()
        showing("Choose a model")
        topRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { state.setup == null }

        startConversation()
        showing("Choose a model")
        topRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 10_000) { state.conversations.size == 1 }
        assertEquals("gemma3:1b", state.conversations.single().agent?.model)
        state.close()
    }

    @Test fun anAgentThatUsesToolsIsOfferedOnlyModelsThatCan() = runDesktopComposeUiTest(1100, 760) {
        models = """[{"name":"gemma3:4b"},{"name":"gemma4:e2b"}]"""
        server.createContext("/api/show") { exchange ->
            val asked = exchange.requestBody.readBytes().decodeToString()
            val bytes = (if ("gemma4" in asked) """{"capabilities":["completion","tools"]}""" else """{"capabilities":["completion"]}""").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val state = LibraryState(store, mapOf("opencode" to OpenCodeAgent(ollamaUrl = url)), setups = mapOf("opencode" to OllamaSetup(url, emptyList())))
        setContent { ReviewTheme { LibraryWindow(state) { agent, model -> state.newConversation(dir, agent, model) } } }
        startConversation()
        showing("only models that can use them are listed")
        showing("Gemma 4, small")

        assertEquals(listOf("gemma4:e2b"), state.setup!!.installed)
        assertEquals(0, onAllNodesWithText("Gemma 3, small").fetchSemanticsNodes().size, "it cannot call tools")
        shot("setup-tools")
        state.close()
    }
}

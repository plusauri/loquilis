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
import loquilis.store.SqliteStore
import loquilis.store.Store

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
        setContent { ReviewTheme { LibraryWindow(state) { agent -> state.newConversation(dir, agent) } } }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.showing(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.shot(name: String) {
        val folder = File("build/review-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
    }

    @Test fun withNoModelTheCardOffersOneAndTheConversationStartsOnceItIsDownloaded() = runDesktopComposeUiTest(1100, 760) {
        val state = open(OllamaSetup(url, emptyList()))
        onNodeWithText("New conversation").performClick()
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
        showing("Stays on this computer")
        state.close()
    }

    @Test fun withNoOllamaTheCardSendsThePersonToItsSite() = runDesktopComposeUiTest(1100, 760) {
        val state = open(OllamaSetup("http://127.0.0.1:1", emptyList()))
        onNodeWithText("New conversation").performClick()
        showing("Get Ollama")
        shot("setup-install")
        onNodeWithText("Close").performClick()
        waitUntil(timeoutMillis = 5_000) { state.setup == null }
        state.close()
    }

    @Test fun aReadyRunnerStartsAtOnce() = runDesktopComposeUiTest(1100, 760) {
        models = """[{"name":"gemma3:4b"}]"""
        val state = open(OllamaSetup(url, emptyList()))
        onNodeWithText("New conversation").performClick()
        waitUntil(timeoutMillis = 10_000) { state.conversations.size == 1 }
        assertEquals(null, state.setup)
        state.close()
    }
}

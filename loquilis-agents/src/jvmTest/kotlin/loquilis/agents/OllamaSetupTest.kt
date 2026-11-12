package loquilis.agents

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A small server stands in for Ollama, answering as its API documents. */
class OllamaSetupTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val nowhere = listOf(File("/no/such/Ollama.app"))

    @AfterTest fun close() = server.stop(0)

    private fun answer(path: String, body: String) = server.createContext(path) { exchange ->
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test fun readyListsOnlyTheModelsThatRunHere() {
        answer("/api/tags", """{"models":[{"name":"gpt-oss:120b-cloud"},{"name":"gemma3:4b"}]}""")
        assertEquals(RunnerState.Ready(listOf("gemma3:4b")), OllamaSetup(url, nowhere).check())
    }

    @Test fun aRunnerWithOnlyCloudModelsHasNoModels() {
        answer("/api/tags", """{"models":[{"name":"glm-4.6:cloud"}]}""")
        assertEquals(RunnerState.NoModels, OllamaSetup(url, nowhere).check())
    }

    @Test fun nothingAnsweringIsNotRunningWhenInstalledAndNotInstalledOtherwise() {
        val closed = "http://127.0.0.1:1"
        val app = Files.createTempDirectory("Ollama.app").toFile()
        assertEquals(RunnerState.NotRunning, OllamaSetup(closed, listOf(app)).check())
        assertEquals(RunnerState.NotInstalled, OllamaSetup(closed, nowhere).check())
    }

    @Test fun aDownloadReportsTheSumOfItsPartsAndEndsOnSuccess() {
        answer(
            "/api/pull",
            listOf(
                """{"status":"pulling manifest"}""",
                """{"status":"pulling a","digest":"a","total":100,"completed":40}""",
                """{"status":"pulling b","digest":"b","total":300,"completed":0}""",
                """{"status":"pulling a","digest":"a","total":100,"completed":100}""",
                """{"status":"pulling b","digest":"b","total":300,"completed":300}""",
                """{"status":"verifying sha256 digest"}""",
                """{"status":"success"}""",
            ).joinToString("\n"),
        )
        val seen = mutableListOf<PullProgress>()
        OllamaSetup(url, nowhere).pull("gemma3:4b") { seen += it }
        assertEquals(PullProgress("pulling b", 40, 400), seen[2])
        assertEquals(PullProgress("pulling a", 100, 400), seen[3])
        assertEquals(400L, seen.last().completed)
        assertEquals("success", seen.last().status)
    }

    @Test fun aRefusedDownloadSaysWhatOllamaSaid() {
        answer("/api/pull", """{"status":"pulling manifest"}""" + "\n" + """{"error":"pull model manifest: file does not exist"}""")
        val failure = assertFailsWith<IOException> { OllamaSetup(url, nowhere).pull("nope:1b") {} }
        assertEquals("pull model manifest: file does not exist", failure.message)
    }

    @Test fun aDownloadCutShortFails() {
        answer("/api/pull", """{"status":"pulling a","digest":"a","total":100,"completed":40}""")
        assertFailsWith<IOException> { OllamaSetup(url, nowhere).pull("gemma3:4b") {} }
    }

    @Test fun theRecommendationLeavesMostMemoryToEverythingElse() {
        val names = { gb: Double -> OllamaSetup.recommend(OllamaSetup.catalog, gb).name }
        assertEquals("gemma3:1b", names(8.6))
        assertEquals("gemma3:4b", names(17.2))
        assertEquals("gemma4:e2b", names(25.8))
        assertEquals("gemma3:1b", names(2.0), "the smallest when none fits")
    }

    @Test fun theShippedCatalogReads() {
        assertTrue(OllamaSetup.catalog.size >= 3)
        assertTrue(OllamaSetup.catalog.none { isCloudModel(it.name) }, "the catalog offers only models that run here")
    }
}

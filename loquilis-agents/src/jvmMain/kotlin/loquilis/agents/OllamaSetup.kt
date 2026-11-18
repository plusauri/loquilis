package loquilis.agents

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** How far a computer is from talking to a model through Ollama. */
sealed interface RunnerState {
    data object NotInstalled : RunnerState

    data object NotRunning : RunnerState

    data object NoModels : RunnerState

    /** Ready, with the models that run here. Cloud models are left out, since they would send data away. */
    data class Ready(val models: List<String>) : RunnerState
}

/** One open model offered for download. */
data class CatalogModel(
    val name: String,
    val label: String,
    val maker: String,
    val downloadGb: Double,
    val memoryGb: Double,
    val goodFor: String,
    /** Whether it can call tools, which an agent such as OpenCode needs. */
    val tools: Boolean = false,
)

/** A download under way: [completed] of [total] bytes, summed over its parts, and what Ollama is doing. */
data class PullProgress(val status: String, val completed: Long, val total: Long)

/**
 * Gets a newcomer from nothing to a model that answers, with Ollama: whether it is installed and running,
 * which model suits this computer, and the download. It never installs Ollama itself: the person gets it
 * from Ollama's own site, as with any app, so nothing fetched from the internet runs unseen.
 */
class OllamaSetup(
    private val baseUrl: String = "http://localhost:11434",
    private val installedAt: List<File> = DEFAULT_PLACES,
    /** `-g` leaves Loquilis in front, so a newcomer stays on the card and not on Ollama's own welcome. */
    private val launch: List<String> = listOf("open", "-g", "-a", "Ollama"),
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun check(): RunnerState {
        val answer = try {
            client.send(HttpRequest.newBuilder(URI.create("$baseUrl/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (_: IOException) {
            return if (installedAt.any(File::exists)) RunnerState.NotRunning else RunnerState.NotInstalled
        }
        val listed = (Json.parseToJsonElement(answer.body()) as? JsonObject)?.get("models") as? JsonArray
        val models = listed.orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }.filterNot(::isCloudModel)
        return if (models.isEmpty()) RunnerState.NoModels else RunnerState.Ready(models)
    }

    /** Whether Ollama says [model] can call tools. Not knowing reads as no. */
    fun canUseTools(model: String): Boolean {
        val call = HttpRequest.newBuilder(URI.create("$baseUrl/api/show"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("model", model) }.toString()))
            .build()
        val answer = runCatching { client.send(call, HttpResponse.BodyHandlers.ofString()).body() }.getOrNull() ?: return false
        val capabilities = runCatching { (Json.parseToJsonElement(answer) as? JsonObject)?.get("capabilities") as? JsonArray }.getOrNull()
        return capabilities.orEmpty().any { (it as? JsonPrimitive)?.content == "tools" }
    }

    /** Opens the Ollama app, which starts its runner. Whether it worked shows in the next [check]. */
    fun start() {
        runCatching { ProcessBuilder(launch).redirectErrorStream(true).start() }
    }

    /**
     * Downloads [model], reporting as it goes, until Ollama says it is done. Throws with Ollama's own words
     * when it refuses. Interrupting the calling thread stops it.
     */
    fun pull(model: String, progress: (PullProgress) -> Unit) {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
        }.toString()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/pull"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofLines())
        } catch (failure: IOException) {
            throw IOException("Nothing answered at $baseUrl. Is Ollama running?", failure)
        }
        // Each part reports its own bytes, so the whole is their sum.
        val parts = LinkedHashMap<String, Pair<Long, Long>>()
        var done = false
        response.body().use { lines ->
            for (line in lines) {
                if (Thread.currentThread().isInterrupted) return
                val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                event.str("error")?.let { throw IOException(it) }
                val status = event.str("status").orEmpty()
                val digest = event.str("digest")
                val total = (event["total"] as? JsonPrimitive)?.longOrNull
                if (digest != null && total != null) parts[digest] = ((event["completed"] as? JsonPrimitive)?.longOrNull ?: 0L) to total
                progress(PullProgress(status, parts.values.sumOf { it.first }, parts.values.sumOf { it.second }))
                if (status == "success") done = true
            }
        }
        if (!done) throw IOException("The download of $model ended before it finished.")
    }

    companion object {
        /** Where the Mac app and the command-line tool put Ollama. */
        val DEFAULT_PLACES = listOf(
            File("/Applications/Ollama.app"),
            File(System.getProperty("user.home"), "Applications/Ollama.app"),
            File("/usr/local/bin/ollama"),
            File("/opt/homebrew/bin/ollama"),
        )

        /** Ollama's download page, opened in the person's browser. */
        const val DOWNLOAD_PAGE = "https://ollama.com/download"

        /** The models offered, from the catalog that ships inside. */
        val catalog: List<CatalogModel> by lazy {
            val text = checkNotNull(OllamaSetup::class.java.getResource("/loquilis/local-models.json")) { "local-models.json is missing" }.readText()
            readCatalog(text)
        }

        fun readCatalog(text: String): List<CatalogModel> =
            ((Json.parseToJsonElement(text) as JsonObject)["models"] as JsonArray).map { item ->
                val model = item as JsonObject
                fun number(key: String) = (model[key] as JsonPrimitive).doubleOrNull ?: 0.0
                CatalogModel(
                    model.str("name")!!,
                    model.str("label")!!,
                    model.str("maker").orEmpty(),
                    number("download_gb"),
                    number("memory_gb"),
                    model.str("good_for").orEmpty(),
                    tools = (model["tools"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }

        /**
         * The largest model that leaves most of the memory to everything else: one using over about a third
         * made a 16 GB Mac with other work open stutter. With too little memory for any, the smallest.
         */
        fun recommend(models: List<CatalogModel>, memoryGb: Double = installedMemoryGb()): CatalogModel =
            models.filter { it.memoryGb <= memoryGb * SHARE }.maxByOrNull { it.memoryGb } ?: models.minBy { it.memoryGb }

        /** Whether [model] leaves most of [memoryGb] to everything else, as [recommend] wants. */
        fun suits(model: CatalogModel, memoryGb: Double): Boolean = model.memoryGb <= memoryGb * SHARE

        fun installedMemoryGb(): Double {
            val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            return (bean?.totalMemorySize ?: 0L) / GB
        }

        /** Disk free where Ollama keeps models, under the home folder. */
        fun freeDiskGb(): Double = File(System.getProperty("user.home")).usableSpace / GB

        // A third: at 0.35 an 8 GB Mac (8.6 GB as Java reads it) just took gemma3:4b at 2.9 GB, leaving too little.
        private const val SHARE = 0.33
        private const val GB = 1_000_000_000.0
    }
}

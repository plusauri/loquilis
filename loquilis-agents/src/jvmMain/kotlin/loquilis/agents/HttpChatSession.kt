package loquilis.agents

import com.squareup.wire.Instant
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import loquilis.model.AppCommand
import loquilis.model.Decision
import loquilis.model.Interrupt

/** One message a model has already seen. [role] is `user` or `assistant`. */
data class ChatMessage(val role: String, val text: String)

/**
 * A conversation with a model on a runner, with no agent in between. A runner remembers nothing, so
 * this keeps the conversation and sends it again each turn, and [history] carries on a stored one.
 * With no [model], it asks the runner which it has and takes the first.
 *
 * A failure is a line in the raw log like any other, so reading the log back gives the same turn.
 */
class HttpChatSession(
    private val parser: AgentParser,
    private val baseUrl: String,
    private var model: String?,
    private val workspace: String,
    history: List<ChatMessage>,
    firstRawSeq: Long = 1,
    /** How long the runner keeps the model in memory after a reply, as Ollama reads it (`"2m"`). Null leaves its own default. */
    private val keepAlive: String? = null,
    private val now: () -> Instant = { java.time.Instant.now() },
    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
) : AgentSession {
    private val channel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val lock = Any()
    private val messages = history.toMutableList()
    private val waiting = LinkedBlockingQueue<Waiting>()
    private var rawSeq = firstRawSeq - 1
    private var request: CompletableFuture<HttpResponse<InputStream>>? = null
    private var body: InputStream? = null
    private var turnRunning = false
    private var stopped = false

    private sealed interface Waiting {
        data class Message(val text: String) : Waiting
        data object End : Waiting
    }

    override val events: Flow<SessionEvent> = channel.receiveAsFlow()

    private val worker = thread(isDaemon = true, name = "chat-http") {
        while (true) {
            val next = waiting.take()
            if (next !is Waiting.Message) break
            turn(next.text)
        }
        val unsent = waiting.filterIsInstance<Waiting.Message>().map { it.text }
        synchronized(lock) {
            parser.abandon(now()).forEach(::emit)
            channel.trySend(SessionEvent.Ended(0, "", unsent))
            channel.close()
        }
    }

    override fun send(text: String) {
        waiting.put(Waiting.Message(text))
    }

    /** Nothing here ever asks, so there is nothing to answer. */
    override fun answer(ask: PermissionAsk, decision: Decision) {}

    override fun interrupt() {
        synchronized(lock) {
            if (!turnRunning || stopped) return
            stopped = true
            parser.command(AppCommand(interrupt = Interrupt()), now()).forEach(::emit)
            cancel()
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopped = true
            cancel()
        }
        waiting.clear()
        waiting.put(Waiting.End)
    }

    override fun close() {
        waiting.put(Waiting.End)
    }

    private fun cancel() {
        request?.cancel(true)
        runCatching { body?.close() }
    }

    private fun turn(text: String) {
        synchronized(lock) {
            turnRunning = true
            stopped = false
            parser.userMessage(text, now()).forEach(::emit)
        }
        messages += ChatMessage("user", text)
        var reply = ""
        val failure = runCatching { reply = stream() }.exceptionOrNull()
        when {
            synchronized(lock) { stopped } -> raw(STOPPED)
            failure != null -> raw(errorLine(failure.message ?: "The connection failed."))
        }
        if (reply.isNotEmpty()) messages += ChatMessage("assistant", reply)
        synchronized(lock) {
            turnRunning = false
            request = null
            body = null
        }
    }

    /** Reads a reply through to its end, and returns as much of it as arrived. */
    private fun stream(): String {
        val chosen = model ?: firstModel().also { model = it }
        emit(ParseOutput.Session(SessionInfo("", chosen, "", workspace)))
        val call = post(chosen)
        synchronized(lock) { request = call }
        val response = try {
            call.get()
        } catch (failure: ExecutionException) {
            throw IOException("Nothing answered at $baseUrl. Is the model runner started?", failure)
        }
        if (response.statusCode() != 200) throw IOException(refusal(response))
        synchronized(lock) { body = response.body() }
        var reply = ""
        response.body().bufferedReader().useLines { lines ->
            for (line in lines) {
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                raw(data).filterIsInstance<ParseOutput.Writing>().lastOrNull()?.text?.takeIf(String::isNotEmpty)?.let { reply = it }
                if (data == OpenAiChatParser.DONE) return reply
            }
        }
        throw IOException("The connection ended before the reply did.")
    }

    private fun post(chosen: String): CompletableFuture<HttpResponse<InputStream>> {
        val payload = buildJsonObject {
            put("model", chosen)
            keepAlive?.let { put("keep_alive", it) }
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                for (message in messages) addJsonObject {
                    put("role", message.role)
                    put("content", message.text)
                }
            }
        }
        val call = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        return client.sendAsync(call, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun firstModel(): String {
        val call = HttpRequest.newBuilder(URI.create("$baseUrl/v1/models")).GET().build()
        val answer = try {
            client.send(call, HttpResponse.BodyHandlers.ofString())
        } catch (failure: IOException) {
            throw IOException("Nothing answered at $baseUrl. Is the model runner started?", failure)
        }
        val listed = (Json.parseToJsonElement(answer.body()) as? JsonObject)?.get("data") as? JsonArray
        // A cloud model would send the conversation away, so one is used only when asked for by name.
        return listed.orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }.firstOrNull { !isCloudModel(it) }
            ?: throw IOException("The model runner at $baseUrl has no models on this computer yet. Download one first.")
    }

    private fun refusal(response: HttpResponse<InputStream>): String {
        val text = response.body().use { it.readBytes().decodeToString() }
        val said = runCatching { ((Json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject)?.str("message") }.getOrNull()
        return said ?: "The model runner answered with status ${response.statusCode()}."
    }

    private fun raw(line: String): List<ParseOutput> = synchronized(lock) {
        val at = now()
        val seq = ++rawSeq
        channel.trySend(SessionEvent.Raw(seq, line, at))
        parser.line(seq, line, at).onEach(::emit)
    }

    private fun emit(output: ParseOutput) {
        channel.trySend(SessionEvent.Parsed(output))
    }

    private fun errorLine(message: String): String =
        buildJsonObject { putJsonObject("error") { put("message", message) } }.toString()

    private companion object {
        const val STOPPED = """{"stream_ended":"stopped"}"""
    }
}

/**
 * Whether [model] is one Ollama runs in its own cloud, which its tag says: `gpt-oss:120b-cloud`, `glm-4.6:cloud`.
 * Such a model is listed beside local ones and answers at the same local address.
 */
fun isCloudModel(model: String): Boolean {
    val tag = model.substringAfter(':', "")
    return tag == "cloud" || tag.endsWith("-cloud")
}

/** Whether [baseUrl] names this computer, so nothing sent to it crosses the network. */
fun isLoopback(baseUrl: String): Boolean {
    val host = runCatching { URI.create(baseUrl).host }.getOrNull()?.removeSurrounding("[", "]") ?: return false
    return host == "localhost" || host == "::1" || host.startsWith("127.")
}

/**
 * Asks the runner at [baseUrl] to let go of [model] now, freeing its memory, the way Ollama documents: an empty
 * request with `keep_alive` 0. Best effort: a runner that is gone has nothing to free.
 */
fun unloadModel(baseUrl: String, model: String, client: HttpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build()) {
    val body = buildJsonObject {
        put("model", model)
        put("keep_alive", 0)
    }.toString()
    val call = HttpRequest.newBuilder(URI.create("$baseUrl/api/generate"))
        .timeout(java.time.Duration.ofSeconds(3))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    runCatching { client.send(call, HttpResponse.BodyHandlers.discarding()) }
}

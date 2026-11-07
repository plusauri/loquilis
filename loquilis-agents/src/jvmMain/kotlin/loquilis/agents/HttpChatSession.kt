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
        return (listed?.firstOrNull() as? JsonObject)?.str("id")
            ?: throw IOException("The model runner at $baseUrl has no models yet. Download one first.")
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

package loquilis.agents

import com.squareup.wire.Instant
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `grok agent stdio` for the whole conversation, spoken to in JSON-RPC. See [GrokAcpParser].
 *
 * [resume] is an earlier session of the agent's own to carry on. Each session sets `yoloMode` false, so
 * Grok asks here even for someone whose own settings approve everything. [command] is the program and
 * any fixed arguments, so a test can stand a script in for `grok`.
 */
class GrokLiveSession(
    parser: AgentParser,
    workingDir: File,
    model: String? = null,
    resume: String? = null,
    firstRawSeq: Long = 1,
    command: List<String> = listOf("grok"),
    now: () -> Instant = { java.time.Instant.now() },
) : AgentSession by ProcessSession(
    parser,
    GrokProtocol(workingDir.path, resume),
    buildList {
        addAll(command)
        add("agent")
        if (model != null) addAll(listOf("--model", model))
        add("stdio")
    },
    workingDir,
    firstRawSeq,
    now,
)

private class GrokProtocol(private val workingDir: String, private var sessionId: String?) : SessionProtocol {
    private var nextId = OPEN_SESSION
    private val waitingAnswers = linkedMapOf<String, JsonElement>()

    override val opening: List<String> = listOf(
        request(
            INITIALIZE,
            "initialize",
            buildJsonObject {
                put("protocolVersion", 1)
                put(
                    "clientCapabilities",
                    buildJsonObject {
                        put("terminal", false)
                        put(
                            "fs",
                            buildJsonObject {
                                put("readTextFile", false)
                                put("writeTextFile", false)
                            },
                        )
                    },
                )
            },
        ),
        request(
            OPEN_SESSION,
            if (sessionId == null) "session/new" else "session/resume",
            buildJsonObject {
                sessionId?.let { put("sessionId", it) }
                put("cwd", workingDir)
                put("mcpServers", buildJsonArray {})
                put("_meta", buildJsonObject { put("yoloMode", false) })
            },
        ),
    )

    override fun heard(line: String): Heard {
        val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return Heard()
        val method = message.str("method")
        val id = message["id"]
        return when {
            method == "session/request_permission" && id != null -> Heard().also { waitingAnswers[(id as JsonPrimitive).content] = id }
            method != null && id != null -> Heard(reply = notOffered(id))
            (id as? JsonPrimitive)?.content == OPEN_SESSION.toString() -> opened(message)
            (message["result"] as? JsonObject)?.str("stopReason") != null -> Heard().also { waitingAnswers.clear() }
            else -> Heard()
        }
    }

    private fun opened(response: JsonObject): Heard {
        (response["error"] as? JsonObject)?.let { return Heard(refused = it.str("message") ?: "Grok refused the session.") }
        sessionId = sessionId ?: (response["result"] as? JsonObject)?.str("sessionId")
        return Heard(opened = true)
    }

    override fun message(text: String): String = request(
        ++nextId,
        "session/prompt",
        buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        },
    )

    /** The protocol has the app withdraw any question it left open when it cancels. */
    override fun interrupting(): List<String> {
        val cancel = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "session/cancel")
            put("params", buildJsonObject { put("sessionId", sessionId) })
        }.toString()
        val withdrawn = waitingAnswers.values.map(::cancelled)
        waitingAnswers.clear()
        return listOf(cancel) + withdrawn
    }

    override fun answered(requestId: String) {
        waitingAnswers.remove(requestId)
    }

    private companion object {
        const val INITIALIZE = 1
        const val OPEN_SESSION = 2
        const val METHOD_NOT_FOUND = -32601

        fun request(id: Int, method: String, params: JsonObject): String = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

        fun cancelled(id: JsonElement): String = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", buildJsonObject { put("outcome", buildJsonObject { put("outcome", "cancelled") }) })
        }.toString()

        fun notOffered(id: JsonElement): String = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject { put("code", METHOD_NOT_FOUND); put("message", "Not offered by this client") })
        }.toString()
    }
}

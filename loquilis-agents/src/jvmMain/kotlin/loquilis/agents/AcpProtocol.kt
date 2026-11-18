package loquilis.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Agent Client Protocol from the console's side: opens or resumes a session in [workingDir], sends each message
 * as a prompt, and withdraws open questions when it cancels. [sessionMeta] goes with the session request, as Grok's
 * `yoloMode`. A [model] is set once the session opens, and the session counts as open only once the agent says it
 * uses that model, since OpenCode would otherwise answer with its own default, which may run in a cloud.
 */
internal class AcpProtocol(
    private val workingDir: String,
    private var sessionId: String?,
    private val agentName: String,
    private val sessionMeta: JsonObject? = null,
    private val model: String? = null,
) : SessionProtocol {
    private var nextId = SET_MODEL
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
                sessionMeta?.let { put("_meta", it) }
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
            (id as? JsonPrimitive)?.content == SET_MODEL.toString() -> modelSet(message)
            (message["result"] as? JsonObject)?.str("stopReason") != null -> Heard().also { waitingAnswers.clear() }
            else -> Heard()
        }
    }

    private fun opened(response: JsonObject): Heard {
        (response["error"] as? JsonObject)?.let { return Heard(refused = it.str("message") ?: "$agentName refused the session.") }
        sessionId = sessionId ?: (response["result"] as? JsonObject)?.str("sessionId")
        if (model == null) return Heard(opened = true)
        val choose = buildJsonObject {
            put("sessionId", sessionId)
            put("configId", "model")
            put("value", model)
        }
        return Heard(reply = request(SET_MODEL, "session/set_config_option", choose))
    }

    private fun modelSet(response: JsonObject): Heard {
        val refusal = (response["error"] as? JsonObject)?.str("message")
        val options = ((response["result"] as? JsonObject)?.get("configOptions") as? kotlinx.serialization.json.JsonArray).orEmpty()
        val using = options.filterIsInstance<JsonObject>().firstOrNull { it.str("id") == "model" }?.str("currentValue")
        return when {
            refusal != null -> Heard(refused = "$agentName could not use $model: $refusal")
            using != model -> Heard(refused = "$agentName did not switch to $model, so nothing was sent.")
            else -> Heard(opened = true)
        }
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
        const val SET_MODEL = 3
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

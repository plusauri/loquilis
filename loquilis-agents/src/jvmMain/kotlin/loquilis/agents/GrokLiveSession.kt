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
 * `grok agent stdio` for the whole conversation, spoken to in JSON-RPC. See [AcpParser].
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
    AcpProtocol(workingDir.path, resume, "Grok", sessionMeta = buildJsonObject { put("yoloMode", false) }),
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

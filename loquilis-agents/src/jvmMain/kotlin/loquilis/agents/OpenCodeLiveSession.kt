package loquilis.agents

import com.squareup.wire.Instant
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `opencode acp` for the whole conversation, which speaks the Agent Client Protocol like Grok. See [AcpParser].
 *
 * OpenCode as it comes runs every tool without asking, writes outside the folder it was given, offers its own
 * cloud models first, and reads the person's Claude instructions. Loquilis starts it with [ENVIRONMENT], so it asks
 * here before anything but reading and searching the folder, offers no cloud models of its own, and reads only the
 * folder's and OpenCode's own instructions. [model] is set once the session opens, as `ollama/gemma4:e2b`.
 */
class OpenCodeLiveSession(
    parser: AgentParser,
    workingDir: File,
    model: String?,
    resume: String? = null,
    firstRawSeq: Long = 1,
    command: List<String> = listOf("opencode"),
    now: () -> Instant = { java.time.Instant.now() },
) : AgentSession by ProcessSession(
    parser,
    AcpProtocol(workingDir.path, resume, "OpenCode", model = model),
    command + "acp",
    workingDir,
    firstRawSeq,
    now,
    ENVIRONMENT,
) {
    companion object {
        /** What every OpenCode session Loquilis starts is told, checked against OpenCode 2.3.2. */
        val ENVIRONMENT: Map<String, String> = mapOf(
            "OPENCODE_CONFIG_CONTENT" to buildJsonObject {
                put(
                    "permission",
                    buildJsonObject {
                        put("*", "ask")
                        for (quiet in listOf("read", "glob", "grep", "list")) put(quiet, "allow")
                        put("external_directory", "ask")
                    },
                )
            }.toString(),
            "OPENCODE_DISABLE_FREE_MODELS" to "1",
            "OPENCODE_DISABLE_CLAUDE_CODE" to "1",
        )
    }
}

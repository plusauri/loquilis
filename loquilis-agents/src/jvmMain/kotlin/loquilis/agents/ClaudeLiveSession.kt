package loquilis.agents

import com.squareup.wire.Instant
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

/**
 * `claude` for the whole conversation. Claude Code holds its context across messages and emits one
 * `result` per message.
 *
 * [resume] is an earlier session of the agent's own to carry on. [command] is the program and any fixed
 * arguments, so a test can stand a script in for `claude`.
 */
class ClaudeLiveSession(
    parser: AgentParser,
    workingDir: File,
    model: String? = null,
    extraArgs: List<String> = emptyList(),
    resume: String? = null,
    firstRawSeq: Long = 1,
    command: List<String> = listOf("claude"),
    now: () -> Instant = { java.time.Instant.now() },
) : AgentSession by ProcessSession(
    parser,
    ClaudeProtocol(),
    buildList {
        addAll(command)
        addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
        if (model != null) addAll(listOf("--model", model))
        if (resume != null) addAll(listOf("--resume", resume))
        addAll(extraArgs)
        addAll(listOf("--permission-prompt-tool", "stdio"))
    },
    workingDir,
    firstRawSeq,
    now,
)

private class ClaudeProtocol : SessionProtocol {
    private var interrupts = 0

    override fun message(text: String): String {
        val quoted = JsonPrimitive(text).toString()
        return """{"type":"user","message":{"role":"user","content":[{"type":"text","text":$quoted}]}}"""
    }

    override fun interrupting(): List<String> =
        listOf("""{"type":"control_request","request_id":"interrupt-${++interrupts}","request":{"subtype":"interrupt"}}""")
}

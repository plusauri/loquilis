package loquilis.store

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import loquilis.model.AgentBinding
import loquilis.model.Conversation
import org.junit.jupiter.api.io.TempDir

/** Each agent says where data goes from what it actually uses, and doubt reads as unknown. */
class DataGoesTest {
    @TempDir lateinit var dir: Path

    private fun local(baseUrl: String = "http://localhost:11434") = LocalChatAgent(SqliteStore.open(dir.resolve("library.sqlite")), baseUrl)

    private fun using(model: String) = Conversation(id = "c", agent = AgentBinding(cli = "local", reported_model = model))

    @Test fun claudeSendsToAnthropicUnlessPointedElsewhere() {
        assertEquals(DataGoes.ToCompany("Anthropic"), ClaudeAgent(env = emptyMap()).dataGoes(null))
        assertEquals(DataGoes.Unknown, ClaudeAgent(env = mapOf("ANTHROPIC_BASE_URL" to "http://localhost:11434")).dataGoes(null))
        assertEquals(DataGoes.Unknown, ClaudeAgent(env = mapOf("CLAUDE_CODE_USE_BEDROCK" to "1")).dataGoes(null))
    }

    @Test fun grokSendsToXai() {
        assertEquals(DataGoes.ToCompany("xAI"), GrokAgent().dataGoes(null))
    }

    @Test fun aLocalRunnerKeepsItHereUnlessTheModelIsInItsCloud() {
        assertEquals(DataGoes.StaysHere, local().dataGoes(null))
        assertEquals(DataGoes.StaysHere, local().dataGoes(using("gemma4:e2b")))
        assertEquals(DataGoes.ToCompany("Ollama"), local().dataGoes(using("gpt-oss:120b-cloud")))
    }

    @Test fun aRunnerElsewhereIsUnknown() {
        assertEquals(DataGoes.Unknown, local("http://192.168.1.20:11434").dataGoes(using("gemma4:e2b")))
    }

    @Test fun openCodeOnALocalModelSaysTheModelIsHereButNotItsTools() {
        assertEquals(DataGoes.ModelHere, OpenCodeAgent().dataGoes(using("ollama/gemma4:e2b")))
        assertEquals(DataGoes.ModelHere, OpenCodeAgent().dataGoes(using("gemma4:e2b")), "as chosen, before OpenCode reports it")
        assertEquals(DataGoes.Unknown, OpenCodeAgent().dataGoes(using("ollama/gpt-oss:120b-cloud")))
        assertEquals(DataGoes.Unknown, OpenCodeAgent().dataGoes(using("opencode/big-pickle")))
        assertEquals(DataGoes.Unknown, OpenCodeAgent().dataGoes(null))
        assertEquals(DataGoes.Unknown, OpenCodeAgent(ollamaUrl = "http://192.168.1.20:11434").dataGoes(using("ollama/gemma4:e2b")))
    }
}

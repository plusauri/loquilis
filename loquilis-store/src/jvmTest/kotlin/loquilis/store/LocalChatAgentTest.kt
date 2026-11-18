package loquilis.store

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/** Plain local chat holds a model in memory only while it is wanted. */
class LocalChatAgentTest {
    @TempDir lateinit var dir: Path

    @Test fun closingLetsGoOfEachModelItUsedOnce() {
        val store = SqliteStore.open(dir.resolve("library.sqlite"))
        val freed = mutableListOf<String>()
        val agent = LocalChatAgent(store, "http://127.0.0.1:9", unload = { _, model -> freed += model })
        val first = store.newConversation(dir, Instant.EPOCH, cli = "local", model = "gemma3:4b")
        val second = store.newConversation(dir, Instant.EPOCH, cli = "local", model = "gemma3:4b")
        store.newConversation(dir, Instant.EPOCH, cli = "local", model = "gemma4:e2b")
        for (conversation in listOf(first, second)) {
            agent.start(conversation, agent.parser(conversation.id, { _, _ -> error("nothing is written") }), 1).close()
        }
        agent.close()

        assertEquals(listOf("gemma3:4b"), freed, "a model never run is not touched, and one run twice is freed once")
    }
}

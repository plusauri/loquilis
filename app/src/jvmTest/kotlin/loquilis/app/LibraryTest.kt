package loquilis.app

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import loquilis.store.SqliteStore
import loquilis.store.recordTake
import org.junit.jupiter.api.io.TempDir

class LibraryTest {
    @TempDir
    lateinit var dir: java.nio.file.Path

    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/claude-take/$name")) { name }

    @Test
    fun withNoSourceTheBundledTakeOpensFromAScratchLibraryThatCloseRemoves() {
        val library = openLibrary(null)
        assertTrue(library.store.lastSeq(library.conversationId) > 0)
        val scratch = assertNotNull(library.scratch)
        assertTrue(scratch.exists())
        library.close()
        assertFalse(scratch.exists())
    }

    @Test
    fun aLibraryFileOpensAsItIsAndSurvivesClosing() {
        val path = dir.resolve("library.sqlite")
        val id = SqliteStore.open(path).use { recordTake(it, fixture("conversation.json"), fixture("raw.jsonl.gz")) }
        val library = openLibrary(path)
        assertEquals(id, library.conversationId)
        library.close()
        assertTrue(path.exists())
    }

    @Test
    fun aTakeFolderIsRecordedIntoAScratchLibrary() {
        val take = dir.resolve("take").also(Files::createDirectory)
        listOf("conversation.json", "raw.jsonl.gz").forEach { name -> fixture(name).use { Files.copy(it, take.resolve(name)) } }
        val library = openLibrary(take)
        assertTrue(library.store.lastSeq(library.conversationId) > 0)
        library.close()
        assertTrue(take.resolve("conversation.json").exists())
    }

    @Test
    fun aLibraryWithNoConversationsSaysSo() {
        val path = dir.resolve("empty.sqlite")
        SqliteStore.open(path).close()
        assertFailsWith<IllegalStateException> { openLibrary(path) }
    }
}

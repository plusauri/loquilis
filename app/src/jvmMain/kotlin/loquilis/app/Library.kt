package loquilis.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import loquilis.store.SqliteStore
import loquilis.store.Store
import loquilis.store.recordTake

/** A library opened for the window, and the conversation to show first. */
class OpenedLibrary(val store: Store, val conversationId: String, val scratch: Path?) {
    /** Closes the library, and deletes it if it was only made for this window. */
    @OptIn(ExperimentalPathApi::class)
    fun close() {
        store.close()
        scratch?.deleteRecursively()
    }
}

/**
 * [source] names a library file, opened as it is, or a take folder. A take, or the bundled take when
 * there is no [source], is recorded into a scratch library that lasts as long as the window.
 */
fun openLibrary(source: Path?): OpenedLibrary {
    if (source != null && source.isRegularFile()) {
        val store = SqliteStore.open(source)
        val newest = store.conversations(limit = 1).firstOrNull()
        if (newest == null) {
            store.close()
            error("$source holds no conversations")
        }
        return OpenedLibrary(store, newest.id, null)
    }
    val scratch = Files.createTempDirectory("loquilis-review")
    val store = SqliteStore.open(scratch.resolve("library.sqlite"))
    val id = source?.let { recordTake(store, it) } ?: recordBundledTake(store)
    return OpenedLibrary(store, id, scratch)
}

/** The take bundled with the app: the one the parity test proves against. */
private fun recordBundledTake(store: Store): String {
    fun open(name: String) = checkNotNull(OpenedLibrary::class.java.getResourceAsStream("/fixtures/claude-take/$name")) { "missing $name" }
    return recordTake(store, open("conversation.json"), open("raw.jsonl.gz"))
}

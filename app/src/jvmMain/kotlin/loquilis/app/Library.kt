package loquilis.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import loquilis.store.SqliteStore
import loquilis.store.Store
import loquilis.store.recordTake

/** The bundled take, a recorded agent session (see the README's "Terms"), is opened by this instead of a path. */
const val SAMPLE = "--sample"

/** A library opened for the window, and the conversation to show first, if it has one. */
class OpenedLibrary(val store: Store, val conversationId: String?, val scratch: Path?) {
    /** Closes the library, and deletes it if it was only made for this window. */
    @OptIn(ExperimentalPathApi::class)
    fun close() {
        store.close()
        scratch?.deleteRecursively()
    }
}

/** Opens what the command line named: the default library with nothing, or the bundled take. */
fun openArgument(argument: String?, defaultLibrary: Path = defaultLibraryPath()): OpenedLibrary = when (argument) {
    null -> openFile(defaultLibrary.also { it.parent.createDirectories() })
    SAMPLE -> openLibrary(source = null)
    else -> openLibrary(Path.of(argument))
}

/**
 * [source] names a library file, opened as it is, or a take folder (`conversation.json` and `raw.jsonl.gz`).
 * A take, or the bundled take when there is no [source], is recorded into a scratch library that lasts as long
 * as the window.
 */
fun openLibrary(source: Path?): OpenedLibrary {
    if (source != null && source.isRegularFile()) return openFile(source)
    val scratch = Files.createTempDirectory("loquilis-review")
    val store = SqliteStore.open(scratch.resolve("library.sqlite"))
    val id = try {
        source?.let { recordTake(store, it) } ?: recordBundledTake(store)
    } catch (failure: Exception) {
        OpenedLibrary(store, "", scratch).close()
        throw failure
    }
    return OpenedLibrary(store, id, scratch)
}

/** A library file, created if there is none yet. */
private fun openFile(path: Path): OpenedLibrary {
    val store = SqliteStore.open(path)
    return OpenedLibrary(store, store.conversations(limit = 1).firstOrNull()?.id, null)
}

/** Where conversations live: each person's own app data, never a checkout. */
fun defaultLibraryPath(
    home: Path = Path.of(System.getProperty("user.home")),
    os: String = System.getProperty("os.name"),
    appData: String? = System.getenv("APPDATA"),
): Path = when {
    os.startsWith("Mac") -> home.resolve("Library/Application Support/plusauri/loquilis")
    os.startsWith("Windows") -> (appData?.let(Path::of) ?: home).resolve("plusauri/loquilis")
    else -> home.resolve(".local/share/plusauri/loquilis")
}.resolve("library.sqlite")

/** The take bundled with the app: the one the parity test proves against. */
private fun recordBundledTake(store: Store): String {
    fun open(name: String) = checkNotNull(OpenedLibrary::class.java.getResourceAsStream("/fixtures/claude-take/$name")) { "missing $name" }
    return recordTake(store, open("conversation.json"), open("raw.jsonl.gz"))
}

package loquilis.store

import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import loquilis.kit.Ulid
import loquilis.model.Entry
import loquilis.model.EntryState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Writes the take to the library at args[0], printing each line's number, until it is killed. */
object CrashWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = SqliteStore.open(Path.of(args[0]))
        store.putConversation(conversation())
        Take.play(Recorder(store, CONVERSATION, parserFor(store))) { println(it); System.out.flush() }
        Thread.sleep(60_000)
    }
}

class CrashTest {
    @TempDir
    lateinit var dir: Path

    /** The stable shape of an entry: everything but ids, which differ between two runs. */
    private fun shape(entries: List<Entry>): List<Entry> {
        val seqs = entries.associate { it.id to it.seq.toString() }
        return entries.map { it.copy(id = it.seq.toString(), parent_id = seqs[it.parent_id].orEmpty()) }
    }

    private fun killWriterAfter(lines: Int, path: Path) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashWriter::class.java.name, path.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.inputStream.bufferedReader().useLines { progress -> progress.first { it.toInt() >= lines } }
        process.destroyForcibly().waitFor()
    }

    @Test
    fun aKilledWriterLeavesALibraryThatRecoversToTheUninterruptedResult() {
        for (lines in listOf(20, 111, 250)) {
            val path = dir.resolve("killed-$lines.sqlite")
            killWriterAfter(lines, path)

            SqliteStore.open(path).use { store ->
                val rawCount = store.rawCount(CONVERSATION)
                assertTrue(rawCount >= lines, "the lines reported were durable: $rawCount of $lines")
                recover(store, CONVERSATION, AT) { parserFor(store, it) }

                recorded(dir.resolve("whole-$lines.sqlite"), limit = rawCount.toInt()).use { whole ->
                    val expected = shape(whole.allEntries()).map { if (it.state == EntryState.ENTRY_STATE_STREAMING) it.copy(state = EntryState.ENTRY_STATE_INTERRUPTED) else it }
                    assertEquals(expected, shape(store.allEntries()))
                    assertEquals(whole.turns(CONVERSATION, MAIN, 0, 100).map { it.ordinal to it.reply_head }, store.turns(CONVERSATION, MAIN, 0, 100).map { it.ordinal to it.reply_head })
                    assertEquals(Take.lines.take(rawCount.toInt()), store.raw(CONVERSATION, 1..rawCount))
                }
            }
        }
    }

    @Test
    fun rawLinesWithNoEntriesAreParsedOnRecovery() {
        SqliteStore.open(dir.resolve("a.sqlite")).use { store ->
            store.putConversation(conversation())
            val recorder = Recorder(store, CONVERSATION, parserFor(store))
            Take.play(recorder, limit = 60)
            val seen = store.lastSeq(CONVERSATION)
            // The window a live session has: the line is on disk, what it parses to is not.
            Take.lines.drop(store.rawCount(CONVERSATION).toInt()).take(30).forEach { store.appendRaw(CONVERSATION, it) }

            val changed = recover(store, CONVERSATION, AT) { parserFor(store, it) }

            assertTrue(changed > 0)
            assertTrue(store.lastSeq(CONVERSATION) > seen)
            assertEquals((1L..store.lastSeq(CONVERSATION)).toList(), store.allEntries().map { it.seq })
        }
    }

    @Test
    fun recoveringAFinishedConversationChangesNothing() {
        recorded(dir.resolve("a.sqlite")).use { store ->
            val before = store.allEntries()
            assertEquals(0, recover(store, CONVERSATION, AT) { parserFor(store, it) })
            assertEquals(before, store.allEntries())
        }
    }

    @Test
    fun recoveredEntriesKeepTheirIds() {
        val ulids = generateSequence { Ulid.next() }.iterator()
        SqliteStore.open(dir.resolve("a.sqlite")).use { store ->
            store.putConversation(conversation())
            Take.play(Recorder(store, CONVERSATION, parserFor(store, { ulids.next() })), limit = 40)
            val ids = store.allEntries().map { it.id }
            recover(store, CONVERSATION, AT) { parserFor(store, it) }
            assertEquals(ids, store.allEntries().map { it.id })
        }
    }
}

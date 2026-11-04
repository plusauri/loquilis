package loquilis.kit

import com.squareup.wire.AnyMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import loquilis.model.Entry
import loquilis.model.Extension
import loquilis.model.Notice
import loquilis.model.SetModel
import okio.ByteString.Companion.encodeUtf8

/** Kit messages stand in for an extending app's own: the kit does not read what is inside either way. */
class ExtensionsTest {
    private val noteKey = ExtensionKey(Notice.ADAPTER)
    private val modelKey = ExtensionKey(SetModel.ADAPTER)
    private val plain = entry(everyBody[0])

    @Test fun readsBackWhatWasWritten() {
        val note = Notice(text = "hello")
        assertEquals(note, plain.withExt(noteKey, note).ext(noteKey))
    }

    @Test fun anAbsentExtensionIsNull() {
        assertNull(plain.ext(noteKey))
        assertNull(plain.withExt(modelKey, SetModel("m")).ext(noteKey))
    }

    @Test fun writingAgainReplacesInPlace() {
        val e = plain.withExt(noteKey, Notice(text = "one")).withExt(modelKey, SetModel("m")).withExt(noteKey, Notice(text = "two"))
        assertEquals(2, e.extensions.size)
        assertEquals("two", e.ext(noteKey)?.text)
        assertEquals(Notice.ADAPTER.typeUrl, e.extensions[0].type_url)
    }

    /** The promise that lets an app's data pass through a kit that has never seen its .proto. */
    @Test fun anExtensionOfAnUnknownTypeSurvivesBinary() {
        val foreign = Extension("type.googleapis.com/some.app.v1.Thing", "opaque".encodeUtf8())
        val e = plain.copy(extensions = listOf(foreign))
        val back = Entry.ADAPTER.decode(e.encode())
        assertEquals(listOf(foreign), back.extensions)
        assertNull(back.ext(noteKey))
    }

    /** Same field numbers as google.protobuf.Any, so a tool that reads Any reads these bytes. */
    @Test fun encodesExactlyAsAnAnyDoes() {
        val url = "type.googleapis.com/some.app.v1.Thing"
        val bytes = "opaque".encodeUtf8()
        assertEquals(AnyMessage(url, bytes).encodeByteString(), Extension(url, bytes).encodeByteString())
    }
}

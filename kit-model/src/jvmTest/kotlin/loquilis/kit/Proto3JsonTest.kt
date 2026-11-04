package loquilis.kit

import com.squareup.moshi.Moshi
import com.squareup.wire.WireJsonAdapterFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import loquilis.model.Entry
import loquilis.model.ExportManifest
import loquilis.model.Conversation
import loquilis.model.Extension
import loquilis.model.Notice
import okio.ByteString.Companion.encodeUtf8

/** Exports are proto3-JSON, so every entry kind has to survive it, not only binary. */
class Proto3JsonTest {
    private val moshi = Moshi.Builder().add(WireJsonAdapterFactory()).build()
    private val entries = moshi.adapter(Entry::class.java)

    @Test fun everyBodySurvivesJson() {
        everyBody.forEachIndexed { i, body ->
            val e = entry(body, i + 1L)
            assertEquals(e, entries.fromJson(entries.toJson(e)))
        }
    }

    @Test fun everyCommandSurvivesJson() {
        everyCommand.forEachIndexed { i, c ->
            val e = entry(loquilis.model.EntryBody(app_command = c), i + 1L)
            assertEquals(e, entries.fromJson(entries.toJson(e)))
        }
    }

    /** The shape other languages' proto3-JSON readers expect, pinned so a Wire upgrade cannot move it. */
    @Test fun writesStandardProto3Json() {
        val json = entries.toJson(entry(everyBody[9]).withExt(ExtensionKey(Notice.ADAPTER), Notice(text = "x")))
        listOf(
            "\"conversationId\":",                 // camelCase names
            "\"seq\":\"1\"",                       // 64-bit numbers are strings
            "\"author\":\"AUTHOR_AGENT\"",         // enums by name
            "\"at\":\"2026-10-26T07:33:20.123Z\"", // timestamps as RFC 3339
            "\"value\":\"EgF4\"",                  // an extension's value as base64
        ).forEach { assertTrue(it in json, "missing $it in $json") }
    }

    @Test fun anExtensionOfAKnownTypeSurvivesJson() {
        val e = entry(everyBody[0]).withExt(ExtensionKey(Notice.ADAPTER), Notice(text = "hello"))
        assertEquals(e, entries.fromJson(entries.toJson(e)))
    }

    @Test fun anExtensionOfAnUnknownTypeSurvivesJson() {
        val foreign = Extension("type.googleapis.com/some.app.v1.Thing", "opaque".encodeUtf8())
        val e = entry(everyBody[0]).copy(extensions = listOf(foreign))
        assertEquals(e, entries.fromJson(entries.toJson(e)))
    }

    @Test fun aManifestSurvivesJson() {
        val manifest = ExportManifest(schema = 1, conversation = Conversation(id = "c", title = "t"), entry_count = 2)
        val adapter = moshi.adapter(ExportManifest::class.java)
        assertEquals(manifest, adapter.fromJson(adapter.toJson(manifest)))
    }
}

package loquilis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import loquilis.model.AppCommand
import loquilis.model.EntryBody
import okio.ByteString.Companion.decodeHex

class EntryKindTest {
    @Test fun everyBodyReadsAsItsOwnKind() {
        val kinds = everyBody.map { it.kind::class }
        assertEquals(kinds.size, kinds.toSet().size, "two bodies read as one kind")
        assertEquals(false, EntryKind.Unknown::class in kinds)
    }

    @Test fun everyCommandReadsAsItsOwnKind() {
        val kinds = everyCommand.map { it.kind::class }
        assertEquals(kinds.size, kinds.toSet().size, "two commands read as one kind")
        assertEquals(false, CommandKind.Unknown::class in kinds)
    }

    @Test fun everyBodySurvivesBinary() {
        everyBody.forEach { assertEquals(it, EntryBody.ADAPTER.decode(it.encode())) }
    }

    @Test fun everyCommandSurvivesBinary() {
        everyCommand.forEach { assertEquals(it, AppCommand.ADAPTER.decode(it.encode())) }
    }

    /** Field 99 of EntryBody, a varint 1: a kind this build has never heard of, from a newer schema. */
    @Test fun aKindFromANewerSchemaIsUnknownAndKeepsItsBytes() {
        val fromTheFuture = "98 06 01".replace(" ", "").decodeHex()
        val body = EntryBody.ADAPTER.decode(fromTheFuture)
        assertIs<EntryKind.Unknown>(body.kind)
        assertEquals(fromTheFuture, body.encodeByteString())
    }

    @Test fun anEmptyBodyIsUnknownNotACrash() {
        assertIs<EntryKind.Unknown>(EntryBody().kind)
    }
}

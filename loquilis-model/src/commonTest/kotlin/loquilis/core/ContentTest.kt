package loquilis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import loquilis.model.Content

class ContentTest {
    @Test fun inlineShowsItsWholeText() {
        val c = inlineContent("hello")
        assertFalse(c.isStored)
        assertEquals("hello", c.preview)
    }

    @Test fun storedShowsItsHead() {
        val c = storedContent(blob, "first lines")
        assertTrue(c.isStored)
        assertEquals("first lines", c.preview)
        assertEquals(blob.media_type, c.media_type)
    }

    /** A oneof holds one case: Wire enforces it, and this pins that it still does. */
    @Test fun cannotBeBothInlineAndStored() {
        assertFailsWith<IllegalArgumentException> { Content(inline_ = "x", blob = blob) }
    }
}

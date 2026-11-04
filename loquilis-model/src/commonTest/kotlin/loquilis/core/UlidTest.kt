package loquilis.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UlidTest {
    @Test fun isTwentySixCrockfordCharacters() {
        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{26}").matches(Ulid.next()))
    }

    /** The specification's own example time: 1469918176385 encodes to 01ARYZ6S41. */
    @Test fun encodesTimeAsTheSpecDoes() {
        assertEquals("01ARYZ6S41", Ulid.at(1469918176385, Random(1)).take(10))
    }

    @Test fun sortsByTime() {
        val ids = listOf(1L, 2L, 31L, 32L, 1_000_000L, 1_793_000_000_000L).map { Ulid.at(it, Random(7)) }
        assertEquals(ids, ids.sorted())
    }

    @Test fun sameMillisecondDiffers() {
        val random = Random(3)
        assertNotEquals(Ulid.at(5, random), Ulid.at(5, random))
    }

    @Test fun refusesATimeThatDoesNotFit() {
        assertFailsWith<IllegalArgumentException> { Ulid.at(1L shl 48, Random(1)) }
        assertFailsWith<IllegalArgumentException> { Ulid.at(-1, Random(1)) }
    }
}

package loquilis.core

import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.time.Clock

/** Ids are plain strings in the protos. These keep an entry id from being passed as a conversation id in Kotlin. */
@JvmInline value class ConversationId(val value: String)

@JvmInline value class EntryId(val value: String)

@JvmInline value class BranchId(val value: String)

/**
 * ULIDs: 48 bits of Unix milliseconds, then 80 random bits, as 26 Crockford base32 characters. They
 * sort by time as plain strings and are unique across libraries with no coordination, so two
 * libraries can be merged without renumbering. Order within a conversation comes from `seq`, not from
 * the id, so two ids from the same millisecond need not be ordered.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_CHARS = 10
    private const val RANDOM_CHARS = 16

    fun next(): String = at(Clock.System.now().toEpochMilliseconds(), Random.Default)

    fun at(epochMillis: Long, random: Random): String {
        require(epochMillis in 0 until (1L shl 48)) { "a ULID time is 48 bits: $epochMillis" }
        val chars = CharArray(TIME_CHARS + RANDOM_CHARS)
        var time = epochMillis
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ALPHABET[(time and 31).toInt()]
            time = time ushr 5
        }
        for (i in TIME_CHARS until chars.size) chars[i] = ALPHABET[random.nextInt(ALPHABET.length)]
        return chars.concatToString()
    }
}

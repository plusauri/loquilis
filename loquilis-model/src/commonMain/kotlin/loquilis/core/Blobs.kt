package loquilis.core

import loquilis.model.BlobRef
import loquilis.model.Content
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/** Where large content goes. The store implements it; a parser only needs somewhere to put bytes. */
fun interface BlobSink {
    fun put(bytes: ByteString, mediaType: String): BlobRef
}

/** The content-addressed reference for [bytes]: the same bytes always give the same ref. */
fun blobRefOf(bytes: ByteString, mediaType: String): BlobRef =
    BlobRef(sha256 = bytes.sha256().hex(), size = bytes.size.toLong(), media_type = mediaType)

/**
 * Decides inline or blob for every piece of text, so the threshold lives in one place and no adapter
 * can disagree with another about it.
 */
class ContentFactory(
    private val sink: BlobSink,
    private val inlineLimitBytes: Int = DEFAULT_INLINE_LIMIT_BYTES,
) {
    fun text(text: String, mediaType: String = ""): Content {
        val bytes = text.encodeUtf8()
        if (bytes.size <= inlineLimitBytes) return inlineContent(text, mediaType)
        val blobType = mediaType.ifEmpty { "text/plain; charset=utf-8" }
        return storedContent(sink.put(bytes, blobType), headOf(text), mediaType)
    }

    fun bytes(bytes: ByteString, mediaType: String): BlobRef = sink.put(bytes, mediaType)

    companion object {
        const val DEFAULT_INLINE_LIMIT_BYTES = 4096
        private const val HEAD_LINES = 12
        private const val HEAD_CHARS = 1000

        /** The first lines of [text], for previews and search snippets. */
        fun headOf(text: String): String =
            text.lineSequence().take(HEAD_LINES).joinToString("\n").take(HEAD_CHARS).trimEnd()
    }
}

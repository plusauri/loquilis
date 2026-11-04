package loquilis.kit

import loquilis.model.BlobRef
import loquilis.model.Content

/** Text held in the record itself. */
fun inlineContent(text: String, mediaType: String = ""): Content = Content(inline_ = text, media_type = mediaType)

/** Bytes held as a blob. [head] is the preview views and search snippets show without fetching them. */
fun storedContent(blob: BlobRef, head: String, mediaType: String = blob.media_type): Content =
    Content(blob = blob, head = head, media_type = mediaType)

val Content.isStored: Boolean get() = blob != null

/** What can be shown without touching the blob store: the whole text if inline, else its head. */
val Content.preview: String get() = inline_ ?: head

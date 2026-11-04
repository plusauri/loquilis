package loquilis.kit

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import loquilis.model.Branch
import loquilis.model.Conversation
import loquilis.model.Entry
import loquilis.model.Extension

/**
 * A typed handle on one kind of extension: a message from the extending app's own `.proto`.
 *
 * ```kotlin
 * // In the app built on the kit:
 * val TurnExt = ExtensionKey(CogilisTurn.ADAPTER)
 * val beat = entry.ext(TurnExt)?.beat_id
 * ```
 *
 * The kit never reads an extension. It stores the [Extension] and writes it back, including ones
 * whose type this build has never heard of.
 */
class ExtensionKey<M : Message<M, *>>(internal val adapter: ProtoAdapter<M>) {
    internal val typeUrl: String = requireNotNull(adapter.typeUrl) { "an extension must be a message with a type url" }
}

internal fun <M : Message<M, *>> List<Extension>.find(key: ExtensionKey<M>): M? =
    firstOrNull { it.type_url == key.typeUrl }?.let { key.adapter.decode(it.value_) }

/** The list with [value] in [key]'s place: an extension type appears at most once, and order is kept. */
internal fun <M : Message<M, *>> List<Extension>.with(key: ExtensionKey<M>, value: M): List<Extension> {
    val packed = Extension(type_url = key.typeUrl, value_ = key.adapter.encodeByteString(value))
    val at = indexOfFirst { it.type_url == key.typeUrl }
    return if (at < 0) this + packed else toMutableList().also { it[at] = packed }
}

fun <M : Message<M, *>> Entry.ext(key: ExtensionKey<M>): M? = extensions.find(key)
fun <M : Message<M, *>> Entry.withExt(key: ExtensionKey<M>, value: M): Entry = copy(extensions = extensions.with(key, value))

fun <M : Message<M, *>> Conversation.ext(key: ExtensionKey<M>): M? = extensions.find(key)
fun <M : Message<M, *>> Conversation.withExt(key: ExtensionKey<M>, value: M): Conversation = copy(extensions = extensions.with(key, value))

fun <M : Message<M, *>> Branch.ext(key: ExtensionKey<M>): M? = extensions.find(key)
fun <M : Message<M, *>> Branch.withExt(key: ExtensionKey<M>, value: M): Branch = copy(extensions = extensions.with(key, value))

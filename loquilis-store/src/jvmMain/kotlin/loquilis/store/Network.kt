package loquilis.store

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * The networks this computer is joined to, by interface name (`en0`), read from its own interfaces. Nothing is
 * sent anywhere to find out: probing a website would send traffic in the one mode that promises none.
 */
fun joinedNetworks(): List<String> {
    val interfaces = try {
        NetworkInterface.networkInterfaces().toList()
    } catch (_: SocketException) {
        // Not knowing is not the same as being off the network.
        return listOf("unknown")
    }
    return interfaces.filter { link ->
        runCatching { link.isUp && !link.isLoopback && reachesBeyond(link.inetAddresses.toList()) }.getOrDefault(true)
    }.map { it.name }
}

/**
 * Whether an interface holding [addresses] can reach past this computer. A link-local address only is one every
 * Mac keeps for nearby devices (`awdl0`, `utun0`) and reaches no network; any other address does, a VPN's too.
 */
fun reachesBeyond(addresses: List<InetAddress>): Boolean =
    addresses.any { !it.isLoopbackAddress && !it.isLinkLocalAddress }

/**
 * Offline only: nothing is sent while this computer is on any network, and a conversation is sent only where it
 * stays on this computer. Checked again before every send, since a network can come up mid-conversation.
 */
object OfflineOnly {
    /** Why [goes] cannot be sent now, with [networks] joined, or null when it can. */
    fun refusal(goes: DataGoes, networks: List<String>): String? = when {
        goes is DataGoes.ToCompany -> "Offline only is on, and this conversation is sent to ${goes.company}."
        goes == DataGoes.Unknown -> "Offline only is on, and it is not known where this conversation goes."
        networks.isNotEmpty() -> "Offline only is on, and this computer is on a network (${networks.joinToString()}). Turn off Wi-Fi and unplug network cables, then send."
        else -> null
    }
}

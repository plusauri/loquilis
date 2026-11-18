package loquilis.store

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline only reads the computer's own interfaces, and refuses whatever could leave it. */
class NetworkTest {
    private fun at(vararg addresses: String) = addresses.map(InetAddress::getByName)

    @Test fun onlyAnAddressBeyondTheLinkCountsAsANetwork() {
        assertFalse(reachesBeyond(at("fe80::1")), "awdl0 and utun keep only link-local addresses")
        assertFalse(reachesBeyond(at("169.254.10.2")), "a self-assigned address reaches no network")
        assertFalse(reachesBeyond(at("127.0.0.1")))
        assertTrue(reachesBeyond(at("fe80::1", "10.0.0.151")), "Wi-Fi on a home network")
        assertTrue(reachesBeyond(at("2001:db8::5")))
        assertFalse(reachesBeyond(emptyList()))
    }

    @Test fun refusalSaysWhyOrLetsItGo() {
        assertEquals("Offline only is on, and this conversation is sent to Anthropic.", OfflineOnly.refusal(DataGoes.ToCompany("Anthropic"), emptyList()))
        assertTrue("not known" in OfflineOnly.refusal(DataGoes.Unknown, emptyList())!!)
        assertTrue("(en0, utun3)" in OfflineOnly.refusal(DataGoes.StaysHere, listOf("en0", "utun3"))!!)
        assertNull(OfflineOnly.refusal(DataGoes.StaysHere, emptyList()))
        assertNull(OfflineOnly.refusal(DataGoes.ModelHere, emptyList()), "an agent's tools have no network to reach")
        assertTrue("(en0)" in OfflineOnly.refusal(DataGoes.ModelHere, listOf("en0"))!!)
    }

    @Test fun thisComputerCanBeRead() {
        joinedNetworks()
    }
}

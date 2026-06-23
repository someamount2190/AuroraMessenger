package com.aura.network

import com.aura.crypto.HybridPrivateKey
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigningKey
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodePrivateIdentity
import com.aura.crypto.NodePublicIdentity
import com.aura.crypto.toHex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demonstrates that the [Rendezvous] seam enables **in-process two-peer simulation**:
 * two peers (each their own [Rendezvous] handle) exchange opaque signals through one
 * shared in-memory broker, with no network. This is the relay the whole pairing
 * handshake rides on (pairreq → pairaccept → pairverify are all signals).
 */
class TwoPeerRendezvousTest {

    /**
     * A lightweight [NodeIdentity] for routing tests: a chosen nodeId + dummy key
     * material. The in-memory broker only reads `nodeId`, and these are plain
     * byte-wrapping data classes, so no key generation (liboqs) is needed.
     */
    private fun identity(tag: Int): NodeIdentity {
        val nodeId = ByteArray(32) { tag.toByte() }
        val pub = NodePublicIdentity(
            nodeId,
            HybridPublicKey(ByteArray(8), ByteArray(32)),
            HybridVerifyKey(ByteArray(8), ByteArray(32))
        )
        val priv = NodePrivateIdentity(
            nodeId,
            HybridPrivateKey(ByteArray(8), ByteArray(8), ByteArray(32)),
            HybridSigningKey(ByteArray(8), ByteArray(32))
        )
        return NodeIdentity(nodeId, pub, priv)
    }

    @Test
    fun two_peers_relay_signals_in_process() = runBlocking {
        val broker = InMemoryRendezvous.Broker()
        val alice = InMemoryRendezvous(broker)
        val bob = InMemoryRendezvous(broker)
        val aliceId = identity(0xA1)
        val bobId = identity(0xB2)

        alice.checkIn("mem://", aliceId).getOrThrow()
        bob.checkIn("mem://", bobId).getOrThrow()

        // Alice → Bob (the pairing request leg).
        alice.postSignal("mem://", bobId.nodeId.toHex(), "pairreq-from-alice").getOrThrow()
        assertEquals(listOf("pairreq-from-alice"), bob.getSignals("mem://", bobId).getOrThrow())
        // Draining is destructive — a second drain is empty.
        assertTrue(bob.getSignals("mem://", bobId).getOrThrow().isEmpty())

        // Bob → Alice (the accept leg), proving bidirectional routing.
        bob.postSignal("mem://", aliceId.nodeId.toHex(), "pairaccept-from-bob").getOrThrow()
        assertEquals(listOf("pairaccept-from-bob"), alice.getSignals("mem://", aliceId).getOrThrow())

        // FIFO order across multiple queued signals.
        bob.postSignal("mem://", aliceId.nodeId.toHex(), "s1").getOrThrow()
        bob.postSignal("mem://", aliceId.nodeId.toHex(), "s2").getOrThrow()
        assertEquals(listOf("s1", "s2"), alice.getSignals("mem://", aliceId).getOrThrow())
    }

    @Test
    fun find_resolves_only_checked_in_peers() = runBlocking {
        val broker = InMemoryRendezvous.Broker()
        val node = InMemoryRendezvous(broker)
        val bobId = identity(0xB2)

        // Unknown peer → no candidates.
        assertTrue(node.find("mem://", bobId.nodeId.toHex()).getOrThrow().isEmpty())

        // After check-in, find resolves it.
        node.checkIn("mem://", bobId).getOrThrow()
        assertTrue(node.find("mem://", bobId.nodeId.toHex()).getOrThrow().isNotEmpty())
    }
}

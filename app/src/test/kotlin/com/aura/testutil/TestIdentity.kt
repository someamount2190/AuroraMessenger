package com.aura.testutil

import com.aura.crypto.HybridPrivateKey
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigningKey
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodePrivateIdentity
import com.aura.crypto.NodePublicIdentity
import com.aura.crypto.toHex
import com.aura.identity.IdentityProvider

/**
 * A [NodeIdentity] with a chosen nodeId and dummy key material — for JVM unit tests
 * where liboqs native (real key generation) isn't available. The ratchet and the
 * transport routing only use the nodeId, so the keys never need to be real.
 */
fun testIdentity(tag: Int): NodeIdentity {
    val nodeId = ByteArray(32) { tag.toByte() }
    return NodeIdentity(
        nodeId,
        NodePublicIdentity(nodeId, HybridPublicKey(ByteArray(40)), HybridVerifyKey(ByteArray(8), ByteArray(32))),
        NodePrivateIdentity(nodeId, HybridPrivateKey(ByteArray(32)), HybridSigningKey(ByteArray(8), ByteArray(32)))
    )
}

/** A fixed-identity [IdentityProvider] for tests. */
class FakeIdentityProvider(private val identity: NodeIdentity) : IdentityProvider {
    override suspend fun getOrCreate(): NodeIdentity = identity
    override val nodeIdHexOrNull: String = identity.nodeId.toHex()

    val hex: String get() = identity.nodeId.toHex()
}

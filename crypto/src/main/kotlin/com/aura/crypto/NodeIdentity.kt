package com.aura.crypto

/**
 * An Aurora node's complete cryptographic identity.
 *
 * Combines:
 *   - HybridKem keypair    (Kyber-1024 + X25519)   — for key agreement
 *   - HybridSigner keypair (Dilithium-3 + Ed25519) — for signatures
 *
 * nodeId = SHA3-256(kemPublicKey.toBytes() || signingPublicKey.toBytes())
 * This is the stable network address. It is derived from the public keys
 * so any peer can independently verify a nodeId from the public keys.
 *
 * The public half (NodePublicIdentity) is safe to transmit. The private half
 * (NodePrivateIdentity) never leaves the device.
 *
 * Ported from ShadowMesh core/crypto (APK-binding/IBD machinery removed —
 * Aurora generates identity from fresh entropy only).
 */
class NodeIdentityGenerator(
    private val kem:    HybridKem,
    private val signer: HybridSigner,
    private val hkdf:   Hkdf = Hkdf.instance
) {
    suspend fun generate(): CryptoResult<NodeIdentity> = cryptoRunCatching {
        val kemKp     = kem.generateKeyPair().getOrThrow()
        val signingKp = signer.generateSigningKeyPair().getOrThrow()

        val nodeId = hkdf.sha3_256(kemKp.publicKey.toBytes() + signingKp.publicKey.toBytes())

        NodeIdentity(
            nodeId     = nodeId,
            publicPart = NodePublicIdentity(
                nodeId           = nodeId,
                kemPublicKey     = kemKp.publicKey,
                signingPublicKey = signingKp.publicKey
            ),
            privatePart = NodePrivateIdentity(
                nodeId            = nodeId,
                kemPrivateKey     = kemKp.privateKey,
                signingPrivateKey = signingKp.privateKey
            )
        )
    }
}

data class NodeIdentity(
    val nodeId:      ByteArray,
    val publicPart:  NodePublicIdentity,
    val privatePart: NodePrivateIdentity
) {
    override fun equals(other: Any?) = other is NodeIdentity && nodeId.contentEquals(other.nodeId)
    override fun hashCode() = nodeId.contentHashCode()
}

/**
 * The transmittable public half of a node identity.
 * Wire format: [32B nodeId][kemPublicKey bytes][signingPublicKey bytes]
 * Each public key is length-prefixed internally (see HybridPublicKey / HybridVerifyKey).
 */
data class NodePublicIdentity(
    val nodeId:           ByteArray,
    val kemPublicKey:     HybridPublicKey,
    val signingPublicKey: HybridVerifyKey
) {
    fun toBytes(): ByteArray =
        nodeId +
        kemPublicKey.toBytes() +
        signingPublicKey.toBytes()

    companion object {
        fun fromBytes(bytes: ByteArray, hkdf: Hkdf = Hkdf.instance): NodePublicIdentity {
            require(bytes.size >= 32) { "NodePublicIdentity: too short (${bytes.size})" }

            val nodeId   = bytes.copyOfRange(0, 32)
            var offset   = 32

            // HybridPublicKey: 4-byte length prefix + kyber key + 32 x25519
            require(bytes.size >= offset + 4) { "NodePublicIdentity: truncated before KEM key" }
            val kemLen = readInt4(bytes, offset)
            // Overflow-safe bounds check: compute the max allowed length first so a
            // near-Int.MAX_VALUE kemLen can't overflow the addition into a negative.
            require(kemLen >= 0 && kemLen <= bytes.size - offset - 4 - 32) {
                "NodePublicIdentity: KEM key length out of range ($kemLen)"
            }
            val kemEnd = offset + 4 + kemLen + 32
            val kemPub = HybridPublicKey.fromBytes(bytes.copyOfRange(offset, kemEnd))
            offset = kemEnd

            // HybridVerifyKey: 4-byte length prefix + dilithium key + 32 ed25519
            require(bytes.size >= offset + 4) { "NodePublicIdentity: truncated before signing key" }
            val sigLen = readInt4(bytes, offset)
            require(sigLen >= 0 && sigLen <= bytes.size - offset - 4 - 32) {
                "NodePublicIdentity: signing key length out of range ($sigLen)"
            }
            val sigEnd = offset + 4 + sigLen + 32
            val sigPub = HybridVerifyKey.fromBytes(bytes.copyOfRange(offset, sigEnd))

            // Verify nodeId matches the keys
            val expected = hkdf.sha3_256(kemPub.toBytes() + sigPub.toBytes())
            require(expected.contentEquals(nodeId)) {
                "NodePublicIdentity: nodeId does not match public keys — identity may be tampered"
            }

            return NodePublicIdentity(nodeId, kemPub, sigPub)
        }
    }

    override fun equals(other: Any?) = other is NodePublicIdentity &&
        nodeId.contentEquals(other.nodeId)
    override fun hashCode() = nodeId.contentHashCode()
}

/** Private half of a node identity. Never transmitted. Never serialized to disk unencrypted. */
data class NodePrivateIdentity(
    val nodeId:            ByteArray,
    val kemPrivateKey:     HybridPrivateKey,
    val signingPrivateKey: HybridSigningKey
) {
    override fun equals(other: Any?) = other is NodePrivateIdentity &&
        nodeId.contentEquals(other.nodeId)
    override fun hashCode() = nodeId.contentHashCode()
}
